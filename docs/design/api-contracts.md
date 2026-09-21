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

**TaskDto**: `id, title, description, owner, author?, workflow { key, rev }, currentState, statusProjection, suspended, parentTaskId?, tags, params, webhookUrl? (когда currentState = WAIT_WEBHOOK), workspaceBindings?: [{ stateCode, logicalKey }], createdAt, updatedAt`. `owner` — **username** владельца (как `owner` в SessionDto §2: `preferred_username` из JWT; резолв `owner_user_id → username` — забота серверного слоя); `author?` — username автора, отсутствует для агентских записей (author_user_id NULL).

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

## 5. Клиент-релей (WebSocket)

`WS /api/v1/relay` — Bearer-JWT (тот же SSO-гейт D-41; браузерному клиенту — билет,
WebUI-фаза §9). Единица маршрутизации — **сессия** (D-84): релей обслуживает FREE
root-сессии; сессии задач (STATE, ролевой агент) — всегда SERVER. Транспорт — JSON-фреймы,
у каждого поле `type`. Исходящие кадры одного соединения сериализуются (D-83).

### 5.1 Handshake

| Направление | Фрейм | Семантика |
|---|---|---|
| → | `hello { protocol: 1 }` | первый фрейм клиента |
| ← | `welcome { protocol: 1 }` | версия согласована, соединение активно |
| × | — | нет/невалиден JWT → close **4401** `unauthenticated` |
| × | — | фрейм до `hello` → close **4403** `protocol` |
| × | — | версия протокола вне поддержки → close **4403** `protocol-mismatch` |

### 5.2 Регистрация и декларация инструментов

| Направление | Фрейм | Семантика |
|---|---|---|
| → | `register { sessionId, basePath, client: { version, tools[] } }` | `basePath` — локальный корень клиента (информативно, логи/UI); `client.version` — версия клиента; `tools[]` — декларация |
| ← | `registered { sessionId }` | успех; декларация — активный runtime-оверлей сессии |
| ← | `error { code, message }` + close **4409** | отказ регистрации (коды ниже) |

`tools[]: { name, description, inputSchema, source }`:
- `name` — **free-form имя** (не enum), уникально в декларации; дубликат → `duplicate-tool-name`;
- `description` — для рендера манифеста модели;
- `inputSchema` — JSON Schema; `args` валидируются сервером до отправки `tool.call`;
- `source` ∈ {`client`, `client.mcp:<server>`} — информативная метка для UI/аудита; сервер к
  MCP-серверам клиента **не ходит** (D-82);
- пустая декларация валидна.

Отказы регистрации (`error`-фрейм, close 4409):

| code | Ситуация |
|---|---|
| `session-not-found` | `sessionId` неизвестен |
| `wrong-session-kind` | STATE-сессия задачи (релею доступны только FREE root-сессии) |
| `workspace-occupied` | сессия занята живым соединением **другого** principal |
| `duplicate-tool-name` | дубль имени в `tools[]` |
| `superseded` | закрывается **старое** соединение при takeover тем же principal |

Повторный `register` с того же соединения — idempotent success. Takeover: тот же principal
на живой сессии → старое закрывается 4409 `superseded`, новое получает `registered`;
`unregister` — CAS по connection-identity, протухший сокет не вытесняет новое (D-78).

### 5.3 Маршрутизация tool-фреймов

| Направление | Фрейм | Семантика |
|---|---|---|
| ← | `tool.call { callId, sessionId, tool, args }` | `tool` — free-form имя из декларации; клиент исполняет локально |
| → | `tool.progress { callId, chunk }` | опционально, промежуточный вывод |
| → | `tool.result { callId, output, exitCode }` | идемпотентно по `callId` (первый финал выигрывает) |
| ← | `tool.cancel { callId }` | отмена in-flight (stop Turn'а); поздний `tool.result` игнорируется |

`tool` — **free-form имя декларации**, не enum. Финальную запись в журнал делает Turn-поток
под `sess`-локом; WS-поток только complete'ит future по `callId` (D-81, как async/MCP — D-64).
Tool-уровневые отказы отдаются агенту как `TOOL_RESULT ERROR <code>` (§6): `tool-not-available`
(инструмент не резолвится), `params-schema` (`args` не прошли `inputSchema` — клиент не
дёргается), `tool-timeout` (нет ответа за `harness.relay.tool-call-timeout`).

### 5.4 Heartbeat и разрыв

| Направление | Фрейм | Семантика |
|---|---|---|
| ← | `ping { }` | **инициирует сервер**, интервал `harness.relay.heartbeat-interval` (дефолт 15 с) |
| → | `pong { }` | ответ клиента |
| × | — | нет `pong` за `2 × heartbeat-interval` → разрыв соединения |

Разрыв: незавершённые `tool.call` → синтетический `TOOL_RESULT LOST` «потеряно при
отключении исполнителя»; оверлей сессии очищается (D-80), повторное подключение
восстанавливает его новой декларацией. Реестр соединений — in-memory (D-78); при рестарте
процесса пуст, осиротевшие вызовы закрывает рестарт-скан LOST «операция потеряна при
перезапуске». Авто-парковки (`PARKED_CLIENT`) нет — вызовы после disconnect →
`tool-not-available`.

### 5.5 Close-коды

| close | code | Ситуация |
|---|---|---|
| **4401** | `unauthenticated` | нет/невалиден JWT |
| **4403** | `protocol` / `protocol-mismatch` | фрейм до `hello` / несовместимая версия |
| **4409** | `session-not-found`, `wrong-session-kind`, `workspace-occupied`, `superseded`, `duplicate-tool-name` | отказ регистрации / takeover |

## 6. Каталог ошибок

| code | HTTP / WS | Ситуация |
|---|---|---|
| `validation-failed` | 400/422 | тело/параметры; граф/params — `errors[]` |
| `unauthenticated` / `signature-invalid` | 401 | без challenge |
| `session-not-found` / `task-not-found` / `workflow-not-found` / `trigger-not-found` / `agent-not-found` | 404 | |
| `file-not-found` | 404 | файла/каталога назначения нет в workspace сессии (§8) |
| `method-not-allowed` / `not-acceptable` / `unsupported-media-type` | 405/406/415 | |
| `wrong-session-kind` | 409 / WS 4409 | compact на STATE; регистрация релея на STATE-сессии (§5) |
| `task-not-waiting-webhook` / `task-already-terminal` | 409 | |
| `wrong-transition` | 409 | transition не по разрешённому ребру / пустой reason |
| `workspace-occupied` | WS 4409 | сессия занята соединением иного principal (§5) |
| `superseded` | WS 4409 | старое соединение вытеснено takeover'ом (§5) |
| `duplicate-tool-name` | WS 4409 | дубль имени в декларации `tools[]` (§5) |
| `protocol-mismatch` | WS 4403 | несовместимая версия протокола релея (§5) |
| `graph-invalid` / `dependency-invalid` | 422 | |
| `params-schema` | 422 / TOOL_RESULT ERROR | params против paramsSchema ревизии; также `args` клиентского инструмента против `inputSchema` (§5) |
| `path-invalid` | 422 | canonical-path-гвард: абсолютный путь, `..`-эскейп, symlink, каталог (§8) |
| `extension-not-allowed` | 422 | расширение вне `workspace.download.allow-extensions` (§8) |
| `payload-too-large` | 413 | тело > лимита (дефолт 1 МБ) либо файл > `workspace.download.max-bytes` (§8) |
| `trigger-revoked` | 410 | |
| `tool-not-available` / `tool-timeout` | TOOL_RESULT ERROR | клиентский инструмент не резолвится / нет ответа за `tool-call-timeout` (§5) |

## 7. Доступ

SSO-гейт (`groups`-claim, конфиг) → дальше всё (D-41). Владелец — метаданные для UI («моё»), не барьер. Сырые payload'ы видны всем — прозрачность (D-27).

## 8. Скачивание workspace-файлов

`GET /api/v1/sessions/{id}/workspace/files?path=<relative>` — отдача файла из **серверного**
каталога workspace сессии `workspaces/sessions/{sessionId}` (SSO-гейт D-41; машиночитаемый
контракт — `api/openapi.yaml`). Ответы: 200 (поток, `application/octet-stream`), 404
`session-not-found` / `file-not-found`, 413 `payload-too-large`, 422 `path-invalid` /
`extension-not-allowed`.

- **Canonical-path-гвард** (D-72): посегментная symlink-проверка, canonical-резолв,
  containment в корне `workspaces/sessions/{sessionId}`, `NOFOLLOW_LINKS` на финальный
  компонент; абсолютный путь, `..`-эскейп, нулевые сегменты, каталог и symlink → 422.
  Остаточный TOCTOU — принятый риск: **писатель** workspace — агент (arbitrary `bash` в
  примонтированном каталоге, prompt-injection), **читатель** — аутентифицированный
  SSO-пользователь; митигация — NOFOLLOW + ре-канонизация каждого сегмента.
- **Safe-лист** расширений (`harness.workspace.download.allow-extensions`, дефолт —
  текстовые/кодовые расширения; сравнение case-insensitive); вне листа → 422
  `extension-not-allowed`.
- **Pre-stat 413** до отправки заголовков (`Files.size` против
  `harness.workspace.download.max-bytes`, дефолт 10 МБ); отдача — потоком с `Content-Length`,
  без загрузки файла в память. Усечение «в процессе» не используется.
- Для CLIENT-сессий (релей §5) серверный workspace может быть пуст — файлы у клиента; это
  задокументированное ограничение, основные потребители — SERVER-сессии и Web Desktop.

## 9. Фазы и вне объёма MVP

- **WebUI-фаза** (может идти после M1 — D-42): билеты `POST /api/v1/auth/ticket` для SSE/WS браузера.
- **Выброшено** (вернём при появлении нужды): папки, шары, visibility, fork, rewind, includeHidden, экспорт, архивация, идемпотентность-хранилище.
- Селективная компакция (агентская), MCP-сервер наружу, авто-регистрация вебхуков — как раньше.

## 10. Решения по открытым вопросам

1. Suspend + Stop — обе; подтверждено владельцем.
2. ~~Роуминг-workspace: `(taskId, binding)` + `basePath`~~ — **supersede D-84**: единица маршрутизации релея — **сессия**, регистрация `register { sessionId, basePath, ... }`; `CLIENT_EXEC`/`binding` релеем не используются.
3. Триггер-сущность — D-25.
4. `/v1` — да; вебхуки без версии.
5. Capability-URL — D-26.
6. ~~Права workflow-ревизий~~ — **отменено D-41**: единое правило доступа; ревизии — любому аутентифицированному.
