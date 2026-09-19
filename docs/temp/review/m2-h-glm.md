# Ревью пачки H (миграции + WorkflowRegistry/TaskRegistry) — GLM-5.3-Flash

Дата: 2026-09-19. Объект: миграции 010..016 + changeset-master; пакеты `workflow`/`task` (контракты, impl, сущности, репозитории, исключения), `WorkflowGraphSchemaValidator`, `common/jsonschema/LimitedJsonSchemaValidator`, ArchUnit. Сборки не запускались; генераты/артефакты прогона dev не проверялись (пачка консистентна по чтению).

**Вердикт: REJECT** — 6 находок (1 major / 3 minor / 2 nit). Ядро пачки крепкое (миграции точно по data-model, stop-CAS корректен, DFS-циклы в реестре без багов), но в валидаторе графа — ложные срабатывания на каноничных циклических графах.

---

## (1) Миграции 010..016 — ✅

Сверено с data-model §3–§4 постатейно: `workflow` (UNIQUE key, FK owner), `workflow_revision` (UNIQUE (workflow_id, rev)), `task` (все 18 колонок, включая `task_event_seq BIGINT DEFAULT 0` — durable-носитель SSE-курсора, `state_attempt`, `deadline_at` tz-nullable; FK author/owner/revision/parent — cascade), `task_dependency` (PK пары + `idx_task_dependency__blocked_task_id`), `task_comment` (append-only), `task_transition_history` (+ `idx (task_id, created_at, id)` — курсор `(created_at, id)`, CHECK kind). Индексы task: `(parent_task_id, status_projection)`, GIN `tags`, partial `WAIT_TASKS`, partial `(AGENT+RUNNING)`, partial `deadline_at` — все пять из data-model §4 на месте. CHECK-констрейнты на enum-колонки — в стиле M1 (ck_session__kind). preConditions MARK_RAN, changeset-master 010..016 по порядку. Замечаний нет.

## (2) WorkflowGraphSchemaValidator + D-58 — ❌ major (ложный terminal-unreachable на циклах) + ✅ остальное

Правила: code-unique, unknown-state, cancel-edge-forbidden, fan-out (≤1 kind), outgoing-required, bash-error/timeouted-required, **wait-error-required/wait-timeout-required** (новое — на месте, сценарий «WAIT без ERROR» покрыт тестом), timeout-format, контракт по типам (AGENT→agent_key, BASH→script, WAIT_TASKS→scope+condition, TERMINAL→outcome), workspace enum, payloadSchema/paramsSchema — «объект». Ошибки собираются все, pointers корректные. LimitedJsonSchemaValidator — чистый профиль D-58 (type/enum/required/properties/items), численный enum с 1≡1.0 — приемлемо. Тесты: ~20 кейсов, включая цикл и все новые rule-имена.

### V-1 (MAJOR). terminal-unreachable: memo отравляется на циклах — валидные графы «цикл с возвратом» отвергаются
- **Где**: `WorkflowGraphSchemaValidator.reachesTerminalFrom` (строки 353–374).
- **Воспроизведение** (аналитически, по коду): states `plan(AGENT)`, `reviewer(AGENT)`, `done(TERMINAL)`; рёбра `plan→reviewer (NEXT)`, `reviewer→plan (NEXT)`, `plan→done (NEXT)`. Обход от `plan`: visiting={plan}; первый путь — reviewer: его единственное ребро `→plan` упирается в revisiting → `return false` → **`memo[reviewer]=false` кэшируется**; возврат в plan: второе ребро `done` → true, `memo[plan]=true`. Далее верхний цикл добирается до `reviewer`: `memo.get → false` → ошибка `terminal-unreachable` на `/states/1`. Граф валиден по правилам §2 (у reviewer есть исходящий NEXT, TERMINAL достижим: reviewer→plan→done), но отвергнут.
- **Почему тесты не поймали**: `validCycleGraphWithTerminalPasses` использует граф, где цикл-узел (`checks`) имеет прямые рёбра в `done` после циклового ребра → memo кэширует true. Отравление требует, чтобы у цикл-узла **все** рёбра уходили в ancestors visiting-стека. Форма «возврат к плану, у которого выход вперёд» — каноничная для acceptance-сценария 7.2 (reviewer-1→plan, план→merge→done) в других порядках рёбер/состояний.
- **Предложение**: заменить прямой DFS на **обратный BFS/DFS от всех TERMINAL** по инвертированным рёбрам (O(V+E), тривиально корректен, без цветов/memo-ловушек); либо не кэшировать отрицательный результат узла, вычисленный при срезке по visiting (classic white/grey/black). Добавить регресс-тест ровно на форму выше.

## (3) TaskRegistry — ✅ ядро, 2 замечания

- **DFS-циклы + self-loop**: `addDependency` — self-loop → `dependency-invalid (self-loop)`; существующее ребро — идемпотентный no-op; цикл — `reaches(blocked ⇒* blocker)` по графу «блокирует→блокированный» — направление и предикат корректны; DFS с visiting-набором **без memo** — циклобезопасен (в отличие от валидатора). ✅
- **params иммутабельность**: `TaskPatch` не содержит params — реестр не может их изменить; 422 `rule=immutable` — зона api-слоя (пачка K, merge-patch-фильтр) — проконтролировать при ревью K. ✅ (для H)
- **suspend/resume/stop**: suspend — recursive CTE (cascade) + идемпотентно; resume — 409 на терминале + `task-wake` afterCommit (K.2-семантика уже соблюдена); **stop** — `SELECT … FOR UPDATE` + `UPDATE … WHERE status_projection IN ('RUNNING','WAITING')` **без гварда suspended** (data-model §7.2) + запись `kind=CANCEL, reason={kind:stop,actor:user}` в той же транзакции; терминальные подзадачи каскада молча пропускаются, корень терминальный → 409. Соответствует контракту. ✅
- Курсоры `(updated_at,id)`/`(created_at,id)` — tuple-сравнение, Base64-URL opaque — стабильно. Чтение графа ревизии собственным SQL — task не зависит от workflow-классов (architecture §2 соблюдена). ✅

## (4) ArchUnit — ✅ для H, ⚠️ порядок с пачкой K (M-2)

foreignImpl-проверка параметризована 6 модулями (`session, execution, intelligence, identity, task, workflow`), task/workflow добавлены в слой-граф (`task→identity`, `workflow→identity`, `execution→{…,task,workflow}`). Но `api.mayOnlyAccessLayers` по-прежнему **без task/workflow** — сегодня это ок (в api нет импортов task), однако пачка K заводит REST-контроллеры, импортирующие `TaskRegistry`/`WorkflowRegistry` (D-49: WebhookHandlers в api/impl делегирует в task), и ArchUnit упадёт **в K**, тогда как план кладёт расширение allowed-layers в M.1 (приёмка, после K/L) — сам текст M.1 это признаёт («иначе HTTP-контроллеры задач/вебхуков не пройдут CI»). Порядок в плане/исполнении разъехался.

### M-2 (minor). Перенести расширение `api.mayOnlyAccessLayers(+task,+workflow)` из пачки M в пачку K (первая же задача K)
- **Цитата**: `ArchitectureRulesTest.java:65` — `.whereLayer("api").mayOnlyAccessLayers("execution", "intelligence", "session", "identity")`; tasks M.1 — «allowed-layers для api расширяется… иначе HTTP-контроллеры задач/вебхуков не пройдут CI».
- **Предложение**: выполнить строку M.1 про api-слои в начале пачки K (одна строка + комментарий), в M.1 оставить только финальную верификацию.

## (5) Девиации params/initial/deadline + документирование

### M-1 (minor). `paramsSchema` читается только с начального состояния — договорённость не зафиксирована (совпадает с DS H-8; найдено независимо)
- **Цитата**: `TaskRegistryImpl.createTask` → `initial.paramsSchema()`; openapi.yaml (WorkflowState.paramsSchema): «JSON-Schema для params задач **этой ревизии** … создание задачи/триггера валидирует».
- **Проблема**: контракт объявляет paramsSchema на уровне ревизии (поле состояния с ревизионной семантикой); реализация берёт схему только у initial-state — объявление на другом состоянии молча игнорируется. Ни apply-notes, ни Javadoc этого решения не фиксируют.
- **Предложение**: выбрать одно и зафиксировать: (а) валидировать против любой paramsSchema ревизии (первая найденная по состояниям, детерминированный порядок) — соответствует формулировке контракта; (б) подтвердить «initial-state-only» и поправить description в openapi.yaml (одна строка). Тест на не-initialную схему в обоих случаях.

### M-3 (minor). Секция «Пачка H» в apply-notes.md отсутствует — 9 отклонений dev недокументированы (совпадает с DS H-9; **Mercury против DS — прав DS**)
- **Проверено мной**: grep apply-notes.md по «Пачка» даёт только секции D.2/D; ни H-секции, ни отдельного файла-отчёта нет. Утверждение Mercury «9 отклонений dev применены в apply-notes.md» — не соответствует репозиторию.
- **Оценка заявленных отклонений по коду** (несмотря на отсутствие отчёта): #2 paramsSchema-source — см. M-1; #3 `deadline_at` из `state.timeout` при создании — реализовано (`initial.deadline(now)`), консистентно: при отсутствии `state.timeout` deadline=NULL, дефолты `kind-timeouts` — зона движка (пачка I) — приемлемо, но зафиксировать; #8 валидатор-тесты без BaseApplicationTest — оправдано (чистая функция, D-56: «тестируемость без БД»), 20+ кейсов есть. Прочие девиации, видимые из кода и не зафиксированные: эвристика initial-state (0/многие sources → fallback первый + WARN), гвард stop по `status_projection` (не `current_state` — корректно: СУБД-независимый терминал), no-op повторного addDependency, `(updated_at,id)`-курсор списка, `getHistory(limit=null) → все записи`.
- **Предложение**: дописать секцию H в apply-notes.md (9+ пунктов, формат D-пачки) до коммита пачки; без неё кросс-чек пачки не воспроизводим.

### N-1 (nit). `TaskRegistryImpl.list`: NPE при `criteria.limit() == null`
- **Цитата**: `params.add(criteria.limit() + 1)` (строка 340).
- **Предложение**: либо задокументировать в `TaskRegistry` «limit обязателен (проверяется api-слоем)», либо defensive-default из конфига.

### N-2 (nit). `resolveInitialState`: fallback при 0/многих source — решение нигде не записано
- **Цитата**: Javadoc «Деградации (0 или несколько source'ов — правила §2 это не запрещают) — fallback на первый state + WARN».
- **Предложение**: включить в apply-notes H как отклонение (это в точности класс «девиация, которую ревью должен видеть»).

---

## Позитив (кратко)

- Миграции — эталонно по data-model §3–§4, включая оба partial-индекса сканов и `blocked_task_id`; CHECK-констрейнты в стиле M1.
- stop-CAS, история в транзакции, после-commit wake, tuple-курсоры — стиль SessionStoreImpl выдержан.
- Граница task↔workflow выдержана (граф читается SQL-ом как данные ревизии); ArchUnit foreignImpl теперь на 6 модулях.
- HMAC-подобных самодеятельностей нет; числа не захардкожены.

## Вердикт

**REJECT** — 6 находок (1 major: V-1 ложный terminal-unreachable на валидных циклах — контрактизация каноничного сценария M2; 3 minor: M-1 paramsSchema-source, M-2 порядок ArchUnit api→task перед пачкой K, M-3 apply-notes H/9 отклонений; 2 nit). После фикса V-1 (обратный BFS от терминалов + регресс-тест) и однострочных minors — approve.

---

# Re-approval пачки H (2026-09-19)

## Статус моих находок

- **V-1 (major, terminal-unreachable на циклах)** — **закрыто**: `validateTerminalReachability` переписан на **обратный BFS от всех TERMINAL** по инвертированным рёбрам (строки 358–391); проверено вручную на моём poison-графе (plan→reviewer, reviewer→plan, plan→done): все три состояния попадают в `reaching`, ложных ошибок нет. Циклобезопасно по построению; Javadoc фиксирует причину исходного дефекта. Регресс-тесты заявлены (цикл «review → plan → merge» + полный сценарий 7.2 с возвратом).
- **M-1 (paramsSchema initial-state-only)** — **закрыто**: решение зафиксировано (apply-notes H, п.2): валидация по paramsSchema **явного `start_state`** ревизии; эвристика «единственный source» и fallback «первый в JSON» удалены; `resolveStartState` бросает защитный отказ на повреждённой ревизии. Связка замкнута: валидатор теперь требует `start_state` (required + ∈ codes, строки 99–107), миграция 011 — NOT NULL колонка. Однозначная точка привязки вместо молчаливого игнора — принято.
- **M-2 (порядок ArchUnit api→task/workflow)** — **закрыто**: `ArchitectureRulesTest.java:65` — `api mayOnlyAccessLayers(..., "task", "workflow")` — пачка K больше не упрётся.
- **M-3 (apply-notes H / 9 отклонений)** — **закрыто**: секция «Пачка H» — 9 решений (владелец в сигнатуре createWorkflow, paramsSchema/start_state, deadline_at из state.timeout, SQL-чтение графа, D-58 в common, TaskWakeListener-контракт, без новых конфигов, limit-семантика, идемпотентность зависимостей) + секция применённых фиксов ревью-цикла.
- **N-1 (limit NPE)** — **закрыто**: контракт `TaskRegistry` задокументировал «limit >= 1 (верхняя граница — API-слой, limits.page)»; `getHistory(limit=null) → все записи` — отдельно (п.8 apply-notes).
- **N-2 (fallback initial-state не записан)** — **закрыто**: эвристика удалена вовсе (заменена явным start_state), зафиксировано в apply-notes п.2.

## Попутно проверено (фиксы коллег/судьи)

- **H-4 (resume FOR UPDATE)** — `resume` теперь берёт строку `FOR UPDATE` (строка 230) — гонка resume↔stop/transition закрыта на уровне реестра.
- **H-6 (addDependency)** — `SELECT id FROM task WHERE id IN (?, ?) ORDER BY id FOR UPDATE` — детерминированный порядок блокировок, конкурентное добавление рёбер не породит цикл мимо DFS.
- **LimitedJsonSchemaValidator integer** — строгая интегральность (целые типы + whole Double/Float — соответствует JSON-Schema: 1.0 ≡ integer).
- Нит по косметике (не блокер): в apply-notes H артефакты форматирования («	ask», «
emoveDependency»/«ddDependency» — съеденные бэкслеши/табы) — поправить при следующей правке файла.

## Вердикт re-approval

**APPROVE** — незакрытых: 0. V-1 исправлен алгоритмически правильно (обратный BFS + регресс-тесты), M-1/M-3 решены через явный `start_state` и документирование, границы ArchUnit закрыты до пачки K. Пачка готова к коммиту.
