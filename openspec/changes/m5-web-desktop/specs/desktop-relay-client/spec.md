# desktop-relay-client Specification

## Purpose

WebSocket-клиент релея в Web Desktop: подключение к `/api/v1/relay`, регистрация на сессии с декларацией локальных инструментов, локальное исполнение `tool.call`, heartbeat, переподключение и takeover. Контракт — api-contracts §5 (M4).

## ADDED Requirements

### Requirement: Подключение и handshake

Клиент SHALL подключаться к `{baseUrl}/api/v1/relay` с Bearer-JWT из safeStorage; при 4401 — триггер silent-refresh/relogin. После соединения SHALL отправлять `hello { protocol: 1 }` и ждать `welcome`; таймаут handshake — конфиг (дефолт 10 с), при превышении — reconnect с backoff.

#### Scenario: штатное подключение

- **WHEN** пользователь открывает сессию (или приложение стартует с активной сессией)
- **THEN** клиент подключается, отправляет hello, получает welcome

#### Scenario: истёк токен

- **WHEN** сервер закрывает соединение с 4401
- **THEN** клиент делает silent refresh и переподключается

### Requirement: Регистрация на сессии с декларацией

Клиент SHALL отправлять `register { sessionId, basePath, client: { version, tools[] } }` для активной FREE root-сессии (STATE-сессии не регистрируются). `basePath` — локальный корень (по умолчанию `~/harness-workspaces/{sessionId}` или каталог, выбранный пользователем); main-process SHALL создавать его (`fs.mkdir recursive`) при обработке register, до отправки фрейма. `tools[]` — стандартный файловый набор (см. «Локальные инструменты»). Обработка ответов: `registered` — подтверждение; `error workspace-occupied` — сообщение «сессия занята другим пользователем» (кнопки «забрать» нет — takeover работает только для того же principal); `error superseded` (при reconnect) — прежнее подключение того же principal закрыто; `error session-not-found`/`wrong-session-kind`/`duplicate-tool-name` — соответствующие сообщения пользователю. При старте приложения с сохранённой активной сессией клиент SHALL автоматически connect + register.

#### Scenario: регистрация на новую сессию

- **WHEN** пользователь открывает FREE root-сессию без активного подключения
- **THEN** клиент регистрируется с basePath и стандартным набором инструментов

#### Scenario: сессия занята другим пользователем

- **WHEN** сервер отвечает `workspace-occupied`
- **THEN** показывается сообщение «сессия занята другим пользователем»; попыток takeover нет

#### Scenario: reconnect того же пользователя

- **WHEN** клиент переподключается после разрыва и шлёт register
- **THEN** сервер сам разруливает (idempotent/takeover/superseded) — клиент не ветвит логику

#### Scenario: STATE-сессия

- **WHEN** пользователь пытается зарегистрироваться на STATE-сессии
- **THEN** регистрация не отправляется; пользователю показывается, что релей доступен только для root-сессий

### Requirement: Локальное исполнение tool.call

Получив `tool.call { callId, sessionId, tool, args }`, клиент SHALL исполнять его локально: `bash` — через `child_process.spawn` в `basePath` (shell ОС, таймаут из args/конфига, stdout+stderr объединены, non-zero exit = OK с exitCode); `read_file`/`write_file`/`edit_file` — через `fs` с лимитами вывода (конфиг); `glob` — поиск в `basePath`; `grep` — поиск по содержимому. Результат SHALL отправляться фреймом `tool.result { callId, output, exitCode }`; длинный вывод — через опциональные `tool.progress { callId, chunk }` (streaming) с финальным `tool.result`. `tool.cancel { callId }` SHALL прерывать исполняемый процесс (SIGTERM → SIGKILL по таймауту) и отменять незавершённый `tool.result`.

#### Scenario: bash-вызов

- **WHEN** оркестратор вызывает клиентский `bash`
- **THEN** команда исполняется локально в basePath; клиент отправляет tool.result с output и exitCode

#### Scenario: длинный вывод

- **WHEN** вывод bash превышает лимит
- **THEN** клиент стримит его через tool.progress и завершает tool.result с маркером truncated

#### Scenario: отмена

- **WHEN** приходит `tool.cancel` во время исполнения bash
- **THEN** процесс убивается; `tool.result` для этого `callId` НЕ отправляется (если процесс успел завершиться до cancel — отправляется, сервер игнорирует дубль по идемпотентности callId)

### Requirement: Heartbeat и переподключение

Клиент SHALL отвечать `pong` на серверный `ping`; при потере соединения (network down / close без 4409) SHALL реконнектиться с backoff (конфиг: начальная 1 с, максимум 30 с, экспонента) и повторно отправлять `register` с теми же параметрами — сервер сам решает idempotent/takeover/error, клиент не ветвит. При 4409 `superseded` (подключение забрал другой клиент) — прекратить reconnect-попытки, показать уведомление «сессия открыта в другом месте».

#### Scenario: сеть пропала

- **WHEN** соединение разрывается по причине сети
- **THEN** клиент реконнектится с backoff и перерегистрируется

#### Scenario: takeover с другого устройства

- **WHEN** сервер закрывает соединение с 4409 `superseded`
- **THEN** клиент прекращает reconnect-попытки и показывает уведомление

### Requirement: Безопасность локального исполнения

Локальное исполнение SHALL происходить с ведома пользователя: первая регистрация на сессию — подтверждение «разрешить оркестратору выполнять команды на этом компьютере в каталоге X»; команда bash перед исполнением отображается в UI (с возможностью abort) если включён режим подтверждения (конфиг `confirmCommands`: always/never — дефолт never для root-сессий владельца? — фиксируется в design). Путями по умолчанию остаётся `basePath` (chdir); выход за пределы не блокируется (это машина пользователя — D-88).

#### Scenario: первый раз на сессии

- **WHEN** пользователь регистрируется на сессию впервые
- **THEN** показывается подтверждение с путём basePath и набором инструментов

#### Scenario: режим подтверждения команд

- **WHEN** включён confirmCommands и оркестратор вызывает bash
- **THEN** команда показывается пользователю; исполняется только после confirm
