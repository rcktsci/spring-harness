# M2-workflow-engine: судья (оркестратор)

## Консенсус-блокеры (принять всё)

| # | Находка | Источники | Вердикт |
|---|---|---|---|
| J-1 | **Нет MODIFIED-дельт для `session-api` и `agent-turn`** (заявлены в proposal как Modified, но файлов specs нет) | GLM M6, DS C-1 | **Принять**: добавить `specs/session-api/spec.md` (MODIFIED Requirements: SessionDto.kind/taskId/stateCode/runtimeStatus, SSE-задач, POST /sessions только FREE, новые коды ошибок) и `specs/agent-turn/spec.md` (MODIFIED Requirements: инструмент transition, гейт metaTools, write-ahead для transition). Без `## Purpose` (только ADDED-дополнения к MODIFIED-блокам) |
| J-2 | **DS объявил также `session-store` и `workspace-tools` как Modified** | DS C-1 | **Принять**: добавить дельты — `session-store`: STATE-создание/резюм по (task_id, state_code); `workspace-tools`: новый task-контейнер `harness-task-<taskId>` |
| J-3 | **Дублирование M1-схемы**: поля `task_id/state_code/agent_revision_id` + PARTIAL UNIQUE `(task_id, state_code) WHERE kind='STATE'` уже в миграции 005 | DS C-2 | **Принять**: убрать повторное создание из tasks 2.1/4.1 и Migration Plan; переформулировать как «переиспользовать существующие» |
| J-4 | **`wrong-transition` код отсутствует в §6 каталога** | GLM M1, DS H-1 | **Принять**: добавить `wrong-transition \| 409 \| transition не по разрешённому ребру / пустой reason` в api-contracts §6 и proposal (новый код в M2 — единственный) |
| J-5 | **D-52 metaTools-гейт vs D-41 (instructionSource/metaTools-гейт явно вырезаны)** | GLM M2, DS H-3 | **Принять**: зарегистрировать в decisions.md **D-59**: «metaTools-гейт частично supersede D-41 в M2: только точка входа = USER-сообщение в STATE-сессию; вне M2 — по D-41». Добавить задачу в пачку 4 на plumbing `instructionSource` (Caller.instructionSource(), sessionOwner()) |
| J-6 | **JSON-Schema валидация**: hibernate.validator не покрывает произвольный JSON-Schema | GLM M3 | **Принять (вариант б)**: дескоуп — `paramsSchema`/`payloadSchema` в M2 поддерживают **ограниченный профиль** (required, type, enum, items, properties первого уровня) с ручным обходом. Зафиксировать профиль в спеке workflow-engine/task-engine/inbound-triggers и в design (D-58 — переформулировать). Полная JSON-Schema — точка эволюции |
| J-7 | **WAIT_WEBHOOK/WAIT_TASKS без ERROR-ребра — клинч движка** | GLM M4 | **Принять**: правило 5 валидации графа дополнить: «WAIT_WEBHOOK и WAIT_TASKS обязаны иметь **и** ERROR, **и** TIMEOUT переходы». Сценарий-тест негативный |
| J-8 | **transition тайминг размыт** (spec vs tasks) | GLM M5 | **Принять**: фиксируем «применение транзакционно в момент исполнения tool-call». tasks 4.2 — поправить (write-ahead не делает отложенную запись transition) |
| J-9 | **Границы модулей / stop-оркестрация не определены** | GLM M7 | **Принять**: (а) BashStateExecutor — в `execution.impl` поверх контракта `WorkspaceTools` (направление execution→execution OK); TaskEngine — в `execution.impl` поверх контракта `TaskRegistry` (execution → task); (б) `WebhookHandlers` — в `api/impl/` (HTTP-контроллеры api; webhook-handler делегирует в `task` через `TaskRegistry`); контракт `InboundTriggers` заменяется на `TaskRegistry` (для задач) + `TriggerRegistry` (для триггеров); (в) stop-фасад — `execution.impl.StopTaskFacade` (TaskRegistry.stop + отмена Turn'ов STATE-сессий каскадом); (г) удалить placeholder «D-M2-…» |
| J-10 | **inbound-triggers webhook развилка** | GLM m7, DS H-2 | **Принять**: оставить единственный вариант — невалидный payload → `202` + переход ERROR с reason. Удалить вопросительные формулировки и placeholder |
| J-11 | **DS M-4: triggers/webhooks — M2 или M3?** (roadmap расходится) | DS M-4 | **Open Question для владельца**: перенести triggers/webhooks в M2 (требует правки roadmap) или убрать из M2 (отложить в M3). Судья зафиксирует решение владельца в apply-фазе |

## Minor (принять консолидированно)

| # | Что | Вердикт |
|---|---|---|
| J-12 | 6→7 таблиц, 010..015→010..016; счёт «10 новых кодов»/«12 операций» — переформулировать | Принять |
| J-13 | Индексы: добавить partial `(current_state_kind='AGENT' AND status_projection='RUNNING')` (bootstrap-скан) и `task_dependency(blocked_task_id)` | Принять |
| J-14 | Сигнатура transition — унифицировать на `(taskId, toState, kind, reason)` (taskId опц., из STATE-сессии берётся неявно) | Принять (в спеке taskId обязателен; в адаптере резолвится из сессии) |
| J-15 | `create_subtask` инструмент в M2 — переписать сценарий через REST POST /tasks/{id}/subtasks | Принять |
| J-16 | POLL-выборка: исключить WAIT_WEBHOOK (только таймаут-скан) | Принять |
| J-17 | fan-out валидация: ≤1 ребра каждого kind | Принять |
| J-18 | Конфиг-дыры: добавить в список proposal все конфиги (max-per-turn, webhook.base-url, scheduler.batch-size); ShedLock ключ — зафиксировать `task-scheduler` | Принять |
| J-19 | `/sessions/{id}/tree` — добавить задачу в пачку 5 (или покрыть регенерацией 1.2) | Принять (покрыть 1.2 — регенерация заставит обновить контроллер) |
| J-20 | resume → wake/переоценка (снять suspended-гвард, либо явно инициировать wake) | Принять (resume: снять suspended + emit `task-wake` событие → POLL подхватит) |
| J-21 | R5 race — переформулировать (долгая транзакция в HTTP-потоке / нет дедупликации триггера) | Принять |
| J-22 | cascade в stop — зафиксировать «stop всегда каскадный» (убрать параметр cascade из API stop) | Принять |
| J-23 | BREAKING для `agent {key,rev}` — снять (уже в M1) | Принять |
| J-24 | STATE-сессия атомарность insert + seed-SYSTEM + last_seq (execution-model §7.2) | Принять (добавить в спеку task-engine §STATE-сессии и в task 4.1) |
| J-25 | SSE задач — курсор для не-transition событий (снапшот + добор history) | Принять (определить: `since=` монотонный `task_event_seq`; новые события нумеруются; снапшот = последний `task.status`) |
| J-26 | История cursor `id` → `(created_at,id)` | Принять |
| J-27 | Пачки G…M в design ↔ 1…8 в tasks — переименовать tasks пачки в G…M | Принять (tasks.md: G/H/I/J/K/L/M) |
| J-28 | D-47…D-58 — зафиксировать в decisions.md в apply-фазе | Принять (добавить task в 7.3 — перенос D-47…D-58 в decisions.md) |
| J-29 | H1-заголовки для новых спекул + main спекул | Принять |
| J-30 | Грамматика POLL сценария, имя ключа ShedLock, R2 miscitation | Принять |
| J-31 | InProcessTaskWakeBus vs TaskWakeBroadcaster — Javadoc различие | Принять (добавить в tasks заметку) |

## Отклонено

| # | Находка | Обоснование |
|---|---|---|
| R-1 | Mercury «D-29 idempotency_key — конфликт» | D-41 явно supersede D-29 (вырезано «идемпотентность-хранилище»). Mercury misread: proposal ссылается на D-29/D-41 вместе — корректно |
| R-2 | DS M-2 «TriggerRegistry подменяет InboundTriggers» | J-9 решает: контракт `InboundTriggers` остаётся в architecture.md (для будущих внешних интеграций); `TriggerRegistry` — внутренняя деталь пакета `task` (CRUD триггеров) |
| R-3 | GLM n5 «BREAKING для agent.key — клиенты M1 должны мигрировать» | J-23 снимает пометку — M1 уже имеет `{key, rev}` |

## Open Questions (владельцу)

1. **Triggers/webhooks — M2 или M3?** (DS M-4, J-11). Roadmap M3 заявляет «триггеры + вебхук-эндпоинты», M2 делает. Решение: перенести в M2 (правка roadmap.md) или убрать из M2. **Решение владельца перед apply.**

## План re-approval

После применения dev фиксов — re-approve 3 ревьюера (по их спискам).
