# client-relay Specification

## Purpose

WebSocket-релей: внешний клиент (будущий Web Desktop, в M4 — тестовый клиент) подключается к серверу, регистрируется на **сессии** (D-84: единица маршрутизации — сессия, не задача), декларирует свои инструменты и исполняет `tool.call`'ы локально. Протокол — api-contracts §5.

## ADDED Requirements

### Requirement: Handshake и аутентификация

WS-эндпоинт `/api/v1/relay` SHALL принимать соединения с Bearer-JWT (тот же токен, что у REST; SSO-гейт D-41 — groups-claim). Без/с кривым токеном — close 4401. После подключения клиент SHALL отправить `hello { protocol: 1 }`; сервер отвечает `welcome { protocol }`. Любой фрейм до `hello` → close 4403. Несовместимая версия протокола → close 4403 `protocol-mismatch`.

#### Scenario: штатное подключение

- **WHEN** клиент подключается с валидным JWT и шлёт `hello { protocol: 1 }`
- **THEN** сервер отвечает `welcome { protocol: 1 }`

#### Scenario: без токена

- **WHEN** клиент подключается без JWT
- **THEN** сервер закрывает соединение с кодом 4401

#### Scenario: фрейм до hello

- **WHEN** клиент шлёт `register` до `hello`
- **THEN** сервер закрывает соединение с кодом 4403

### Requirement: Регистрация на сессию с декларацией инструментов

Клиент SHALL зарегистрироваться фреймом `register { sessionId, basePath, client: { version, tools[] } }`. `basePath` — локальный корень клиента (информативно, для логов/UI). `tools[]` — декларация клиентских инструментов (см. `client-tool-bridge`). Регистрация на несуществующую сессию → `error session-not-found` (close 4409). Регистрация на STATE-сессию задачи → `error wrong-session-kind` (close 4409): релею доступны только FREE root-сессии. Успех → `registered { sessionId }` и маршрутизация последующих `tool.call` этому клиенту. Повторный `register` с того же соединения — idempotent success. Нераспознаваемая декларация (аномальный `inputSchema`) → close 4403 `protocol`.

#### Scenario: успешная регистрация

- **WHEN** клиент регистрируется на существующую FREE-сессию
- **THEN** сервер отвечает `registered { sessionId }` и сохраняет декларацию инструментов как активный оверлей

#### Scenario: несуществующая сессия

- **WHEN** sessionId неизвестен
- **THEN** `error session-not-found`, close 4409

#### Scenario: STATE-сессия не регистрируется

- **WHEN** клиент регистрируется на STATE-сессии задачи
- **THEN** `error wrong-session-kind`, close 4409

### Requirement: Takeover и идемпотентность реестра

Соединение на sessionId SHALL подчиняться политике: живое соединение и регистрируется **другое** соединение того же principal → takeover: старое закрывается кодом 4409 с `error superseded`, новое регистрируется; другой principal → `error workspace-occupied` (close 4409). `unregister` SHALL выполняться CAS по connection-identity — протухший сокет не может удалить сменившее его новое соединение.

#### Scenario: takeover тем же пользователем

- **WHEN** клиент переподключается новым соединением при живом старом
- **THEN** старое закрывается 4409 `superseded`, новое получает `registered`

#### Scenario: конфликт разных пользователей

- **WHEN** другой пользователь регистрируется на занятую сессию
- **THEN** `error workspace-occupied`, close 4409

#### Scenario: протухший сокет не вытесняет новое

- **WHEN** heartbeat-таймаут старого сокета срабатывает после реконнекта
- **THEN** старое соединение удаляется из реестра только если оно всё ещё там (CAS), новое остаётся

### Requirement: Двунаправленная маршрутизация tool-фреймов

Сервер SHALL пересылать агентские вызовы клиенту фреймом `tool.call { callId, sessionId, tool, args }` (где `tool` — free-form имя из декларации) и принимать ответы `tool.result { callId, output, exitCode }` (идемпотентно по `callId` — первый финальный результат выигрывает) и опциональные `tool.progress { callId, chunk }`. `exitCode` — информативное поле: non-zero exit **не** ошибка инструмента (та же семантика, что у native `bash`); финальный `TOOL_RESULT` получает статус OK. `tool.cancel { callId }` SHALL отправляться при отмене Turn'а (stop) — клиент прерывает локальное исполнение; повторный `tool.result` после cancel игнорируется. Незавершённый `tool.call` при разрыве соединения закрывается синтетическим `TOOL_RESULT LOST` «потеряно при отключении исполнителя». Журнал финального результата пишется только Turn-потоком под `sess`-локом (та же модель, что у async/MCP — D-64); WS-поток только complete'ит future по callId.

#### Scenario: вызов и результат

- **WHEN** агент вызывает клиентский инструмент
- **THEN** клиент получает `tool.call`, исполняет локально, шлёт `tool.result`; результат доходит до агента как TOOL_RESULT

#### Scenario: дублирующий результат

- **WHEN** клиент шлёт два `tool.result` с одним `callId`
- **THEN** принимается первый, второй игнорируется

#### Scenario: отмена

- **WHEN** на сервере пришёл stop во время in-flight `tool.call`
- **THEN** клиенту уходит `tool.cancel`; последующие `tool.result` по этому callId игнорируются

#### Scenario: разрыв с in-flight вызовом

- **WHEN** соединение клиента разрывается, есть незавершённый `tool.call`
- **THEN** в журнал сессии дописывается синтетический TOOL_RESULT LOST «потеряно при отключении исполнителя»

### Requirement: Heartbeat и таймауты

Соединение SHALL поддерживаться `ping`/`pong`, инициируемыми **сервером**, с интервалом `harness.relay.heartbeat-interval` (конфиг, дефолт 15 с); разрыв — по превышению `2 × heartbeat-interval` без ответного pong. `tool.call`, не получивший ответа за `harness.relay.tool-call-timeout` (конфиг, дефолт 5 мин), SHALL заканчиваться синтетическим `TOOL_RESULT ERROR tool-timeout`. Исходящие кадры на одном WS-соединении SHALL сериализоваться общим мьютексом/очередью (параллельные отправки из Turn'ов root-сессии и её sub-сессий, а также ping'и планировщика).

#### Scenario: живое соединение

- **WHEN** сервер шлёт `ping`, клиент отвечает `pong`
- **THEN** соединение удерживается

#### Scenario: молчащий клиент

- **WHEN** клиент не отвечает на два подряд `ping`
- **THEN** соединение разрывается, in-flight вызовы закрываются LOST

#### Scenario: tool.call завис

- **WHEN** `tool.call` не получает ответа сверх `tool-call-timeout`
- **THEN** агент получает TOOL_RESULT ERROR tool-timeout

### Requirement: Реестр соединений и видимость

Активные соединения SHALL храниться в in-memory реестре, ключём которого является `sessionId`; одна сессия — одно активное соединение (с учётом takeover). Реестр не персистится (D-80): рестарт процесса обнуляет его, а незавершённые вызовы закрываются рестарт-сканом как LOST. Регистрация/разрыв SHALL отражаться в логах (MDC: sessionId, principal, число инструментов) — отдельной audit-таблицы нет (D-77).

#### Scenario: рестарт процесса

- **WHEN** процесс упал и поднялся
- **THEN** реестр соединений пуст; незавершённые tool.call закрыты рестарт-сканом LOST; новый register возможен

#### Scenario: логирование handshake

- **WHEN** клиент регистрируется
- **THEN** в логе появляются sessionId, principal и число декларированных инструментов
