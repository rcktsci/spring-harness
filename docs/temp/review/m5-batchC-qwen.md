# M5 Batch C Review — Qwen 3.8‑27B

**Дата:** 2026-09-23
**Объект:** незакоммиченные изменения поверх `d801321` (пачка C, задачи 3.1–3.5)
**Скоуп:** `openspec/changes/m5-web-desktop/tasks.md` 3.1–3.5, `specs/desktop-relay-client/spec.md` (все требования), `design.md` D-91/D-93 против `web-desktop/src/main/{relay-client.ts, local-tools.ts, index.ts}`, `src/shared/ipc-contract.ts`, `src/preload/index.ts`, `src/renderer/src/composables/useRelay.ts`, `tests/unit/{relay-client,local-tools}.test.ts`, `tests/unit/relay-harness.ts`.
**Сборка:** не запускалась (верификация по файлам; дев-сессия сообщила `pnpm verify` 65/65). D-91/D-93 присутствуют в `design.md` (реализованы в коде).

---

## Вердикт: **REJECT**

Требования 3.1–3.5 в целом реализованы и архитектурно чисты (main держит секреты/сеть, renderer — тонкий IPC-адаптер, gating `confirmCommands` без обходных путей). Однако обнаружены дефекты, нарушающие зафиксированные требования спеки и явное правило владельца «хардкод чисел запрещён»; без фикса «100% требований» не выполняется.

---

## Чек‑лист задания

| # | Пункт | Итог |
|---|---|---|
| 1 | backoff 1s→30s, экспонента, из конфига | ✅ `relay-client.ts:423-440` + defaults `ipc-contract.ts:71-73` (1_000/30_000/×2). См. M1 — нет сброса на успех |
| 2 | handshake‑timeout → reconnect | ✅ `relay-client.ts:155-172` → `closeSocketAndReconnect`; тест `relay-client.test.ts:205` |
| 3 | 4401 → silent refresh → ретрай | ✅ `relay-client.ts:378-382,393-409`; теста нет (N‑тест) |
| 4 | 4403 фатально, без реконнекта | ✅ `relay-client.ts:354-365`; тест `relay-client.test.ts:84` |
| 5 | 4409 superseded — без реконнекта | ✅ `relay-client.ts:366-377`; тест `relay-client.test.ts:98`. См. M2 (reason для остальных 4409) |
| 6 | ping‑watchdog | ✅ `relay-client.ts:484-490` (2×интервал из конфига); теста нет |
| 7 | mkdir basePath ДО register | ✅ `relay-client.ts:229-230` |
| 8 | все error‑ответы register | ✅ 5 кодов в `relay-client.ts:59-65`, обработка `289-304`; M2 по close 4409 |
| 9 | авто‑register при старте + очистка stale | ✅ `index.ts:90-106` (сброс при `session-not-found`/`wrong-session-kind`) |
| 10 | bash timeout = min(args, server) | ✅ `local-tools.ts:93-94`; см. D1 (дефолт не `<` серверного) |
| 11 | cancel неизвестного callId — игнор | ✅ `relay-client.ts:545-553`; тест `relay-client.test.ts:298` |
| 12 | SIGTERM→SIGKILL | ✅ `local-tools.ts:118-144`; grace‑число хардкод → M5 |
| 13 | read/write/edit/glob/grep по спеке | ✅ в целом (`local-tools.ts:170-336`); N3/N5 + M3 |
| 14 | confirmCommands=always по умолчанию, блокирует | ✅ `ipc-contract.ts:80` + `relay-client.ts:509-521`; тесты `relay-client.test.ts:234,259` |
| 15 | токены/секреты не в renderer (D-91) | ✅ `preload/index.ts` (только `loggedIn`), `useRelay.ts` — IPC |
| 16 | числа из конфига | ⚠️ частично — M5 |

---

## Блокеры (Major)

**M1. `reconnectAttempt` не сбрасывается при успешном подключении → backoff навсегда эскалирует до 30 с.**
`relay-client.ts:73` (поле), `:430` (`+= 1`), сброс только в `:404` (`handle401`). `connect()`/`handleFrame('welcome')`/успешный `sendRegister` сброса не делают. После первого же сетевого обрыва и удачного реконнекта счётчик остаётся `1`; при следующих обрывах задержка растёт `×2` до потолка и там остаётся, даже когда сессия только что была восстановлена.
**Действие:** обнулять `reconnectAttempt = 0` в `handleFrame('welcome')` (или после успешного `registered`); добавить тест на сброс.

**M2. Close 4409 безусловно трактуется как `superseded` → пользователю показывается неверная причина для остальных кодов.**
Контракт §5.2/§5.5: отказ регистрации приходит как `error { code }` **+ close 4409** для всех кодов (`session-not-found`, `wrong-session-kind`, `workspace-occupied`, `duplicate-tool-name`). `relay-client.ts:366-377` (reason на `:374`) всегда эмитит «Session opened in another place.» и `code:'superseded'`, перетирая корректный статус из error‑фрейма (`:294-302`). Тест `relay-client.test.ts:166` шлёт только error без close, поэтому дефект не ловится.
**Действие:** при close 4409 не перетирать уже сообщённый код/причину (запомнить, что отказ пришёл error‑фреймом), либо классифицировать по close‑reason; добавить тест «error + close 4409» для workspace-occupied.

**M3. `runGrep` с невалидным regex бросает исключение; `handleToolCall` не имеет `catch` → `tool.result` не отправляется, вызов висит до серверного `tool-timeout`.**
`local-tools.ts:294` (`new RegExp(pattern, …)`); `relay-client.ts:508-542` — только `try/finally`, без `catch`, вызов через `void this.handleToolCall(frame)` (`:470`) → unhandled rejection.
**Действие:** оборачивать компиляцию regex в try/catch и возвращать `tool.result` с ошибкой; добавить `catch` в `handleToolCall`, гарантирующий отправку `tool.result` (exitCode≠0) при любом исключении.

**M4. Утечка listener'а при таймауте регистрации.**
`relay-client.ts:269-306`: в ветках `registered`/`error` делается `removeListener('frame', onFrame)` (`:278,:291`), а в `setTimeout`‑колбэке (`:270-273`) — нет. Каждый таймаут оставляет подписку на `frame` навсегда (накопление листенеров, `MaxListenersExceededWarning`, поздние фреймы).
**Действие:** добавить `this.removeListener('frame', onFrame)` в таймаут‑колбэк.

**M5. Хардкод числовых параметров — нарушение AGENTS («Все числовые параметры — конфиг; хардкод чисел запрещён»).**
`local-tools.ts:128,142` — grace SIGKILL `2000` мс; `local-tools.ts:254` — `maxResults ?? 1000`; `local-tools.ts:292` — `maxMatches ?? 1000`.
**Действие:** вынести в `ServerConfig`/дефолты (`toolKillGraceMs`, `toolGlobMaxResults`, `toolGrepMaxMatches`).

---

## Прочие находки

**D1 (Medium). Дефолтный клиентский таймаут равен серверному потолку, а не строго меньше.**
`ipc-contract.ts:75` `relayToolCallTimeoutMs: 300_000` = ровно 5 мин. Спека «Безопасность локального исполнения»: клиентский таймаут «обязан быть **<** серверного `tool-call-timeout` 5 мин». При равенстве гонка с серверным `tool-timeout`.
**Действие:** дефолт < 300 000 (напр. 270 000–295 000), зафиксировать в комментарии.

**D2 (Medium). Смена `serverBaseUrl` не пересоздаёт/не переподключает relay.**
`RelayClient.cfg` — `readonly`, захвачен в конструкторе (`relay-client.ts:87-92`); `onServerConfigChanged` (`index.ts:137-151`) лишь логирует «clients will reconnect» и шлёт renderer'у `config:changed`, но relay с прежним URL остаётся.
**Действие:** пересоздавать `RelayClient` (или добавить `updateConfig`) при смене endpoint'а.

**D3 (Medium). `this.registration` выставляется до фактической регистрации.**
`relay-client.ts:253` — `registration` устанавливается перед `sendRegister`. При отказе (workspace-occupied и др.) `currentStatus.registered === true` (`:625`), а реконнект вновь шлёт register на отклонённую сессию.
**Действие:** присваивать `registration` только в ветке `registered`.

**D4 (Medium, тесты). Пробелы покрытия 3.5.**
`tests/unit/relay-client.test.ts`: нет тестов ping‑watchdog, 4401 (refresh+retry), экспоненциального роста/сброса backoff, кодов `session-not-found`/`wrong-session-kind`/`duplicate-tool-name` через WS, «error + close 4409».
**Действие:** добавить перечисленные кейсы.

**N1 (Minor). `currentStatus.phase === 'fatal'` после штатного `disconnect()`.**
`relay-client.ts:627-633`: `stopped` переиспользуется как флаг «fatal»; `disconnect()` ставит `stopped=true` (`:188`). `RELAY_STATUS` вернёт `fatal` вместо `disconnected`.
**Действие:** развести флаги `stopped`/`fatal`.

**N2 (Minor). `IPC.TOOL_CONFIRM` в списке `implemented`, но обработчик не зарегистрирован.**
`index.ts:168`; ни `ipcMain.handle(IPC.TOOL_CONFIRM, …)`, ни preload‑обёртки нет → любой invoke отклоняется «No handler registered».
**Действие:** убрать из `implemented` (или реализовать/удалить канал).

**N3 (Minor). Лимит усечения — посимвольный, спека говорит о байтах.**
`local-tools.ts:61-63,108`: `text.length > limit`, где `limit = outputLimitBytes`; для UTF‑8 мультибайт реальный размер может превысить лимит.
**Действие:** сравнивать `Buffer.byteLength(text,'utf8')` либо задокументировать отклонение.

**N4 (Minor). `handle401` без ограничения повторов.**
`relay-client.ts:393-409`: при повторных 4401 возможен цикл refresh→connect без backoff.
**Действие:** ограничить число попыток или уважать backoff.

**N5 (Minor). `glob` добавляет каталоги, совпавшие с паттерном.**
`local-tools.ts:272-274`: `if (regex.test(rel)) matches.push(rel)` без проверки `entry.isDirectory()`.
**Действие:** если ожидаются только файлы — пропускать каталоги.

**N6 (Info).** `register()` при ошибке `mkdir` реджектит промис без обработки (низкая вероятность); сообщение `write_file` считает `content.length` в символах.

---

## Отклонения дева — допустимы

1. **consent early-decision (`pendingConsent`)** — рационализация гонки «ответ пришёл раньше запроса»; корректна, отклонением от спеки не является (улучшает надёжность).
2. **Gated `toolCall` emit (только при `confirmCommands==='always'`)** — renderer использует событие исключительно для подтверждения; при `never` видимость вызова обеспечивает лента чата (пачка D). Допустимо.
3. **`write_file` создаёт родительские каталоги** — удобно для оркестратора, согласуется с D-88 (path‑guard на клиенте отсутствует осознанно). Допустимо, отметить в apply‑notes.

---

## Сильные стороны

- D-91 соблюдён безупречно: JWT/refresh/WS/файловые операции — только в main; preload отдаёт renderer'у `loggedIn` без токена; `contextIsolation`/`sandbox`.
- `confirmCommands='always'` реально блокирует исполнение: единственный путь к `executeTool` — через ожидание `awaitConfirmation` (`relay-client.ts:509-521`), обходных IPC нет.
- Жизненный цикл сокета аккуратен: `dropSocket`/`clearTimers`, one‑shot `once` для handshake, дедуп `registering`.
- Тесты чистые, on‑test WS‑сервер честно реализует §5; негативные пути (`timeout`, `ambiguous`, `not-found`, `truncated`, cancel‑гонка) покрыты для local‑tools.

---

## Резюме (REJECT)

Пачка C архитектурно зрелая, но не достигает 100% требований:
1. **M1** — backoff не сбрасывается на успехе (деградация реконнекта).
2. **M2** — пользователь получает «superseded» на любой 4409‑отказ (нарушение «Обработка ответов» спеки).
3. **M3** — исключение в tool повышает риск зависшего вызова без `tool.result`.
4. **M4** — утечка listener'а при таймауте регистрации.
5. **M5** — хардкод чисел против явного правила владельца.
6. **D1** — дефолт клиентского таймаута нарушает «строго < серверного».

После фикса M1–M5 и D1 (D2–D4 — до архива M5) пачка может быть переведена в APPROVE повторным циклом.

---

## Re-approval

**Дата:** 2026-09-23 (повторная верификация по файлам, сборка не запускалась)
**Заявлено:** M1–M5, D1–D4, +11 тестов, 76/76 pass.

### Проверка фиксов

| ID | Фикс | Файл:строка | Итог |
|---|---|---|---|
| M1 | Сброс `reconnectAttempt` на welcome | `relay-client.ts:483` (+ getter `:691`) | ✅ подтверждён |
| M2 | `lastRegisterError` сохраняет причину error‑фрейма; close 4409 не перетирает | `relay-client.ts:84,282,312,390-407` | ✅ подтверждён |
| M3 | `executeTool` try/catch + catch в `handleToolCall` → `tool.result` | `local-tools.ts:369-395`; `relay-client.ts:576-590` | ✅ подтверждён |
| M4 | `removeListener('frame')` в таймаут‑ветке | `relay-client.ts:286` | ✅ подтверждён |
| M5 | Числа в конфиг: `toolKillGraceMs`, `toolGlobMaxResults`, `toolGrepMaxMatches` | `ipc-contract.ts:84-89`; `local-tools.ts:36-40,134,148,260,298`; `relay-client.ts:562-564` | ✅ подтверждён |
| D1 | Дефолт `relayToolCallTimeoutMs: 295_000` < 300 000 | `ipc-contract.ts:79` | ✅ подтверждён |
| D2 | Пересоздание relay при смене `baseUrl` (`createRelay`) | `index.ts:124-141,160-169` | ✅ подтверждён |
| D3 | `registration` присваивается только в ветке `registered`; отказ очищает | `relay-client.ts:296,313-316` | ✅ подтверждён |
| D4 | +11 тестов (M1, watchdog, 4401, 3× error‑коды, M2, M4, M3, D3, grep‑regex) | `relay-client.test.ts:328-478`; `local-tools.test.ts:178-183` | ✅ подтверждён (11 новых, суммарно 76) |

Дополнительно подтверждены ранее minor: **N1** — отдельный флаг `fatal` (`relay-client.ts:75-76,197-198,379-380,679-683`), `currentStatus` больше не рапортует `fatal` после штатного disconnect; **N2** — `IPC.TOOL_CONFIRM` убран из `implemented`, теперь корректно попадает в stub с исключением (`index.ts:175-201`).

### Регрессии

Не обнаружено. Побочные ветки проверены: успешный `sendRegister` обнуляет `lastRegisterError` (`:282`), `disconnect()` — тоже (`:205`); catch в `handleToolCall` не отправляет результат для отменённого вызова (`:581-583`); `createRelay` захватывает новый `config` после `config = next` (`index.ts:151,163`).

### Остаточные (не блокирующие, приняты в бэклог)

- **N3 (Info):** лимит усечения — символьный, не байтовый (`local-tools.ts:68,114`). Задокументировать или `Buffer.byteLength`.
- **N4 (Minor):** `handle401` без ограничения повторов (`relay-client.ts:424-440`).
- **N5 (Minor):** `glob` добавляет совпавшие каталоги (`local-tools.ts:279`).
- **Info:** множитель буфера bash `outputLimitBytes * 2` (`local-tools.ts:114-116`) — внутренняя стратегия, не пользовательский параметр.

### Вердикт повторного ревью: **APPROVE**

Все блокеры (M1–M5) и D1 устранены, D2–D4 закрыты с тестами и без регрессий; `pnpm verify` (65→76) заявлен зелёным. Требования 3.1–3.5 и `desktop-relay-client` выполнены. Остаточные N3–N5 — не мешают приёмке и могут быть оформлены отдельными задачами.
