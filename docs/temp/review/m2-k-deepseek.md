# Ревью M2 batch K: REST задач (tasks/comments/dependencies/tree) + suspend/resume/stop

> Ревьюер: DeepSeek-V4.1-Flash (субагент).
> Дата: 2026-09-18.
> Объект: `api/{TasksController,TaskCommandsController,ApiMappers,ApiExceptionHandler,ProblemCodes,SessionsController}`, `task/{TaskRegistry,impl/TaskRegistryImpl}` (suspend/resume/stop/listComments), `execution/impl/TaskWakeDispatcher` (handleStop), `config/WebhookProperties`, `application.yml`, тесты `tests/api/{TasksApiTest,TaskCommandsApiTest}`, `tests/execution/task/TaskWakeDispatcherStopTest`.
> Контекст: data-model §4/§7, api-contracts §4.1/§6, спеки task-engine (создание/suspend/resume/stop/история/комментарии/зависимости), tasks.md K.1–K.3, design D-49/D-54.
> Сборки не запускались; сверка по исходникам.
> Severity: **MEDIUM** — контрактный/поведенческий дефект; **MINOR** — пробел/документация; **NIT** — формулировка.

## Сводка

| Severity | Кол-во |
|---|---|
| MEDIUM | 2 |
| MINOR | 3 |
| NIT | 1 |
| **Итого** | **6** |

---

## Проверка фокусных пунктов

- **data-model §7 атомарность suspend/resume/stop CAS** — ✅ `suspend` (UPDATE + `task_event_seq+1` + кадр `task.status`, один `@Transactional`), `resume` (`SELECT … FOR UPDATE` → проверка терминальности → UPDATE + seq + кадр), `stop` (CAS `'$CANCELLED'` + history + seq в той же транзакции, I.1). События — строго `afterCommit`.
- **stop без гварды suspended** — ✅ `cancelByStop`: `SELECT … FOR UPDATE` + `UPDATE … WHERE status_projection IN ('RUNNING','WAITING')` (без `suspended`), выигрывает у переходов.
- **suspend vs stop** — ✅ suspend — идемпотентный флаг (`{ cascade }`); stop — всегда каскадный (параметра `cascade` в API нет), CAS поддерева + `$CANCELLED`.
- **cascade stop без параметра** — ✅ `TaskCommandsController.stopTask` без тела; `TaskRegistry.stop` каскаден по CTE.
- **task-already-terminal** — ✅ resume нетерминальной → 204, терминальной → 409 `task-already-terminal`; stop нетерминальной → 202, терминальной → 409 (`ApiExceptionHandler` → 409). Тесты `resumeTerminalTaskReturns409TaskAlreadyTerminal`, `stopTerminalTaskReturns409`.
- **unknown blocker → 422 dependency-invalid (не 404)** — ✅ `TasksController.addTaskDependencies` ловит `TaskNotFoundException` от блокера и перебрасывает `DependencyInvalidException` (422, `rule=unknown-task`); 404 — только для блокируемой `{id}`. Тест покрывает оба.
- **отклонение #3 в спеке** — ❌ см. K-2 (наиболее вероятное отклонение — асинхронная отмена Turn'ов на stop; в спеке/design не отражено).
- Остальные пункты (создание с пином, merge-patch, список/курсор, дерево, история, комментарии, wake «blocked-changed») — ✅ с тестами.

---

## Findings

### K-1 [MEDIUM]. `POST /tasks/{id}/dependencies` не атомарен: при нескольких `blockedBy` часть рёбер остаётся при 422

- **Где:** `api/TasksController.addTaskDependencies` — цикл `for (UUID blockerId : request.getBlockedBy()) tasks.addDependency(blockerId, id)`; каждый вызов — своя транзакция (`TaskRegistryImpl` `@Transactional`).
- **Цитата:** api-contracts §4.1 — «`POST /tasks/{id}/dependencies` …; **атомарно**: невалидный/self/цикл → `422`»; tasks K.3 «DFS-валидация … → 422».
- **Проблема:** при `blockedBy=[b, unknown]` первое ребро фиксируется (и публикует wake), затем второй блокер → 422; запрос завершается ошибкой, но граф зависимостей частично изменён. Аналогично для цикла во втором элементе. `Set` не даёт детерминированного порядка — эффект непредсказуем.
- **Предложение:** добавить в контракт реестра пакетную операцию (напр. `addDependencies(UUID blockedTaskId, Collection<UUID> blockerIds)`) в одной транзакции с общей валидацией, и вызывать её из контроллера; тест «часть валидна, часть нет → ни одного ребра, 422».

### K-2 [MEDIUM]. Отклонение «stop-отмена Turn'ов асинхронна (EVENT-wake → `TaskWakeDispatcher.handleStop`)» не отражено в спеке/design

- **Где:** `execution/impl/TaskWakeDispatcher.handleStop` (отмена Turn'ов по wake после коммита), `TaskCommandsController.stopTask` (202 сразу), `design.md` D-49 и proposal.md §Impact всё ещё называют `StopTaskFacade` в `execution.impl` («`TaskRegistry.stop` + каскадная отмена Turn'ов STATE-сессий»); в `task-engine/spec.md` §Stop отмена Turn'ов — часть операции.
- **Цитата:** `TaskWakeDispatcherStopTest` — «Отмена идёт **асинхронно** (диспетчер на виртуальных потоках) — ждём фиксацию флага».
- **Проблема:** фактическая оркестрация `stop` отличается от зафиксированной: ответ 202 возвращается до отмены Turn'ов; `StopTaskFacade` не реализован. Это осознанное решение (не синхронный фасад), но в спеку/design не внесено — контракт-first рассинхрон. Функционально корректно (CAS перехода всё равно отклонится из-за `$CANCELLED`/`suspended`), но поведение (когда именно гаснут Turn'ы) не задокументировано.
- **Предложение:** либо реализовать синхронный `StopTaskFacade` по design D-49, либо обновить `design.md`/дельта-спеку `task-engine` §Stop (отмена Turn'ов — асинхронно через EVENT-wake, 202 до завершения; идемпотентно, POLL-страховка) и зафиксировать как отклонение в apply-notes. Ответ на вопрос ревью: отклонение **не зафиксировано**.

### K-3 [MINOR]. Секция «Пачка K» в `apply-notes.md` отсутствует

- **Где:** `openspec/changes/m2-workflow-engine/apply-notes.md` (последняя — I; J присутствует, но K нет).
- **Проблема:** решения/отклонения пачки K (асинхронная отмена Turn'ов, эмиссия `task.status` на suspend/resume, `owner` подзадачи из JWT в M2, 422 для неизвестного blocker, `webhook.base-url` как обязательный конфиг) не задокументированы — нельзя сверить «отклонение #3» по списку.
- **Предложение:** добавить секцию «Пачка K» с перечнем решений/отклонений и числом тестов.

### K-4 [MINOR]. `TasksController.toDto` — N+1 в списковых выдачах

- **Где:** `TasksController.toDto` (`workflows.revisionSummaries(List.of(id))` + `users.usernames(...)` на каждый элемент); вызывается в `listTasks`/`listComments`.
- **Проблема:** для страницы из N задач — N запросов метаданных ревизии и N запросов username (плюс вложенные). На MVP-масштабе терпимо, но это горячий путь списка; реестр уже умеет пакетные `revisionSummaries(List)` / `usernames(Set)`.
- **Предложение:** собирать id ревизий/пользователей по странице и запрашивать пачкой (стиль M1 `listSessions`).

### K-5 [NIT]. `patchTask`: не-массивный `tags` молча игнорируется

- **Где:** `TasksController.patchTask` — `tags = tagsNode.isNull() ? List.of() : updateTaskRequest.getTags()`.
- **Проблема:** если клиент прислал `tags` строкой/числом (не массив и не null), `getTags()` вернёт null → реестр не изменит теги, ответ 200 без ошибки (вместо 422).
- **Предложение:** при `tagsNode != null && !tagsNode.isArray() && !tagsNode.isNull()` → 422 `validation-failed` (rule=type).

### K-6 [NIT]. `createSubtask` owner — только из JWT

- **Где:** `TasksController.create` — `ownerUserId = userId` (JWT) для обеих веток.
- **Проблема:** спека §Подзадачи допускает наследование owner от породившей сессии (агент-инициатор); в M2 все API-входы USER (D-59), поэтому ветка агента не активна — стоит явно зафиксировать это в apply-notes (иначе выглядит как недостающая реализация).
- **Предложение:** отметить в apply-notes K/D-59, что агентский owner появится с M3-инструментами.

---

## Позитив (проверено)

- Контроллеры полностью реализованы на сгенерированных интерфейсах; тесты `TasksApiTest` (15) и `TaskCommandsApiTest` (11) покрывают happy/error-пути через сгенерированный клиент.
- suspend/resume эмитят `task.status` с собственным `task_event_seq` (SSE), stop — кадры CANCEL; публикация строго после коммита.
- CAS-семантика stop (без гварды `suspended`, выигрывает у переходов) соответствует data-model §7.2; resume — с `SELECT … FOR UPDATE`.
- unknown blocker → 422, unknown blocked/path → 404 — точно по api-contracts.
- `handleStop` отменяет Turn'ы всех STATE-сессий поддерева идемпотентно; wake «blocked-changed» при add/remove зависимости; `harness.webhook.base-url` — обязательный конфиг.

## Вердикт

**REJECT — 6 находок (2 MEDIUM: K-1 неатомарное добавление зависимостей при 422, K-2 отклонение «асинхронная stop-отмена Turn'ов» не зафиксировано в спеке/design; 3 MINOR: K-3 нет apply-notes K, K-4 N+1, K-6 owner подзадачи; 1 NIT: K-5).** Блокеры приёмки — K-1 (нарушение «атомарно» api-contracts §4.1) и K-2 (contract-first рассинхрон по stop-оркестрации).

---

# Re-approval M2 batch K (2026-09-18)

> Проверены: `task/TaskRegistry(+Impl).addDependencies`, `api/TasksController`, `api/MergePatchHttpMessageConverter`, `api/TaskCommandsController`, `design.md`/`proposal.md`/`specs/task-engine/spec.md`, `apply-notes.md` §K, тесты `TasksApiTest`. Сборки не запускались.

## Статусы находок

| # | Sev | Статус | Проверка |
|---|---|---|---|
| K-1 | MEDIUM | **закрыто** | `TaskRegistry.addDependencies(blockedTaskId, Collection<UUID>)` — всё в одной `@Transactional`: `SELECT … WHERE id IN (…blockers+blocked…) ORDER BY id FOR UPDATE` (детерминированный лок), missing blocker → `DependencyInvalidException` (422) до вставок, DFS с локальной копией графа (ребро пачки учитывается); `addDependency` делегирует в батч. Контроллер вызывает батч-метод. Тест `TasksApiTest.dependencyBatchIsAtomicNoPartialCommit`: `[b, unknown]` → 422 `dependency-invalid` и `COUNT(*) FROM task_dependency WHERE blocked_task_id=a == 0` |
| K-2 | MEDIUM | **закрыто** | Отклонение задокументировано: `design.md` D-49 — `StopTaskFacade` заменён на `TaskWakeDispatcher.handleStop`; D-54/proposal §What Changes — «Stop = sync + async» (sync: CAS+history+`task.status`/seq в транзакции, 202 сразу; async: EVENT-wake → dispatcher отменяет Turn'ы STATE-сессий поддерева, идемпотентно, POLL не нужна); дельта `task-engine/spec.md` §Stop переписана на (1)(3)(4)(5) sync + async-фазу и сценарий |
| K-3 | MINOR | **закрыто** | `apply-notes.md` §«Пачка K» (5 отклонений dev + гейты + число тестов) |
| K-4 | MINOR | **закрыто** | `TasksController` — `enricherOf(tasks)` батчит usernames (owner+author) через `AppUserDirectory.usernames(Set)` и метаданные ревизий через `WorkflowRegistry.revisionSummaries(List)` одним запросом; N+1 в списке/чтении устранён |
| K-5 | NIT | **закрыто** | `MergePatchHttpMessageConverter.shapeViolationOf` — не-массивный `tags` → `422 validation-failed` `rule=array-required` (`/tags`); тест `patchWithNonArrayTagsReturns422ArrayRequired` |
| K-6 | NIT | **закрыто** | apply-notes §K п.1: owner подзадачи = JWT (все входы M2 USER, D-59), агентский owner — M3 |
| GLM nit | — | **закрыто** | `TaskCommandsController.suspendTask`: `SuspendTaskRequest == null`/`cascade == null` → `422 validation-failed` `rule=required` (`/cascade`) |

## Проверка

- K-1: батч-операция реально атомарна (мутации — только после полной валидации всех blockers/циклов); гонки сериализуются локом задач; частичного коммита нет.
- K-2: sync/async-разделение stop консистентно во всех трёх артефактах (design/proposal/spec) и совпадает с реализацией (`TaskWakeDispatcher.handleStop` + `TurnManager.requestStop`); `StopTaskFacade` упоминания удалены.
- Остальные фокусные пункты (атомарность suspend/resume/stop, stop без гварды suspended, always-cascade, resume/stop терминальной → 409, unknown blocker → 422) — без регрессий.

**APPROVE — 0 незакрытых.** Пачка K принята.