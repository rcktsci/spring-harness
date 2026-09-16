# Публичные контракты spring-harness (REST/SSE/WS) — v2

> Спецификация — источник истины; OpenAPI 3.1 и SDK генерируются из неё. Термины — `docs/glossary.md`. Права — матрицей в §7.
> Изменения v2 — по итогам трёхстороннего ревью (см. `docs/temp/review/api-review-*.md`).

## 0. Общие правила

1. **Auth**: `Authorization: Bearer <Keycloak JWT>` на всех эндпоинтах, кроме входящих вебхуков (capability-токен в URL, §4.4) и SSE/WS (ticket, §1.4). Проверки — только через `AccessPolicy`.
2. **Ошибки**: RFC 9457 Problem Details + `code` из каталога §6; любой `code` вне каталога — дефект реализации. Для `422`: `errors[]: { pointer, rule, message }`.
3. **Идемпотентность**: `Idempotency-Key` обязателен на `POST .../messages`, опционален на прочих POST, **включая вебхуки** (внешние отправители вроде GitHub ключ не присылают; задача-вебхук идемпотентен по построению — `409` вне WAIT_WEBHOOK; триггер без ключа создаёт задачу на каждый вызов — дубликаты задокументированы, ключ рекомендован). Повтор с тем же ключом и тем же телом → исходный ответ (replay-safe); тот же ключ с другим телом → `409 idempotency-conflict`; конкурентные запросы с одним ключом — PK `(scope, key)` сериализует: проигравший ждёт коммита победителя и получает его ответ. TTL 24 ч; хранение — `idempotency_key` (data-model §6).
4. **Пагинация**: списковые ответы — конверт `{ items: T[], nextCursor? }` (`?cursor=&limit=`). Потоковые коллекции (messages, events, history) — `?since=<seq>` с полуг开放的 интервалом `(since, …]` и `nextCursor` = max seq. Переданы оба — `since` приоритетен.
5. **Версионирование**: `/api/v1/...` — для человеко- и SDK-контрактов. Вебхуки `/api/webhooks/**` — без сегмента версии: URL регистрируются во внешних системах и меняются последними; ломка — только с новым корнем.
6. **PATCH**: JSON Merge Patch (RFC 7396): отсутствующее поле — не менять, `null` — очистить.
7. **201**: всегда с `Location: <url ресурса>`.
8. **Имена**: пути `kebab-case`, ресурсы во множественном числе; поля `camelCase`; enum `SCREAMING_SNAKE`. Время ISO-8601 UTC.
9. **Кэширование**: иммутабельные артефакты (сообщения, ревизии, история переходов) — `ETag` + `Cache-Control: private, max-age=…`; списки — `private, no-cache`.
10. **Rate limiting**: `/api/webhooks/**` — лимит по IP+пути (429 + `Retry-After`); билеты (§1.4) — лимит на пользователя.
11. **CORS**: разрешены доверенные origin WebUI; методы+заголовки — по OpenAPI.
12. **Командные эндпоинты** (`/fork`, `/rewind`, `/compact`, `/stop`, `/suspend`, `/resume`, `/archive`, `/unarchive`) — осознанный RPC-стиль над ресурсами: REST-ожидания кэширования к ним не применяются.
13. **Лимиты тела**: максимум 1 МБ на запрос (включая вебхуки); больше — `413 payload-too-large`. Per-trigger rate-cap: 10 вызовов/мин (алерт при 80%).

## 1. Аутентификация, агенты, папки

### 1.4 Ticket для SSE/WS (браузеры не умеют заголовки)
| Метод | Описание |
|---|---|
| `POST /api/v1/auth/ticket` | `→ 201 { ticket }` — одноразовый, TTL 60 с, привязан к пользователю; передаётся `?ticket=` в SSE/WS. Повторное использование — `401 ticket-used`. JWT в query запрещён. |

### 1.5 Агенты
| Метод | Описание |
|---|---|
| `GET /api/v1/agents` | `{ items: [{ key, name, latestRev, description? }] }` — права: аутентифицированный. Управление ревизиями — вне v1 (только глоссарий/БД). |

### 1.6 Папки (субъект шаринга)
| Метод | Описание |
|---|---|
| `POST /api/v1/folders` | `{ name, parentId? } → 201 FolderDto` |
| `GET /api/v1/folders` | `{ items: FolderDto[] }` — свои + расшаренные |
| `PATCH/DELETE /api/v1/folders/{id}` | переименование/перемещение/удаление (только пустой или каскадом — `409 folder-not-empty`); перемещение с циклом — `422 folder-cycle` |
| `GET/POST/PATCH/DELETE /api/v1/folders/{id}/shares` | как у сессий (§2); share папки наследуется на сессии внутри: FOLDER-VIEW → SESSION-VIEW, FOLDER-PARTICIPATE → SESSION-PARTICIPATE |

## 2. Сессии и сообщения

| Метод и путь | Тело → Ответ | Права |
|---|---|---|
| `POST /api/v1/sessions` | `{ title?, agentKey, agentRev?, folderId?, visibility? } → 201 SessionDto`. Создаёт **только FREE** (`kind` в теле нет; STATE-сессии создаёт движок). Ревизия агента: `agentRev` или последняя на момент создания — пинится. | аутентифицированный |
| `GET /api/v1/sessions` | `?folder=&mine=true&kind=&q=&archived=false&cursor=&limit=` (`q` — по title; сортировка `lastActivityAt` desc) | по видимости |
| `GET /api/v1/sessions/{id}` | `→ 200 SessionDto` | VIEW |
| `PATCH /api/v1/sessions/{id}` | merge-patch `{ title?, folderId?, visibility? }` | владелец |
| `POST /api/v1/sessions/{id}/archive` | архивация (`archivedAt`) → `204`; `POST .../unarchive → 204`. Настоящего DELETE в v1 нет | владелец |
| `POST /api/v1/sessions/{id}/messages` | `{ text } → 202 { messageId, seq }` | PARTICIPANT |
| `GET /api/v1/sessions/{id}/messages` | `?since=&limit=&includeHidden=false` → `{ items: MessageDto[], nextCursor? }`. `includeHidden=true` — **только владелец** (аудит-режим: оригиналы под COMPACT и скрытое rewind) | VIEW |
| `GET /api/v1/sessions/{id}/messages/{messageId}` | `→ 200 MessageDto` (включая скрытые — только владелец) | VIEW / владелец |
| `POST /api/v1/sessions/{id}/fork` | `{ atSeq } → 201 SessionDto` (в форк входит `seq ≤ atSeq`, включительно); только FREE → иначе `409 wrong-session-kind` | владелец |
| `POST /api/v1/sessions/{id}/rewind` | `{ atSeq } → 204` (скрывается `seq > atSeq`); только FREE | владелец |
| `POST /api/v1/sessions/{id}/compact` | `→ 202` — команда; результат — `COMPACT`-событие в потоке (внутри активного Turn — на границе раунда, простаивающей сессии — после wake); только FREE (`409 wrong-session-kind`). `202` осознанно: синхронного ответа с результатом не бывает — вердикт судьи по спору ревьюеров | владелец |
| `POST /api/v1/sessions/{id}/stop` | `→ 202` — отмена Turn'а + поддерева | PARTICIPANT |
| `GET /api/v1/sessions/{id}/export` | `?format=markdown|json → 200` file (`Content-Disposition`; `text/markdown; charset=utf-8` / `application/json`) | VIEW |
| `GET /api/v1/sessions/{id}/workspace/files` | `?path=<rel> → 200` файл из workspace сессии («скачать артефакт»). `path` — только относительный, без `..` и абсолютных префиксов; резолв строго внутри корня workspace, иначе `422 validation-failed` | VIEW |
| `GET/POST/PATCH/DELETE /api/v1/sessions/{id}/shares` | `{ subjectType, subjectId, level }`; смена уровня — PATCH `/shares/{shareId}`; GET — конверт `{ items, nextCursor? }` | владелец |
| `GET /api/v1/sessions/{id}/tree` | `?depth=` (по умолчанию — всё поддерево рекурсивно) → `{ items: TreeNode[] }`; `TreeNode = { id, parentSessionId, kind, agent {key,rev}, runtimeStatus, lastSeq, taskId?, lastActivityAt }` | VIEW на корень (права на детей наследуются по дереву сессий — «проваливание» из глоссария) |

**SessionDto**: `id, kind, title, owner, visibility: PRIVATE|PUBLIC, folderId, taskId?, stateCode?, agent {key, rev}, parentSessionId?, workspaceBinding { type, pathTemplate?, logicalKey? — для CLIENT_EXEC это `(taskId, binding)`, ключ регистрации релея }, runtimeStatus: IDLE|TURN_RUNNING|PARKED_ASYNC|PARKED_CLIENT, lastTurnOutcome?: COMPLETED|FAILED|CANCELLED, forkedFrom?: { sessionId, atSeq }, rewindAtSeq?, archivedAt?, lastSeq, messageCount, tokensTotal, lastActivityAt, createdAt`.

**MessageDto**: `id (ULID), seq, kind: USER|ASSISTANT|SYSTEM|TOOL_CALL|TOOL_RESULT|COMPACT, author?: string (username; только USER), payload, callId?, late?, tokens?, createdAt`. Сообщения неизменяемы: PATCH/DELETE не существует.

**Доступ к поддереву сессий**: субагентские сессии наследуют права от корневой сессии задачи (участник задачи видит/пишет во всё дерево её сессий) — «смотреть и дописывать субагентам» реализуется `POST /messages` на любую сессию дерева с атрибуцией.

## 3. SSE-потоки

### 3.1 События сессии
`GET /api/v1/sessions/{id}/events?since=<seq>&ticket=…` — `text/event-stream`, права VIEW.

- Каждому событию — SSE `id:`: у `message.created` равен seq сообщения; статусные не имеют своей позиции.
- **При коннекте/реконнекте первым событием всегда отдаётся текущий `session.status`** (закрывает пропуск переходов во время дисконнекта). При наличии `Last-Event-ID` он приоритетен над `?since=`.
- События: `message.created` (MessageDto), `session.status` (`{ runtimeStatus, lastTurnOutcome? }` — без служебных идентификаторов лока), `ping` (SSE-комментарий `: ping`, каждые 15 с; `retry: 5000`).

### 3.2 События задачи («следить вместе с оркестратором»)
`GET /api/v1/tasks/{id}/events?since=&ticket=…` — права VIEW по задаче. События: `task.transition` (TransitionDto), `task.status` (`statusProjection`, `suspended`), `subtask.terminal` (терминалы подзадач), `task.comment` (новый комментарий), `ping`. При коннекте/реконнекте первым — снапшот `task.status` (правила §3.1). `task.transition` несёт SSE `id:` = id записи истории (UUIDv7, монотонный); он же — курсор `?since=` и `nextCursor` в `GET /history`. Потерянные статусные восстанавливаются снапшотом, переходы — догрузкой `history`.

## 4. Задачи, workflow, триггеры, вебхуки

### 4.1 Задачи
| Метод и путь | Тело → Ответ | Права |
|---|---|---|
| `POST /api/v1/tasks` | `{ title, description, workflowKey, rev?, params?, tags? } → 201 TaskDto` | аутентифицированный |
| `GET /api/v1/tasks` | `?parent=&status=&mine=&tags=&q=&cursor=` | по видимости задач |
| `GET /api/v1/tasks/{id}` | `→ 200 TaskDto` | TASK-VIEW |
| `PATCH /api/v1/tasks/{id}` | merge-patch `{ title?, description?, tags? }` — **`params` иммутабельны после создания** (задаются при создании задачи/подзадачи/триггера) | владелец |
| `POST /api/v1/tasks/{id}/subtasks` | `{ title, description, workflowKey, rev?, params?, tags? } → 201` | TASK-PARTICIPATE |
| `POST /api/v1/tasks/{id}/suspend` `{ cascade } / resume` | `→ 204` (флаг синхронно; активный Turn дорабатывает); resume остановленной (терминальной) задачи → `409 task-already-terminal` | владелец |
| `POST /api/v1/tasks/{id}/stop` | `→ 202` = suspend + отмена активных Turn'ов сессий задачи + **терминал**: `current_state = '$CANCELLED'`, `status_projection = CANCELLED` (workflow-domain, «Принудительная отмена») | владелец |
| `GET /api/v1/tasks/{id}/history` | `?since=&limit=` → `{ items: TransitionDto[], nextCursor? }` | TASK-VIEW |
| `GET/POST /api/v1/tasks/{id}/comments` | POST `{ body } → 201`; GET `?cursor=` → `{ items, nextCursor }` | TASK-PARTICIPATE / TASK-VIEW |
| `POST /api/v1/tasks/{id}/dependencies` | `{ blockedBy: [taskId] } → 201`; атомарно: любой невалидный id, self, цикл → `422` целиком | TASK-PARTICIPATE |
| `DELETE /api/v1/tasks/{id}/dependencies/{blockerId}` | `→ 204` | TASK-PARTICIPATE |
| `GET /api/v1/tasks/{id}/tree` | поддерево подзадач `{ items: TaskTreeNode[] }` (statusProjection, suspended) | TASK-VIEW |

**TaskDto**: `id, title, description, owner, author?: string, workflow { key, rev }, currentState, statusProjection, suspended, parentTaskId?, tags: string[], params, webhookUrl? (заполнен, когда currentState = WAIT_WEBHOOK; отдаётся только TASK-PARTICIPATE и владельцу — capability не выдаётся READ-уровню), workspaceBindings?: [{ stateCode, logicalKey }] (состояния ревизии с биндингом CLIENT_EXEC — для `relay --binding`; у FREE-сессий logicalKey отсутствует), createdAt, updatedAt`.

**Модель прав задач** (синхронизирована с data-model/glossary): `task.visibility: PRIVATE|PUBLIC` + `share.resource_type = TASK` с уровнями `VIEW|PARTICIPATE` (участие: подзадачи, зависимости, комментарии, сообщения в сессии задачи). STATE-сессии наследуют доступ от задачи. Кросс-деревная зависимость: ставить может PARTICIPANT обеих задач.

### 4.2 Workflow
| Метод | Описание | Права |
|---|---|---|
| `GET /api/v1/workflows` | `{ items: [{ key, name, latestRev, owner }] }`, `?cursor=` | аутентифицированный |
| `POST /api/v1/workflows` | `{ key, name, graph } → 201` (rev=1; `422 graph-invalid` с `errors[]` по правилам workflow-domain §2) | аутентифицированный |
| `GET /api/v1/workflows/{key}` | метаданные + список ревизий | аутентифицированный |
| `GET /api/v1/workflows/{key}/revisions/{rev}` | граф ревизии | аутентифицированный |
| `POST /api/v1/workflows/{key}/revisions` | `{ graph } → 201` новая ревизия | **владелец workflow или роль `harness-admin`** (правка шаблонов — не всем: privilege-эскалация через графики) |

### 4.3 Триггеры
Сущность подтверждена (D-25, таблица в data-model §6): `trigger (id, name, workflow_key, rev, params_jsonb, tags?, owner_user_id, created_at, revoked_at)`; DELETE → `revokedAt=now` — URL умирает мгновенно (проверка по таблице).

| Метод | Описание | Права |
|---|---|---|
| `POST /api/v1/triggers` | `{ name, workflowKey, rev?, params?, tags? } → 201 TriggerDto { id, url, … }`; params валидируются по JSON-Schema ревизии (`422`) | аутентифицированный |
| `GET /api/v1/triggers?cursor=&limit=` / `DELETE /api/v1/triggers/{id}` | список своих (`{ items, nextCursor? }`) / отзыв | владелец |

### 4.4 Входящие вебхуки (без JWT, без `/v1`)
Capability-URL: токен — не поддающийся перебору идентификатор полномочия, `token = HMAC(server_secret, kind + ':' + entityId)`, передаётся в пути.

| Эндпоинт | Описание |
|---|---|
| `POST /api/webhooks/tasks/{taskId}/{token}?source=` | задача в WAIT_WEBHOOK → переход NEXT, payload + source → `reason`; иначе `409 task-not-waiting-webhook`; кривой токен → `401 signature-invalid` |
| `POST /api/webhooks/triggers/{triggerId}/{token}` | → создание задачи на workflow триггера → `202 { taskId }`; отозванный триггер → `410 trigger-revoked` |

`webhookUrl` (с токеном) выдаётся: полем TaskDto (WAIT_WEBHOOK, TASK-PARTICIPATE/владельцу — см. матрицу §7) и TriggerDto (владельцу).
**Threat-model (осознанно принятая, D-26)**: токен в URL — как unsubscribe-ссылки: утёкший URL позволяет дёрнуть переход. Компенсации: идемпотентность задач по построению (`409` вне WAIT_WEBHOOK), опциональный `Idempotency-Key` (рекомендован триггерам), TLS-only, rate-limit, логирование всех вызовов в `reason`; эволюция — окно действия/ротация секрета. Покрытие тела подписью отклонено: внешний отправитель не владеет секретом сервера (GitHub-style общий секрет — будущий адаптер, не MVP).

## 5. Клиент-релей CLIENT_EXEC

WebSocket `/api/v1/relay?ticket=…` (JWT при handshake недоступен браузерам — билет §1.4; CLI может и заголовком).

Фреймы (все — JSON, обёртка `{ type, … }`; ошибки — `type:"error" { code, message, refId? }`, критические — WS close-код):

| Направление | Фрейм | Семантика |
|---|---|---|
| → | `hello { protocol: 1 }` | версия протокола; ← `welcome { protocol, sessionId? }` |
| → | `register { taskId, binding, basePath }` | регистрация исполнителя логического workspace `(taskId, binding)`; проверка прав на задачу (TASK-PARTICIPATE), иначе `error forbidden`; занято → `error workspace-occupied` (close-код 4409); успех → `← registered { workspaceId }` |
| ← | `tool.call { callId, sessionId, tool: BASH\|READ\|WRITE\|EDIT\|GLOB\|GREP, args }` | только когда задача в CLIENT_EXEC-состоянии; клиент исполняет локально своими средствами |
| → | `tool.progress { callId, chunk }` | опционально |
| → | `tool.result { callId, output, exitCode }` | идемпотентно по callId (повтор — тот же ответ) |
| ←→ | `ping` / `pong` каждые 15 с | разрыв по таймауту 2×15 с |
| ← | `tool.cancel { callId }` | Turn отменён — клиент прекращает in-flight |

Разрыв/падение: незавершённые `tool.call` получают синтетический `TOOL_RESULT` «потеряно при отключении исполнителя» (тот же предикат, что рестарт-скан); исполнитель отпускается; задача → `PARKED_CLIENT`; повторная регистрация — новый `register`. Нет исполнителя → Turn паркуется (`PARKED_CLIENT`), таймаут — по декларации состояния.

## 6. Каталог ошибок

| code | HTTP | Ситуация |
|---|---|---|
| `validation-failed` | 400/422 | тело/параметры; для графа/params — `errors[]` |
| `unauthenticated` / `ticket-used` / `signature-invalid` | 401 | без challenge (осознанно) |
| `forbidden` | 404 | **нет гранта отвечаем 404** (не раскрываем существование); 403 не используется для absent-grant |
| `session-not-found` / `task-not-found` / `workflow-not-found` / `trigger-not-found` / `folder-not-found` / `agent-not-found` / `message-not-found` | 404 | |
| `method-not-allowed` / `not-acceptable` / `unsupported-media-type` | 405/406/415 | |
| `wrong-session-kind` | 409 | fork/rewind/compact на STATE |
| `folder-not-empty` | 409 | |
| `folder-cycle` | 422 | перемещение папки в своё поддерево |
| `task-not-waiting-webhook` / `task-already-terminal` | 409 | |
| `idempotency-conflict` | 409 | |
| `workspace-occupied` (WS `error` / close 4409) | — | |
| `graph-invalid` / `dependency-invalid` (self/цикл/чужой id) / `params-schema` | 422 | |
| `rate-limited` | 429 | `Retry-After` |
| `payload-too-large` | 413 | тело > 1 МБ |
| `trigger-revoked` | 410 | |

## 7. Матрица прав (сводно; полная — security-док)

| Ресурс | VIEW | PARTICIPATE | Управление (владелец) |
|---|---|---|---|
| FREE-сессия | читать, SSE, export, workspace-files | + messages, stop | PATCH/archive/shares/fork/rewind/compact, includeHidden |
| Папка | видеть сессии внутри | + писать в них | CRUD, shares |
| Задача | TaskDto **без `params` и `webhookUrl`**, history, tree, SSE | + subtasks, dependencies, comments, `webhookUrl` | PATCH (title/description/tags), suspend/stop/resume; `params` иммутабельны после создания — поле только в ответах владельцу |
| STATE-сессия | наследуется от задачи | наследуется (в т.ч. «дописать субагенту») | — (движок) |
| Workflow | читать (аутентифицированный) | — | ревизии: владелец/`harness-admin` |
| Триггер | — | — | CRUD: владелец |

Сырые payload'ы (`TOOL_RESULT`, `SYSTEM`) видны VIEW — осознанно: прозрачность «смотреть, как работает субагент» важнее редакции (D-27).

## 8. Вне объёма v1 (зафиксировано, чтобы не считалось дырой)

Селективная компакция с параметрами диапазонов; MCP-сервер наружу; скачивание файлов из workspace **задачных** состояний (только FREE-сессий в v1); авто-регистрация вебхуков во внешних системах (GitHub-style общие секреты); управление ревизиями агентов через API.

## 9. Решения по бывшим открытым вопросам (мои, как архитектора)

1. **Suspend + Stop** — обе операции введены (флаг против «флаг+отмена Turn'ов»); стоп-команда `.stop` у сессий — для поддерева. **Подтверждено владельцем 2026-09-16.**
2. **Роуминг workspace** — логический ключ `(taskId, binding)` + локальный `basePath` исполнителя — принято.
3. **Триггер-сущность** — подтверждена (D-25), таблица в data-model.
4. **`/v1`** — да, для `/api/v1`; вебхуки без версии с политикой «ломка последними» — принято.
5. **Capability-URL вебхуков** — принято с threat-model (D-26).
6. **Права workflow-ревизий: владелец или `harness-admin`** — принято. **Подтверждено владельцем 2026-09-16** (весь API — только за SSO/OIDC; анонимны лишь вебхуки).
