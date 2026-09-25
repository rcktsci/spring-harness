# Ревью change `relay-session-workspace-isolation` (незакоммиченное рабочее дерево)

Рецензент: субагент-ревьюер (DeepSeek-V4.1-Flash). Дата: 2026-09-25.
Вход: `openspec/changes/relay-session-workspace-isolation/` (proposal/design/tasks + delta desktop-relay-client/desktop-shell), рабочее дерево (`relay-client.ts`, `auth.ts`, `ipc-contract.ts`, тесты), отчёт `docs/temp/relay-session-workspace-isolation-glm.md`.
Линза: корректность и fail-closed маршрутизации каталога, гонки, жизненный цикл map, решение по отвязанной сессии, KC-таймаут, тесты. Сборка/тесты/docker не запускались; проверял по коду.

## Дефект 1 (главный): per-session маршрутизация — корректна и fail-closed

- **Единственный источник каталога — map по `call.sessionId`.** `relay-client.ts:105` (`sessionBasePaths`), `:618` (`handleToolCall`: `const basePath = this.sessionBasePaths.get(call.sessionId)`); `this.basePath` участвует только в UI (`currentStatus.basePath`, `:779`) и в пере-регистрации (`this.registration.*`), в исполнение не попадает. Запись пишется только при `registered` и только для совпавшего `frame.sessionId` (`:374-376`), т.е. пара (`sessionId → basePath`) всегда консистентна — «не тот каталог» структурно невозможен.
- **Пропуск записи → отказ до запуска процесса.** `:619-627`: `output = 'no registration for session <id> — cannot execute'`, `emit('toolResult', …, -1)` + `tool.result` exitCode -1, `return` до `executeTool`. Fail-closed.
- **Кадр без `sessionId` не доходит до исполнения.** `ws-frames.ts:193-200`: `parseRelayFrame` отбрасывает `tool.call`, если `sessionId` не строка → `handleToolCall` не вызывается (fail-closed; сервер получит tool-timeout, но исполнения нет).
- **Все ветки линзы:**
  - нет basePath → отказ (`:619`);
  - устаревшая запись → ключ = `call.sessionId`, каталожная пара всегда своя (`:376`);
  - повторный register с другим basePath → `set` перезаписывает (`:376`); сценарий spec «реконнект не меняет каталог» покрыт тестом (в);
  - reconnect (close без 4409) → map не чистится, `handle401`/`scheduleReconnect` пере-регистрируют `this.registration` (`:524-525,553-554`), `registered` заново пишет пару; вызовы в окне разрыва не приходят (сокета нет);
  - `disconnect()` → `sessionBasePaths.clear()` (`:229`);
  - register-timeout → запись не создавалась (устанавливается только на `registered`), вызов такой сессии → отказ;
  - takeover 4409 → socket закрыт, кадров больше нет; map не чистится, но miscouting невозможен (ключи по сессии, новые вызовы сервер маршрутизирует на владельца).
- **Гонки register/ack/tool.call.** Пара (`sessionId`, `basePath`) пишется из closure конкретного `sendRegister`, а `onFrame` проверяет `frame.sessionId === sessionId` (`:363`); параллельные регистрации запрещены (`busyRegisteringOtherThan`/`consent-pending`), поэтому перекрёстная запись исключена. В сервер-порядке `registered` всегда предшествует `tool.call` для этой сессии (один сокет, упорядоченная доставка), значит запись существует до вызова. Вызов «в полёте» исполняется в каталоже, захваченном ДО `await` подтверждения (`:618` фиксирует `basePath` до `awaitConfirmation`), т.е. смена map во время диалога не меняет каталог уже принятого вызова.

## Решение «отвязанная сессия всё равно исполняется» — приемлемо (не блокер)

- Записи прошлых сессий сохраняются до `disconnect()` (design D-1): сервер после отвязки не шлёт новых вызовов сессии, но in-flight вызов на момент переключения может прилететь; исполнение в каталоге **этой** сессии (`call.sessionId`) корректнее отказа. Каталог принадлежит той же сессии — «команда чужой сессии» не исполняется; пользователь уже дал per-session consent на неё (D-93). Для сессии, никогда не регистрировавшейся, — отказ. Контракту §5 (маршрутизация по сессии) и D-88 (basePath per-session) не противоречит. Терминальность для агента не хуже отказа (отчёт §4.2).

## Дефект 2: KC-таймаут — корректно

- `ipc-contract.ts:112, 200-201`: `keycloakRequestTimeoutMs` в `DEFAULT_CONFIG` (15 000) и `ServerConfig`; `config.ts:53` мержит `{...DEFAULT_CONFIG, ...raw, ...e2e}` → старые `config.json` без поля получают дефолт (обратная совместимость, иначе `AbortSignal.timeout(undefined)` бросил бы TypeError).
- `auth.ts:91-94` (`exchangeCode`) и `:151-153` (`refreshTokens`) — `signal: AbortSignal.timeout(cfg.keycloakRequestTimeoutMs)`. В `refreshTokens` abort попадает в catch → `RefreshTransientError` (`:152-153`), т.е. **транзиентный** исход: токены сохраняются, вызывающие получают null, single-flight сбрасывается в `finally` — гонки с single-flight нет. `exchangeCode` без try/catch, но уходит в `startLogin` `.catch` → `exchange-failed` (токенов для очистки нет). Тест `auth-refresh.test.ts:182-202` (мок отклоняет по `signal.abort`, 50 мс) — ловит именно проводку таймаута.

## Дефект 3: consent вне таймаута регистрации — корректно

- Таймер создаётся в `sendRegister` (`relay-client.ts:344-357`), который вызывается **после** `await requestRegistrationConsent` (`:295-296`), — ожидание consent таймаут не расходует. Тест `:344-359` (700 мс > 2×300 мс) — ни одного `register-timeout`, затем `ok`.
- Срыв виден: таймер эмитит `{connected:true, registered:false, phase:'connected', code:'register-timeout', reason:'registration timeout'}` (`:349-357`); renderer показывает `reason` (`relay-label.ts:15`). Тест `:361-382` — статус + повторная регистрация (dedup снят) без перезапуска.

## Тесты — ловят именно регресс

- `relay-client.test.ts:262-278` отказ для незарегистрированной сессии; `:280-309` A(dirA)→B(dirB): вызовы каждой сессии возвращают **свой** `process.cwd()` (black-box через `tool.result`); `:311-342` реконнект + перерегистрация с новым basePath → cwd = dirB, не dirA; `:344-382` consent-vs-timeout и register-timeout+retry. Проверяют наблюдаемое поведение (output/exitCode/статусы/fetch), не внутренности; синхронный in-test сервер и малые таймауты — детерминированы.

## Остаточные замечания (не блокирующие)

1. **(minor) `sessionBasePaths` не очищается на терминальном close 4409/4403** (`relay-client.ts:464-495`): записи живут до `disconnect()`. Miscouting невозможен (кадров нет; ключи по сессии; сервер маршрутизирует иначе), но это «висящая» память до явного disconnect/quit. Design D-1 оговаривает только close-с-реконнектом; терминальный close можно чистить.
2. **(minor) Рост map** ограничен числом сессий, регистрировавшихся за жизнь соединения (design/отчёт признают). При сотнях переключений растёт; альтернатива (LRU) не требуется.
3. **(minor, тест)** Тест отказа `:262-278` не проверяет явно «процесс не запускался» (следует из раннего `return` и текста отказа); можно усилить мок-шпионом на `child_process`.
4. **(minor, текст)** `RefreshTransientError('refresh endpoint unreachable: …')` для таймаута вводит в заблуждение («unreachable» vs `TimeoutError`); причина в тексте есть.
5. **(observation)** 4409/4403 не обнуляют `this.registration` — pre-existing, вне scope.

## Итог

Главный дефект закрыт fail-closed: каталог выбирается строго по `call.sessionId` из консистентной map, отсутствие записи и кадр без `sessionId` ведут к отказу без запуска процесса, все ветки (нет basePath, устаревшая запись, повторный register, reconnect/disconnect, register-timeout, 4409) проверены. Решение по отвязанной сессии согласуется с §5/D-88. KC-таймаут — из конфига и транзиентный, single-flight не ломает; consent не съедает таймаут регистрации, срыв виден и повторим. Тесты ловят регресс. Остаток — minor-замечания, не нарушающие требования.

## ВЕРДИКТ: `approve`

---

# Дополнение: закрытие minor-остатков (2026-09-25)

Проверка заявленного: (1) очистка `sessionBasePaths` на терминальных 4409/4403; (2) тест отказа с независимым признаком «процесс не запускался»; (3) формулировка таймаута отделена от «unreachable» и содержит конфиг-значение.

## Что подтверждено

- **(1) Очистка на терминальных закрытиях.** `relay-client.ts:468` (ветка `protocol`/4403) и `:485` (ветка `registration`/4409) вызывают `sessionBasePaths.clear()` — симметрично `disconnect()` (`:229`); ветки реконнекта (4401 `handle401`, network-drop `scheduleReconnect`, `:498-504`) **не** чистят — инвариант «close с реконнектом сохраняет записи» сохранён. Новая регистрация восстанавливает запись при `registered` (`:376`). Тесты: `relay-client.test.ts:289-310` (4409 → count 0 → ре-регистрация другой сессии → count 1) и `:312-325` (4403 → count 0). Функционально чистить на терминальном close безопасно: после него кадров нет, in-flight вызовы исполняются по `basePath`, захваченному до закрытия.
- **(2) Независимый признак «процесс не запускался».** `relay-client.test.ts:272-286`: команда-отказ `node -e "process.stdout.write('EXECUTED-IN:' + process.cwd())"`; ассерты — `output` содержит `no registration for session sess-never`, `exitCode -1`, **и не содержит** `EXECUTED-IN` **и** `dirA`. Если бы процесс запустился хоть в чужом каталоге, маркер `EXECUTED-IN:` присутствовал бы — тест ловит регресс по факту исполнения, а не только по тексту.
- **(3) Таймаут отделён от «unreachable» и содержит конфиг.** `auth.ts:99-102` (`exchangeCode`) и `:162-165` (`refreshTokens`): при `err.name === 'TimeoutError'` → `timed out after ${cfg.keycloakRequestTimeoutMs} ms` (+`cause`), иначе → `unreachable: …`. В `refreshTokens` таймаут остаётся `RefreshTransientError` (токены сохраняются). Тест `auth-refresh.test.ts:184-205`: abort-мок с `name='TimeoutError'` → сообщение содержит `timed out after 50 ms` и **не** содержит `unreachable`; сетевой кейс по-прежнему `unreachable` + `ECONNREFUSED` (`:95-108`).

## Новое расхождение (minor, не блокирует)

- Delta-спека `specs/desktop-relay-client/spec.md` (единственный MODIFIED-параграф «Локальное исполнение tool.call») и `design.md` D-1 (стр. 22, 24) описывают очистку соответствия **только** на `disconnect()` («записи … сохраняются до `disconnect()`; `disconnect()` очищает соответствие целиком»), тогда как код теперь чистит и на терминальных 4409/4403. Функционально не противоречит (после терминального close вызовы прийти не могут; ветка reconnect, которую спека оговаривает, не затронута), но формулировка «до `disconnect()`» стала неточной. Требование: дополнить спеку/дизайн клаузой «терминальное закрытие (4409/4403) также очищает соответствие» — иначе артефакт не отражает поведение.
- **(minor, тест)** Очистка проверяется через тест-хук `trackedSessionCount` (`relay-client.ts:791-794`, аналог существующего `backoffAttempt`), т.е. по размеру map, а не поведенчески. Приемлемо (паттерн уже есть), но поведенческий ассерт (вызов по старой сессии после терминального закрытия → отказ) был бы строже.

## Итог дополнения

Все три minor закрыты по существу: терминальные закрытия чистят соответствие симметрично disconnect(), тест отказа проверяет фактическое неисполнение независимым маркером, таймаут отдаёт отдельный текст с конфиг-значением и покрыт тестами. Новое расхождение — только формулировка delta-спеки/дизайна про терминальные закрытия (minor); новых блокеров нет.

## ВЕРДИКТ: `approve`
