# task-engine Specification

## Purpose
Жизненный цикл задач: создание с пином ревизии, переходы (атомарный CAS с записью истории), подзадачи, зависимости (с валидацией циклов), suspend/resume/stop, история, комментарии, дерево, системные состояния BASH_SCRIPT/WAIT_WEBHOOK/WAIT_TASKS. STATE-сессии, резюмируемые по `(task_id, state_code)`. Мета-инструмент `transition` с обязательным reason.

## Requirements

### Requirement: Создание задачи (пин ревизии)

`POST /api/v1/tasks { title, description, workflowKey, rev?, params?, tags? } → 201 TaskDto`. Задача SHALL быть создана с пином к конкретной `workflow_revision` (`rev` из запроса или `latestRev` workflow). `params` валидируются `paramsSchema` ревизии (если объявлена; ограниченный профиль JSON-Schema — см. workflow-engine) — невалидные → `422 params-schema`. `tags` — массив строк (по умолчанию пуст). `owner` = пользователь из JWT; создание STATE-сессии не происходит автоматически (агент создаёт переходом).

#### Scenario: успешное создание

- **WHEN** клиент создаёт задачу с валидными title и workflowKey
- **THEN** `201` с TaskDto и `Location: /api/v1/tasks/{id}`

#### Scenario: невалидные params

- **WHEN** клиент создаёт задачу с params, нарушающим `paramsSchema`
- **THEN** `422` с кодом `params-schema` и `errors[]` по схеме

### Requirement: Список и просмотр задач

Система SHALL реализовать: `GET /api/v1/tasks?parent=&status=&mine=&tags=&q=&cursor=` — фильтры (`parent` — `taskId` подзадач; `status` — `RUNNING|WAITING|SUCCEEDED|FAILED|CANCELLED`; `mine` — только владельца; `tags` — содержит все указанные; `q` — по title/description), сортировка `updatedAt` desc, конверт-пагинация `{ items, nextCursor? }`; `GET /api/v1/tasks/{id} → 200 TaskDto | 404 task-not-found`; `GET /api/v1/tasks/{id}/tree?depth=` — поддерево `{ items: TaskTreeNode[] }`.

#### Scenario: фильтр по статусу

- **WHEN** клиент запрашивает `?status=WAITING`
- **THEN** возвращаются только задачи со `status_projection = WAITING`

### Requirement: PATCH задачи (merge-patch)

`PATCH /api/v1/tasks/{id}` SHALL принимать merge-patch `{ title?, description?, tags? }`. `params` иммутабельны после создания — попытка включить `params` в patch SHALL приводить к `422 validation-failed` с error `rule=immutable`.

#### Scenario: изменение title

- **WHEN** клиент отправляет PATCH `{ title: "новое название" }`
- **THEN** `200`, `title` обновлён, `updated_at` сдвинут

#### Scenario: попытка изменить params

- **WHEN** клиент отправляет PATCH с полем `params`
- **THEN** `422` с error `rule=immutable`

### Requirement: Подзадачи

`POST /api/v1/tasks/{id}/subtasks { title, description, workflowKey, rev?, params?, tags? } → 201`. Новая задача SHALL иметь `parent_task_id = {id}` и наследовать `owner_user_id` от родителя (если инициатор — агент сессии) или от JWT (если инициатор — пользователь). Циклы на уровне parent-зависимостей запрещены (`task_dependency` валидация, см. §зависимости).

#### Scenario: создание подзадачи

- **WHEN** агент создаёт подзадачу через REST `POST /api/v1/tasks/{id}/subtasks` (инструмент `create_subtask` в M2 не вводится)
- **THEN** `201`, `parent_task_id` указывает на родителя, `owner` наследован от породившей сессии

### Requirement: Suspend и Resume

`POST /api/v1/tasks/{id}/suspend { cascade: bool }` SHALL устанавливать `suspended=true` и возвращать `204`. Активный Turn на сессии состояния SHALL доработать без немедленного прерывания (Turn не убивается; suspend — флаг). `POST /api/v1/tasks/{id}/resume → 204` SHALL снимать флаг для нетерминальной задачи и публиковать `task-wake` событие (переоценка WAIT_TASKS / bootstrap AGENT-state подхватывают задачу без ожидания POLL). Для терминальной (`SUCCEEDED|FAILED|CANCELLED`) resume SHALL возвращать `409 task-already-terminal`. Suspend идемпотентен.

#### Scenario: suspend активной задачи

- **WHEN** клиент suspend'ит задачу с активным Turn'ом
- **THEN** `204`, Turn дорабатывает, дальнейшие переходы блокируются флагом

#### Scenario: resume терминальной

- **WHEN** клиент пытается resume терминальную задачу
- **THEN** `409` с кодом `task-already-terminal`

### Requirement: Stop (принудительная отмена)

`POST /api/v1/tasks/{id}/stop → 202` SHALL: (1) установить `suspended=true`; (2) отменить активные Turn'ы сессий состояния задачи **и всех подзадач — stop всегда каскадный (параметр `cascade` в API stop отсутствует)**; (3) атомарным CAS записать `current_state = '$CANCELLED'` и добавить запись в `task_transition_history` с `kind=CANCEL`, `to_state='$CANCELLED'`, `reason_jsonb={ kind: 'stop', actor: 'user' }`; (4) обновить `status_projection = CANCELLED`. `'$CANCELLED'` — зарезервированный псевдо-код вне `codes` ревизии; CANCEL-рёбра в графе не требуются. Resume `'$CANCELLED'`-задачи → `409 task-already-terminal`.

#### Scenario: stop задачи с активным AGENT-состоянием

- **WHEN** клиент stop'ит задачу, у которой активная STATE-сессия
- **THEN** Turn отменяется, контейнеры освобождаются, `current_state='$CANCELLED'`, `status_projection=CANCELLED`, в истории — запись `kind=CANCEL`

#### Scenario: stop уже терминальной задачи

- **WHEN** клиент stop'ит SUCCEEDED-задачу
- **THEN** `409` с кодом `task-already-terminal`

### Requirement: История переходов

`GET /api/v1/tasks/{id}/history?since=&limit=` SHALL возвращать `{ items: TransitionDto[], nextCursor? }` за интервал `(since, …]`. Курсор — пара `(created_at, id)` записи `task_transition_history` (стабильная пагинация при равных `created_at`; индекс `(task_id, created_at, id)`). `TransitionDto`: `id (UUID), fromState, toState, kind: NEXT|ERROR|TIMEOUT|CANCEL, reason (JSON-объект; для bash — stdout/stderr+exit; для агента — текст-обоснование; для вебхука — source+сводка; для WAIT_TASKS — какие задачи закрыли условие), createdAt`.

#### Scenario: чтение истории

- **WHEN** клиент читает историю с `since=0`
- **THEN** возвращаются все записи `task_transition_history` для задачи в порядке `created_at` asc

### Requirement: Комментарии

`POST /api/v1/tasks/{id}/comments { body } → 201` SHALL дописывать `task_comment` (иммутабельный). `GET /api/v1/tasks/{id}/comments?cursor=` — список, конверт-пагинация.

#### Scenario: добавление комментария

- **WHEN** пользователь отправляет комментарий
- **THEN** `201` с CommentDto, атрибуция по `author_user_id`

### Requirement: Зависимости (blocked_by)

`POST /api/v1/tasks/{id}/dependencies { blockedBy: [taskId] } → 201`. Валидация: все `taskId` существуют; нет self-loop; нет цикла (DFS по графу зависимостей). Нарушения → `422 dependency-invalid` с `errors[]`. Добавление ребра SHALL предотвращать запуск блокированной задачи, пока блокирующая не пришла в нужный терминал (через переоценку `WAIT_TASKS` и POLL). `DELETE /api/v1/tasks/{id}/dependencies/{blockerId} → 204` снимает ребро.

#### Scenario: создание циклической зависимости

- **WHEN** клиент пытается добавить зависимость, замыкающую цикл `A → B → C → A`
- **THEN** `422` с кодом `dependency-invalid` и error `rule=cycle`

#### Scenario: самозависимость

- **WHEN** клиент пытается добавить блокирующую задачу, равную блокируемой
- **THEN** `422` с кодом `dependency-invalid` и error `rule=self-loop`

### Requirement: Системное состояние BASH_SCRIPT

Когда задача входит в `BASH_SCRIPT`-state, движок SHALL: (1) инкрементировать `state_attempt` (транзакционно с записью перехода); (2) запустить `script` через `WorkspaceTools` в контейнере `harness-task-<taskId>` с `workspace = { type: SERVER_DIR, mode: PATH, path: ${task.id} }` (по умолчанию) или согласно `state.workspace`; (3) по завершении: `exit=0` → переход по NEXT; `exit≠0` → переход по ERROR с `reason_jsonb={ exitCode, stdout, stderr, durationMs }`; таймаут (превышение `state.timeout`) → переход по TIMEOUT с аналогичным reason. Скрипт SHALL быть идемпотентным: повторный прогон после crash помечается новой попыткой, side-effect может выполниться дважды.

#### Scenario: bash успешен

- **WHEN** `BASH_SCRIPT`-state исполнен с exit=0
- **THEN** переход NEXT, stdout/stderr в reason, `state_attempt` инкрементирован

#### Scenario: bash с ошибкой

- **WHEN** `BASH_SCRIPT`-state исполнен с exit≠0
- **THEN** переход ERROR, reason содержит exitCode+stderr

#### Scenario: bash таймаут

- **WHEN** `BASH_SCRIPT`-state превысил timeout
- **THEN** процесс в контейнере убит, переход TIMEOUT

### Requirement: Системное состояние WAIT_WEBHOOK

Задача в `WAIT_WEBHOOK` SHALL переходить в NEXT при приёме валидного payload'а через `POST /api/webhooks/tasks/{taskId}/{token}` (см. inbound-triggers). Если `state.payloadSchema` объявлен (ограниченный профиль JSON-Schema — см. workflow-engine) и payload не прошёл валидацию — переход ERROR с reason `{ validationErrors }`. Таймаут (превышение `state.timeout`) → переход TIMEOUT.

#### Scenario: payload прошёл

- **WHEN** задача в WAIT_WEBHOOK получает валидный webhook POST
- **THEN** переход NEXT, payload и source в reason

### Requirement: Системное состояние WAIT_TASKS

Задача в `WAIT_TASKS` SHALL переоцениваться при: терминале любой дочерней/разблокированной задачи (через индекс `(parent_task_id, status_projection)`), изменении `blocked_by` (через индекс `(blocked_task_id)`), изменении тегов (GIN). Scope: `ALL_CHILDREN` (все подзадачи по `parent_task_id`), `BLOCKED_BY` (задачи по `task_dependency.blocker_task_id`), `TAGGED(x)` (по тегам), `EXPLICIT(${task.params.key})` (явный список ID из params). Condition: `ALL_TERMINAL` (все scope-задачи в терминале, `CANCELLED`-потомок — терминал); `ALL_SUCCESS` (как ALL_TERMINAL, но первый `FAILED|CANCELLED` ведёт немедленно по ERROR). Состав закрывшего условия → reason. Таймаут → TIMEOUT.

#### Scenario: все подзадачи завершены

- **WHEN** scope=ALL_CHILDREN, condition=ALL_TERMINAL, и все подзадачи пришли в SUCCEEDED
- **THEN** переход NEXT, reason перечисляет закрывшие подзадачи

#### Scenario: FAILED подзадача при ALL_SUCCESS

- **WHEN** scope=ALL_CHILDREN, condition=ALL_SUCCESS, и одна подзадача FAILED
- **THEN** переход ERROR с reason `{ failed: [taskId] }`

### Requirement: STATE-сессии (резюм по `task_id`+`state_code`)

STATE-сессия SHALL быть создана (или найдена существующая) движком при входе задачи в AGENT-state: `session.kind=STATE`, `session.task_id = task.id`, `session.state_code = state.code`, `session.agent_revision_id = revision`. Создание SHALL быть атомарным: в одной транзакции insert сессии + seed-`SYSTEM`-сообщение + `last_seq` (состояния «сессия есть, seed не записан» не существует — execution-model §7.2). Повторный вход в то же `(task_id, state_code)` SHALL резюмировать ту же сессию (PARTIAL UNIQUE гарантирует). Привязка `agent_revision` фиксируется на момент входа (замена ревизии родительского workflow не задевает идущие задачи). Сообщения сессии изолированы от FREE — `POST /api/v1/sessions` создаёт только FREE.

#### Scenario: первый вход в AGENT-state

- **WHEN** задача переходит в AGENT-state впервые
- **THEN** создаётся STATE-сессия, Turn стартует

#### Scenario: повторный вход (резюм)

- **WHEN** задача возвращается в тот же AGENT-state (цикл в графе)
- **THEN** существующая STATE-сессия подхватывается, Turn продолжает диалог

### Requirement: Мета-инструмент `transition`

Агент SHALL иметь доступ к инструменту `transition(taskId, toState, kind, reason)` для перевода задачи (`taskId` в контракте реестра обязателен; при вызове из STATE-сессии адаптер резолвит его из сессии неявно). Параметры: `toState` — `code` одного из разрешённых исходящих рёбер текущего состояния; `kind` — `NEXT|ERROR` (определяется ребром, по умолчанию `NEXT`); `reason` — **обязательный** текст-обоснование (записывается в `task_transition_history.reason_jsonb`). Гейт metaTools (D-52/D-59): инструмент SHALL быть доступен только при `instructionSource = USER`; в M2 единственная точка входа — USER-сообщение прямо в сессию состояния (`POST /api/v1/sessions/{id}/messages`); системные переходы (BASH/WAIT_* → NEXT/ERROR/TIMEOUT) исполняются движком, не через инструмент. Конфликт — `409 wrong-transition` (не разрешённое ребро).

#### Scenario: агент переводит по NEXT

- **WHEN** агент в AGENT-state вызывает `transition(to=next_code, reason="...")` и `reason` непустой
- **THEN** запись в `task_transition_history`, `current_state` атомарно обновлён, `status_projection` транзакционно сменён

#### Scenario: пустой reason

- **WHEN** агент вызывает `transition` без reason или с пустой строкой
- **THEN** `409 wrong-transition` с error `rule=reason-required`

#### Scenario: недостижимое состояние

- **WHEN** агент вызывает `transition(to=code)` не из текущего
- **THEN** `409 wrong-transition` с error `rule=not-an-outgoing-edge`

### Requirement: Таймаут-скан (deadline_at)

Системная джоба (интервал — конфиг `harness.task.timeout.scan-interval`) SHALL сканировать задачи с `deadline_at IS NOT NULL AND deadline_at <= now() AND status_projection IN ('RUNNING','WAITING')`. Для каждой: переход TIMEOUT с записью в историю и снятием `deadline_at`. Индекс: `(deadline_at) WHERE deadline_at IS NOT NULL`.

#### Scenario: задача превысила timeout

- **WHEN** `BASH_SCRIPT`-задача не завершилась к `deadline_at`
- **THEN** переход TIMEOUT, bash убит, в reason — durationMs

### Requirement: Задачный POLL-страховка

Системная джоба (ShedLock-лок `task-scheduler`, интервал — конфиг `harness.task.poll-interval`, по умолчанию ~5 с) SHALL подбирать: (1) AGENT-state задачи без STATE-сессии → bootstrap (создать сессию, поставить в очередь); (2) `WAIT_TASKS` — переоценить (по событиям терминалов подзадач). `WAIT_WEBHOOK` в POLL-выборку не входит — состояние пассивно, его страхует только таймаут-скан по `deadline_at`. Индексы: partial `(current_state_kind) WHERE current_state_kind = 'WAIT_TASKS'`, partial `(current_state_kind='AGENT' AND status_projection='RUNNING')` (bootstrap-скан), partial `(deadline_at) WHERE deadline_at IS NOT NULL`.

#### Scenario: AGENT-state без сессии

- **WHEN** у AGENT-задачи нет STATE-сессии (EVENT-wake не сработал)
- **THEN** POLL создаёт сессию и инициирует Turn

### Requirement: Атомарность переходов

Смена `current_state` SHALL выполняться атомарным CAS: `UPDATE task SET current_state = next, status_projection = …, current_state_kind = …, deadline_at = … WHERE id = ? AND current_state = expected AND suspended = false`. Гонки stop↔transition, двойной `transition`, дубль терминала и ретрай вебхука — один победитель; проигравшие — no-op. **Исключение — stop**: его CAS-запись `'$CANCELLED'` гварды `NOT suspended` не имеет (иначе сам себя заблокировал бы) и выигрывает у любых переходов. Транзакция покрывает: `task_transition_history` INSERT + обновление `task`.

#### Scenario: двойной transition

- **WHEN** два вызова `transition(to=next)` происходят одновременно
- **THEN** один успешен, второй no-op (CAS промахнулся) → запись в истории одна

#### Scenario: stop vs transition

- **WHEN** `transition` и `stop` финишируют одновременно
- **THEN** stop выигрывает (его CAS не имеет гварды suspended), `'$CANCELLED'` финальный
