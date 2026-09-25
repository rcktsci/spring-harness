# Отчёт: relay-session-workspace-isolation (GLM-5.3-Flash, субагент-разработчик)

Дата: 2026-09-25. Change: `openspec/changes/relay-session-workspace-isolation/` (создан до кода, `openspec validate --strict` — valid). Delta-спеки: `desktop-relay-client` (MODIFIED «Локальное исполнение tool.call», «Регистрация на сессии с декларацией») и `desktop-shell` (MODIFIED «SSO-логин и хранение токена» — таймауты авторизации).

## Дефект 1 (главный) — bash исполнялся в каталоге чужой сессии

**Суть**: `handleToolCall` брал каталог из изменяемого поля `this.basePath ?? this.registration?.basePath`, игнорируя `call.sessionId`. Поле перезаписывается при каждой регистрации (`register()`, `registered`-ветка), поэтому вызов сессии A, прилетевший после записи поля регистрацией B (гонка стартовой авторегистрации и открытия сессии; поздний ack), исполнялся в каталоге B. Сервер маршрутизирует вызовы строго по сессии — клиент был единственным местом, где сессия «терялась».

**Решение** (design D-1):
- `sessionBasePaths: Map<sessionId, basePath>` — запись добавляется при `registered`; `handleToolCall` читает **только** `sessionBasePaths.get(call.sessionId)`.
- Отсутствие записи → явный отказ: `tool.result { exitCode: -1, output: 'no registration for session <id> — cannot execute' }` + локальный `toolResult`-эмит (виден в ленте) — процесс не запускается, чужой каталог не используется.
- **Записи прошлых сессий** (переключение A→B) остаются до `disconnect()`: сервер после отвязки A новых вызовов A не шлёт, но вызов «в полёте» на момент переключения прилететь может — исполнение в каталоге A корректнее отказа из-за гонки; запись не может дать неверного исполнения (каталог A принадлежит A); рост ограничен числом сессий за жизнь соединения.
- `close` (разрыв с последующим reconnect+re-register) — **не** чистит: перерегистрация восстанавливает запись; `disconnect()` — чистит (соединение и все его сессии мертвы).
- Поле `this.basePath` сохранено только для UI (`currentStatus`) и больше не участвует в выборе каталога.

**Политика конкурентных регистраций** (design D-2): **запрет** (уже в проде с фикс-раунда `relay-connect-consent-confirm`: `register-in-progress`/`consent-pending` через `busyRegisteringOtherThan`) — обратный порядок ack'ов структурно недостижим. Per-session map при этом сохранён: он устраняет сам класс бага и покрывает легальные последовательные переключения A→B и смену basePath при перерегистрации.

**Тесты** (`relay-client.test.ts`, +5):
- (а) «rejects a tool call for a session without registration…» — `tool.call` для `sess-never` → отказ `no registration for session sess-never`, exitCode -1, процесс не запускался.
- (б) «executes calls of successively registered sessions in their own workspaces» — A(dirA) → B(dirB): вызовы обеих сессий (`node -e "process.stdout.write(process.cwd())"`) возвращают **свой** каталог.
- (в) «uses the freshly registered basePath after a reconnect» — разрыв (terminate) → реконнект+перерегистрация → перерегистрация той же сессии с dirB → вызов исполняется в dirB, не в dirA.

## Дефект 2 — запросы к Keycloak без таймаута

**Суть**: `fetch` token endpoint (silent refresh и обмен кода) без таймаута — зависание сети до KC подвешивало UI (пустые списки, ни ошибки, ни лога).

**Решение** (design D-3): новый параметр `keycloakRequestTimeoutMs` (DEFAULT_CONFIG = 15 000; правило «числа — через конфиг» соблюдено), оба fetch (`refreshTokens`, `exchangeCode`) с `AbortSignal.timeout(cfg.keycloakRequestTimeoutMs)`. Истечение — в существующую **транзиентную** ветку (`RefreshTransientError`): токены сохраняются, вызывающие получают «нет токена»/`exchange-failed`, причина (TimeoutError) — в warn/error логе. `refreshTokens` оборачивает и сетевые ошибки в `RefreshTransientError`.

**Тест**: «aborts a hanging token endpoint request by the configured timeout» — никогда не резолвящийся fetch с сигналом → `refreshIfNeeded` возвращает null за таймаут, `clearTokens`/`saveTokens` не вызывались, warn-лог содержит причину (`unreachable`/`aborted`).

## Дефект 3 — consent-диалог исчезал, регистрация молча умирала

**Суть/анализ**: таймер регистрации стартует с отправкой register-фрейма — **после** ответа consent, т.е. ожидание согласия таймаут не consumes (это требование выполнено архитектурно и теперь зафиксировано тестом). Наблюдаемое исчезновение диалога — срыв регистрации по таймауту (сервер не подтвердил за 10 с) происходил **молча**: статус не эмитился, UI не показывал причину.

**Решение** (design D-4):
- Тест-фиксация «does not consume the register timeout while consent is pending» — 700 мс ожидания (> 2× `relayRegisterTimeoutMs`=300) без ответа → ни одного `register-timeout`-статуса; после ответа регистрация проходит штатно.
- Срыв по таймауту теперь **виден**: эмитится `{connected, registered:false, phase:'connected', code:'register-timeout', reason:'registration timeout'}` → строка релея показывает причину.
- Повтор: dedup снят (`registering` сброшен), consent запрашивается заново — кнопка «Подключить»/переоткрытие сессии без перезапуска приложения.

**Тест**: «surfaces a register-timeout status and allows a retry without restart» — таймаут → `register-timeout` в статусах → повторная регистрация (с новым consent) завершается `ok:true`.

## Команды и результаты

- `pnpm verify`: **зелёный** — Vitest **30 файлов / 248 тестов passed** (+10: +5 relay-client, +1 abort, +4 ранее не учтённых в счёте итерации — см. тесты выше), build ok.
- `pnpm e2e:electron`: **2 passed** (~4.6 c) — stub расширения не потребовал: сценарии гонки покрыты unit-уровнем против in-test WS-сервера; e2e-флоу (одна регистрация) не затронут.
- `pnpm e2e:stub`: **7 passed** (~7.4 c).
- `openspec validate relay-session-workspace-isolation --strict`: **valid**.

## Остаточные риски

1. Записи прошлых сессий живут до `disconnect()` — при очень длинной жизни соединения с сотнями переключений соответствие растёт (числа сессий, не каталогов; каждый entry — два строки) — признано приемлемым (см. D-1).
2. Вызов «в полёте» для сессии, отвязанной сервером при переключении, исполняется (в своём каталоге), а не отклоняется — серверный контракт такую гонку допускает; терминальность для агента не хуже отказа.
3. Таймаут KC-запроса (15 с) — стартовое значение: если Keycloak на VM отвечает медленнее (heavy load), параметр поднимается конфигом без релиза.
4. Отказ «no registration for session …» для легитимной сессии возможен только при рассинхроне соответствия и сервера (гонка отвязки) — терминален для агента; альтернатива (исполнение в «последнем известном» каталоге) выбрана для зарегистрировавшихся ранее сессий.

---

## 8. Фикс-раунд (три minor от ревьюеров, approve)

### 8.1 Очистка sessionBasePaths на терминальных закрытиях

**Суть**: соответствие sessionId → basePath чистилось только в disconnect(); после 4409 superseded / 4403 protocol-mismatch записи оставались, хотя соединение и его сессии мертвы.

**Решение**: sessionBasePaths.clear() в close-обработчике для обеих терминальных веток (protocol и egistration) — симметрично disconnect(). Test-hook 	rackedSessionCount (по образцу ackoffAttempt) для наблюдаемости.

**Тесты** (+2): «clears the session-to-basePath mapping on terminal closes» (4409 → count 0 → новая регистрация восстанавливает соответствие, count 1) и «…on a fatal protocol close» (4403 → count 0).

### 8.2 Отказ-тест усилен: процесс не запускался

**Суть**: тест (а) не доказывал главное — что процесс не был запущен в неизвестном каталоге.

**Решение**: команда отказ-вызова теперь 
ode -e "process.stdout.write('EXECUTED-IN:' + process.cwd())"; ассерты: output содержит отказ 
o registration for session sess-never, exitCode -1, **не** содержит EXECUTED-IN и **не** содержит каталог зарегистрированной сессии (dirA) — регресс «начали исполнять в неизвестном каталоге» ловится по двум независимым признакам.

### 8.3 Точная формулировка таймаута KC

**Суть**: «unreachable» использовалось и для зависания ответа (таймаут) — путало диагностику (unreachable = endpoint недоступен, таймаут = ответ не пришёл вовремя).

**Решение**: catch в efreshTokens и exchangeCode разделяет TimeoutError (от AbortSignal.timeout) → «timed out after <N> ms» (N — из конфига, keycloakRequestTimeoutMs) и прочее → «unreachable». Оба с cause (требование preserve-caught-error). Тест abort проверяет точный текст 	imed out after 50 ms и отсутствие unreachable; сетевой тест остаётся на unreachable+ECONNREFUSED. Имитация abort в тесте уточнена: rejection с 
ame='TimeoutError' — как реальный AbortSignal.timeout.

### Команды и результаты (фикс-раунд)

- pnpm verify: **зелёный** — Vitest **30 файлов / 250 тестов passed** (+2 теста очистки, усилен отказ-тест, +2 ассерта abort-теста), build ok.
- pnpm e2e:electron: **2 passed** (~5.3 c).
- pnpm e2e:stub: **7 passed** (~7.4 c).
- openspec validate relay-session-workspace-isolation --strict: **valid**.

### Осталось открытым

Без изменений относительно §7: записи прошлых сессий до disconnect, полётный вызов отвязанной сессии исполняется в своём каталоге, 15 с на KC — конфигурируемо.
