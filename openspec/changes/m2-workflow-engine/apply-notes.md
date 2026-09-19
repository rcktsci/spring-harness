# Apply-заметки: m2-workflow-engine

## Пачка D.2 — генерация из замороженной спеки + wiring

### Зафиксированные решения пачки

- **`jackson-databind`: `test` → `provided`.** Единственный сгенерированный main-класс с
  databind-импортом — `AddTaskDependenciesRequest` (`uniqueItems: true` → `@JsonDeserialize(as =
  LinkedHashSet.class)`). provided = compile+test classpath, в упаковку не попадает; Jackson 3 в
  main-runtime не тронут (AGENTS.md). jsr310 остался test (только тест-клиент).
- **`@RequestMapping("/api")` на WebhooksController.** openapi-generator не поддерживает
  path-item `servers` (ни server, ни client): интерфейсы мапятся на корневой `/api/v1`. Локальная
  аннотация класса затеняет интерфейсную → сервер слушает `/api/webhooks/**` (§0.5).
- **Тест-клиент вебхуков** (DS F-1): у сгенерированного `WebhooksApi` пути `/webhooks/...`
  относительно baseUri; для e2e конструировать `ApiClient` + `updateBaseUri("<server>/api")` —
  закреплено тестом `WebhooksRoutingTest.generatedClientResolvesApiBasePath`. Паттерн обязателен
  для L.3.
- **Security — две цепочки** (GLM M-1): вебхуки — отдельная `SecurityFilterChain` `@Order(1)`
  с `securityMatcher("/api/webhooks/**")` без oauth2ResourceServer (чужой `Authorization: Bearer`
  не валидируется как JWT и не меняет исход); основная цепочка сужена до
  `securityMatcher("/api/v1/**")`. HMAC capability-токена — в контроллере (гейт D.2), полный
  верификатор — L.2, обработчики — L.3.
- **`harness.webhook.secret`** — новый конфиг (`WebhookProperties`): секрет HMAC
  capability-токенов; env `HARNESS_WEBHOOK_SECRET` в рантайме VM, dev-дефолт в application.yml.
- **Стабы → `501 not-implemented`** (DS F-4): `ApiNotImplementedException` вместо
  `UnsupportedOperationException` — ответ RFC 9457 с каталожным кодом (добавлен в §6 и в
  ProblemCode спеки; не ошибка контракта). `SignatureInvalidException` → `401 signature-invalid`.

### Пачка D.2 — план удаления переходного кода

- **`not-implemented` (501)** — код переходного периода: удаляется после **L.4** (последняя
  пачка реализации M2): из ProblemCode спеки (openapi.yaml), api-contracts §6,
  `ProblemCodes.java`, `ApiExceptionHandler` и всех стабов (к L.4 реализованных). Напоминание —
  задача L.5 в tasks.md; verify — main-компиляция без упоминаний not-implemented.

### Ревью-цикл D.2 (round 2)

- Mercury: APPROVE. GLM: REJECT (M-1 + 2 nit) → фиксы. DeepSeek: REJECT (F-1 major, F-2 medium,
  F-3 minor, F-4 nit) → фиксы.
- Принято: F-1 (basePath клиент вебхуков + тест), F-2 (`WebhooksRoutingTest`: bad-token →
  401 signature-invalid; чужой Bearer → signature-invalid, не unauthenticated;
  `/api/v1/webhooks/**` → 401 unauthenticated — маппинга без /v1 нет; valid token → 501
  not-implemented), F-3 (эта секция), F-4 (501 not-implemented, код в §6/спеке/ProblemCodes),
  GLM M-1 (две security-цепочки), GLM N-1/N-2 (закреплено тестом; Javadoc TaskEventsController
  уточнён: «ручной SseEmitter по api-contracts §3.2; сгенерированный интерфейс исключён из
  генерации»).
- **Round 2**: N-1 (catch-all-цепочка `@Order(2)` denyAll с общим entry point — аноним на
  неописанных путях получает 401 unauthenticated, не 200/403; тесты на `/error` и
  `/actuator/prometheus`), N-2 (эта секция «план удаления» + задача-напоминание L.5 в tasks.md),
  N-3 (HMAC вынесен из контроллера в `common/security/WebhookSignatureVerifier` — базовая
  структура L.2, расширение тестами за L.2).

## Пачка D — спека M2 (D.1) — отклонения dev

Отклонения от api-contracts §4/§0 при проектировании OpenAPI-подмножества
(`src/main/resources/api/openapi.yaml`, v1.1.0-m2). Прошли ревью-цикл D.1
(GLM-Flash + DeepSeek-Flash + Mercury: `docs/temp/review/m2-d-{glm,deepseek,mercury}.md`),
фиксы ревью применены.

1. **`wrong-transition` (409) не привязан к REST-операции.** Response-компонент
   и код в ProblemCode есть, но ни одна REST-операция его не возвращает: путь
   перехода — инструмент агента `transition` (D-59, гейт metaTools,
   instructionSource=USER); REST-генерации не требует (agent-turn).
2. **`WorkflowState.agent_key` — snake_case.** Взят буквально из контракта
   `graph_jsonb` (workflow-domain §2) — доменный контракт графа, исключение из
   camelCase-правила §0.8 для API-полей; сосуществует с camelCase
   `payloadSchema`/`paramsSchema` того же контракта.
3. **`WorkflowState.timeout` — string, ISO-8601 duration** (напр. `PT30M`).
   Формат в источниках нигде не зафиксирован; выбран по аналогии с конвенцией
   времени §0.8. Конфиг движка — Spring-стиль (`30s`); конвертация на границе.
4. **DELETE `/tasks/{id}/dependencies/{blockerId}`: отсутствующее ребро →
   идемпотентный 204** (в §4.1 не оговорено; повторный вызов безопасен).
5. **Дубликат `key` workflow → 422 validation-failed, rule=key-unique**
   (каталог §6 не имеет 409-конфликт-кода; выбран каталог-консистентный вариант).
6. **Webhook задачи: несуществующая задача → 409 task-not-waiting-webhook**
   (без отдельного 404) — по дельте inbound-triggers: «задача существует и в
   WAIT_WEBHOOK, иначе → 409»; идемпотентность по построению.
7. **WebhookProblemCode — подмножество каталога** (signature-invalid,
   task-not-waiting-webhook, trigger-revoked). Инфраструктурные 406/413/415 на
   вебхуках — стандартный ProblemDetail с полным ProblemCode (консистентность
   с M1; фикс F-4 ревью).
8. **`TransitionDto.reason` агентского перехода — `{text}`** — форма обёртки
   обоснования в источниках зафиксирована не буквально (task-engine: «текст-
   обоснование»); остальные формы — по спекам (bash/webhook/WAIT_TASKS/stop).
9. **История: `?since=` — opaque-пара (createdAt, id)** вместо числового seq
   §0.4 — санкционировано task-engine (стабильная пагинация при равных
   `created_at`); после F-1 выровнено: api-contracts §0.4 обновлён, сценарий
   «since=0» в дельте заменён на «без since».
10. **`TaskDto.author` — required+nullable** (M1-паттерн «поле присутствует
    всегда», api-contracts пишет `author?`); `CommentDto.author` — nullable,
    вне required (фикс F-5: агентский комментарий — NULL + агент-пометка).
11. **`GET /sessions/{id}/tree` добавлен в спеку пачкой D** — в замороженной
    M1-спеке отсутствовал (хотя есть в api-contracts §2); включён по постановке
    D.1 («слияние с M1-операциями», taskId+stateCode для STATE-узлов);
    регенерация D.2 заставит создать контроллер.
12. **Генерация (D.2): pom `<apis>` расширяется** на
    `Tasks,TaskCommands,Workflows,Triggers,Webhooks`; SSE-теги (`SessionEvents`,
    `TaskEvents`) исключены — контроллеры на SseEmitter вручную;
    `openApiNullable=false` стоит с M1 в обоих executions.

### Результаты ревью-цикла D.1

- Mercury: APPROVE. GLM: REJECT (3 minor + 4 nit) → фиксы. DeepSeek: REJECT
  (2 major + 4 minor + 3 nit) → фиксы.
- **Принято и применено**: DS F-1 (курсор истории — выравнивание трёх источников,
  правки в openapi.yaml + api-contracts §0.4 + дельта task-engine), F-2 (404
  workflow-not-found у subtasks), F-3 (Location комментария → коллекция),
  F-4 (406/413/415 вебхуков — ProblemDetail, WebhookProblemCode урезан),
  F-5 (author в CommentDto — nullable, вне required), F-6 (Location зависимостей
  задокументирован как осознанный), F-7 (`blockerTaskId` → `blockerId`, как в
  §4.1/K.3), F-8 (архивные ссылки M1, kind-фильтр), F-9+GLM M1 (тело вебхука
  обязательное — зафиксировано), GLM M2 (errors[] для всех 422-кодов),
  GLM M3 (since=0 = снапшот + полная история), GLM N1 (AUTO default +
  эквивалентность PATH, path=${task.id}), GLM N3 (CANCEL-рёбра в графе
  невалидны, enum без CANCEL).
- **Отклонено**: GLM N2 («cascade required без обоснования») — оставлен
  required: явность каскада при suspend осознанная; замечание ссылалось на
  J-22 (убран cascade из stop API — stop всегда каскадный), к suspend не
  относится.

## Пачка H — миграции + WorkflowRegistry/TaskRegistry (CRUD без движка)

### Зафиксированные решения пачки (отклонения dev)

1. **createWorkflow(ownerUserId, ...)** — сигнатура контракта принимает владельца явно:
   workflow.owner_user_id NOT NULL (data-model §3), а JWT-контекст доступен только api-слою
   (модуль workflow не зависит от api). Резолв владельца — забота контроллера (пачка K).
2. **paramsSchema валидируется по стартовому состоянию ревизии** (уточнено ревью H-1/H-8):
   поле контракта — per-state, но для создания задачи однозначной точкой привязки является
   явный start_state ревизии. Эвристика «единственный source» удалена (ломалась на
   циклических графах); fallback «первый в JSON» удалён.
3. **deadline_at при создании** — из state.timeout стартового состояния (кроме TERMINAL):
   иначе задача, вставшая в WAIT-состояние сразу при создании, никогда не попадёт в
   таймаут-скан (deadline_at IS NULL не индексируется частичным индексом).
4. **Чтение graph_jsonb в task — собственный SQL по id ревизии**: модуль task не зависит от
   Java-классов workflow (ArchUnit: 	ask → identity); граф — данные ревизии, а не её API.
5. **Ограниченный профиль D-58 живёт в common/jsonschema** (LimitedJsonSchemaValidator,
   JsonSchemaError): переиспользуется валидатором графа (workflow) и реестром задач (task);
   неизвестные ключевые слова схем игнорируются (профиль — подмножество, description легален).
6. **TaskWakeListener (task) — контракт wake после коммита** (аналог SessionEventListener):
   реализация InProcessTaskWakeBus — пачка I.5 (execution); модуль task не зависит от
   execution. В тестах — RecordingTaskWakeListener (test-classpath @Component, попадает
   в общий контекст через component-scan, как DatabaseCleaner; FailFast-гвард не тронут).
7. **WorkflowProperties/TaskProperties не созданы** — в пачке H нет новых числовых
   параметров (правило владельца: сущность обязана иметь сценарий). Появятся в пачке I
   (kind-timeouts, poll-interval, scheduler.ttl).
8. **getHistory(limit=null) — все записи** (сценарий спеки: «без since — все»); default-limit —
   забота API-слоя (limits.page), как в M1 searchSessions.
9. **Идемпотентность зависимостей**: emoveDependency — no-op на отсутствующем ребре
   (отклонение dev D-пачки №4); ddDependency на существующее ребро — no-op (PK-пара как
   backstop); SELECT FOR UPDATE на обеих задачах (детерминированный порядок по id) — H-6.

### Ревью-цикл H (GLM-Flash + DeepSeek-Flash + Mercury) — применённые фиксы

- **V-1 (GLM, major)**: достижимость TERMINAL — обратный BFS от всех терминалов по встречным
  рёбрам вместо прямого DFS с memo (отравление кэша на циклах отвергало валидные графы
  «review → возврат в plan»). Регресс-тесты: цикл «review → plan (ERROR) → merge» и полный
  сценарий «plan → bash → reviewer-1 → plan → reviewer-2 → merge».
- **H-1 (DS, medium)**: явный workflow_revision.start_state (миграция 011, NOT NULL,
  start_state ∈ states[].code — правило валидатора); 	ask.current_state := start_state;
  эвристика источника удалена. Регресс-тест: два source'а, старт ≠ первый в JSON.
- **H-4 (DS, medium)**: esume — SELECT FOR UPDATE до проверки терминальности; проверка и
  снятие suspended атомарны под локом (гонка с stop закрыта).
- **H-3 (DS, minor)**: 
ewRevision — SELECT rev ... ORDER BY rev DESC LIMIT 1 FOR UPDATE
  (сериализация конкурентных ревизий; UNIQUE (workflow_id, rev) — последний рубеж).
- **H-5 (DS, minor)**: graph_jsonb jsonb NOT NULL (миграция 011).
- **H-6 (DS, minor)**: ddDependency — SELECT FOR UPDATE обеих задач до проверки цикла
  и вставки (детерминированный порядок по id — без deadlock).
- **H-7 (DS, nit)**: LimitedJsonSchemaValidator — целостность числа (integer) определяется
  по типу значения (Integer/Long/BigInteger/целый Double|Float), не сравнением через double
  (потеря точности > 2^53); enum-сравнение целых — по longValue.
- **H-8 (DS, minor)**: однозначная привязка paramsSchema к start_state (см. №2).
- **ArchUnit api→{task, workflow}** — расширен в пачке H (не отложен в M.1/K.1).

### Тесты пачки H

WorkflowGraphSchemaValidatorTest 24 (unit) + WorkflowRegistryImplTest 13 + TaskRegistryCreateTest 10
+ TaskRegistryDependencyTest 7 + TaskRegistryLifecycleTest 15 + TaskRegistryListTreeHistoryTest 8,
ArchUnit 8→10 (foreignImpl по 6 модулям). Итог сюиты после фиксов ревью: 295 зелёных (было 289 до пачки H: 209).
### Ревью-цикл H, round 2 — применённые фиксы

- **R-1 (DS, major)**: start_state введён в замороженную спеку: (a) openapi.yaml —
  CreateWorkflowRequest.startState (required), CreateWorkflowRevisionRequest.startState
  (required), WorkflowRevisionDto.startState; регенерация из обновлённой спеки;
  (b) workflow-domain.md §2 — абзац про workflow_revision.start_state (current_state :=
  start_state; валидация ∈ codes); (c) дельта workflow-engine — требование «WorkflowRevision
  хранит start_state» + сценарии (невалидный start_state → 422 graph-invalid rule=unknown-state;
  задача стартует в start_state).
- **R-2 (DS, minor)**: фантом READ COMMITTED при вставке ревизии — 
ewRevision переводит
  вставку в TransactionTemplate (REQUIRES_NEW, попытка = независимая транзакция: после
  UNIQUE-violation текущая транзакция Postgres прервана, retry внутри неё невозможен);
  основной барьер — лок строки-родителя (SELECT ... FROM workflow WHERE id = ? FOR UPDATE)
  + лок строки max(rev); entityManager.flush() — конфликт ловится внутри попытки;
  retry — backstop, число попыток — конфиг harness.workflow.revision-insert-retries
  (новый WorkflowProperties; числа — только конфиг). Тест: конкурентный newRevision —
  обе ревизии с разными rev, latestRev=3.

## Пачка I — движок состояний (BASH/WAIT_WEBHOOK/WAIT_TASKS) + wake-инфраструктура

### Зафиксированные решения пачки

- **Event-wake из TaskRegistry** (D-49): createTask (стартовое состояние раскачивается
  сразу — BASH/WAIT_TASKS/AGENT без ожидания POLL), patch с изменением тегов (триггер
  TAGGED-барьеров), add/removeDependency (blocked-changed), stop ('$CANCELLED' — терминал
  для ALL_TERMINAL-наблюдателей). Публикация строго afterCommit.
- **TaskWakeDispatcher — полнопроходная переоценка барьеров на любой wake**: любой wake
  может закрывать чужие барьеры (ребёнок, созданный сразу терминальным; patch тегов; stop).
  Переоценка идемпотентна (CAS), масштаб — MVP (выборка по partial-индексу с LIMIT).
- **Premature-closure барьера — ответственность плана**: переоценка честно закрывает
  ALL_CHILDREN по текущему состоянию; если дети создаются после входа в барьер, одиночный
  терминальный ребёнок законно закрывает ALL_CHILDREN. Зафиксировано комментарием в тесте
  (дети создаются под suspended, resume — детерминированный триггер).
- **BashStateExecutor**: reason `output` — stdout и stderr, объединённые (контракт
  workspace-tools; раздельные поля без хака недоступны). `exitCode=null` — валидное
  содержимое истории (kill без exit-кода). Скрипт-инвариант идемпотентности — Javadoc.
- **Task-контейнер одноразовый**: BashStateExecutor снимает `harness-task-<taskId>` в
  finally на любом исходе (NEXT/ERROR/TIMEOUT/отмена); workspace на хосте сохраняется;
  остатки после рестарта добивает task-timeout-scanner.
- **payloadSummary без полного тела** (I-5/GLM M-1, D-29): в reason вебхука — только
  {topKeys, byteSize}; сверх `harness.webhook.payload-summary.byte-size-limit` —
  {byteSize, truncated} (конфиг: 4096 prod / 64 test). BASH-dispatch под гейтом
  `harness.task.bash-dispatch.enabled` (в тестах выключен — executor зовут напрямую).
- **Self-исключение из scope** (I-3): BLOCKED_BY/TAGGED — `id <> self` в SQL, EXPLICIT —
  фильтр при разборе params (WAITING-задача в собственном барьере висела бы вечно);
  ALL_CHILDREN сам себя содержать не может.
- **ORDER BY id во всех LIMIT-выборках джоб** (I-4): детерминизм страниц
  (dispatcher-барьеры, POLL bootstrap/WAIT_TASKS, таймаут-скан).
- **uuid[] в JdbcTemplate** — только через `AbstractSqlTypeValue` + `Connection.createArrayOf`
  (setObject(UUID[]) pgjdbc не поддерживает; varargs-расширение массива — ловушка).

### Фиксы ревью пачки I (применены до ревью-цикла — пачка оркестратора)

- **I-1 (DS, medium)**: NPE `Map.copyOf(reason)` при null-значениях — `TaskEngineImpl`
  переведён на null-толерантную копию (`Collections.unmodifiableMap(LinkedHashMap)`); в
  WaitWebhookStateExecutor nullable `source` в reason не попадает. Регресс: webhook без
  `?source=`, bash-TIMEOUT/kill с `exitCode=null` (unit + интеграция с убийством контейнера).
- **GLM nit**: null-safe `properties.transition()` в TaskEngineImpl и BashStateExecutor.
- **GLM M-3**: openapi.yaml TransitionDto.description — bash-reason
  `{exitCode, output, durationMs, attempt}` вместо раздельных stdout/stderr
  (description-only, регенерация не требуется; правка замороженной спеки — согласована
  ревью пачки I).

### Тесты пачки I

BashStateExecutorClassifyTest 6 + BashStateExecutorTaskContainerTest 5 (live-контейнер) +
TaskEngineTransitionTest 8 (CAS-гонки, stop-vs-transition ×10) + TaskSchedulerJobsTest 5 +
TaskWakeBusTest 4 + WaitTasksScopeTest 8 + WaitTasksStateExecutorIntegrationTest 11 +
WaitWebhookStateExecutorIntegrationTest 6, ConfigPropertiesBindingTest +1 (bindsTaskDefaults).
Итог сюиты: **351 зелёных** (было 295 после пачки H).

## Пачка J — AGENT-состояния + STATE-сессии + metaTools + SSE задач

### Отклонения dev (4)

1. **`StateSessionService` — контракт в корне session-модуля** (`session/StateSessionService`
   + `session/impl/StateSessionServiceImpl`), а не целиком в `session/impl`: ArchUnit
   «чужой `.impl` не импортируется» — execution зовёт только контракт. `owner_user_id`
   STATE-сессии читается собственным SQL из строки `task` (прецедент «данные-не-API» пачки H:
   TaskRegistryImpl → workflow_revision); session-модуль task-классы не импортирует.
2. **`instructionSource` резолвится в execution из батча, поднявшего Turn** (виды незапрошенных
   событий после `last_consumed_seq`: USER → USER; только TOOL_RESULT → TOOL_RESULT; иначе
   SYSTEM), а не через `Caller`: Turn исполняется на виртуальном потоке вне HTTP-контекста,
   SecurityContext там недоступен, а api-модуль execution не импортирует. `Caller.instructionSource()`
   /`sessionOwner()` добавлены по брифу как plumbing API-точек входа (атрибуция K.1; в M2 все
   входы API — USER).
3. **`task_event_seq` расходуют не только переходы** (session-api: «сквозной счётчик всех
   событий, включая не-transition»): `TaskRegistryImpl.addComment` и `cancelByStop` (stop)
   резервируют seq RETURNING-ом транзакционно с эмиссией. Эмиссия статуса на suspend/resume —
   K.2 (REST этой пачки); `suspended` накрывается снапшотом при каждом коннекте/реконнекте.
4. **SSE-backlog in-memory и ограниченный** (`harness.sse.task-backlog`, D-J-5-стиль): после
   рестарта процесса реконнект добирает только снапшот; durable-бэкфилл потребовал бы таблицу
   журнала событий задачи (новая миграция — вне объёма J, схема заморожена H.1). Курсор
   `task_event_seq` durable; пара кадров `task.transition`+`task.status` делит один seq
   (курсор — привязка к событию, не уникальный id кадра; фильтры доставки — строго «меньше»).

### Фиксы ревью пачки J (применены)

- **J-1 (DS, medium)**: `instructionSource` резолвился раз на Turn — USER во время
  TOOL_RESULT/SYSTEM-хода попадал в доп. раунд с устаревшим source; watermark COMPLETED его
  потреблял, а EVENT-wake выпадал на занятом локе — USER-намерение терялось.
  Fix: `AgentTurnEngine.run` возвращает source хода; `TurnManagerImpl` после turn-finish
  не-USER-хода (строго после unlock) переподнимает Turn (`session-wake`), если в журнале есть
  непрочитанный USER. Для USER-ходов не применяется (их mid-turn USER идёт в доп. раунд с
  корректным гейтом). Регресс: `AgentTransitionToolTest.userArrivingDuringToolResultTurnStartsRewokenTurnWithUserGate`
  (USER во время долгого bash + stop → CANCELLED с непрочитанным USER → re-wake → transition применён).
- **J-1/R-1 (DS, round 2 — полнота фикса)**: первый фикс не покрывал COMPLETED-кейс:
  ход от TOOL_RESULT рендерит mid-turn USER доп. раундом, гейт блокирует transition,
  но финальный ASSISTANT потребляет watermark (`last_consumed_seq == last_seq`) —
  journal-проверка re-wake не срабатывала. Fix: pending-намерение кодируется в самом
  watermark — `AgentTurnEngine` (TurnState) отслеживает минимальный seq нового USER'а,
  отрендеренного не-USER-ходом; при блокировке transition гейтом `last_consumed_seq`
  на turn-finish замораживается на `userIntentSeq - 1` (все три исхода: COMPLETED/FAILED/
  CANCELLED). USER остаётся в батче → post-finish re-wake поднимает ход с source=USER.
  Терминальность цепочки: re-woken ход — USER-source, для него re-wake не применяется.
  Регресс: `AgentTransitionToolTest.completedToolResultTurnKeepsUserIntentPendingAndRewakes`
  (COMPLETED-ход, bash-окно для USER, cap `last_consumed == userSeq-1`, re-wake, transition
  применён, в истории одна запись).
- **J-3**: `TransitionMetaTool.declaration()` — полноценный `FunctionToolCallback` по стилю
  M1 (`NativeAgentTools`); опциональность полей — `@ToolParam(required=false)` (taskId —
  «optional; resolved from the current state session», kind — optional), попадает в JSON-Schema
  провайдера; тело callback возвращает маркер (внутреннее исполнение Spring AI отключено —
  write-ahead/исполнение на Turn'е).
- **J-4**: `TaskWakeBroadcaster.streams` — eviction: поток удаляется из карты при уходе
  последнего подписчика; при переполнении backlog выброшенные кадры сигналятся живым
  подписчикам служебным кадром `TaskEvent.BacklogOverflow` → SSE `notify-dropped-events`
  (клиент ресинхронизируется снапшотом). `harness.sse.task-backlog` — только конфиг
  (fallback-дефолты 512/15000 из кода убраны, включая таймаут/ping контроллера).
- **J-5**: `rule=reason-required` — только для пустого `reason`; пустой `toState` →
  `rule=to-state-required`.
- **J-6**: Javadoc `TaskEvent.Status`/`TaskEventsController`: парный кадр `task.status` НЕ
  расходует собственный seq — делит `task_event_seq` перехода; счётчик нумерует события,
  а не кадры (продублировано в этом разделе, п.4 отклонений).
- **GLM nit**: числовые fallback-дефолты в коде → конфиг: `harness.sse.task-backlog` (yml),
  `harness.sse.ping-interval`/`timeout` читаются напрямую; закреплено `ConfigPropertiesBindingTest.bindsSseDefaults`.

### Тесты пачки J

StateSessionServiceTest 4 (атомарное создание+seed, резюм без дубля seed, разные state-code,
гонка 8 потоков — одна сессия/один seed) + AgentTransitionToolTest 6 (USER-ход применяет
переход транзакционно; TOOL_RESULT-ход блокирован гейтом; пустой reason; max-per-turn=1;
J-1 re-wake CANCELLED; J-1/R-1 pending-intent COMPLETED) + AgentStateBootstrapperTest 2
(bootstrap без сессии → Turn; повторный — резюм) + TaskEventsSseTest 5 (снапшот+живые кадры,
реконнект по Last-Event-ID, subtask.terminal на потоке родителя, task.comment с username
автора, 404) + ConfigPropertiesBindingTest +1 (assert task-backlog). Итог сюиты: **368 зелёных**
(было 351 после пачки I).

## Пачка K — REST задач + suspend/resume/stop + зависимости

### Отклонения dev (5)

1. **`owner` подзадачи = пользователь JWT (только в M2; K-6)**: замороженная спека даёт
   ветки «наследование от родителя (инициатор — агент сессии состояния)» / «из JWT
   (инициатор — пользователь)». В M2 все входы API — пользовательские (D-59), агентские
   инструменты появятся в M3 — тогда заработает ветка наследования от owner родителя.
2. **merge-patch `title:null`/`description:null` → 422 rule=required**: колонки NOT NULL
   (миграция 012) — «удаление члена» по RFC 7396 невозможно; `tags:null` → очистка
   (пустой массив). `params` в патче (любое присутствие) → 422 rule=immutable (§4.1).
3. **Неизвестный blocker в `blockedBy` → 422 dependency-invalid (rule=unknown-task)**, а не
   404: по тексту замороженной спеки §4.1 («self-loop/цикл/несуществующий taskId → 422»);
   404 task-not-found — только для блокируемой задачи `{id}` пути.
4. **`TaskEngineTransitionTest.suspendedTaskRejectsTransitionByCasGuard`: инвариант
   обновлён** — suspend теперь резервирует один `task_event_seq` (кадр task.status, см.
   ниже); отклонённый CAS-переход seq не расходует (это и есть проверяемый инвариант).
5. **`harness.webhook.base-url` — новый обязательный конфиг**: `TaskDto.webhookUrl`
   (WAIT_WEBHOOK) — абсолютный capability-URL `base + /api/webhooks/tasks/{id}/{HMAC}`;
   env `HARNESS_WEBHOOK_BASE_URL`, dev-дефолт в application.yml. Переиспользуется L.1
   (URL триггеров).

### Дополнительно (решения реализации)

- **Suspend/resume попадают в SSE-поток** (решение пачки J-3, отложено на K.2):
  `TaskRegistryImpl.suspend/resume` резервируют `task_event_seq` (UPDATE … RETURNING;
  каскадный suspend — один CTE-UPDATE на всё поддерево) и эмитируют кадры `task.status`
  строго после коммита.
- **Stop = sync + async (K-2)**: sync (HTTP-транзакция) — CAS `'$CANCELLED'` + история +
  task.status; async (после коммита) — EVENT-wake → `TaskWakeDispatcher.handleStop`
  отменяет Turn'ы STATE-сессий поддерева (`TurnManager.requestStop`, идемпотентно).
  Отдельный `StopTaskFacade` не выделялся (design.md D-54, proposal.md, specs/task-engine
  §Stop — синхронизированы).
- **`GET /sessions/{id}/tree` — плоский список**: сгенерированный `SessionTreeNode` не имеет
  `children` — родство несёт `parentSessionId` каждого узла; дерево задач, наоборот,
  вложенное (`TaskTreeNode.children`). Поддерево сессий — рекурсивный CTE
  `SessionStore.findSubtree(sessionId, depth)`, 404 на отсутствующем корне.
- **`webhookUrl` в списке задач** вычисляется на лету (HMAC stateless, без БД) — цена
  одного HMAC на WAIT_WEBHOOK-задачу.

### Фиксы ревью пачки K

- **K-1 (DS, medium)**: `POST /tasks/{id}/dependencies` с `[b, unknown]` коммитил первое
  ребро и только потом падал (частичный коммит — N отдельных транзакций). Fix: новая
  атомарная операция `TaskRegistry.addDependencies(blockedTaskId, blockerTaskIds[])` —
  лок всех затронутых задач одним SELECT … IN (…) ORDER BY id FOR UPDATE (расширение H-6
  на пачку), exists/DFS (с учётом рёбер самой пачки)/insert в одной транзакции; wake
  «blocked-changed» после коммита. Одиночный `addDependency` оставлен (делегирует в
  пачку); REST вызывает batch. *Именование: направление параметров следует
  REST-контракту ({id} = блокируемая, список = блокирующие), а не формулировке
  `addDependencies(blockerId, blockedIds[])` из ревью — иначе batch не выражается одним
  вызовом.* Тест: `TasksApiTest.dependencyBatchIsAtomicNoPartialCommit` (422 + 0 рёбер).
- **K-2 (DS, medium)**: async-отмена Turn'ов задокументирована: design.md D-49 (упоминание
  StopTaskFacade заменено) + D-54 (раздел «StopTaskFacade → sync + async»), proposal.md
  §What Changes («Stop = sync + async»), specs/task-engine/spec.md §Stop (двухфазный
  requirement + сценарий).
- **K-4 (GLM M-1, minor)**: N+1 в `listTasks` устранён: `TasksController.enricherOf(...)`
  резолвит usernames (owner+author всех задач страницы) ОДНИМ запросом через
  `AppUserDirectory.usernames` (WHERE id IN) и выжимки пиннутых ревизий одним
  `WorkflowRegistry.revisionSummaries`; DTO собираются без обращений к БД. Отдельный
  класс `BatchUsernameResolver` не заводился — `AppUserDirectory.usernames` уже является
  батч-резолвером (WHERE id IN), обёртка = дублирование (правило «без энтерпрайза»).
  Аналогично: `listTaskComments` (один батч по author'ам), `addTaskComment` (один запрос).
  Тест: `TasksApiTest.listTasksResolvesUsernamesWithSingleBatchQuery` — logback
  ListAppender на `org.springframework.jdbc.core.JdbcTemplate` → ровно 1 SQL с
  `username FROM app_user` (важно: `appender.start()` — без него logback молча
  роняет события).
- **K-5 (minor)**: не-массивный `tags` в патче (`tags: "foo"`) → 422 validation-failed
  rule=array-required (pointer /tags). Binding выполняется в `MergePatchHttpMessageConverter`
  ДО контроллера — поэтому shape-проверка «top-level член не массив при коллекционном
  свойстве DTO» реализована там (`shapeViolationOf`, reflection по бин-свойствам, без
  Jackson-introspection); прочие сбои связывания — прежний generic parse-отказ.
  Тест: `TasksApiTest.patchWithNonArrayTagsReturns422ArrayRequired`.
- **GLM nit**: `POST /tasks/{id}/suspend` — `cascade` required по спеке: отсутствующее
  тело/поле → 422 validation-failed rule=required (pointer /cascade), а не тихий default
  false. Тест: `TaskCommandsApiTest.suspendWithoutCascadeReturns422Required` (Accept:
  application/problem+json — эндпоинт produces только problem+json, иначе 406).
- **K-3/K-6**: настоящая секция + отклонение №1 (owner подзадачи).

### Итоги пачки K

TasksApiTest 17 (создание/пин/404/422 params-schema, merge-patch, список+фильтры+курсор,
подзадачи, дерево, история-курсор, комментарии, зависимости+циклы+атомарность пачки,
array-required, 1-SQL usernames, 404-матрица) + TaskCommandsApiTest 11 (suspend
идемпотентно/каскад/frame task.status, resume+wake, 409 на терминале, stop каскад+CANCEL,
409, 404, cascade-required, отмена Turn'ов STATE-сессии) + SessionsApiTest +2 (дерево:
STATE-поля taskId/stateCode, depth, 404) + TaskWakeDispatcherStopTest 2 (stop отменяет
Turn'ы поддерева; patch-wake не трогает cancel_requested) + TaskEngineTransitionTest
инвариант обновлён + ConfigPropertiesBindingTest +1 (bindsWebhookDefaults).
**Всего: 401 тест зелёный** (было 368 после пачки J).