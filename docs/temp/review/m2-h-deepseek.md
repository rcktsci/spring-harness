# Ревью M2 batch H: миграции 010..016 + `workflow/` + `task/` + D-58 + ArchUnit

> Ревьюер: DeepSeek-V4.1-Flash (субагент).
> Дата: 2026-09-18.
> Объект: `src/main/resources/db/changelog/migrations/2026/010..016`, `changeset-master.xml`, пакеты `workflow/`, `task/`, `common/jsonschema/`, `tests/architecture/ArchitectureRulesTest`, тесты `tests/{workflow,task}`, `DatabaseCleaner`.
> Контекст: `docs/design/data-model.md` §3/§4/§6/§7, `docs/design/workflow-domain.md` §2, спеки M2 (`workflow-engine`, `task-engine`), `tasks.md` H.1–H.3, D-56/D-58, api-contracts §4.1/§4.2.
> Сборки не запускались; сверка по исходникам и `target/generated-*`.
> Severity: **MEDIUM** — контрактный/поведенческий пробел; **MINOR** — узкая гонка/несогласованность; **NIT** — формулировка.

## Сводка

| Severity | Кол-во |
|---|---|
| MEDIUM | 2 |
| MINOR | 6 |
| NIT | 1 |
| **Итого** | **9** |

---

## (1) Миграции 010..016 ↔ data-model §3/§4/§6 — ✅ (1 minor)

- `010 workflow` (§3): `id uuid PK`, `key text UNIQUE`, `name`, `owner_user_id FK app_user`, `created_at` — совпадает.
- `011 workflow_revision` (§3): `id uuid PK` (пин задачи), `workflow_id FK workflow`, `rev int`, `graph_jsonb jsonb`, `created_at`, `UNIQUE (workflow_id, rev)` — совпадает. **H-5:** `graph_jsonb` без `nullable="false"` (граф обязателен по контракту).
- `012 task` (§4): все колонки (`title/description/author_user_id/owner_user_id/workflow_revision_id/current_state/current_state_kind/state_attempt/task_event_seq/deadline_at/status_projection/params_jsonb/parent_task_id/tags/suspended/created_at/updated_at`), CHECK `current_state_kind`, `status_projection`; индексы `(parent_task_id, status_projection)`, GIN `tags`, partial `current_state_kind='WAIT_TASKS'`, partial `(AGENT, RUNNING)`, partial `deadline_at` — совпадает (спека/decision J-16 учтены).
- `013 task_dependency` (§4): PK-пара, FK×2, `INDEX (blocked_task_id)` — совпадает.
- `014 task_comment` (§4, append-only): совпадает.
- `015 task_transition_history` (§4, append-only): `INDEX (task_id, created_at, id)`, CHECK kind `NEXT|ERROR|TIMEOUT|CANCEL` — совпадает.
- `016 trigger` (§6): `id/name/workflow_key/rev/params_jsonb/tags/owner_user_id/revoked_at/created_at` — совпадает.
- `changeset-master` включает 010..016 в FK-порядке; changeSet-id уникальны (14..26, M1 — 1..13); `DatabaseCleaner` чистит в корректном FK-порядке (comment/history/dependency → task → trigger → revision → workflow). JSON/ARRAY-маппинг опирается на существующий `Jackson3JsonFormatMapper` (JpaConfig), `TaskEntity`/`WorkflowRevisionEntity` — `@JdbcTypeCode(SqlTypes.JSON)`.

## (2) WorkflowRegistry + валидатор — ✅

`WorkflowGraphSchemaValidator` покрывает все rule-имена спеки `workflow-engine`: `code-unique`, `unknown-state`, `cancel-edge-forbidden`, `fan-out-forbidden` (≤1 ребро каждого kind), `outgoing-required`, `bash-error-required`/`bash-timeout-required`, `wait-error-required`/`wait-timeout-required`, `terminal-unreachable` (memo + cycle-guard), `timeout-format`, `enum`/`required`/`type` контракта, workspace-типы/режимы; собирает все нарушения. D-58-профиль `paramsSchema`/`payloadSchema` проверяется на «объект». Реестр: иммутабельные ревизии (`@Immutable` + `UNIQUE`), `rev=prev+1`, `key` уникален, список с opaque-курсором `(createdAt,id)`. Тесты — 20+ кейсов, включая `validCycleGraphWithTerminalPasses` и негативные `bash-timeout-required`/`wait-error-required`/`cancel-edge-forbidden`.

## (3) TaskRegistry CRUD + stop CAS — ✅ с оговорками (H-4)

- **stop** (`cancelByStop`): `SELECT … FOR UPDATE` + `UPDATE … WHERE status_projection IN ('RUNNING','WAITING')` (без гварда `suspended`) + запись истории `kind=CANCEL` в одной транзакции — соответствует data-model §7.2 (stop выигрывает у transition; терминальный → 409; каскад с пропуском терминальных потомков). Тесты: stop с историей, каскад, терминал, очистка deadline.
- Остальные переходы (с гвардой `current_state AND NOT suspended`) — движок `TaskEngine` (пачка I); в H их нет — корректно.
- **H-4**: `resume` делает незащищённый `UPDATE … SET suspended=false WHERE id=?` после Java-проверки терминальности → гонка resume↔stop (концуррентный stop → resume вернёт 204 на терминальной).
- DFS-валидация циклов зависимостей корректна (direct/transitive/self), `removeDependency` идемпотентен, дубль ребра — no-op.

## (4) paramsSchema (D-58) — ✅ (H-8)

`LimitedJsonSchemaValidator` (type/enum/required/properties/items, ручной обход, сбор всех ошибок) применён в `createTask`; тесты `paramsWrongTypeReportedWithPointer`, `paramsMissingRequiredReported` подтверждают pointers `/params/module`. **H-8:** схема берётся только с начального состояния.

## (5) ArchUnit — ⚠️ (H-2)

`foreignImplPackageIsHiddenBehindContract` расширен `task`/`workflow` (6 модулей). Слоёвые правила уже содержат `task`/`workflow` (task/workflow → identity). **H-2:** `api mayOnlyAccessLayers(...)` не включает `task`/`workflow` — сработает при K.1 (REST-контроллеры на `TaskRegistry`); план — tasks M.1.

## (6) «9 отклонений dev» — ⚠️ не задокументированы (H-9)

Артефакта с перечнем отклонений пачки H нет (нет секции H в `apply-notes.md`, нет отдельного файла; Javadoc фиксирует лишь два D-отклонения — removeDependency no-op и 422 key-unique). Ниже — обнаруженные мной девиации; прошу dev их зафиксировать.

---

## Findings

### H-1 [MEDIUM]. Начальное состояние задачи не определено контрактом; эвристика «уникальный source» + fallback на порядок JSON
- **Где:** `TaskRegistryImpl.resolveInitialState` (`task/impl/TaskRegistryImpl.java:479-506`); контракт графа (`workflow-domain.md` §2, `openapi.yaml` `WorkflowState`) поля «начальное состояние» не имеет.
- **Цитата:** «Начальное состояние: единственный state без входящих рёбер. Деградации (0 или несколько source'ов — правила §2 это не запрещают) — fallback на первый state + WARN».
- **Проблема:** для легальных циклических графов (приёмочный M.2: `reviewer → … → plan` — у `plan` есть входящее ребро) source-состояний может не быть вовсе → выбирается «первый state в массиве», т.е. старт зависит от порядка элементов в `graph_jsonb`, что не является частью контракта и не задокументировано. Спека task-engine/`workflow-engine` старт не описывают.
- **Предложение:** ввести явное начальное состояние — поле `graph.initial` (или зафиксировать «первый state»/«единственный source» в контракте §2 и в спеке), добавить в валидатор правило (ровно один source/явный initial), покрыть тестом на циклический граф; до решения — записать как девиацию.

### H-2 [MINOR]. ArchUnit: api-слой не получил доступ к `task`/`workflow`
- **Где:** `ArchitectureRulesTest.java:65` — `whereLayer("api").mayOnlyAccessLayers("execution","intelligence","session","identity")`.
- **Проблема:** tasks M.1/D.2 требуют `… "task", "workflow"` (контроллеры задач K.1 будут ходить в `TaskRegistry`); сейчас правило зелёное (api ещё не импортирует task), но упадёт в K.1, если M.1 не выполнится.
- **Предложение:** выполнить расширение в M.1 (или сразу), синхронно с `architecture.md` §2.

### H-3 [MINOR]. `newRevision`: `rev = max+1` без защиты от гонки
- **Где:** `WorkflowRegistryImpl.newRevision` (`workflow/impl/WorkflowRegistryImpl.java:90-95`).
- **Проблема:** два параллельных `POST /workflows/{key}/revisions` вычислят один `rev` → `UNIQUE (workflow_id, rev)` → исключение БД (500) вместо ретрая/409. Внутренний однoинстансный контур — вероятность низкая.
- **Предложение:** ретрай на unique-violation либо `INSERT … SELECT coalesce(max(rev),0)+1 …` в одной транзакции; тест конкурентности (2 потока).

### H-4 [MEDIUM]. `resume` не перепроверяет терминальность в UPDATE → гонка resume↔stop
- **Где:** `TaskRegistryImpl.resume` (`task/impl/TaskRegistryImpl.java:221-234`).
- **Цитата:** проверка `entity.getStatusProjection().isTerminal()` в Java, затем `UPDATE task SET suspended=false WHERE id=?` без гварда статуса.
- **Проблема:** между проверкой и UPDATE конкурентный `stop` может записать `$CANCELLED`; resume вернёт 204 и снимет `suspended` у терминальной задачи — нарушение спеки («resume терминальной → 409»). Секвенциально поведение корректно (тесты).
- **Предложение:** условный UPDATE `… WHERE id=? AND status_projection IN ('RUNNING','WAITING')`; `updated == 0` → перечитать статус и вернуть `409 task-already-terminal` (или 404).

### H-5 [MINOR]. `workflow_revision.graph_jsonb` не `NOT NULL`
- **Где:** миграция 011 (`011_create_table_workflow_revision.xml:42-44`).
- **Проблема:** граф обязателен по контракту; колонка допускает NULL (реестр всегда пишет, но схема слабее контракта).
- **Предложение:** `nullable="false"`.

### H-6 [MINOR]. `addDependency`: DFS-проверка и вставка без конкурентного гварда
- **Где:** `TaskRegistryImpl.addDependency` (`task/impl/TaskRegistryImpl.java:166-192`).
- **Проблема:** два параллельных `addDependency` (A→B и B→A) могут оба пройти DFS и вставиться → цикл в БД. Внутренний контур — вероятность низкая.
- **Предложение:** `SELECT … FOR UPDATE`/advisory-lock на пару задач или сериализация по графу; тест конкурентности.

### H-7 [NIT]. `LimitedJsonSchemaValidator` «integer» через `longValue() == doubleValue()`
- **Где:** `LimitedJsonSchemaValidator.matchesType` (`common/jsonschema/LimitedJsonSchemaValidator.java:108-109`).
- **Проблема:** сравнение теряет точность для значений > 2^53; для длинных целых из JSON (BigInteger) `Number` может дать ложное срабатывание.
- **Предложение:** для `integer` проверять `instance instanceof Integer|Long|BigInteger` (или `BigDecimal.scale()<=0`).

### H-8 [MINOR]. `paramsSchema` читается только с начального состояния
- **Где:** `TaskRegistryImpl.createTask` (`initial.paramsSchema()`), `InitialState.paramsSchema`.
- **Проблема:** спека/OpenAPI формулируют `paramsSchema` как свойство ревизии (по `workflow-domain` §2 — поле состояния); если схема объявлена на не-начальном состоянии, при создании она игнорируется. Не зафиксировано как решение.
- **Предложение:** определить правило (схема начального состояния / схема ревизии / слияние) и записать в спеку + Javadoc контракта.

### H-9 [MINOR/PROCESS]. Перечень «9 отклонений dev» пачки H отсутствует
- **Где:** `openspec/changes/m2-workflow-engine/apply-notes.md` (секций D.2/D.1; пачки H нет), отдельного файла нет.
- **Проблема:** практика M1/D — документировать решения и девиации пачки; по H проверить заявленные отклонения по списку невозможно. Обнаруженные мной девиации: (1) начальное состояние — эвристика (H-1); (2) `paramsSchema` с начального состояния (H-8); (3) removeDependency no-op (зафиксировано в Javadoc, D-пачка №4); (4) дубль ребра зависимости — no-op; (5) 422 key-unique (D-пачка №5); (6) гвард stop по `status_projection`, а не `current_state`; (7) курсор списка задач `(updated_at,id)` (спека не фиксировала поля); (8) `getHistory` с `limit=null` — все записи; (9) `deadline_at` из `state.timeout` при создании.
- **Предложение:** добавить секцию «Пачка H» в apply-notes с этим перечнем и обоснованиями.

---

## Позитив (проверено)

- Миграции точно соответствуют data-model §3/§4/§6; id changeSet уникальны и упорядочены; FK-зависимости и очистка БД корректны.
- Валидатор графа закрывает все правила §2 + D-58-профиль + `cancel-edge-forbidden`; тесты позитив/негатив обширны (в т.ч. циклы, достижимость, fan-out, обязательные рёбра).
- stop CAS — точно по data-model §7.2 (без гварда suspended, история CANCEL транзакционно, каскад с пропуском терминалов).
- paramsSchema-валидация D-58 работает и даёт корректные pointers.
- Архитектурные границы `task`/`workflow` соблюдены (`task` читает граф SQL-ом, не зависит от workflow-классов; JSON через Jackson3 FormatMapper); ArchUnit foreign-impl расширен.
- Тестовое покрытие пачки H сильное (create/lifecycle/dependency/list/tree/history + validator + registry).

## Вердикт

**REJECT — 9 находок (2 MEDIUM: H-1 стартовое состояние не определено/зависит от порядка JSON, H-4 гонка resume↔stop; 6 MINOR: H-2, H-3, H-5, H-6, H-8, H-9; 1 NIT: H-7).** Блокеры приёмки — H-1 (контрактный пробел стартового состояния, критичный для циклического приёмочного графа M.2) и H-4 (спека resume терминальной нарушается в гонке).

---

# Re-approval M2 batch H (2026-09-18)

> Проверены: миграции 011/012, `WorkflowGraphSchemaValidator`, `WorkflowRegistry(+Impl)`, `WorkflowRevisionEntity`, `TaskRegistryImpl`, `LimitedJsonSchemaValidator`, `ArchitectureRulesTest`, `TaskTestFixtures`, тесты validator/registry, `apply-notes.md` (секция H). Сборки не запускались.

## Статусы находок раунда 1

| # | Sev | Статус | Проверка |
|---|---|---|---|
| H-1 | MEDIUM | **закрыто в коде** | Явный `workflow_revision.start_state` (миграция 011, NOT NULL) + `WorkflowRegistry.createWorkflow/newRevision(..., startState)`, `TaskRegistryImpl.resolveStartState` читает `start_state` из ревизии; эвристика «source»/«первый в JSON» удалена; валидатор `validateStartState` (`/start_state`, required/unknown-state); тесты `missingStartState`/`unknownStartState`/циклических графов. **Но см. R-1** |
| H-2 | MINOR | **закрыто** | `ArchitectureRulesTest.java:65` — `api mayOnlyAccessLayers(..., "task", "workflow")` (расширено в H, не отложено в M.1) |
| H-3 | MINOR | **частично** | `newRevision` добавил `SELECT rev … ORDER BY rev DESC LIMIT 1 FOR UPDATE` — **не сериализует вставку** (см. R-2) |
| H-4 | MEDIUM | **закрыто** | `resume` — `SELECT status_projection, suspended … FOR UPDATE`, проверка терминальности и снятие `suspended` под локом; гонка с `stop` (тот же `FOR UPDATE`) закрыта |
| H-5 | MINOR | **закрыто** | 011 `graph_jsonb … nullable="false"` |
| H-6 | MINOR | **закрыто** | `addDependency` — `SELECT id FROM task WHERE id IN (?,?) ORDER BY id FOR UPDATE` (детерминированный порядок) до DFS и вставки; конкурентные A→B/B→A сериализуются, DFS видит закоммиченное ребро |
| H-7 | NIT | **закрыто** | `LimitedJsonSchemaValidator.isIntegral` (Integer/Long/BigInteger/целые Double|Float); enum-целые — по `longValue` |
| H-8 | MINOR | **закрыто** | `paramsSchema` однозначно привязана к `start_state` (apply-notes №2), согласовано с H-1 |
| H-9 | MINOR | **закрыто** | `apply-notes.md` §«Пачка H» — 9 отклонений dev + решения по ревью + число тестов |
| V-1 (GLM) | major | **закрыто** | Достижимость TERMINAL — обратный BFS от терминалов по встречным рёбрам (`validateTerminalReachability`), циклобезопасно; регресс-тесты «review → plan (ERROR)». Прежний прямой DFS с memo действительно отравлял кэш на циклах |

## Новые/остаточные находки

### R-1 [MEDIUM]. `start_state` введён в домен/БД, но отсутствует в замороженной спеке (OpenAPI/api-contracts/workflow-domain)
- **Где:** `openapi.yaml` (`CreateWorkflowRequest`, `CreateWorkflowRevisionRequest`, `WorkflowGraph`, `WorkflowRevisionDto`) — поля `startState`/`start_state` нет; `docs/design/workflow-domain.md §2` — контракт графа без стартового состояния; `tasks.md` H.2/H.3 — сигнатура `createWorkflow(key, name, graph)`. Код: `WorkflowRegistry.createWorkflow(..., String startState)`, миграция 011 `start_state NOT NULL`.
- **Проблема:** замороженная спека — источник истины для генерации (D.1); `WorkflowRegistry` теперь требует обязательный `startState`, а REST-запросы не имеют поля, где его передать (эвристика вывода удалена). H.2/K-обвязка REST не сможет выставить стартовое состояние; генерация из спеки его не знает. Контракт-first рассинхрон: изменение контракта не отражено в спеке и не проходило ревью-цикл.
- **Предложение:** добавить `startState` в `CreateWorkflowRequest`/`CreateWorkflowRevisionRequest` (и в `WorkflowRevisionDto` для чтения), в `workflow-domain.md §2` и api-contracts §4.2; провести delta-ревью спеки → регенерация (шаг 2) до REST-проводки. Либо (если решено хранить внутри графа) зафиксировать `graph.start_state` в схеме графа и читать его в реестре — но тогда pointer/контракт кода привести в соответствие.

### R-2 [MINOR]. `newRevision`: `FOR UPDATE` на последней ревизии не предотвращает фантомную вставку
- **Где:** `WorkflowRegistryImpl.newRevision` (строки 90-99): `SELECT rev FROM workflow_revision WHERE workflow_id = ? ORDER BY rev DESC LIMIT 1 FOR UPDATE`.
- **Проблема:** блокируется существующая строка max-rev, но новая ревизия **вставляется**, а не обновляет её. При READ COMMITTED вторая транзакция после снятия блокировки видит ту же старую max-строку (не изменённую) и вычисляет тот же `rev` → `UNIQUE (workflow_id, rev)` → исключение БД (500). Заявленная в apply-notes «сериализация конкурентных ревизий» этим локом не достигается; реальный рубеж — UNIQUE (ошибка, не порча).
- **Предложение:** лочить стабильную строку-родителя: `SELECT id FROM workflow WHERE id = ? FOR UPDATE` (до вычисления max), либо ретрай на unique-violation, либо advisory-lock. Тест конкурентности (2 потока).

## Итог re-approval H

Закрыто: H-1 (в коде), H-2, H-4, H-5, H-6, H-7, H-8, H-9, V-1. Остаточные: **R-1** (спека не знает `start_state` — блокер проводки/регенерации), **R-2** (`FOR UPDATE` H-3 на неверной строке, гонка сохранена под UNIQUE-бэкстопом). Блокеров ровно один — R-1 (контракт-first).

**REJECT — 2 незакрытых (1 MEDIUM: R-1 `start_state` вне замороженной спеки; 1 MINOR: R-2 неэффективный лок `newRevision`).** После синхронизации спеки по `start_state` (и, желательно, лока-родителя в newRevision) — approve.

---

# Re-approval 2 M2 batch H (2026-09-18)

> Проверены: `openapi.yaml`, регенерированные модели (`target/generated-sources/openapi-server/.../model`), `workflow-domain.md §2`, дельта `workflow-engine/spec.md`, `WorkflowRegistryImpl.newRevision/insertRevision`, `WorkflowProperties`, `application.yml`, `ConfigPropertiesBindingTest`, `WorkflowRegistryImplTest`. Сборки не запускались.

## Статусы остаточных находок

| # | Sev | Статус | Проверка |
|---|---|---|---|
| R-1 | MEDIUM | **закрыто** | `openapi.yaml`: `startState` в `CreateWorkflowRequest` (2326/2355), `CreateWorkflowRevisionRequest` (2364/2368), `WorkflowRevisionDto` (2207/2217); `workflow-domain.md:39` — `start_state` ревизии, `current_state := start_state`, `start_state ∈ states[].code` → 422 `graph-invalid`/`unknown-state`; дельта `workflow-engine/spec.md:80-91` — Requirement + сценарии; модели регенерированы (`CreateWorkflowRequest`/`CreateWorkflowRevisionRequest`/`WorkflowRevisionDto` содержат `startState`) |
| R-2 | MINOR | **закрыто** | `newRevision` делегирует в `insertRevision` под `TransactionTemplate(PROPAGATION_REQUIRES_NEW)`: (1) `SELECT id FROM workflow WHERE id = ? FOR UPDATE` — блокируется **строка-родитель** (сериализация по workflow), затем `SELECT rev … ORDER BY rev DESC LIMIT 1` под тем же локом (READ COMMITTED видит закоммиченный max); (2) `entityManager.flush()` выявляет UNIQUE-violation внутри попытки; (3) retry-цикл `harness.workflow.revision-insert-retries` (дефолт 3), конфиг валидируется `WorkflowProperties` (>=1); тест конкурентности в `WorkflowRegistryImplTest`, binding-тест `bindsWorkflowDefaults` (3) |

## Проверка

- Контракт-first восстановлен: источник истины (`openapi.yaml`) и доменный док (`workflow-domain.md`) знают `start_state`; спека обновлена дельтой; генерация даёт `startState`-поле.
- R-2 теперь корректен: лок именно на стабильной строке `workflow` исключает фантомную вставку; UNIQUE + retry — backstop; число попыток — конфиг (правило владельца).
- Более ранние фиксы (V-1 обратный BFS, H-4 resume FOR UPDATE, H-6 лок пар задач, H-5 NOT NULL, H-7 integer, H-2 ArchUnit api→task/workflow, H-8/H-9) не регрессировали.

## Замечание (nit, не блокер)

- `docs/design/api-contracts.md` §4.2 (таблица Workflow) не упоминает `startState`, хотя `openapi.yaml`/`workflow-domain.md`/дельта уже синхронизированы. Стоит добавить строку в §4.2 при следующей правке api-contracts (косметика документации; контракт и генерация не затронуты).

**APPROVE — 0 незакрытых (1 nit: `api-contracts.md §4.2` без `startState`).** Пачка H принята.