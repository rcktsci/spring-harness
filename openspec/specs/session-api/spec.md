# session-api Specification

## Purpose
Публичный REST/SSE-контракт M1: сессии и сообщения FREE-сессий, каталог агентов, SSE-поток событий сессии, минимальный attach (стриминг + отправка). Подмножество api-contracts v3 (§0, §1–§3, §6).

## Requirements

### Requirement: Каталог агентов

`GET /api/v1/agents` SHALL возвращать `{ items: [{ key, name, latestRev, description? }] }`. Управление ревизиями агентов через API не предоставляется (вручную в БД, MVP).

#### Scenario: список агентов

- **WHEN** аутентифицированный клиент запрашивает каталог
- **THEN** возвращаются все агенты с последними ревизиями

### Requirement: CRUD сессий (минимум)

Система SHALL реализовать: `POST /api/v1/sessions { title?, agentKey, agentRev? } → 201 SessionDto + Location` (только FREE; STATE-сессии через этот endpoint не создаются — их порождает движок задачи); `GET /api/v1/sessions ?mine=&kind=&q=&cursor=&limit=` — список, сортировка по `lastActivityAt` desc, конверт-пагинация; `GET /api/v1/sessions/{id} → 200 | 404 session-not-found`; `PATCH /api/v1/sessions/{id}` merge-patch `{ title? }`. Дерево сессий `GET /api/v1/sessions/{id}/tree` дополняется полями `taskId`/`stateCode` для STATE-узлов.

#### Scenario: создание сессии

- **WHEN** клиент создаёт сессию с title и agentKey
- **THEN** `201` с SessionDto (kind=FREE) и заголовком Location

SessionDto с M2: `id, kind (FREE|STATE), title, owner, agent {key, rev}, workspaceBinding { type: SERVER_DIR }, runtimeStatus: IDLE|TURN_RUNNING|PARKED_ASYNC|PARKED_CLIENT, lastTurnOutcome?, lastSeq, lastActivityAt, createdAt`; для STATE-сессий дополнительно `taskId` и `stateCode` (у FREE отсутствуют). Enum `runtimeStatus` расширен до четырёх значений: фактическое присвоение `PARKED_ASYNC` появляется с M3 (async-инструменты), `PARKED_CLIENT` — с M4 (релей); в M2 встречаются `IDLE|TURN_RUNNING`.

#### Scenario: несуществующая сессия

- **WHEN** клиент запрашивает GET по неизвестному id
- **THEN** `404` с кодом `session-not-found`

### Requirement: Отправка сообщений

Система SHALL принимать `POST /api/v1/sessions/{id}/messages { text } → 202 { messageId, seq }` с атрибуцией автора из JWT. Лимит тела — конфиг (дефолт 1 МБ); превышение → `413 payload-too-large`.

#### Scenario: отправка сообщения

- **WHEN** клиент отправляет текст в существующую сессию
- **THEN** `202` с messageId и seq; событие появляется в потоке с атрибуцией пользователя

#### Scenario: превышение лимита тела

- **WHEN** тело сообщения больше лимита
- **THEN** `413` с кодом `payload-too-large`

### Requirement: Чтение сообщений

`GET /api/v1/sessions/{id}/messages ?since=&limit=` SHALL возвращать `{ items: MessageDto[], nextCursor? }` за интервал `(since, …]`; только видимые события. MessageDto: `id (ULID), seq, kind, author? (username; только USER), payload, callId?, late? (поле M3; в M1 всегда отсутствует), tokens?, createdAt`.

#### Scenario: чтение с курсора

- **WHEN** клиент читает сообщения с `since=5`
- **THEN** возвращаются события с seq > 5 в порядке возрастания

### Requirement: Команды compact и stop

Система SHALL принимать: `POST /api/v1/sessions/{id}/compact → 202` — только FREE, иначе `409 wrong-session-kind`; `POST /api/v1/sessions/{id}/stop → 202` — отмена Turn'а (+ поддерево субагентов, когда появятся в M3).

#### Scenario: compact на FREE

- **WHEN** отправлена команда compact для FREE-сессии
- **THEN** `202`, COMPACT-событие появляется на границе раунда

### Requirement: SSE-поток событий сессии

`GET /api/v1/sessions/{id}/events?since=<seq>` SHALL отдавать `text/event-stream`: `message.created` (SSE `id:` = seq), `session.status { runtimeStatus, lastTurnOutcome? }`, `ping` (комментарий `: ping`, интервал — конфиг, дефолт 15 с; `retry: 5000` — контрактная константа api-contracts §3.1). При коннекте/реконнекте первым отправляется снапшот `session.status`; `Last-Event-ID` приоритетен над `?since=`.

#### Scenario: подключение с since

- **WHEN** клиент подключается с `since=10`
- **THEN** первым приходит снапшот session.status, далее message.created только для seq > 10

#### Scenario: реконнект по Last-Event-ID

- **WHEN** соединение рвётся и клиент переподключается с Last-Event-ID=12
- **THEN** поток продолжается с событий после seq 12 без пропусков и дублей

### Requirement: Единый формат ошибок

Ошибки SHALL форматироваться RFC 9457 Problem Details с полем `code` из каталога. Подмножество M1: `validation-failed`, `unauthenticated`, `session-not-found`, `agent-not-found`, `wrong-session-kind`, `payload-too-large`, `method-not-allowed`, `not-acceptable`, `unsupported-media-type`. Дополнение M2 — коды задачного домена api-contracts §6: `task-not-found`, `workflow-not-found`, `trigger-not-found`, `task-not-waiting-webhook`, `task-already-terminal`, `dependency-invalid`, `graph-invalid`, `params-schema`, `trigger-revoked`, `signature-invalid` и единственный новый код `wrong-transition` (409 — переход не по разрешённому ребру / пустой reason). Код вне каталога — дефект реализации. Для `422`: `errors[]: { pointer, rule, message }`.

#### Scenario: невалидное тело

- **WHEN** POST /sessions с телом без agentKey
- **THEN** `422` с кодом `validation-failed` и errors[] с указателем на поле

#### Scenario: переход не по ребру

- **WHEN** агент вызывает transition на состояние, не являющееся исходящим ребром текущего состояния задачи
- **THEN** `409` с кодом `wrong-transition`

### Requirement: SSE-поток событий задачи

`GET /api/v1/tasks/{id}/events?since=<task_event_seq>` SHALL отдавать `text/event-stream` задачного домена: `task.transition { id, taskId, fromState, toState, kind, reason, createdAt }`, `task.status { taskId, currentState, statusProjection, suspended }`, `subtask.terminal { taskId, terminalTaskId, terminalStatus }`, `task.comment { id, taskId, body, author, createdAt }`, `ping` (комментарий `: ping`, интервал — конфиг, `retry: 5000`). Курсор `since=` — монотонный `task_event_seq` задачи: все события задачи (включая не-transition) нумеруются сквозным счётчиком — монотонным и durable (колонка `task.task_event_seq`, по стилю `last_seq`/`state_attempt`), курсор переживает рестарт, инкремент выполняется транзакционно с эмиссией события; `Last-Event-ID` приоритетен над `?since=`. При коннекте/реконнекте первым отправляется снапшот — последний `task.status`; реконнект добирает пропущенное по курсору без пропусков и дублей. Неизвестный `taskId` → `404 task-not-found`.

#### Scenario: подключение со снапшотом

- **WHEN** клиент подключается к потоку задачи
- **THEN** первым приходит снапшот (последний `task.status`), далее события с `task_event_seq > since`

#### Scenario: реконнект без пропусков

- **WHEN** соединение рвётся и клиент переподключается с Last-Event-ID
- **THEN** поток продолжается с событий после курсора без пропусков и дублей

#### Scenario: неизвестная задача

- **WHEN** клиент подключается к потоку несуществующей задачи
- **THEN** `404` с кодом `task-not-found`
