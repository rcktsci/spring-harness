# inbound-triggers Specification

## Purpose
Входящая интеграция: capability-URL вебхуки задач и триггеров (stateless, проверка HMAC без БД для задач), CRUD триггеров. Сервер пассивен — URL выдаётся API, регистрацию во внешней системе делает агент предыдущего состояния или человек.

## Requirements

### Requirement: Webhook задачи (WAIT_WEBHOOK → NEXT)

`POST /api/webhooks/tasks/{taskId}/{token}?source=<string>` SHALL: (1) проверить `token = HMAC(server_secret, "task:"+taskId)`; невалидный → `401 signature-invalid`. (2) Проверить, что задача существует и `current_state.type = WAIT_WEBHOOK`; иначе → `409 task-not-waiting-webhook`. (3) Если `state.payloadSchema` объявлен (ограниченный профиль JSON-Schema — см. workflow-engine) и body не прошёл валидацию → `202` + переход ERROR с reason `{ validationErrors }` (переход из состояния; идемпотентно — без 409). (4) Атомарный CAS переход в NEXT (см. task-engine) с `reason_jsonb = { kind: 'webhook', source, payloadSummary }`. (5) Возвращать `202 Accepted` (задача принята к обработке; результат — в SSE задачи). Идемпотентность — по построению: вне WAIT_WEBHOOK — `409`; повторный валидный POST в WAIT_WEBHOOK (между двумя доставками) — переход NEXT уже произошёл → `409 task-not-waiting-webhook`.

#### Scenario: валидный webhook в WAIT_WEBHOOK

- **WHEN** агент/внешняя система POST'ит на `/api/webhooks/tasks/{taskId}/{token}` с валидным HMAC-токеном и задача в WAIT_WEBHOOK
- **THEN** `202`, переход NEXT, payload+source в reason

#### Scenario: кривой токен

- **WHEN** внешняя система POST'ит с битым токеном
- **THEN** `401` с кодом `signature-invalid` (без challenge, тело не парсится)

#### Scenario: задача не в WAIT_WEBHOOK

- **WHEN** внешняя система POST'ит webhook для задачи в RUNNING
- **THEN** `409` с кодом `task-not-waiting-webhook`

#### Scenario: невалидный payload

- **WHEN** задача в WAIT_WEBHOOK получает POST с payload, не прошедшим `payloadSchema`
- **THEN** `202`, переход ERROR, reason содержит `validationErrors`

### Requirement: Webhook триггера (создание задачи)

`POST /api/webhooks/triggers/{triggerId}/{token}` SHALL: (1) проверить `token = HMAC(server_secret, "trigger:"+triggerId)`; невалидный → `401 signature-invalid`. (2) Загрузить триггер; если `revoked_at IS NOT NULL` → `410 trigger-revoked`. (3) Создать задачу с `workflow_revision_id = pin триггера`, `params = trigger.params_jsonb`, `tags = trigger.tags`, `owner = trigger.owner` (агент не указан — `author_user_id = NULL`, агент-пометка в payload по факту прихода webhook). (4) Запустить AGENT-state при наличии (если `current_state.type = AGENT` — bootstrap STATE-сессии и Turn). (5) Вернуть `202 { taskId }`. Без тела запроса триггеры не принимают payload.

#### Scenario: успешное создание

- **WHEN** внешняя система POST'ит валидный webhook триггера
- **THEN** `202`, новая задача создана, в SSE задачи — `task.transition` (создание фиксируется первым переходом)

#### Scenario: отозванный триггер

- **WHEN** внешняя система POST'ит webhook отозванного триггера
- **THEN** `410` с кодом `trigger-revoked`

### Requirement: CRUD триггеров

Система SHALL реализовать: `POST /api/v1/triggers { name, workflowKey, rev?, params?, tags? } → 201 TriggerDto { id, url, … }` (URL содержит токен); `GET /api/v1/triggers?cursor=` — список с фильтром `mine`, сортировка `createdAt` desc, конверт-пагинация; `DELETE /api/v1/triggers/{id} → 204` (revoke — `UPDATE … SET revoked_at = now()`; URL умирает мгновенно). `rev` пин к ревизии workflow при создании триггера.

#### Scenario: создание триггера

- **WHEN** пользователь создаёт триггер с валидными workflowKey и params
- **THEN** `201` с TriggerDto, поле `url` содержит рабочий capability-URL

#### Scenario: revoke

- **WHEN** пользователь DELETE'ит триггер
- **THEN** `204`, последующие webhook POST'ы возвращают `410 trigger-revoked`

### Requirement: Capability-URL токен (HMAC)

`token` SHALL вычисляться как `HMAC-SHA256(server_secret, kind + ':' + entityId)`, где `kind ∈ {"task", "trigger"}`, `entityId = taskId|triggerId`. Серверный секрет — конфиг `harness.webhook.secret` (env-переменная). Проверка — чистая функция, без БД для задач (для триггеров — нужен `revoked_at`, отдельный точечный lookup).

#### Scenario: HMAC проверка

- **WHEN** приходит POST с токеном
- **THEN** сервер пересчитывает HMAC и сравнивает; несовпадение → `401`

### Requirement: Threat-model и логирование

Capability-URL SHALL быть защищён: TLS-only (конфиг деплоя); webhook-вызовы логируются в `reason_jsonb` (source, summary); rate-limit — вне MVP (D-41). Идемпотентность — по построению (`409` вне WAIT_WEBHOOK). Никаких тел в логах с секретами.

#### Scenario: payload в reason

- **WHEN** webhook принят
- **THEN** в `task_transition_history.reason_jsonb.payloadSummary` — компактное summary (без полного тела, если оно большое)
