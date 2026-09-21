# client-relay Specification

## Purpose

WebSocket-релей исполнителя CLIENT_EXEC: внешний клиент (будущий Web Desktop, в M4 — тестовый клиент) подключается к серверу, регистрируется на workspace задачи `(taskId, binding)`, декларирует свои инструменты и исполняет `tool.call`'ы локально. Протокол — api-contracts §5. Параллельный маршрутизатор инструментальных вызовов нативному серверному исполнению (workspace-tools) и MCP-клиентам (mcp-client).

## ADDED Requirements

### Requirement: Handshake и аутентификация

WS-эндпоинт `/api/v1/relay` SHALL принимать соединения с Bearer-JWT (тот же токен, что у REST; SSO-гейт D-41 — groups-claim). Без/с кривым токеном — close 4401. После подключения клиент SHALL отправить `hello { protocol: 1 }`; сервер отвечает `welcome { protocol }`. Любой фрейм до `hello` → close 4403. Версия протокола несовместима → close 4403 `protocol-mismatch`.

#### Scenario: штатное подключение

- **WHEN** клиент подключается с валидным JWT и шлёт `hello { protocol: 1 }`
- **THEN** сервер отвечает `welcome { protocol: 1 }`

#### Scenario: без токена

- **WHEN** клиент подключается без JWT
- **THEN** сервер закрывает соединение с кодом 4401

#### Scene: фрейм до hello

- **WHEN** клиент шлёт `register` до `hello`
- **THEN** сервер закрывает соединение с кодом 4403

### Requirement: Регистрация исполнителя с декларацией инструментов

Клиент SHALL зарегистрироваться фреймом `register { taskId, binding, basePath, client: { version, tools[] } }`. `binding` — логический ключ workspace-биндинга состояния (по умолчанию — единственный биндинг задачи; при отсутствии/неоднозначности — `error binding-required`). `basePath` — локальный корень клиента (информативно, для логов/UI). `tools[]` — декларация клиентских инструментов (см. `client-tool-bridge`). Регистрация на задачу, не находящуюся в CLIENT_EXEC-состоянии → `error task-not-client-exec` (close 4409). Workspace `(taskId, binding)` уже занято живым соединением → `error workspace-occupied` (close 4409). Успех → `registered { workspaceId }` и маршрутизация последующих `tool.call` этому клиенту.

#### Scenario: успешная регистрация

- **WHEN** клиент регистрируется на задачу в CLIENT_EXEC-состоянии со свободным workspace
- **THEN** сервер отвечает `registered { workspaceId }` и сохраняет декларацию инструментов как активный оверлей

#### Scenario: занятый workspace

- **WHEN** второй клиент регистрируется на занятый `(taskId, binding)`
- **THEN** сервер отвечает `error workspace-occupied` и закрывает соединение с кодом 4409

#### Scenario: задача не в CLIENT_EXEC

- **WHEN** клиент регистрируется на задачу в SERVER-исполнении
- **THEN** `error task-not-client-exec`, close 4409

### Requirement: Двунаправленная маршрутизация tool-фреймов

Сервер SHALL пересылать агентские вызовы клиенту фреймом `tool.call { callId, sessionId, tool, args }` и принимать ответы `tool.result { callId, output, exitCode }` (идемпотентно по `callId` — первый финальный результат выигрывает, дубликаты игнорируются) и опциональные `tool.progress { callId, chunk }`. `tool.cancel { callId }` SHALL отправляться при отмене Turn'а (stop) — клиент прерывает локальное исполнение; повторный `tool.result` после cancel игнорируется. Незавершённый `tool.call` при разрыве соединения закрывается синтетическим `TOOL_RESULT LOST` «потеряно при отключении исполнителя» (та же семантика LOST, что у workspace-tools).

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

Соединение SHALL поддерживаться `ping`/`pong` с интервалом `harness.relay.heartbeat-interval` (конфиг, дефолт 15 с); разрыв — по превышению `2 × heartbeat-interval` без ответного pong. `tool.call`, не получивший ответа за `harness.relay.tool-call-timeout` (конфиг), SHALL заканчиваться синтетическим `TOOL_RESULT ERROR tool-timeout` (не LOST — исполнитель жив, но не отвечает).

#### Scenario: живое соединение

- **WHEN** сервер шлёт `ping`, клиент отвечает `pong`
- **THEN** соединение удерживается

#### Scenario: молчащий клиент

- **WHEN** клиент не отвечает на два подряд `ping`
- **THEN** соединение разрывается, in-flight вызовы закрываются LOST

#### Scenario: tool.call завис

- **WHEN** `tool.call` не получает ответа сверх `tool-call-timeout`
- **THEN** агент получает TOOL_RESULT ERROR tool-timeout

### Requirement: Переподключение и grace-период

При разрыве соединения задача SHALL оставаться в CLIENT_EXEC-состоянии и ждать нового исполнителя в течение `harness.relay.session-grace-period` (конфиг, дефолт 60 мин); повторный `register` на тот же `(taskId, binding)` SHALL успешно восстановить маршрутизацию, а незавершённые к моменту разрыва вызовы, уже закрытые синтетическим LOST, НЕ переотправляются. По истечении grace-периода задача переводится в ERROR-переход (разборщик), если граф его предусматривает, иначе остаётcя в CLIENT_EXEC до явного вмешательства.

#### Scenario: переподключение в пределах grace

- **WHEN** клиент переподключается и регистрируется на тот же workspace в течение grace-периода
- **THEN** маршрутизация восстанавливается, новые `tool.call` идут новому клиенту

#### Scenario: истечение grace-периода

- **WHEN** grace-период истёк, а исполнитель не вернулся
- **THEN** задача выполняет ERROR-переход по графу (если есть ребро ERROR), иначе остаётся в CLIENT_EXEC

### Requirement: Реестр соединений и видимость

Активные соединения SHALL храниться в in-memory реестре, ключём которого является `(taskId, binding)`; одно объединение — один исполнитель. Реестр не персистится (D-80): рестарт процесса обнуляет его, а незавершённые вызовы закрываются рестарт-сканом как LOST. Регистрация/разрыв SHALL отражаться в логах (MDC: taskId, binding, sessionId, principal) — отдельной audit-таблицы нет (D-77).

#### Scenario: рестарт процесса

- **WHEN** процесс упал и поднялся
- **THEN** реестр соединений пуст; незавершённые tool.call закрыты рестарт-сканом LOST; задача ждёт нового register

#### Scenario: логирование handshake

- **WHEN** клиент регистрируется
- **THEN** в логе появляются taskId, binding, sessionId, principal и число декларированных инструментов
