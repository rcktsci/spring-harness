# Ревью D.2: генерация M2 + wiring контроллеров

> Ревьюер: DeepSeek-V4.1-Flash (субагент).
> Дата: 2026-09-18.
> Объект: `pom.xml`, `api/{Tasks,TaskCommands,TaskEvents,Workflows,Triggers,Webhooks}Controller.java`, `api/SessionsController.java` (getSessionTree), `identity/SecurityConfig.java`.
> Контекст: `src/main/resources/api/openapi.yaml` (заморожен в D.1), сгенерированные `target/generated-sources/openapi-server/**` и `target/generated-test-sources/openapi-client/**`, `docs/design/api-contracts.md` §§0/4, `SecurityConfig`/`GroupsGateFilter`.
> Сборки не запускались; проверка по сгенерированным исходникам в `target/`.
> Severity: **MAJOR** — сломает e2e/контракт; **MEDIUM** — пробел верификации; **MINOR/NIT** — документация/гигиена.

## Сводка

| Severity | Кол-во |
|---|---|
| MAJOR | 1 |
| MEDIUM | 1 |
| MINOR/NIT | 2 |
| **Итого** | **4** |

---

## (1) pom `<apis>` ↔ tags openapi.yaml — ✅

`<apis>Agents,Sessions,SessionMessages,SessionCommands,Tasks,TaskCommands,Workflows,Triggers,Webhooks</apis>` в обоих executions (server + test-client). Теги openapi: `Agents, Sessions, SessionMessages, SessionCommands, SessionEvents, Tasks, TaskCommands, TaskEvents, Workflows, Triggers, Webhooks`. Сгенерированы ровно 9 интерфейсов (`AgentsApi, SessionsApi, SessionMessagesApi, SessionCommandsApi, TasksApi, TaskCommandsApi, WorkflowsApi, TriggersApi, WebhooksApi`); SSE-теги `SessionEvents`/`TaskEvents` **корректно исключены** из обоих наборов (контроллеры на SseEmitter вручную). Соответствие полное.

## (2) `jackson-databind` `test` → `provided` — ✅ обоснованно

- Причина подтверждена: единственный `@JsonDeserialize` в сгенерированных **main**-исходниках — `model/AddTaskDependenciesRequest.java:64` (`blockedBy`, `uniqueItems: true` → поле `Set<UUID>`, аннотация `@JsonDeserialize(as = LinkedHashSet.class)` из `com.fasterxml.jackson.databind.annotation`). Это main-исходники, поэтому `test`-scope недостаточен — нужен `compile` → `provided` (компиляция+тесты, вне fat-jar).
- `com.fasterxml.jackson.core:jackson-annotations` оставлен отдельно `provided` — корректно: Jackson 3 читает `com.fasterxml.jackson.annotation.*`; `@JsonDeserialize` (Jackson 2 databind) на main-runtime отсутствует — и это осознанно (AGENTS.md: Jackson 2 в main-runtime запрещён). Runtime-деградации нет (missing annotation types JVM игнорирует; Jackson 3 сам умеет `Set`).
- `jackson-datatype-jsr310` остался `test` — нужен только тест-клиенту. ✅

## (3) Контроллеры — все методы интерфейсов перекрыты — ✅

Сверка сгенерированных интерфейсов и контроллеров:

| Интерфейс | Методов | Контроллер | Статус |
|---|---|---|---|
| `TasksApi` | 11 | `TasksController` (createTask, listTasks, getTask, patchTask, createSubtask, addTaskDependencies, removeTaskDependency, listTaskHistory, addTaskComment, listTaskComments, getTaskTree) | ✅ сигнатуры (включая порядок/типы `listTasks(UUID, TaskStatusProjection, Boolean, List<String>, String, String, Integer)`, `listTaskHistory(UUID, String, Integer)`, `removeTaskDependency(UUID, UUID)`, `addTaskDependencies→ResponseEntity<Void>`) | 
| `TaskCommandsApi` | 3 | `TaskCommandsController` (suspend→Void, resume→Void, stop→Object) | ✅ |
| `WorkflowsApi` | 5 | `WorkflowsController` (list/create/get/createRevision/getRevision) | ✅ |
| `TriggersApi` | 3 | `TriggersController` (create/list/revoke) | ✅ |
| `WebhooksApi` | 2 | `WebhooksController` | ✅ |
| `SessionsApi` (+`getSessionTree`) | 5 | `SessionsController` (+`getSessionTree`) | ✅ |

`skipDefaultInterface=true` — методы абстрактные, все реализованы. Компиляционных пропусков нет.

## (4) `WebhooksController` `@RequestMapping("/api")` — ⚠️ обход корректен по семантике Spring, но **не верифицирован** и **ломает тест-клиент**

- Сгенерированный `WebhooksApi` имеет type-level `@RequestMapping("${openapi.springHarnessAPIM1M2.base-path:/api/v1}")` и метод `PATH_HANDLE_TASK_WEBHOOK = "/webhooks/tasks/{taskId}/{token}"` (path-item `servers: /api` генератор server-интерфейса игнорирует). Контроллер перекрывает type-level на `/api` → итоговый маршрут `/api/webhooks/...` (Spring: конкретный класс-`@RequestMapping` приоритетнее интерфейсного). Приём рабочий, но **ни один тест не подтверждает** ни попадание в `/api/webhooks/**`, ни отсутствие `/api/v1/webhooks/**`.
- **Отдельно:** сгенерированный java-тест-клиент `WebhooksApi` использует `localVarPath = "/webhooks/..."` и `ApiClient.basePath = "/api/v1"` по умолчанию → вызовы уйдут на `/api/v1/webhooks/...`, т.е. **мимо серверного маршрута** (см. F-1).

## (5) `SecurityConfig` permitAll — ✅

`.requestMatchers("/api/webhooks/**").permitAll()` **до** `.requestMatchers("/api/v1/**").authenticated()`, затем `.anyRequest().denyAll()` — порядок верный, `/**` покрывает вложенные пути. `GroupsGateFilter` нулл-безопасен: действует только при `JwtAuthenticationToken` (у вебхука без токена аутентификации нет → проходит); `UserSyncFilter` аналогично. Т.е. permitAll действительно открывает вебхуки без JWT, HMAC — ответственность контроллера (L.3).

## (6) TreeNode-расширение — ✅

`target/.../model/SessionTreeNode.java` содержит `@Nullable UUID taskId` и `@Nullable String stateCode` (в `SessionTreePage.items`), `SessionDto` — тоже `taskId`/`stateCode`. `SessionsController.getSessionTree(UUID, Integer)` добавлен (стаб). Регенерация «заставила» обновить контроллер — как и планировалось в D.1/J-19.

---

## Findings

### F-1 [MAJOR]. Тест-клиент вебхуков бьёт в `/api/v1/webhooks/**`, сервер слушает `/api/webhooks/**`

- **Где:** `target/generated-test-sources/openapi-client/.../api/WebhooksApi.java` (`localVarPath = "/webhooks/tasks/{taskId}/{token}"`), `.../ApiClient.java` (`updateBaseUri("/api/v1")`, `basePath`); `WebhooksController` (`@RequestMapping("/api")`); openapi.yaml (`servers: - url: /api` под path-item).
- **Проблема:** java-генератор игнорирует path-level `servers` (та же причина, что у server-интерфейса). Итог: `WebhooksApi` тест-клиента резолвит `/api/v1/webhooks/...`, а сервер отдаёт `/api/webhooks/...`. Любой e2e-тест L.3 через сгенерированный клиент получит 404/401.
- **Предложение:** зафиксировать в apply-notes и в задаче L.3 явный обход — конструировать `WebhooksApi` на `ApiClient` с `basePath = "/api"` (метод уже относительный `/webhooks/...`); либо генерировать webhook-эндпоинты из отдельной спеки с `servers: /api`. Без этого L.3 упрётся.

### F-2 [MEDIUM]. Нет верификации wiring/routing D.2

- **Где:** новых тестов нет (`grep` по `src/test` — ни `/api/webhooks`, ни `/api/v1/tasks`, ни `TaskCommandsApi/WebhooksApi/getSessionTree`).
- **Проблема:** D.2 вводит 5 групп эндпоинтов + неочевидный обход с перекрытием type-level `@RequestMapping` (`/api`) и permitAll. `@SpringBootTest`-контекст загрузится и при неверном маршруте; утверждения о фактическом пути нет. Регресс «маршрут уехал на `/api/v1/webhooks`» или «permitAll не матчится» пройдёт незамеченным.
- **Предложение:** smoke-тест (MockMvc/`@WebMvcTest` или e2e): `POST /api/webhooks/tasks/{id}/{token}` без JWT доходит до контроллера (не 401/404); `GET /api/v1/tasks` без JWT → 401; `GET /api/v1/sessions/{id}/tree` замаплен; отсутствие `/api/v1/webhooks/...`.

### F-3 [MINOR]. `apply-notes.md` без секции D.2

- **Где:** `openspec/changes/m2-workflow-engine/apply-notes.md` (есть только «Пачка D (D.1)»).
- **Проблема:** пачка D.2 несёт три неочевидных решения: `jackson-databind: provided` (нужда — `@JsonDeserialize` в main DTO), `@RequestMapping("/api")` обход, basePath тест-клиента (F-1). Практика M1 — документировать пачку и её девиации.
- **Предложение:** добавить секцию D.2 с этими тремя пунктами.

### F-4 [NIT]. Стабы отдают 500 без каталожного кода

- **Где:** все 6 контроллеров (`UnsupportedOperationException`), `ApiExceptionHandler.@ExceptionHandler(Exception.class)` → `500` c `code=null`.
- **Проблема:** до реализации H–L любой вызов новых эндпоинтов (в т.ч. `permitAll`-вебхуков) отдаёт 500 без кода каталога — приемлемо как интермитент, но стоит зафиксировать, что это ожидаемое состояние D.2, и что `@ExceptionHandler(Exception.class)` не отдаёт stacktrace.
- **Предложение:** упомянуть в apply-notes D.2; при желании — временный `501 not-implemented`-подобный ответ вместо 500 (не обязательно).

---

## Позитив (проверено)

- `<apis>` соответствует тегам; SSE-теги исключены; генерируются ровно нужные 9 интерфейсов.
- Все методы сгенерированных интерфейсов перекрыты, сигнатуры совпадают.
- `jackson-databind: provided` — обоснование подтверждено фактическим `@JsonDeserialize` в `AddTaskDependenciesRequest` (uniqueItems).
- SecurityConfig: permitAll вебхуков перед authenticated, `GroupsGateFilter` не трогает неаутентифицированные запросы.
- M1-поверхность не тронута (кроме аддитивного `getSessionTree`); ArchUnit-слои не нарушены (контроллеры импортируют только `api.gen`).

## Вердикт

**REJECT — 4 находки (1 MAJOR: F-1 basePath тест-клиента вебхуков; 1 MEDIUM: F-2 нет верификации маршрутов; 2 MINOR/NIT: F-3, F-4).** Код-контракт и SecurityConfig корректны; блокируют приёмку отсутствие верификации обхода `@RequestMapping("/api")` и подтверждённый рассинхрон тест-клиента с серверным маршрутом (наступит в L.3).

---

# Re-approval D.2 (2026-09-18)

> Проверены: `pom.xml`, 6 контроллеров (+`SessionsController.getSessionTree`), `SecurityConfig`, `ApiExceptionHandler`, `ProblemCodes`, `ApiNotImplementedException`, `SignatureInvalidException`, `WebhookProperties`, `application.yml`/`application-test.yml`, `openapi.yaml`, новый `WebhooksRoutingTest`, `apply-notes.md`. Сборки не запускались (сверка по исходникам и `target/generated-*`).

## Статусы моих находок

| # | Sev | Статус | Проверка |
|---|---|---|---|
| F-1 | MAJOR | **закрыто** | Паттерн e2e закреплён: `WebhooksRoutingTest.generatedClientResolvesApiBasePath` — `ApiClient` + `updateBaseUri(localServerUrl()+"/api")`, `WebhooksApi` даёт `/api/webhooks/...` (401 signature-invalid), т.е. подтверждён и серверный маршрут, и обход. Задокументировано в apply-notes («паттерн обязателен для L.3») |
| F-2 | MEDIUM | **закрыто** | `WebhooksRoutingTest` — 5 тестов: bad token → 401 signature-invalid; мусорный Bearer → signature-invalid (не unauthenticated); valid HMAC → 501 not-implemented; `/api/v1/webhooks/**` → 401 unauthenticated (маппинга без `/v1` нет); basePath клиента. Ключевой риск обхода `@RequestMapping("/api")` покрыт |
| F-3 | MINOR | **закрыто** | `apply-notes.md` — секция «Пачка D.2»: `jackson-databind: provided`, `@RequestMapping("/api")`, basePath клиента, две security-цепочки, `harness.webhook.secret`, 501-стабы |
| F-4 | NIT | **закрыто** | Все стабы → `ApiNotImplementedException` → 501 `not-implemented` (`ApiExceptionHandler`, `ProblemCodes`, код добавлен в `ProblemCode` и api-contracts §6) |

## Проверка фиксов (сверх моих находок)

- **F-1/F-2 механизм:** отдельная `SecurityFilterChain` `@Order(1)` `securityMatcher("/api/webhooks/**")` без `oauth2ResourceServer` — корректно изолирует вебхуки от разбора Bearer (GLM M-1); основная цепочка сужена `securityMatcher("/api/v1/**")`. HMAC-гейт в `WebhooksController` (constant-time `MessageDigest.isEqual`), `WebhookProperties` зарегистрирован (`@ConfigurationPropertiesScan`). Все сходятся.
- **`not-implemented`:** добавлен в `ProblemCode` (21-й код) и api-contracts §6 — консистентно во всех артефактах, задокументировано как переходный.

## Новые находки

### N-1 [MEDIUM]. Потерян deny-by-default: две цепочки с `securityMatcher`, catch-all нет
- **Где:** `SecurityConfig.java:47-83`. Обе цепочки имеют `securityMatcher` (`/api/webhooks/**` и `/api/v1/**`), третьей (catch-all) нет; в M1 цепочка была без `securityMatcher` с `.anyRequest().denyAll()`.
- **Проблема:** запросы вне `/api/v1/**` и `/api/webhooks/**` (например `GET /error`; в будущем — actuator, который `operations.md` §2 планирует на `/actuator/prometheus`) не матчатся ни одной цепочкой → проходят **без security-фильтров**. Ранее их закрывал `anyRequest().denyAll()`. Сейчас поверхность пуста (actuator в pom отсутствует), но deny-by-default потерян молча.
- **Предложение:** восстановить защиту по умолчанию — третья цепочка `@Order(2)` `securityMatcher("/**")` с `.anyRequest().denyAll()` (либо основную цепочку вернуть на `securityMatcher("/**")` — вебхуки всё равно первыми ловит `@Order(1)`); зафиксировать в apply-notes.

### N-2 [MINOR]. `not-implemented` вморожен в контракт как переходный код
- **Где:** `openapi.yaml` `ProblemCode`, `docs/design/api-contracts.md` §6, generated `ProblemCode`.
- **Проблема:** код живёт, пока стабы не реализованы (пачки H–L); после реализации он становится мёртвым членом замороженного каталога (и клиентского SDK). Удаление позже — изменение контракта.
- **Предложение:** добавить в задачи H–L (или M.3) пункт «убрать `not-implemented` из ProblemCode/§6/ProblemCodes после реализации стабов».

### N-3 [MINOR]. HMAC-логика продублирована в контроллере до L.2
- **Где:** `WebhooksController.expectedToken/requireValidToken`.
- **Проблема:** L.2 планирует `WebhookSignatureVerifier` (чистая функция) — сейчас в `api`-контроллере лежит production-криптография; риск расхождения (алгоритм/кодирование) при рефакторинге.
- **Предложение:** при выполнении L.2 вынести в `WebhookSignatureVerifier` и оставить контроллеру только вызов (единственный источник истины), подтвердить существующим тестом.

## Итог re-approval D.2

Все 4 мои находки закрыты; плюс-фиксы (две цепочки, HMAC-гейт, 501, тесты) выполнены аккуратно. Остаётся новый регресс по умолчанию-запрету (N-1) и две гигиенические заметки (N-2, N-3) — исправляются одной цепочкой/одной строкой задач.

**REJECT — 3 незакрытых (1 MEDIUM: N-1 потерян deny-by-default; 2 MINOR: N-2 переходный `not-implemented` в контракте, N-3 дублирование HMAC до L.2).** Блокер приёмки — N-1 (восстановить catch-all deny).

---

# Re-approval 2 (final) D.2 (2026-09-18)

> Проверены: `identity/SecurityConfig.java`, `api/WebhooksController.java`, `common/security/WebhookSignatureVerifier.java`, `api/WebhooksRoutingTest.java`, `apply-notes.md`, `tasks.md` (L.5). Сборки не запускались.

## Статусы находок

| # | Sev | Статус | Проверка |
|---|---|---|---|
| N-1 | MEDIUM | **закрыто** | `SecurityConfig`: три цепочки — `@Order(1)` `/api/webhooks/**` permitAll без Bearer-резолвера; `@Order(2)` `/api/v1/**` (authenticated + oauth2ResourceServer + GroupsGate/UserSync); `@Order(3)` **catch-all** `securityMatcher("/**")` `.anyRequest().denyAll()` + entry point (401 анониму). Порядок 1→2→3 корректен (first match wins). Тесты: `errorPathIsDeniedWith401Unauthenticated` (`/error` → 401), `unlistedPathsAreDeniedWith401Unauthenticated` (`/actuator/prometheus` и `/` → 401) |
| N-2 | MINOR | **закрыто** | `apply-notes.md` §«Пачка D.2 — план удаления `not-implemented`» (удаление после L.4) + задача **L.5** в `tasks.md`: убрать `not-implemented` из `ProblemCode` спеки, api-contracts §6, `ProblemCodes.java`, `ApiExceptionHandler` и стабов; verify — `mvn clean verify` без `not-implemented` в main |
| N-3 | MINOR | **закрыто** | HMAC вынесен в `common/security/WebhookSignatureVerifier` (constant-time `MessageDigest.isEqual`, `expectedToken` переиспользуется в L.1); `WebhooksController` только вызывает `signatureVerifier.verify(...)` → `SignatureInvalidException` |

## Замечания (не блокеры)

- В `apply-notes.md` строка про N-1 называет catch-all цепочку `@Order(2)`; фактически catch-all — `@Order(3)`, а `/api/v1`-цепочка — `@Order(2)`. Текст apply-notes стоит поправить на «`@Order(3)` catch-all» (косметика).
- `WebhookSignatureVerifier` помечен `@Component` и появился в D.2 (раньше планировался L.2); `WebhooksController` уже его использует — расхождения нет, задача L.2 сводится к юнит-тестам позитив/негатив. Ок.

## Проверка (инварианты)

- Три цепочки не пересекаются по приоритету: `/api/webhooks/**` → 1; `/api/v1/**` → 2; всё прочее (включая `/error`, `/actuator/**`) → 3 (denyAll, 401 анониму) — deny-by-default восстановлен.
- Вебхуки по-прежнему вне JWT-разбора (чужой Bearer не влияет); HMAC-гейт в контроллере, 501-стаб за гейтом.
- `not-implemented` остаётся переходным и имеет явный план удаления (L.5) — контракт вернётся к каталогу §6 без него.
- Мои F-1…F-4 (раунд 1) не регрессировали.

**APPROVE — 0 незакрытых (2 косметических замечания).** D.2 закрыт; приёмка замороженной спеки и wiring завершены.