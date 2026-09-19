# Ревью M2 batch I: TaskEngine + executors + wake + scheduler jobs

> Ревьюер: DeepSeek-V4.1-Flash (субагент).
> Дата: 2026-09-18.
> Объект: `execution/impl/{TaskEngine,TaskEngineImpl,TaskGraphReader,BashStateExecutor,WaitWebhookStateExecutor,WaitTasksStateExecutor,WaitTasksScope,InProcessTaskWakeBus,TaskWakeDispatcher,TaskWakeHandler,TaskSchedulerJob,TaskTimeoutScannerJob}`, `TaskRegistryImpl` (stop), `WorkspaceTools`/`ContainerWorkspaceTools`/`WorkspaceContainerManager` (task-контейнер), `config/TaskProperties`, `application.yml`, тесты `tests/execution/task/*`.
> Контекст: `docs/design/data-model.md §7.2`, `workflow-domain.md §3`, спеки `task-engine`/`workspace-tools`, `tasks.md` I.1–I.5, D-33/D-50/D-55.
> Сборки не запускались; сверка по исходникам.
> Severity: **MEDIUM** — функциональный дефект; **MINOR** — пробел/риск; **NIT** — формулировка.

## Сводка

| Severity | Кол-во |
|---|---|
| MEDIUM | 1 |
| MINOR | 4 |
| NIT | 1 |
| **Итого** | **6** |

---

## (1) data-model §7.2: атомарность CAS+history+kind+status — ✅

`TaskEngineImpl.processTaskTransition` — один `UPDATE task SET current_state, current_state_kind, status_projection, deadline_at, state_attempt(CASE …), task_event_seq+1, updated_at WHERE id=? AND current_state=? AND suspended=false` + `INSERT task_transition_history` в одном `@Transactional`; wake — строго `afterCommit`. Гонки: CAS-промах → `null` (no-op); тесты `doubleTransitionHasSingleWinner`, `suspendedTaskRejectsTransitionByCasGuard`.

## (2) stop CAS без гварды suspended — ✅

`TaskRegistryImpl.cancelByStop` — `SELECT … FOR UPDATE` + `UPDATE … WHERE status_projection IN ('RUNNING','WAITING')` (без `suspended`), история `CANCEL` в той же транзакции; тест `stopWinsAgainstConcurrentTransition` (движковый CAS `suspended=false` проигрывает). Остальные переходы — с гвардой `current_state`/`suspended` (п.1).

## (3) deadline_at при входе в BASH/WAIT_*/AGENT — ✅

`deadlineOf`: явный `state.timeout` → kind-дефолт `harness.task.transition.kind-timeouts.{bash,wait-webhook,wait-tasks,agent}` → null; TERMINAL — всегда null; при `createTask` — из timeout стартового состояния (H). Тест `kindDefaultDeadlineAppliedWhenStateHasNoExplicitTimeout`, `terminalTargetWritesOutcomeProjectionAndClearsDeadline`.

## (4) BashStateExecutor — ✅

`classify`: exit=0→NEXT, exit≠0→ERROR, `timedOut`→TIMEOUT, LOST/ERROR→ERROR, CANCELLED→null (stop уже записал `'$CANCELLED'`). `state_attempt` инкрементируется в CAS при входе в BASH; in-flight-гвардия per-task. Task-контейнер `harness-task-<taskId>` + workspace `workspaceRoot/task-<taskId>` (namespace в `WorkspaceContainerManager`), shell-инвариант идемпотентности в Javadoc `WorkspaceTools.executeBash`. Тесты: unit-classify + реальный контейнер (`success…Next`, `nonZeroExit…Error`, `stateTimeout…Timeout`).

## (5) WaitWebhookStateExecutor: повторный POST → no-op/409 — ✅ (но см. I-1)

Вне `WAIT_WEBHOOK`/терминал → `NOT_WAITING` (HTTP 409); повторная доставка после перехода → `NOT_WAITING`, история не задваивается (тест `redeliveryAfterTransitionIsNoop`). CAS-промах → false.

## (6) WaitTasks: ALL_TERMINAL vs ALL_SUCCESS — ✅

`WaitTasksScope.evaluate`: `ALL_TERMINAL` — все терминальны (`CANCELLED` — терминал) → NEXT; `ALL_SUCCESS` — первый `FAILED|CANCELLED` → ERROR, иначе все терминальны → NEXT; пустой scope → PENDING. Интеграционные тесты: `allSuccessFailsOnCancelledChild`, `allTerminalCountsCancelledChildAsTerminal`, `blockedByScopeClosesOnBlockerTerminal`, `tagChange…TaggedScope`, `explicitScope…`, `emptyScopeDoesNotSatisfyBarrier`, `suspendedBarrierIsNotClosed`.

## (7) WAIT_WEBHOOK не в POLL — ✅

`TaskSchedulerJob` выбирает только `current_state_kind='AGENT' AND status_projection='RUNNING'` без STATE-сессии и `current_state_kind='WAIT_TASKS'`; `WAIT_WEBHOOK` пассивен (только `TaskTimeoutScannerJob`).

## (8) ShedLock-ключи — ✅

`@SchedulerLock(name="task-scheduler", lockAtMostFor="${harness.task.scheduler.ttl}")` (poll-interval) и `name="task-timeout-scanner"` (scan-interval). `TaskProperties` биндит `harness.task.*`; числа — конфиг.

## (9) Заметки dev — ⚠️ (I-2)

Секции «Пачка I» в `apply-notes.md` нет (файл заканчивается пачкой H round 2). Перечень решений/отклонений I отсутствует.

---

## Findings

### I-1 [MEDIUM]. `Map.copyOf(reason)` падает на null-значении reason: вебхук без `?source=` → NPE (500)

- **Где:** `execution/impl/TaskEngineImpl.java:99` — `new Transition(..., reason == null ? Map.of() : Map.copyOf(reason), now)`; `WaitWebhookStateExecutor.java:63,73-76` — `reason.put("source", source)` и `reason.put("payload", payload)`.
- **Цитата:** `Map.copyOf(reason)` (JDK: бросает `NullPointerException`, если map содержит null-ключ или null-значение).
- **Проблема:** `source` — необязательный query-параметр (`openapi.yaml`: `required: false`); `payload` теоретически тоже может быть null. Валидный вебхук без `?source=` даёт `reason` с null-значением → `Map.copyOf` NPE в `processTaskTransition` → переход не выполнится, наружу 500 (после появления L.3). Аналогичный риск — bash-TIMEOUT, если `result.exitCode()` null (`BashStateExecutor.reason` кладёт `exitCode` без null-фильтра). Тесты всегда передают непустой `source` — дыра не покрыта.
- **Предложение:** использовать null-толерантную неизменяемую копию (`Collections.unmodifiableMap(new LinkedHashMap<>(reason))`/`new LinkedHashMap<>`) либо не класть null-значения (опускать `source`/`exitCode` при null); тест «валидный webhook без `source`».

### I-2 [MINOR]. Нет секции «Пачка I» в `apply-notes.md`

- **Где:** `openspec/changes/m2-workflow-engine/apply-notes.md` (заканчивается H round 2).
- **Проблема:** практика M1/D/H — фиксировать решения и отклонения пачки; по I (в т.ч. полная переоценка барьеров, bash-dispatch-гейт, kind-дефолты таймаутов, удаление task-контейнера таймаут-сканом, `state_attempt`-семантика) перечня нет.
- **Предложение:** добавить секцию «Пачка I» с решениями/отклонениями и числом тестов.

### I-3 [MINOR]. Scope `TAGGED(x)`/`EXPLICIT(...)` может включать саму ожидающую задачу → барьер никогда не закроется

- **Где:** `WaitTasksStateExecutor.resolveIds` (`SELECT id FROM task WHERE tags @> …` / explicit ids).
- **Проблема:** если WAIT_TASKS-задача сама несёт тег `x` (или её id попадает в EXPLICIT-список), она входит в scope и, будучи нетерминальной, делает `ALL_TERMINAL`/`ALL_SUCCESS` вечно `PENDING` (самозависимость). Валидатор графа такое не ловит.
- **Предложение:** исключить `id <> task.id` из scope (и/или задокументировать как запрет на уровне контракта графа).

### I-4 [MINOR]. `LIMIT batchSize` без `ORDER BY` в переоценке барьеров и POLL

- **Где:** `TaskWakeDispatcher.reevaluateBarriers` (`… LIMIT ?`) и `TaskSchedulerJob.reevaluateWaitTasks` (`… LIMIT ?`).
- **Проблема:** при числе активных барьеров > `batchSize` выбирается произвольное подмножество (без порядка) — часть барьеров может долго не переоцениваться (задержка закрытия, не строгое голодание).
- **Предложение:** `ORDER BY updated_at`/`created_at` (или keyset), чтобы проход охватывал очереди; либо отсутствие `LIMIT` при MVP-масштабе.

### I-5 [MINOR]. WAIT_WEBHOOK в reason хранит полный payload, а не только summary

- **Где:** `WaitWebhookStateExecutor.java:76` — `reason.put("payload", payload)` вдобавок к `payloadSummary`.
- **Проблема:** контракт inbound-triggers/§7 и threat-model говорят о `payloadSummary` («компактное summary, без полного тела, если оно большое»); дублирование полного тела (до 1 МБ) в append-only `task_transition_history` — рост истории. Формально task-engine-сценарий говорит «payload и source в reason» — конфликт двух спек не разрешён.
- **Предложение:** согласовать спеки и оставить одно (payloadSummary; полное тело — по решению workflow), либо явно зафиксировать девиацию с ограничением размера.

### I-6 [NIT]. NPE-устойчивость конфига и безусловное снятие контейнера

- **Где:** `TaskEngineImpl.kindDefault` (`properties.transition().kindTimeouts()`), `BashStateExecutor.timeoutOf` (`properties.transition().kindTimeouts()`) — NPE, если `harness.task.transition` не задан; `TaskTimeoutScannerJob.timeout` — `containers.removeContainer(TASK_NAMESPACE, taskId)` для любого kind (для не-BASH — no-op).
- **Предложение:** null-safe доступ к `transition()/kindTimeouts()` (дефолт — пустые), снятие контейнера только для BASH_SCRIPT (или оставить с комментарием).

---

## Позитив (проверено)

- Полный набор требований I.1–I.5 реализован; каждое правило покрыто тестами (unit + интеграционные на живом Postgres/контейнере).
- Task-контейнер `harness-task-<taskId>` изолирован от сессионных (namespace `task-`), workspace не пересекаются; helper-образ и сигнальное убийство процесса переиспользованы.
- Числа — только конфиг (`TaskProperties` + `application.yml`); тестовый профиль гасит джобы/раскачку для детерминизма.
- Wake-инфраструктура: EVENT после коммита + POLL-страховка; dispatcher на виртуальных потоках; `InProcessTaskWakeBus` явно отделён Javadoc от SSE (J.4).

## Вердикт

**REJECT — 6 находок (1 MEDIUM: I-1 NPE `Map.copyOf` на null-valued reason — вебхук без `source`; 4 MINOR: I-2 заметки I, I-3 self-inclusion scope, I-4 LIMIT без ORDER BY, I-5 полный payload в reason; 1 NIT: I-6).** Все 9 пунктов чек-листа по существу выполнены; блокер приёмки — I-1 (функциональный NPE на штатном пути).

---

# Re-approval M2 batch I (2026-09-18)

> Проверены: `TaskEngineImpl`, `BashStateExecutor`, `WaitWebhookStateExecutor`, `WaitTasksStateExecutor`, `TaskWakeDispatcher`, `TaskSchedulerJob`, `WebhookProperties`, `openapi.yaml`, тесты `tests/execution/task/*`, `apply-notes.md` (секция I). Сборки не запускались.

## Статусы находок

| # | Sev | Статус | Проверка |
|---|---|---|---|
| I-1 | MEDIUM | **закрыто** | `TaskEngineImpl:143` — `Collections.unmodifiableMap(new LinkedHashMap<>(reason))` (null-толерантно); `WaitWebhookStateExecutor.webhookReason` кладёт `source` только при non-null; bash-reason допускает `exitCode=null`. Регресс-тесты: `WaitWebhook…webhookWithoutSourceIsAcceptedWithoutNpe`, `BashStateExecutorClassifyTest.timedOutWithoutExitCodeKeepsNullExitCodeInReason`, `BashStateExecutorTaskContainerTest.containerKillMidRunYieldsErrorTransitionWithNullExitCode`. Не NPE на bash-TIMEOUT подтверждён |
| I-2 | MINOR | **закрыто** | `apply-notes.md` §«Пачка I» (стр. 202+): решения (event-wake, полнопроходная переоценка, premature-closure, одноразовый task-контейнер, payloadSummary, self-исключение, ORDER BY, uuid[]), фиксы ревью, тесты (351 зелёных) |
| I-3 | MINOR | **закрыто** | `WaitTasksStateExecutor.resolveIds`: BLOCKED_BY `AND blocker_task_id <> ?`; TAGGED `AND id <> ?`; EXPLICIT — фильтр `id.equals(task.id())`; ALL_CHILDREN сам себя не содержит |
| I-4 | MINOR | **закрыто** | `ORDER BY id` перед `LIMIT` в `TaskWakeDispatcher.reevaluateBarriers` и во всех выборках `TaskSchedulerJob` (bootstrap/WAIT_TASKS/scan) |
| I-5 | MINOR | **закрыто** | `WaitWebhookStateExecutor` кладёт только `payloadSummary` ({topKeys, byteSize} → {byteSize, truncated} сверх лимита); полное тело не пишется; лимит — `WebhookProperties.payloadSummary` / `harness.webhook.payload-summary.byte-size-limit` (4096 prod / 64 test) |
| I-6 | NIT | **закрыто** | null-safe `properties.transition()` в `TaskEngineImpl:158-159` и `BashStateExecutor:142-145`; task-контейнер снимается `BashStateExecutor` в `finally` (одноразовый) |
| M-3 (GLM) | nit | **закрыто** | `openapi.yaml` `TransitionDto.description`: bash-reason `{exitCode, output, durationMs, attempt}` (output = stdout+stderr; exitCode может отсутствовать), вебхук — полное тело не хранится (D-29) — description-only, регенерация не требуется, спека согласована ревью пачки I |

## Проверка

- Все 9 пунктов чек-листа I остаются выполненными; изменения фиксов не задели CAS/stop/atomicity/deadline/scope-семантику.
- Регрессы покрыты тестами (null source, null exitCode/kill, self-exclusion, ORDER BY, payloadSummary-лимит).
- Правка `openapi.yaml` — только `description` `TransitionDto` (контрактные поля/схемы не менялись), генерацию не инвалидирует.

**APPROVE — 0 незакрытых.** Пачка I принята (правка-описание спеки согласована в рамках ре-аппрува).