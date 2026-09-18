# Ревью плановых артефактов ченджа `m2-workflow-engine`

> Ревьюер: DeepSeek-V4.1-Flash (субагент).
> Дата: 2026-09-18.
> Объект: `openspec/changes/m2-workflow-engine/{proposal.md, design.md, tasks.md, specs/*/spec.md}`.
> Контекст: `docs/design/{roadmap,workflow-domain,architecture,data-model,decisions,api-contracts,execution-model,agent-tools}.md`, `openspec/specs/*/spec.md` (M1), факт M1-схемы `src/main/resources/db/changelog/migrations/2026/`.
> Код в apply-фазе не ревьюился; сверка со спекой M1/миграциями — для проверки исполнимости плана.
> Severity: **CRITICAL** — блокирует заморозку; **HIGH** — ломает контракт/противоречие без решения; **MEDIUM** — пробел/несогласованность; **MINOR** — точность формулировок.
>
> `openspec validate m2-workflow-engine --strict` — **valid** (наличие/корректность delta-операций валидатор не проверяет, поэтому пропуск MODIFIED-дельт не выявляется).

## Сводка

| Severity | Кол-во |
|---|---|
| CRITICAL | 2 |
| HIGH | 3 |
| MEDIUM | 10 |
| MINOR | 6 |
| **Итого** | **21** |

---

## CRITICAL

### C-1. Отсутствуют delta-спеки для объявленных Modified Capabilities (`session-api`, `agent-turn`), и не объявлены ещё две модифицируемые capability (`session-store`, `workspace-tools`)

- **Пункт:** proposal.md §Modified Capabilities (стр. 29–32), tasks.md §8.1 (стр. 48), `openspec/changes/m2-workflow-engine/specs/`.
- **Цитата:** proposal: «Modified Capabilities — `session-api`: расширение `SessionDto`…; `agent-turn`: добавление мета-инструмента `transition`…»; tasks 8.1: «спеки синхронизируются в `openspec/specs/{workflow-engine,task-engine,inbound-triggers,session-api,agent-turn}/spec.md`».
- **Проблема:** в каталоге ченджа существуют только `specs/{workflow-engine,task-engine,inbound-triggers}/spec.md`. Дельт `session-api` и `agent-turn` нет. Модификации существующих capability обязаны быть оформлены как дельты `## MODIFIED Requirements` **без** `## Purpose` (в отличие от `ADDED`); вместо этого заявленные изменения (`SessionDto.kind/taskId/stateCode`, `runtimeStatus`, новый SSE `/tasks/{id}/events`, meta-tool `transition`, write-ahead для AGENT-state) в спеках отсутствуют вообще — они живут только в proposal/tasks и де-факто не являются спецификацией. Дополнительно: изменение `session` (STATE-создание/резюм) — это поведение capability `session-store` (спека M1 `session-store/spec.md`), а новый метод/параметр task-контейнера `harness-task-<taskId>` — capability `workspace-tools`; обе фактически модифицируются, но не заявлены.
- **Предложение:** добавить `specs/session-api/spec.md` и `specs/agent-turn/spec.md` с `## MODIFIED Requirements` (без Purpose); объявить `session-store` и `workspace-tools` как Modified и оформить их дельты (STATE-сессия + резюм; task-контейнер в `WorkspaceTools`). До этого чендж не отражает собственных изменений.

### C-2. План миграций ре-добавляет поля/индекс `session`, которые уже существуют в M1 (миграция 005)

- **Пункт:** tasks.md 2.1 (стр. 8), tasks.md 4.1 (стр. 22), design.md Migration Plan (стр. 103).
- **Цитата:** tasks 2.1: «PARTIAL UNIQUE `(task_id, state_code) WHERE kind='STATE'` в `session` — отдельный changelog на изменение `session`»; tasks 4.1: «добавить поля в `SessionEntity` (`task_id`, `state_code`, `agent_revision_id`); PARTIAL UNIQUE INDEX … (миграция в пачке 2.1)»; design: «PARTIAL UNIQUE на `session(task_id, state_code)` — новая колонка + индекс».
- **Проблема:** в M1-миграции `005_create_table_session.xml` уже есть колонки `task_id`/`state_code`/`agent_revision_id` (стр. 46–60), `ck_session__kind CHECK (kind IN ('FREE','STATE'))` (стр. 144–159) и `uidx_session__task_id_state_code … WHERE kind='STATE'` (стр. 110–125); `SessionEntity.java:34–40` уже содержит эти поля, `SessionKind` уже `{FREE, STATE}`. Повторное добавление колонок/индекса либо упадёт, либо (через preconditions) создаст ложное впечатление работы; премиса миграционного плана неверна.
- **Предложение:** убрать из tasks 2.1/4.1 и Migration Plan создание этих колонок/индекса; заменить на «переиспользовать существующие `session.task_id/state_code/agent_revision_id` и `uidx_session__task_id_state_code` из M1 (005)»; при необходимости — только подключить/задействовать их в `StateSessionService`.

---

## HIGH

### H-1. Код ошибки `wrong-transition` используется спекой, но отсутствует в каталоге §6 и в списке расширения каталога

- **Пункт:** specs/task-engine/spec.md §Мета-инструмент `transition` (стр. 171, 181, 186); proposal.md §Modified Capabilities (стр. 31).
- **Цитата:** spec: «Конфликт — `409 wrong-transition`… `409 wrong-transition` с error `rule=reason-required`… `rule=not-an-outgoing-edge`».
- **Проблема:** api-contracts §6 (`docs/design/api-contracts.md:119–130`) такого кода не содержит; proposal перечисляет ровно 10 расширяющих кодов и `wrong-transition` среди них нет. Инвариант api-contracts §0.2: «любой `code` вне каталога — дефект реализации». Спека M2 вводит некаталогизированный код.
- **Предложение:** добавить `wrong-transition | 409 | transition не по разрешённому ребру / пустой reason` в api-contracts §6 и в перечень расширяемого каталога proposal; либо переиспользовать каталогизированный код (напр. `validation-failed` для reason и отдельный код для ребра).

### H-2. inbound-triggers §Webhook задачи оставлен с неразрешённой развилкой поведения (ошибка vs. ERROR-переход) и placeholder'ом `D-M2-…`

- **Пункт:** specs/inbound-triggers/spec.md §Webhook задачи (стр. 9).
- **Цитата:** «…не прошёл → `409 task-not-waiting-webhook` (или явная семантика в reason? — уточнение D-M2-…: невалидный payload → переход ERROR с reason `{ validationErrors }`, идемпотентно, без 409). Уточнённое поведение: невалидный payload → ERROR…».
- **Проблема:** спека замораживается, но содержит два взаимоисключающих предписания и незаполненную ссылку на решение. Это же место противоречит task-engine §WAIT_WEBHOOK, где невалидный payload однозначно ведёт к ERROR. Реализация по такой спеке недетерминирована, тест «negative path» неоднозначен.
- **Предложение:** оставить единственное поведение: невалидный payload → `202` + переход ERROR с `reason_jsonb={validationErrors}`, `409` — только вне `WAIT_WEBHOOK`/после уже совершённого перехода; удалить вопросительную формулировку и `D-M2-…`.

### H-3. D-52 (metaTools-гейт по `instructionSource=USER`) противоречит D-41 и текущему `agent-tools.md`; нет ADR-фиксации

- **Пункт:** design.md D-52 (стр. 54–57); proposal.md §11/§41; `docs/design/decisions.md` D-41 (стр. 47); `docs/design/agent-tools.md:31`.
- **Цитата:** design D-52: «Гейт metaTools: `instructionSource = USER` (D-38, в M2)… разрешён только при `instructionSource = USER`»; decisions D-41: «Выброшено: … делегирование (D-32/D-38-формула, **instructionSource, metaTools-гейт**)…»; agent-tools.md: «Права людей не моделируются (D-41) — **гейтов нет**».
- **Проблема:** D-52 опирается на D-38, который более поздним D-41 явно де-скоуплен вместе с `instructionSource` и metaTools-гейтом; текущий agent-tools.md прямо утверждает отсутствие гейтов. Ни D-52, ни proposal не фиксируют, что решение отменяет соответствующий пункт D-41, и ни один task не обновляет `decisions.md`/`agent-tools.md`. Нарушение «design-решения не противоречат D-01…D-46» и правила AGENTS «решение — строкой в decisions.md».
- **Предложение:** зарегистрировать в `decisions.md` новое решение (например D-59), явно superseding пункт D-41 про metaTools-гейт/`instructionSource`, с обоснованием; обновить `agent-tools.md` (снять «гейтов нет»); добавить task на синхронизацию доков. Либо отказаться от гейта и следовать D-41.

---

## MEDIUM

### M-1. Внутренние расхождения счётчиков и диапазона миграций proposal ↔ tasks

- **Пункт:** proposal.md строки 7 (перечень сущностей), 38 (`БД: 6 таблиц … миграции 010..015`); tasks.md 2.1 (стр. 8, `010..016`).
- **Цитата:** proposal перечисляет 7 сущностей (`workflow, workflow_revision, task, task_dependency, task_comment, task_transition_history, trigger`), но пишет «6 таблиц, … миграции `010..015`»; tasks создаёт `010_create_table_workflow.xml` … `016_create_table_trigger.xml`.
- **Предложение:** привести proposal к 7 таблицам и диапазону `010..016` (+ отдельный session-changelog, если он реально нужен — см. C-2).

### M-2. Контракты модулей расходятся с architecture.md

- **Пункт:** design.md D-48 (стр. 34–37); tasks.md 3.1, 6.1; architecture.md:15/39; glossary.md:92.
- **Цитата:** D-48: «Контракты — `WorkflowRegistry` (workflow) и `TaskRegistry` (task)»; tasks 3.1: «Реализовать `TaskEngine` (Java-интерфейс…)»; tasks 6.1: «`TriggerRegistry` (Java-интерфейс…)»; architecture: контракт `integration` — `InboundTriggers`.
- **Проблема:** `TaskEngine` и `TriggerRegistry` — новые публичные Java-интерфейсы, но не перечислены в контрактах D-48; `TriggerRegistry` подменяет заявленный architecture.md контракт `InboundTriggers`. Architecture/glossary не обновляются.
- **Предложение:** либо назвать новый integration-контракт `InboundTriggers` и включить TriggerRegistry как внутреннюю деталь, либо явно ввести `TriggerRegistry`/`TaskEngine` в таблицу контрактов architecture.md §3 и D-48.

### M-3. Размытая ответственность за запись `task_transition_history`: `AgentTurnEngine` vs `TaskEngine`

- **Пункт:** proposal.md §Modified Capabilities `agent-turn` (стр. 32); tasks.md 3.1 (стр. 14), 4.2 (стр. 23); design.md D-49; execution-model §7.2–7.3.
- **Цитата:** proposal: «Расширение write-ahead в `AgentTurnEngine` для системных состояний BASH_SCRIPT/WAIT_* (запись `task_transition_history` транзакционно…)»; tasks 3.1: «`TaskEngine.processTaskTransition(...)` … транзакция: INSERT в `task_transition_history` + обновление `task`»; execution-model §7.3: bash-переходы пишет движок.
- **Проблема:** системные (BASH/WAIT_*) переходы по design/execution-model — зона `TaskEngine`, а не `AgentTurnEngine`; `AgentTurnEngine` пишет историю только для AGENT-state `transition`-tool-call. Формулировка proposal вводит две точки записи истории и не определяет, кто владелец транзакции CAS+history.
- **Предложение:** зафиксировать единую точку `TaskEngine.processTaskTransition` для всех видов переходов; `AgentTurnEngine` лишь вызывает её при `transition`-tool-call; убрать «системные состояния» из дельты `agent-turn`.

### M-4. Объём M2 расходится с roadmap (триггеры/вебхуки заявлены в M3)

- **Пункт:** roadmap.md (M2 стр. 14 — без триггеров/вебхуков; M3 стр. 19 — «триггеры + вебхук-эндпоинты»); proposal.md Capabilities/`inbound-triggers`.
- **Цитата:** roadmap M3: «…оркестратор-агент …, **триггеры + вебхук-эндпоинты**».
- **Проблема:** M2 включает capability `inbound-triggers` (CRUD триггеров + оба вебхук-эндпоинта), перенося часть M3 вперёд, но roadmap/ADR не обновляются и в proposal это не помечено как пересмотр объёма.
- **Предложение:** обновить roadmap (перенести триггеры/вебхуки в M2) либо зафиксировать перенос отдельным решением в decisions.md; отразить в proposal.

### M-5. Bootstrap STATE-сессии расходится с execution-model §7.2 (атомарный seed-SYSTEM и `last_seq`)

- **Пункт:** specs/task-engine/spec.md §STATE-сессии (стр. 155–157); tasks.md 4.1/4.3; execution-model §7.2.
- **Цитата:** execution-model: «найти/создать сессию пары (задача, code) — **создание атомарно** (в одной транзакции: insert сессии + seed-`SYSTEM`-сообщение + `last_seq`; состояния «сессия есть, seed не записан» не существует)»; spec M2: только «SHALL быть создана (или найдена существующая) движком…».
- **Проблема:** M2-спека/таски не описывают seed-сообщение и атомарность создания STATE-сессии; `findOrCreate` + отдельный wake оставляют окно «сессия есть, контекст не засеян».
- **Предложение:** добавить в требование STATE-сессий/`StateSessionService.findOrCreate` атомарность insert сессии + seed-SYSTEM + `last_seq` и явно указать контракт seed-контекста.

### M-6. SSE задачного потока не имеет курсора для `task.status`/`subtask.terminal`/`task.comment`; сам поток не покрыт спекой

- **Пункт:** tasks.md 4.4 (стр. 25); proposal.md (стр. 16); api-contracts §3.2 (стр. 52); session-api delta (отсутствует, см. C-1).
- **Цитата:** api-contracts §3.2: «`task.transition` (id = id записи истории, он же курсор), `task.status`, `subtask.terminal`, `task.comment`, `ping`; снапшот при коннекте».
- **Проблема:** курсор определён только для `task.transition`; для остальных событий нет ни `since`, ни позиции — реконнект между снапшотами теряет `task.status`/`subtask.terminal`/`task.comment`. Кроме того, требование на `GET /api/v1/tasks/{id}/events` отсутствует и в task-engine, и в (отсутствующей) session-api дельте, т.е. контракт потока не заморожен.
- **Предложение:** определить механику догрузки/курсор для не-transition событий (снапшот + добор `history`, либо отдельный монотонный курсор) и вынести требование task-events в соответствующую дельту.

### M-7. Индексный план не покрывает часть индексов data-model §4

- **Пункт:** tasks.md 2.1 (стр. 8); data-model.md §4 (стр. 101, 108).
- **Цитата:** data-model: `PARTIAL INDEX WHERE current_state_kind = 'AGENT' AND status_projection = 'RUNNING' — bootstrap-скан AGENT-без-сессии`; `task_dependency INDEX (blocked_task_id) — обратный поиск «кто ждёт»`; tasks 2.1 перечисляет только `(parent_task_id, status_projection)`, GIN `tags`, partial WAIT_*, partial deadline.
- **Проблема:** bootstrap-скану AGENT-без-сессии (используется в spec §Задачный POLL-страховка) не хватает заявленного partial-индекса; обратный поиск по `blocked_by` (WAIT_TASKS scope `BLOCKED_BY`) — индекса `(blocked_task_id)`.
- **Предложение:** добавить в 2.1 partial `(current_state_kind) WHERE current_state_kind='AGENT' AND status_projection='RUNNING'` и `INDEX (blocked_task_id)` на `task_dependency`.

### M-8. Курсор истории по `id` не согласован с индексом `(task_id, created_at)` и порядком `created_at`

- **Пункт:** specs/task-engine/spec.md §История переходов (стр. 83, 88); tasks.md 5.1 (стр. 29); data-model §4 (стр. 130).
- **Цитата:** spec: «`?since=&limit=` … в порядке `created_at` asc»; tasks 5.1: «курсор по `id` записи `task_transition_history`»; data-model: `INDEX (task_id, created_at)`.
- **Проблема:** `TransitionDto.id` — UUID, `since` по `id` при индексе `(task_id, created_at)` даёт неверный/неэффективный план и неопределённую границу пагинации; тот же курсор предлагается для SSE `since=`.
- **Предложение:** либо ввести `INDEX (task_id, id)` и зафиксировать `since=id`, либо курсор `(created_at, id)`; согласовать спеку, tasks и data-model.

### M-9. batch-метки в design не существуют в tasks

- **Пункт:** design.md R1 (стр. 91) и Open Questions (стр. 111–113); tasks.md (разделы 1–8).
- **Цитата:** design: «пачки G/H/I/J/K/L/M с отдельными ревью-циклами»; «Решить в пачке L», «Решить в пачке J».
- **Проблема:** tasks.md использует числовые разделы и не содержит меток G…M — трассировка решений/рисков на пачки не работает.
- **Предложение:** либо переименовать пачки tasks в G…M, либо в design ссылаться на номера разделов tasks.md.

### M-10. D-47…D-58 не заведены в `docs/design/decisions.md`, и нет task'а на их фиксацию

- **Пункт:** design.md §Decisions; `docs/design/decisions.md` (журнал заканчивается D-45/D-46); AGENTS.md («Любое сущностное дизайн-решение — строкой в `docs/design/decisions.md`»); tasks.md 7.3 (стр. 44).
- **Цитата:** proposal §41: «Новые ADR примутся в `decisions.md` при необходимости»; tasks 7.3 обновляет только `apply-notes.md` и `AGENTS.md`.
- **Проблема:** 12 решений (D-47…D-58) не имеют записей в журнале решений, и план не содержит шага их фиксации. Формальное нарушение процесса владельца.
- **Предложение:** добавить задачу (или включить в 7.3) перенос D-47…D-58 в `decisions.md` с форматом «решение → альтернативы → почему».

---

## MINOR

### L-1. Сигнатура инструмента `transition` не согласована между артефактами

- **Пункт:** agent-tools.md:24; proposal.md §11; specs/task-engine/spec.md §Мета-инструмент (стр. 171).
- **Цитата:** agent-tools: `transition(target_state_code, reason)`; proposal: `transition(taskId?, from?, to, kind, reason)`; spec: `transition(taskId, toState, kind, reason)`.
- **Предложение:** выбрать одну сигнатуру и синхронизировать все три документа (в т.ч. обязательность `kind`).

### L-2. «BREAKING» для `agent {key, rev}` противоречит спеке M1

- **Пункт:** proposal.md §19 (стр. 19); archived M1 `specs/session-api/spec.md:27` и main `openspec/specs/session-api/spec.md:26`.
- **Цитата:** proposal: «клиенты M1-эпохи, читающие «всё через `agent.key`», должны мигрировать на `{key,rev}`»; спека M1: `SessionDto в M1: … agent {key, rev} …`.
- **Предложение:** убрать/переформулировать «BREAKING»: `agent.rev` уже часть M1-контракта.

### L-3. Конфиг `harness.task.transition.max-per-turn` есть в design, но отсутствует в списке конфигов proposal

- **Пункт:** design.md D-52 (стр. 55); proposal.md §Конфиг (стр. 39).
- **Предложение:** добавить параметр (или убрать из design), соблюдя «все числа — конфиг».

### L-4. Имя ShedLock-лока `task-scheduler` расходится (design — `task-scheduler-lock`)

- **Пункт:** design.md D-49 (стр. 40); specs/task-engine/spec.md §Задачный POLL-страховка (стр. 199); tasks.md 3.5 (стр. 18).
- **Предложение:** зафиксировать одно имя ключа.

### L-5. Новые spec-файлы без H1-заголовка

- **Пункт:** specs/workflow-engine/spec.md:1, specs/task-engine/spec.md:1, specs/inbound-triggers/spec.md:1.
- **Цитата:** файлы начинаются с `## Purpose`; M1-конвенция — `# Spec Delta: <capability>` (архив) / `# <capability> Specification` (main).
- **Предложение:** добавить H1 для консистентности.

### L-6. Неточность: «12 новых операций»

- **Пункт:** proposal.md §API (стр. 37).
- **Цитата:** «12 новых операций + новый SSE-поток».
- **Проблема:** по §4 api-contracts M2 добавляет ~24 эндпоинта (14 task + 5 workflow + 3 trigger + 2 webhook).
- **Предложение:** исправить число/формулировку.

---

## Ответ по чек-листу промпта

| # | Пункт | Статус |
|---|---|---|
| 1 | Внутренняя непротиворечивость (proposal↔specs↔design↔tasks) | ❌ C-1, C-2, M-1, M-2, M-3, M-5, M-9, L-1, L-3, L-4, L-6 |
| 2 | Покрытие api-contracts §4 + workflow-domain §1–§8 + data-model §3–§5 | ⚠️ H-1, M-4/§4.3, M-6, M-7, M-8; §1–§8 в основном покрыты |
| 3 | D-47…D-58 не противоречат D-01…D-46 | ❌ H-3 (D-52 vs D-41); M-10 (нет ADR-записи) |
| 4 | tasks разбиты по пачкам, каждая с верификацией | ⚠️ верификация есть у всех; M-9 (метки), пачка 2.1 перегружена (7 таблиц + session) |
| 5 | Риски/митигации/открытые вопросы адекватны | ✅ в целом; R-нумерация и batch-ссылки — M-9 |
| 6 | Capability paths kebab-case | ✅ `workflow-engine`, `task-engine`, `inbound-triggers` корректны |
| 7 | Delta-операции ADDED/MODIFIED | ❌ C-1 (MODIFIED-дельты отсутствуют); ADDED у трёх новых — корректны, Purpose у новых допустим |

## Вердикт

**REJECT** — 21 находка (2 CRITICAL, 3 HIGH, 10 MEDIUM, 6 MINOR). Блокеры заморозки: C-1 (нет MODIFIED-дельт), C-2 (дублирование M1-схемы), H-1 (`wrong-transition` вне каталога), H-2 (неразрешённая развилка вебхука), H-3 (D-52 vs D-41).

---

# Re-approval (2026-09-18, после фиксов J-1…J-31)

> Проверка по артефактам dev-правки. `openspec validate m2-workflow-engine --strict` — **valid**. Код/сборки не запускались.
> Дизайн-доки dev синхронизировал: `api-contracts.md` (+`wrong-transition`), `decisions.md` (+D-59), `roadmap.md` (триггеры → M2). `data-model.md`, `architecture.md`, `agent-tools.md`, `execution-model.md` — **не менялись**.

## Статусы моих находок

| # | Статус | Проверка |
|---|---|---|
| C-1 | ✅ закрыто | `specs/{session-api,agent-turn,session-store,workspace-tools}/spec.md` добавлены; session-api/agent-turn — `## MODIFIED`, session-store/workspace-tools — `## ADDED`; без `## Purpose`. Корректно |
| C-2 | ✅ закрыто | tasks H.1/J.1, design Migration Plan, proposal §Миграции — «переиспользовать существующее из 005, не создавать» |
| H-1 | ✅ закрыто | api-contracts §6 `wrong-transition`; session-api delta §Единый формат ошибок; proposal §31/§37 |
| H-2 | ✅ закрыто | inbound-triggers §Webhook задачи — единое поведение (невалидный payload → `202`+ERROR), сценарий добавлен |
| H-3 | ✅ закрыто | D-59 записан в decisions.md; design D-52/proposal §32 ссылаются на D-52/D-59 |
| M-1 | ✅ закрыто | proposal: 7 таблиц, `010..016`, «~24 операций» |
| M-2 | 🟡 частично | J-9/R-2 развели `InboundTriggers` (остаётся) и `TriggerRegistry` (деталь task); `architecture.md §3` не обновлён под `TaskEngine`/`TriggerRegistry` (design D-49 фиксирует) |
| M-3 | ✅ закрыто | transition-запись = транзакционно в момент tool-call; owner — `TaskEngine` (I.1), `AgentTurnEngine` — вызов (J.2, agent-turn delta) |
| M-4 | ✅ закрыто | решение владельца, roadmap M2/M3 синхронизирован, proposal §19 |
| M-5 | ✅ закрыто | session-store delta + task-engine §STATE-сессии: атомарный insert + seed-SYSTEM + `last_seq`; task J.1 |
| M-6 | 🔴 не закрыто | SSE-курсор `task_event_seq` введён (session-api ADDED, J.4), но **нет носителя**: в data-model нет колонки/таблицы событий; `task.status`/`subtask.terminal`/`task.comment` нигде не хранятся с seq — «добор пропущенного по курсору» после рестарта нереализуем |
| M-7 | ✅ закрыто | tasks H.1: partial AGENT-bootstrap + `task_dependency(blocked_task_id)`; proposal §8; task-engine §POLL |
| M-8 | 🟡 частично | spec/tasks: курсор `(created_at,id)`, индекс `(task_id, created_at, id)`; `data-model.md §4` всё ещё `INDEX (task_id, created_at)` — дизайн-док не синхронизирован |
| M-9 | ✅ закрыто | tasks переименованы в пачки D/H/I/J/K/L/M/N; design-ссылки («пачка L/J») валидны |
| M-10 | ✅ закрыто (deferred) | task M.3: перенос D-47…D-58 в decisions.md + D-59 (приёмка) — принято J-28 |
| L-1 | 🟡 частично | change-спеки унифицированы на `(taskId, toState, kind, reason)`; `agent-tools.md:24` всё ещё `(target_state_code, reason)` — не обновлён |
| L-2 | ✅ закрыто | proposal §19: аддитивное расширение без BREAKING, `agent{key,rev}` уже в M1 |
| L-3 | 🔴 не закрыто | proposal §39 не содержит `harness.task.transition.max-per-turn` и `harness.webhook.base-url` (J-18 «добавить в список все конфиги» выполнен частично) |
| L-4 | ✅ закрыто | везде `task-scheduler` |
| L-5 | ✅ закрыто | у всех spec-файлов H1 `# <capability> Specification` |
| L-6 | ✅ закрыто | proposal §37 «~24 новых операций» |

## Новые находки (внесены правкой)

| # | Sev | Находка |
|---|---|---|
| N-1 | 🔴 MEDIUM | Эндпоинт дерева сессий описан как **`POST`** `/api/v1/sessions/{id}/tree` в session-api delta (стр. 7), proposal (стр. 17), tasks D.1/K.1 — при `GET` в api-contracts §2. Генерация из спеки создаст POST, ломающий контракт |
| N-2 | MINOR | `PARKED_CLIENT`: proposal §17 — «зарезервирован в M3»; session-api delta (стр. 14) — «с M4 (релей)». Roadmap: релей — M4 |
| N-3 | MINOR | proposal §11 и design Goals (стр. 13) ссылаются на **D-38** (superseded D-41) вместо D-38/D-59 — ровно тот источник противоречия, что закрывал H-3 |

## Итог re-approval

- Закрыто: C-1, C-2, H-1, H-2, H-3, M-1, M-3, M-4, M-5, M-7, M-9, M-10, L-2, L-4, L-5, L-6 (16).
- Не закрыто (2 блокера + 6 minor): **M-6** (курсор SSE без носителя), **N-1** (`POST` vs `GET` tree) — блокеры; **M-8**, **L-1**, **L-3**, **N-2**, **N-3**, **M-2** — minor/дизайн-синк.

**REJECT — 2 блокера (M-6, N-1) + 6 minor = 8 незакрытых.** Блокеры заморозки: M-6 (спека объявляет реплей не-transition событий без durable-носителя), N-1 (в спека-дельте `POST` вместо `GET` для `/sessions/{id}/tree`).

---

# Cross-check находок GLM (m2-glm.md) и Mercury (m2-mercury.md)

> Проверено по `openspec/changes/m2-workflow-engine/*` и `docs/design/*`. Свои находки не перечитывались (кроме сверки номеров). Сборки не запускались.
> Статусы: **valid** — воспроизводится; **duplicate-of-mine** — совпадает с моей; **misread** — артефакты говорят иное; **disagree-with-reasoning** — факт есть, но вывод неверен.

## GLM — первый проход

| # | Находка | Статус | Обоснование |
|---|---|---|---|
| M1 | `wrong-transition` вне каталога | **duplicate-of-mine (H-1)**, valid, closed | api-contracts §6:127 + session-api delta + proposal §31/§37 — исправлено |
| M2 | metaTools-гейт vs D-41, нет plumbing | **duplicate-of-mine (H-3)**, valid, closed | D-59 в decisions.md; tasks J.2 plumbing + тесты. GLM верно указал и на отсутствие `instructionSource`/`sessionOwner` в M1-коде — задачи введены |
| M3 | JSON-Schema/hibernate.validator нереализуем | **valid (уникально, не дубль)** | На первом проходе D-58 действительно говорил «hibernate.validator»; закрыто ограниченным профилем D-58 + спеки + H.2 |
| M4 | WAIT_* без ERROR-ребра — клинч | **valid (уникально)** | Правило 5 дополнено «и ERROR, и TIMEOUT»; сценарий `wait-error-required` — закрыто |
| M5 | Тайминг transition tool-call vs turn-finish | **duplicate (частично моего M-3)**, valid, closed | Единое «транзакционно в момент tool-call»: agent-turn delta, D-52, J.2 |
| M6 | Нет дельт session-api/agent-turn | **duplicate-of-mine (C-1)**, valid, closed | 4 дельты добавлены |
| M7 | Границы модулей / stop-оркестрация / `D-M2-…` | **duplicate (расширяет мой M-2)**, valid, closed | D-49/J-9: `TaskEngine`/`BashStateExecutor`/`StopTaskFacade` → execution.impl; WebhookHandlers → api/impl; плейсхолдеры удалены. Остаточный дизайн-синк architecture.md — мой M-2 |
| m1 | 6 vs 7 таблиц, 010..015 vs 016 | **duplicate-of-mine (M-1)**, valid, closed | proposal §38: 7 таблиц / 010..016 |
| m2 | Нет AGENT-bootstrap + `blocked_task_id` индексов | **duplicate-of-mine (M-7)**, valid, closed | tasks H.1, spec §POLL |
| m3 | Сигнатура transition; `create_subtask` в M2 | **duplicate (L-1) + уникальный create_subtask**, valid, closed | Сигнатура унифицирована; сценарий подзадач переведён на REST |
| m4 | POLL тащит WAIT_WEBHOOK vs «пассивен» | **valid (уникально)**, closed | WAIT_WEBHOOK исключён из выборки (spec/tasks I.5) |
| m5 | Fan-out не ограничивает TIMEOUT | **valid (уникально)**, closed | Правило 7: ≤1 ребро каждого kind |
| m6 | Конфиг-дыры | **duplicate-of-mine (L-3), +`kind-timeouts.*`**, **OPEN** | proposal §39 по-прежнему без `max-per-turn`, `webhook.base-url`, `kind-timeouts.*` |
| m7 | Остатки deliberation (`D-M2-…`, 409-vs-ERROR) | **duplicate-of-mine (H-2)**, valid, closed | Единое поведение, сценарий добавлен |
| m8 | tree не задачен + опечатка метода | **duplicate (мой N-1, метод) + closed (задача)**, **OPEN** | Задачи D.2/K.1 добавлены; метод `POST` остался и расползся |
| m9 | Resume-семантика неполна | **valid (уникально)**, closed | resume публикует `task-wake` (spec/K.2) |
| m10 | R5 описывает несуществующую гонку | **valid (уникально)**, closed | R5 переписан: синхронный HTTP-путь, нет дедупликации |
| m11 | Stop-каскад не зафиксирован | **valid (уникально)**, closed | «Всегда каскадный», параметра нет |
| m12 | «10 новых кодов» — фактически 0 | **duplicate (M-1/H-1)**, valid, closed | Формулировка исправлена |
| n1 | Miscitation R2 (D-47) | **valid (уникально)**, closed | Ссылка снята |
| n2 | Грамматика POLL-сценария | **valid (уникально)**, closed | Исправлено |
| n3 | Названия WakeBus/Broadcaster | **valid (уникально)**, closed | Javadoc-различия I.5/J.4 |
| n4 | D-47…D-58 prenumeration | **duplicate-of-mine (M-10)**, closed/deferred | task M.3 |
| n5 | BREAKING-заметка устарела | **duplicate-of-mine (L-2)**, valid, closed | proposal §19 — «аддитивное расширение» |

## GLM — re-approval (новые)

| # | Находка | Статус | Обоснование |
|---|---|---|---|
| N-1 | ArchUnit: `api` не может в `task/workflow` | **valid, НЕ дубль (моё новое открытие)** — **OPEN** | Подтверждено: `ArchitectureRulesTest.java:65` — `api.mayOnlyAccessLayers("execution","intelligence","session","identity")`; D-49/J-9 селит `WebhookHandlers` и REST-задачи в `api/impl` с делегированием в `TaskRegistry` (task). tasks M.1 расширяет только `execution → {task,session,workflow}` — **про `api` ни слова**. CI-блокер в пачках K/L |
| N-2 | `task_event_seq` не доведён до контракта/модели | **duplicate-of-mine (M-6) + §3.2-синк**, valid — **OPEN** | Совпадает с моим M-6 (нет носителя); GLM добавляет несинхронизированный api-contracts §3.2 |
| N-3 | `POST` vs `GET` `/sessions/{id}/tree` | **duplicate-of-mine (N-1)**, valid — **OPEN** | Подтверждено: session-api delta:7, tasks D.1/K.1, proposal:17 — `POST`; api-contracts:38 — `GET` |
| N-4 | Purpose workflow-engine отстал (только TIMEOUT) | **valid (уникальное nit)** — **OPEN** | Purpose: «обязательные TIMEOUT-рёбра у BASH/WAIT»; после правила 5 у WAIT_* обязателен и ERROR. Не упомянут ERROR |
| N-5 | M.3 «добавление D-59», хотя D-59 уже внесён | **valid (уникальное nit)** — **OPEN** | decisions.md:53 уже содержит D-59; M.3 создаст дубль/конфуз |

## Mercury — первый проход

| # | Находка | Статус | Обоснование |
|---|---|---|---|
| 1 | `[CRITICAL]` D-29: replay-safe требует `idempotency_key`, а M2 отрицает | **disagree-with-reasoning / misread** | decisions.md D-41 (стр. 47) явно вырезает «идемпотентность-хранилище» и по дате позже D-29; proposal §18 цитирует именно пару «D-29/D-41» (suspend+stop из D-29, отказ от хранилища — из D-41). Не конфликт, а две части разных решений. Ядро-гигиена (D-29 не помечен частично отменённым) — не блокер |
| 2 | `[HIGH]` D-38 metaTools без ADR | **duplicate-of-mine (H-3), но reasoning неверен** | Решение D-59 добавлено. Mercury трактует как «уточнение D-38», тогда как D-41 отменил D-38/`instructionSource`; здесь прав GLM (см. разночтения) |
| 3 | `[MEDIUM]` `task-already-terminal` отсутствует в api-contracts §6 | **misread (ложно)** | Код есть: api-contracts §6:126 (`task-not-waiting-webhook` / `task-already-terminal` | 409). Mercury сам отзывает в re-approval |
| 4 | `[MEDIUM]` Пачка 2.1 из 7 миграций — разбить | **disagree-with-reasoning** | Премиса «8 изменений» включала session PARTIAL UNIQUE, которого в плане больше нет (C-2/J-3). Разбиение — вкусовое; судья (J-31) принял единым риском. Дефекта нет |
| — | Re-approval: `APPROVED, 0 незакрытых` | **disagree** | Пропущены открытые: `task_event_seq` (мой M-6 / GLM N-2), `POST` tree (N-1/N-3), ArchUnit `api → task` (GLM N-1), конфиг-дыры (m6). APPROVED не подтверждаю |

## Разночтения GLM ↔ Mercury

1. **D-38/metaTools**: GLM — «конфликт с D-41, нужен supersession-ADR»; Mercury — «уточнение D-38, отдельный ADR не нужен». **Прав GLM**: D-41 отменил и формулу D-38, и `instructionSource`, и metaTools-гейт; корректно оформлено как D-59 (supersede).
2. **D-29**: Mercury видит конфликт с D-41; GLM не поднимает (принимает примат D-41). **Прав GLM** (D-41 — более поздний и прямой).
3. **`task-already-terminal`**: фигурирует только у Mercury и **ложно** — код в §6 был изначально.
4. **Итоговый статус**: GLM — REJECT (6 незакрытых), Mercury — APPROVED (0). Кросс-чек подтверждает GLM: остатки реальны. Mercury не обнаружил ни `task_event_seq`, ни method-опечатку, ни ArchUnit-разрыв.

## Новые открытия (сверх моего списка)

Подтверждены 3 валидных незакрытых находки GLM, которых у меня не было: **N-1 (ArchUnit `api → task/workflow`, CI-блокер)**, **N-4 (Purpose без ERROR-рёбер)**, **N-5 (дубль D-59 в M.3)**. Собственных новых открытий сверх этого нет.

**Вердикт cross-check: reject** — мой 8 незакрытых подтверждены; к ним добавляются 3 валидных GLM (N-1, N-4, N-5); находки Mercury не добавляют валидных (1 misread, 1 duplicate с неверным reasoning, 1 false, 1 disagree), его re-approve «APPROVED» не подтверждаю.

---

# Re-approval 2 (2026-09-18, после фиксов раунда 2)

> Проверены: `openspec/changes/m2-workflow-engine/{proposal.md, design.md, tasks.md, specs/*/spec.md}` и `docs/design/{data-model,architecture,agent-tools,api-contracts,decisions,roadmap}.md`. `openspec validate --strict` — **valid**. Сборки не запускались.

## Статусы моих находок (re-approval 1 → cross-check)

| # | Был | Стало | Где проверено |
|---|---|---|---|
| **M-6** (блокер) | OPEN (нет durable `task_event_seq`) | **CLOSED** | `data-model.md:93` — колонка `task_event_seq bigint DEFAULT 0` с полным описанием «монотонный durable-счётчик… переживает рестарт; инкремент транзакционно с эмиссией события»; `session-api/spec.md:39` — «durable в `task.task_event_seq`, по стилю `last_seq`/`state_attempt`, курсор переживает рестарт»; `api-contracts.md:52` (§3.2) — «durable в `task.task_event_seq`, инкремент транзакционно»; `tasks.md:25` (J.4) — «реализовать инкремент durable-счётчика `task.task_event_seq` транзакционно с эмиссией каждого события» |
| **N-1** (блокер) | OPEN (`POST` tree) | **CLOSED** | `session-api/spec.md:7` — `GET`; `proposal.md:17` — `GET`; `tasks.md:3` (D.1) — `GET`; `tasks.md:4` (D.2) — `GET`; `tasks.md:29` (K.1) — `GET`. Метод полностью консистентен с `api-contracts.md:38` |
| **M-8** | OPEN (data-model индекс) | **CLOSED** | `data-model.md:131` — `INDEX (task_id, created_at, id) — курсор истории — пара (created_at, id)` |
| **L-1** | OPEN (signature в agent-tools) | **CLOSED** | `agent-tools.md:24` — `(taskId, toState, kind, reason)`; taskId-адаптер + metaTools-гейт (D-59) расписаны |
| **L-3** | OPEN (config list) | **CLOSED** | `proposal.md:39` — `max-per-turn`, `base-url`, `kind-timeouts.{bash,wait-webhook,wait-tasks,agent}.default` все на месте |
| **N-2** | OPEN (PARKED_CLIENT M3 vs M4) | **CLOSED** | `proposal.md:17` и `session-api/spec.md:14` — оба `PARKED_CLIENT — с M4 (релей)` |
| **N-3** | OPEN (D-38 без D-59) | **CLOSED** | `proposal.md:11` — «D-38; supersession в M2 — D-59»; `proposal.md:32` — «D-38/D-52/D-59»; `design.md:13` (Goals) — «D-38…; metaTools-гейт — D-59, supersession в M2»; `agent-tools.md:24` — «D-38; supersession в M2 — D-59» |
| **M-2** | OPEN (architecture contracts) | **CLOSED** | `architecture.md:37-38` — добавлены строки `TaskEngine` (execution) и `TriggerRegistry` (task) в таблицу контрактов |

## Sync-остатки GLM cross-check

| # | Был | Стало | Где проверено |
|---|---|---|---|
| **GLM N-1** (ArchUnit api→task/workflow) | OPEN | **CLOSED** | `architecture.md:25` — `api mayOnlyAccessLayers("execution", "intelligence", "session", "identity", "task", "workflow")`; `tasks.md:42` (M.1) — то же + «иначе контроллеры задач/вебхуков падают на CI» |
| **GLM N-2** (`task_event_seq` контракт) | OPEN | **CLOSED** | = мой M-6 (см. выше) |
| **GLM N-3** (POST/GET tree) | OPEN | **CLOSED** | = мой N-1 (см. выше) |
| **GLM N-4** (Purpose ERROR+TIMEOUT) | OPEN | **CLOSED** | `workflow-engine/spec.md:5` — «обязательные ERROR- и TIMEOUT-рёбра у BASH/WAIT» |
| **GLM N-5** (M.3 дубль D-59) | OPEN | **CLOSED** | `tasks.md:44` (M.3) — «+ проверка наличия D-59 (D-59 уже добавлен — не дублировать при переносе)» |

## Итог re-approval 2

- Закрыты все 8 моих остатков (M-6, N-1, M-8, L-1, L-3, N-2, N-3, M-2).
- Закрыты все 5 sync-остатков GLM (N-1…N-5).
- Прочие ранее закрытые (C-1, C-2, H-1…H-3, M-1, M-3, M-4, M-5, M-7, M-9, M-10, L-2, L-4, L-5, L-6) не регрессировали.

**APPROVE — 0 незакрытых.** Спецификация заморожена; следующий шаг — пачка D.2 (регенерация из openapi.yaml) по задачам tasks.md.
