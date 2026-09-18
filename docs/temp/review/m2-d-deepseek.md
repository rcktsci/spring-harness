# Ревью D.1: OpenAPI-подмножество M2 (`src/main/resources/api/openapi.yaml`)

> Ревьюер: DeepSeek-V4.1-Flash (субагент).
> Дата: 2026-09-18.
> Объект: `src/main/resources/api/openapi.yaml` (M1+M2; git-diff против `HEAD` — M1-часть без изменений).
> Контекст: `docs/design/api-contracts.md` §0/§2/§3.2/§4/§6, дельты и спеки M2 (`openspec/changes/m2-workflow-engine/specs/{workflow-engine,task-engine,inbound-triggers,session-api,agent-turn,session-store,workspace-tools}`), `docs/design/{data-model,workflow-domain,decisions}.md`.
> Проверки: YAML парсится (3.1.0, **28 path-объектов**), генерация/сборка не запускались.
> Severity: **MAJOR** — дефект замораживаемого контракта; **MINOR** — несогласованность/пробел; **NIT** — формулировка.

## Сводка

| Severity | Кол-во |
|---|---|
| MAJOR | 2 |
| MINOR | 4 |
| NIT | 3 |
| **Итого** | **9** |

Плюс процессное замечание (п.6 — артефакт «12 отклонений dev» отсутствует).

---

## (1) Endpoint-покрытие — ✅ полное

Diff против `HEAD`: M1-пути **не удалены**; добавлено 21 путь. Сверка с api-contracts §4 + 7 дельтами:

| Требование | Пути в OpenAPI | Статус |
|---|---|---|
| tasks CRUD | POST/GET `/tasks`, GET/PATCH `/tasks/{id}` | ✅ |
| subtasks | POST `/tasks/{id}/subtasks` | ✅ |
| suspend/resume/stop | POST `/tasks/{id}/{suspend,resume,stop}` | ✅ |
| dependencies ± | POST `/tasks/{id}/dependencies`, DELETE `/tasks/{id}/dependencies/{blockerTaskId}` | ✅ |
| history | GET `/tasks/{id}/history` | ✅ |
| comments ± | POST/GET `/tasks/{id}/comments` | ✅ |
| tree | GET `/tasks/{id}/tree` | ✅ |
| events SSE | GET `/tasks/{id}/events` (tag `TaskEvents`, из генерации исключён) | ✅ |
| workflows list/create/revisions | GET/POST `/workflows`, GET `/workflows/{key}`, POST `/workflows/{key}/revisions`, GET `/workflows/{key}/revisions/{rev}` | ✅ |
| triggers POST/GET/DELETE-revoke | POST/GET `/triggers`, DELETE `/triggers/{id}` | ✅ |
| webhook задачи | POST `/webhooks/tasks/{taskId}/{token}` — `servers: /api`, `security: []` | ✅ |
| webhook триггера | POST `/webhooks/triggers/{triggerId}/{token}` — `servers: /api`, `security: []` | ✅ |
| sessions tree | GET `/sessions/{id}/tree` (+taskId/stateCode) | ✅ |

## (2) Схемы и SSE-якоря — ✅ полное

TaskDto, TransitionDto, CommentDto, TaskTreeNode, WorkflowDto, WorkflowRevisionDto (+Summary), TriggerDto, WebhookError — все есть; SSE-якоря TaskTransitionEvent/TaskStatusEvent/SubtaskTerminalEvent/TaskCommentEvent + SessionStatusEvent. Добавлено 40 схем, удалено 0.

## (3) Модификации M1 — ✅ аддитивные

Diff против `HEAD`:
- `ProblemCode` **+11**: task-not-found, workflow-not-found, trigger-not-found, task-not-waiting-webhook, task-already-terminal, wrong-transition, graph-invalid, dependency-invalid, params-schema, trigger-revoked, signature-invalid; удалённых нет.
- `SessionRuntimeStatus`: `[IDLE,TURN_RUNNING]` → `+PARKED_ASYNC,+PARKED_CLIENT`.
- `SessionDto`: +`taskId`/`stateCode` (опциональны); `required` не менялся.
- `MessageDto`, `SessionKind`, `CreateSessionRequest`, `UpdateSessionRequest`, `SendMessageRequest`: без изменений. M1-операции и их `responses` идентичны `HEAD` — слома M1 нет.

## (4) Паттерны — ✅

ULID: `MessageDto.id`, `MessageDto.callId`, `SendMessageAccepted.messageId` — `^[0-9A-HJKMNP-TV-Z]{26}$` + min/max 26 ✅. Время — `format: date-time` везде; `timeout` — ISO-8601 duration ✅. PATCH `/sessions/{id}` и `/tasks/{id}` — `application/merge-patch+json` + `additionalProperties: true` ✅. Все id задач/сессий/workflow-ревизий/комментариев/переходов — `format: uuid` ✅.

## (5) Каталог ошибок — ✅

`wrong-transition` — единственный новый код (в `ProblemCode`, response `WrongTransition`); 10 остальных M2-кодов уже были в api-contracts §6. Компонент `WrongTransition` намеренно не привязан к REST-операции (инструмент агента) — корректно задокументировано.

---

## Findings

### F-1 [MAJOR]. Курсор истории: api-contracts §0.4 (`<seq>`) ↔ M2-спека/OpenAPI (пара `(createdAt,id)`, opaque string)

- **Где:** `openapi.yaml:681-699` (GET `/tasks/{id}/history`); `docs/design/api-contracts.md:11`; `specs/task-engine/spec.md:85,89`.
- **Цитата:** api-contracts §0.4: «Потоковые (messages, events, **history**) — `?since=<seq>`, интервал `(since, …]`»; спека: «Курсор — пара `(created_at, id)`…» и в том же сценарии «клиент читает историю с `since=0`»; OpenAPI: `since` — `type: string` opaque, «отсутствие — с начала».
- **Проблема:** три источника описывают `since` по-разному: числовой seq (api-contracts), пара-курсор (спека), opaque string (OpenAPI). Спека внутренне противоречива (`since=0` при курсоре-паре). Контракт замораживается — клиент/сервер получат рассинхрон типа курсора.
- **Предложение:** выбрать одно: либо обновить api-contracts §0.4 и убрать `since=0` из сценария (opaque-пара как в OpenAPI), либо вернуть числовой seq. Зафиксировать в `api-contracts.md` и `task-engine/spec.md` до генерации.

### F-2 [MAJOR]. `POST /tasks/{id}/subtasks` не объявляет `404 workflow-not-found`

- **Где:** `openapi.yaml:521-561` (createSubtask), ср. `createTask` `openapi.yaml:409-410`.
- **Цитата:** createSubtask responses: `'404': TaskNotFound` — и только; `CreateTaskRequest.workflowKey` («неизвестный — 404 workflow-not-found»).
- **Проблема:** тело подзадачи — `CreateTaskRequest` с `workflowKey`; неизвестный ключ → `404 workflow-not-found` (спека task-engine §Подзадачи, api-contracts §4.1). В операции этот ответ не объявлен (в `createTask` — объявлен).
- **Предложение:** добавить `'404'` `WorkflowNotFound` в `createSubtask` (обе 404-семантики: родитель и workflow).

### F-3 [MINOR]. `Location` комментария ведёт на несуществующий эндпоинт

- **Где:** `openapi.yaml:729-735`.
- **Цитата:** `Location: URL комментария (/api/v1/tasks/{id}/comments/{commentId})`.
- **Проблема:** GET одиночного комментария (`/tasks/{id}/comments/{commentId}`) в контракте отсутствует — `Location` указывает на недоступный ресурс (api-contracts §0.7 — 201 всегда с Location ресурса).
- **Предложение:** либо добавить GET одиночного комментария, либо `Location` = `/api/v1/tasks/{id}/comments` (коллекция).

### F-4 [MINOR]. 406/413/415 на вебхуках типизированы `ProblemDetail`, а не `WebhookError`; часть `WebhookProblemCode` недостижима

- **Где:** `openapi.yaml:1102-1106` (и `1132-1134`); `WebhookProblemCode` — `1729-1739`.
- **Цитата:** `'406': NotAcceptable`, `'413': PayloadTooLarge`, `'415': UnsupportedMediaType` (все → `ProblemDetail`/`ProblemCode`); при этом `WebhookProblemCode` перечисляет `not-acceptable`, `payload-too-large`, `unsupported-media-type`.
- **Проблема:** M2-описание требует для вебхуков «ошибки — RFC 9457 с кодом из каталога (схема WebhookError)»; фактические 406/413/415 отдают `ProblemDetail`, а одноимённые члены `WebhookProblemCode` не используются ни одной response-компонентой.
- **Предложение:** завести компоненты `WebhookNotAcceptable`/`WebhookPayloadTooLarge`/`WebhookUnsupportedMediaType` со схемой `WebhookError` (либо убрать недостижимые члены из `WebhookProblemCode`).

### F-5 [MINOR]. `TaskDto.author`/`CommentDto.author` — required+nullable против `author?` в api-contracts

- **Где:** `openapi.yaml:1809,1821-1823,1899`; api-contracts §4.1 (`TaskDto: … author? …`), §4.1 comments.
- **Проблема:** обе схемы включают `author` в `required` (тип `[string,'null']`), тогда как api-contracts помечает поле опциональным. Для генерации это означает «поле всегда присутствует»; формально — отклонение от источника истины.
- **Предложение:** либо синхронизировать api-contracts (поле всегда присутствует, nullable), либо убрать `author` из `required`.

### F-6 [MINOR]. `Location` у POST `/tasks/{id}/dependencies` — на задачу, а не на созданное ребро

- **Где:** `openapi.yaml:646-654`.
- **Цитата:** `Location: URL задачи (/api/v1/tasks/{id})`.
- **Проблема:** созданный ресурс — ребро `blocked_by`, а не задача (она уже существовала). §0.7 требует Location созданного ресурса; URL ребра в контракте нет.
- **Предложение:** либо зафиксировать осознанное исключение в api-contracts (Location = блокированная задача), либо не отдавать `Location` для этого 201.

### F-7 [NIT]. Имя path-параметра `blockerTaskId` против `{blockerId}` в api-contracts/спеке

- **Где:** `openapi.yaml:666-669,1193-1200`; api-contracts §4.1 `/dependencies/{blockerId}`; tasks K.3 `{blockerId}`.
- **Предложение:** привести к одному имени (на контракт не влияет, но генератор/тест-клиент расходятся с дизайн-докой).

### F-8 [NIT]. Устаревшие ссылки/формулировки

- **Где:** `openapi.yaml:8` — ссылка на `openspec/changes/m1-session-core/specs/session-api` (чендж заархивирован → `openspec/changes/archive/2026-09-18-m1-session-core/...`); `openapi.yaml:147` — «в M1 существуют только FREE» (в M2 появляются STATE-сессии); `SuspendTaskRequest` (`2039`) — `cascade` в `required` без дефолта (api-contracts показывает `{ cascade }` — допустимо, но клиент обязан всегда слать тело).
- **Предложение:** поправить пути/формулировки; для `cascade` при желании сделать `default: false`.

### F-9 [NIT]. Тело вебхука задачи объявлено обязательным

- **Где:** `openapi.yaml:1088-1095`.
- **Проблема:** `requestBody.required: true` — вебхук без тела (например, чистая нотификация в WAIT_WEBHOOK без `payloadSchema`) отвергнется до handler'а (415/422), тогда как семантика §4.4/спеки — «принят → 202, вне WAIT_WEBHOOK → 409».
- **Предложение:** либо сделать тело необязательным, либо явно записать в api-contracts, что payload обязателен.

---

## Процессное замечание (п.6 «12 отклонений dev»)

Артефакта с перечнем «12 отклонений dev» в репозитории **нет** (проверены `openspec/changes/m2-workflow-engine/`, `docs/temp/`, рабочее дерево; единственное упоминание — `m2-d-mercury.md:11`, без списка). В отличие от M1 (`archive/2026-09-18-m1-session-core/apply-notes.md`, раздел «девиации»), D.1 не фиксирует заявленные отклонения. Проверить пункт (6) по списку невозможно; обнаруженные мной отклонения от api-contracts/спек — F-1…F-9. Рекомендация: dev фиксирует отклонения письменно (apply-notes или секция в шапке YAML).

## Позитив (проверено)

- M1-поверхность строго аддитивна: M1-пути и их `responses` не менялись, схемы не удалялись, `required` M1-схем не тронуты.
- Webhooks: `security: []` + `servers: /api` (вне `/v1`) — корректно; `WebhookToken` = `HMAC-SHA256(server_secret, kind+':'+entityId)`.
- SSE задач: `task_event_seq` (durable, транзакционный инкремент), снапшот = последний `task.status`, `Last-Event-ID` приоритетнее `since`; тег `TaskEvents` (исключение из генерации задокументировано).
- D-58 (ограниченный профиль JSON-Schema), D-59 (wrong-transition как инструмент агента), D-44 (`covers` только у сессий) отражены.
- Все `operationId` уникальны; YAML валиден; `$ref` резолвятся.

## Вердикт

**REJECT — 9 находок (2 MAJOR: F-1 курсор истории, F-2 отсутствующий `404 workflow-not-found` у подзадач; 4 MINOR: F-3…F-6; 3 NIT: F-7…F-9).** Плюс процессный пробел: перечень «12 отклонений dev» не задокументирован. До заморозки обязательны F-1 и F-2.

---

# Re-approval D.1 (2026-09-18)

> Проверены: `src/main/resources/api/openapi.yaml`, `docs/design/api-contracts.md`, `openspec/changes/m2-workflow-engine/specs/task-engine/spec.md`, `openspec/changes/m2-workflow-engine/apply-notes.md`. YAML парсится (3.1.0, 28 путей); относительно `HEAD` M1-пути/схемы не удалены, `ProblemCode` +11, M1-операции без изменений. Сборки не запускались.

## Статусы моих находок

| # | Sev | Статус | Проверка |
|---|---|---|---|
| F-1 | MAJOR | **закрыто** | `api-contracts.md:11` — §0.4: history — `?since=<opaque>` (пара `(created_at,id)`), messages/events остались `<seq>`; `task-engine/spec.md:89` — сценарий «без `since`»; `openapi.yaml:688-710` — описание opaque-пары + «числовой seq здесь не применяется… санкционировано task-engine». Три источника выровнены |
| F-2 | MAJOR | **закрыто** | `openapi.yaml:552-557` — `'404'`: «task-not-found (родитель) либо workflow-not-found (workflowKey тела)» |
| F-3 | MINOR | **закрыто** | `openapi.yaml:742-746` — `Location` → `/api/v1/tasks/{id}/comments` (коллекция), «GET одиночного комментария в контракте отсутствует» |
| F-4 | MINOR | **закрыто (девиация задокументирована)** | `WebhookProblemCode` урезан до `{signature-invalid, task-not-waiting-webhook, trigger-revoked}` (`openapi.yaml:1749-1759`); 406/413/415 вебхуков — `ProblemDetail`, явно описано; apply-notes §7 |
| F-5 | MINOR | **закрыто (девиация задокументирована)** | `CommentDto.author` — вне `required`, nullable (`openapi.yaml:1920,1930-1932`); `TaskDto.author` оставлен `required+nullable` — осознанно, apply-notes §10 (M1-паттерн «поле присутствует всегда»). Расхождение с `author?` api-contracts теперь зафиксировано |
| F-6 | MINOR | **закрыто (девиация задокументирована)** | `openapi.yaml:652-661` — `Location` зависимостей = URL блокированной задачи, «созданное ребро отдельного ресурса не образует», apply-notes §… §6-контекст |
| F-7 | NIT | **закрыто** | `/tasks/{id}/dependencies/{blockerId}` + параметр `BlockerId` (`openapi.yaml:673-676,1213-1214`) |
| F-8 | NIT | **закрыто** | ссылки M1 → `openspec/changes/archive/2026-09-18-m1-session-core/...` (`openapi.yaml:8,36`); `kind`-фильтр: «FREE — пользовательские; STATE — сессии состояний задач, M2» (`openapi.yaml:147`) |
| F-9 | NIT | **закрыто (девиация задокументирована)** | `openapi.yaml:1097-1098,1115` — тело вебхука объявлено обязательным, «POST без тела/Content-Type → 415 до проверки состояния»; apply-notes |
| Процесс | — | **закрыто** | `openspec/changes/m2-workflow-engine/apply-notes.md` — 12 отклонений dev (пп.1–12) + «Результаты ревью-цикла D.1» с принятием F-1…F-9 |

## Проверка внесённых фиксов (не мои находки, но затрагивают контракт)

- GLM «errors[] для всех 422»: `ProblemDetail.errors` → «Для 422-кодов — validation-failed, graph-invalid, dependency-invalid, params-schema» (`openapi.yaml:1746`). ОК.
- GLM «since=0 = снапшот + полная история»: `openapi.yaml:832-834`. ОК.
- GLM «AUTO default + эквивалентность PATH, path=${task.id}»: `WorkflowWorkspace.mode` `default: AUTO` + пояснение (`openapi.yaml:2280-2290`). ОК.
- GLM N3 «CANCEL-рёбра в графе невалидны, enum без CANCEL»: `WorkflowTransition.kind` → `[NEXT, ERROR, TIMEOUT]`, CANCEL в графе → `422 rule=cancel-edge-forbidden` (`openapi.yaml:2302-2309`). **Замечание (nit, не блокер):** `workflow-engine/spec.md` (правило 8) всё ещё перечисляет `CANCEL` в `transition.kind` («в графе не обязан»), тогда как OpenAPI теперь запрещает; формулировку спеки стоит уточнить в пачке M/при переносе. На мои находки не влияет.

## Итог re-approval D.1

Все 9 находок закрыты (часть — как задокументированные девиации в apply-notes), процессный пробел устранён (12 отклонений зафиксированы), M1-поверхность осталась строго аддитивной, YAML валиден. Единственное остаточное — nit по формулировке `workflow-engine/spec.md` про CANCEL-рёбра (документация, не контракт).

**APPROVE — 0 незакрытых (1 nit: правило 8 workflow-engine spec ↔ OpenAPI про CANCEL-рёбра).** Спека готова к шагу D.2 (генерация).
