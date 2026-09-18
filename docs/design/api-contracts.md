# Публичные контракты spring-harness (REST/SSE/WS) — v3

> Спецификация — источник истины; OpenAPI 3.1 и SDK генерируются из неё. Термины — `docs/glossary.md`.
> v3: де-скоуп MVP (D-41) — права заменены одним правилом доступа; папки/шары/билеты/fork/rewind/export/архивация вынесены или выброшены.

## 0. Общие правила

1. **Auth**: `Authorization: Bearer <Keycloak JWT>` на всех эндпоинтах, кроме входящих вебхуков (capability-токен в URL, §4.4). **SSO-гейт**: JWT должен содержать группу из `harness.security.allowed-groups` (claim `groups`). Внутри — одно правило: **аутентифицированный видит всё и пишет куда угодно** (D-41).
2. **Ошибки**: RFC 9457 Problem Details + `code` из каталога §6; любой `code` вне каталога — дефект реализации. Для `422`: `errors[]: { pointer, rule, message }`.
3. **Идемпотентность**: хранилища нет (D-41). Задача-вебхук идемпотентен по построению (`409` вне WAIT_WEBHOOK); дубль POST-сообщения survivable.
4. **Пагинация**: списковые ответы — конверт `{ items: T[], nextCursor? }` (`?cursor=&limit=`). Потоковые (messages, events) — `?since=<seq>`, интервал `(since, …]`; история задачи (history) — `?since=<opaque>` (непрозрачный курсор — пара `(created_at, id)` записи `task_transition_history`; стабильная пагинация при равных `created_at`), интервал `(since, …]`.
5. **Версионирование**: `/api/v1/...`; вебхуки `/api/webhooks/**` — без версии (ломка последними).
6. **PATCH**: JSON Merge Patch (RFC 7396): отсутствующее поле — не менять, `null` — очистить.
7. **201**: всегда с `Location: <url ресурса>`.
8. **Имена**: пути `kebab-case`, ресурсы во множественном числе; поля `camelCase`; enum `SCREAMING_SNAKE`. Время ISO-8601 UTC.
9. **CORS**: доверенные origin WebUI; методы+заголовки — по OpenAPI.
10. **Командные эндпоинты** (`/compact`, `/stop`, `/suspend`, `/resume`) — осознанный RPC-стиль.
11. **Лимиты тела**: лимит-конфиг (дефолт 1 МБ), включая вебхуки; больше — `413 payload-too-large`.

## 1. Агенты

| Метод | Описание |
|---|---|
| `GET /api/v1/agents` | `{ items: [{ key, name, latestRev, description? }] }`. Управление ревизиями — вручную в БД (MVP). |

## 2. Сессии и сообщения

| Метод и путь | Тело → Ответ |
|---|---|
| `POST /api/v1/sessions` | `{ title?, agentKey, agentRev? } → 201 SessionDto`. Создаёт **только FREE** (STATE создаёт движок). Ревизия агента: `agentRev` или последняя — пинится. |
| `GET /api/v1/sessions` | `?mine=&kind=&q=&cursor=&limit=` (`q` — по title; сортировка `lastActivityAt` desc) |
| `GET /api/v1/sessions/{id}` | `→ 200 SessionDto` |
| `PATCH /api/v1/sessions/{id}` | merge-patch `{ title? }` |
| `POST /api/v1/sessions/{id}/messages` | `{ text } → 202 { messageId, seq }` — атрибуция из JWT; работает и для «дописать субагенту» (атрибут `[username]:`) |
| `GET /api/v1/sessions/{id}/messages` | `?since=&limit=` → `{ items: MessageDto[], nextCursor? }` (только видимые; COMPACT-оригиналы — инструментом `read_compacted` у агента) |
| `POST /api/v1/sessions/{id}/compact` | `→ 202` — команда; результат — `COMPACT`-событие в потоке (на границе раунда/после wake); только FREE, иначе `409 wrong-session-kind` |
| `POST /api/v1/sessions/{id}/stop` | `→ 202` — отмена Turn'а + поддерева |
| `GET /api/v1/sessions/{id}/tree` | `?depth=` (по умолчанию всё поддерево) → `{ items: TreeNode[] }`; `TreeNode = { id, parentSessionId, kind, agent {key,rev}, runtimeStatus, lastSeq, taskId?, lastActivityAt }` — «проваливание» в субагентов |

**SessionDto**: `id, kind, title, owner, taskId?, stateCode?, agent {key, rev}, parentSessionId?, workspaceBinding { type, pathTemplate?, logicalKey? }, runtimeStatus: IDLE|TURN_RUNNING|PARKED_ASYNC|PARKED_CLIENT, lastTurnOutcome?, lastSeq, lastActivityAt, createdAt`. `owner` — **username** владельца (`preferred_username` из JWT; не `keycloak_subject` — тот остаётся внутренней идентичностью `app_user`; резолв `owner_user_id → username` — забота серверного слоя).

**MessageDto**: `id (ULID), seq, kind: USER|ASSISTANT|SYSTEM|TOOL_CALL|TOOL_RESULT|COMPACT, author? (username; только USER), payload, callId?, late?, tokens?, createdAt`. Сообщения неизменяемы: PATCH/DELETE не существует.

## 3. SSE-потоки

### 3.1 События сессии
`GET /api/v1/sessions/{id}/events?since=<seq>` — `text/event-stream`.
- SSE `id:` у `message.created` = seq; **при коннекте/реконнекте первым — снапшот `session.status`**; `Last-Event-ID` приоритетен над `?since=`.
- События: `message.created`, `session.status { runtimeStatus, lastTurnOutcome? }`, `ping` (комментарий `: ping`, 15 с; `retry: 5000`).

### 3.2 События задачи («следить вместе с оркестратором»)
`GET /api/v1/tasks/{id}/events?since=` — события: `task.transition`, `task.status`, `subtask.terminal`, `task.comment`, `ping`; снапшот при коннекте. Курсор `since=` — `task_event_seq` (монотонный сквозной номер события задачи; durable в `task.task_event_seq`, инкремент транзакционно с эмиссией; см. data-model §4).

## 4. Задачи, workflow, триггеры, вебхуки

### 4.1 Задачи
| Метод и путь | Тело → Ответ |
|---|---|
| `POST /api/v1/tasks` | `{ title, description, workflowKey, rev?, params?, tags? } → 201 TaskDto` |
| `GET /api/v1/tasks` | `?parent=&status=&mine=&tags=&q=&cursor=` |
| `GET /api/v1/tasks/{id}` | `→ 200 TaskDto` |
| `PATCH /api/v1/tasks/{id}` | merge-patch `{ title?, description?, tags? }` — `params` иммутабельны после создания |
| `POST /api/v1/tasks/{id}/subtasks` | `{ title, description, workflowKey, rev?, params?, tags? } → 201` |
| `POST /api/v1/tasks/{id}/suspend` `{ cascade } / resume` | `→ 204` (флаг синхронно; Turn дорабатывает); resume терминальной → `409 task-already-terminal` |
| `POST /api/v1/tasks/{id}/stop` | `→ 202` = suspend + отмена Turn'ов + терминал `'$CANCELLED'` (workflow-domain) |
| `GET /api/v1/tasks/{id}/history` | `?since=&limit=` → `{ items: TransitionDto[], nextCursor? }` |
| `GET/POST /api/v1/tasks/{id}/comments` | POST `{ body } → 201`; GET `?cursor=` → `{ items, nextCursor }` |
| `POST /api/v1/tasks/{id}/dependencies` | `{ blockedBy: [taskId] } → 201`; атомарно: невалидный/self/цикл → `422` |
| `DELETE /api/v1/tasks/{id}/dependencies/{blockerId}` | `→ 204` |
| `GET /api/v1/tasks/{id}/tree` | поддерево подзадач `{ items: TaskTreeNode[] }` |

**TaskDto**: `id, title, description, owner, author?, workflow { key, rev }, currentState, statusProjection, suspended, parentTaskId?, tags, params, webhookUrl? (когда currentState = WAIT_WEBHOOK), workspaceBindings?: [{ stateCode, logicalKey }], createdAt, updatedAt`.

### 4.2 Workflow
| Метод | Описание |
|---|---|
| `GET /api/v1/workflows` | `{ items: [{ key, name, latestRev }] }`, `?cursor=` |
| `POST /api/v1/workflows` | `{ key, name, graph } → 201` (rev=1; `422 graph-invalid` с `errors[]`) |
| `GET /api/v1/workflows/{key}` | метаданные + список ревизий |
| `GET /api/v1/workflows/{key}/revisions/{rev}` | граф ревизии |
| `POST /api/v1/workflows/{key}/revisions` | `{ graph } → 201` новая ревизия (иммутабельность истории — пин задач) |

### 4.3 Триггеры
`trigger` (D-25): `id, name, workflow_key, rev, params_jsonb, tags?, owner_user_id, created_at, revoked_at`; DELETE → revoke (URL умирает).

| Метод | Описание |
|---|---|
| `POST /api/v1/triggers` | `{ name, workflowKey, rev?, params?, tags? } → 201 TriggerDto { id, url, … }` |
| `GET /api/v1/triggers?cursor=` / `DELETE /api/v1/triggers/{id}` | список / отзыв |

### 4.4 Входящие вебхуки (без JWT, без `/v1`)
Capability-URL: `token = HMAC(server_secret, kind + ':' + entityId)` в пути.

| Эндпоинт | Описание |
|---|---|
| `POST /api/webhooks/tasks/{taskId}/{token}?source=` | задача в WAIT_WEBHOOK → переход NEXT, payload+source → `reason`; иначе `409 task-not-waiting-webhook`; кривой токен → `401 signature-invalid` |
| `POST /api/webhooks/triggers/{triggerId}/{token}` | → создание задачи на workflow триггера → `202 { taskId }`; отозванный → `410 trigger-revoked` |

`webhookUrl` — полем TaskDto (WAIT_WEBHOOK) и TriggerDto. Threat-model — D-26 (идемпотентность по построению, TLS, логирование).

## 5. Клиент-релей CLIENT_EXEC

WebSocket `/api/v1/relay` (Bearer; для браузерного клиента — билет, WebUI-фаза §8).

| Направление | Фрейм | Семантика |
|---|---|---|
| → | `hello { protocol: 1 }` | ← `welcome { protocol }` |
| → | `register { taskId, binding, basePath }` | регистрация исполнителя workspace `(taskId, binding)`; занято → `error workspace-occupied` (close 4409); успех → `← registered { workspaceId }` |
| ← | `tool.call { callId, sessionId, tool: BASH\|READ\|WRITE\|EDIT\|GLOB\|GREP, args }` | клиент исполняет локально |
| → | `tool.progress { callId, chunk }` | опционально |
| → | `tool.result { callId, output, exitCode }` | идемпотентно по callId |
| ←→ | `ping`/`pong` 15 с | разрыв по 2×15 с |
| ← | `tool.cancel { callId }` | отмена in-flight |

Разрыв: незавершённые `tool.call` → синтетический `TOOL_RESULT` «потеряно при отключении исполнителя»; задача → `PARKED_CLIENT`.

## 6. Каталог ошибок

| code | HTTP | Ситуация |
|---|---|---|
| `validation-failed` | 400/422 | тело/параметры; граф/params — `errors[]` |
| `unauthenticated` / `signature-invalid` | 401 | без challenge |
| `session-not-found` / `task-not-found` / `workflow-not-found` / `trigger-not-found` / `agent-not-found` | 404 | |
| `method-not-allowed` / `not-acceptable` / `unsupported-media-type` | 405/406/415 | |
| `wrong-session-kind` | 409 | compact на STATE |
| `task-not-waiting-webhook` / `task-already-terminal` | 409 | |
| `wrong-transition` | 409 | transition не по разрешённому ребру / пустой reason |
| `workspace-occupied` (WS error / close 4409) | — | |
| `graph-invalid` / `dependency-invalid` / `params-schema` | 422 | |
| `payload-too-large` | 413 | тело > лимита |
| `trigger-revoked` | 410 | |
| `not-implemented` | 501 | метод ещё не реализован в текущем apply-проходе (stubs); не ошибка контракта |

## 7. Доступ

SSO-гейт (`groups`-claim, конфиг) → дальше всё (D-41). Владелец — метаданные для UI («моё»), не барьер. Сырые payload'ы видны всем — прозрачность (D-27).

## 8. Фазы и вне объёма MVP

- **WebUI-фаза** (может идти сразу после M1, раньше CLI — D-42): билеты `POST /api/v1/auth/ticket` для SSE/WS браузера; скачивание workspace-файлов (`GET /sessions/{id}/workspace/files?path=`, с canonical-path-гвардом).
- **Выброшено** (вернём при появлении нужды): папки, шары, visibility, fork, rewind, includeHidden, экспорт, архивация, идемпотентность-хранилище.
- Селективная компакция (агентская), MCP-сервер наружу, авто-регистрация вебхуков — как раньше.

## 9. Решения по открытым вопросам

1. Suspend + Stop — обе; подтверждено владельцем.
2. Роуминг-workspace: `(taskId, binding)` + `basePath` — принято.
3. Триггер-сущность — D-25.
4. `/v1` — да; вебхуки без версии.
5. Capability-URL — D-26.
6. ~~Права workflow-ревизий~~ — **отменено D-41**: единое правило доступа; ревизии — любому аутентифицированному.
