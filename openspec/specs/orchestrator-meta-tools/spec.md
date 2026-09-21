# orchestrator-meta-tools Specification

## Purpose
Оркестратор-агент: расширенный каталог metaTools (`create_workflow`/`edit_workflow`/`create_task`/`create_subtask`/`set_dependency`/`configure_trigger`) и обратный гейт — для оркестратора metaTools снимает D-59 ограничение `instructionSource = USER`, оставляя обычные агенты под D-59.

## Requirements

### Requirement: Гейт metaTools расширен оркестраторским путём

Инструменты оркестратора SHALL быть доступны агенту, если `agent.permissions_jsonb.metaTools == true` (new boolean). В этом случае D-59 (gate `instructionSource = USER`) для этих инструментов НЕ применяется. Для агентов без `metaTools=true` — все эти инструменты скрыты из tool-declarations (как `forbidden` при попытке прямого вызова в non-metaTool путях).

#### Scenario: оркестратор вызывает create_task

- **WHEN** агент-оркестратор (`metaTools=true`) вызывает `create_task(title="…", workflow_key="…")` из хода, source=TOOL_RESULT
- **THEN** инструмент исполняется (D-59 не действует), задача создаётся

#### Scenario: обычный агент пытается create_task

- **WHEN** обычный агент без `metaTools=true` пытается `create_task` (прямой вызов)
- **THEN** инструмент не в tool-declarations, либо возвращает `TOOL_RESULT forbidden (no-metaTools)`

### Requirement: Атомарный `create_task` через metaTool

`create_task(title, description, workflow_key, params?, tags?)` SHALL: пин `workflow_revision = workflow_key's latest` (если rev не указан); создать задачу с `owner_user_id = parent session.owner_user_id` (наследование); вернуть `{ taskId }` модели. Валидация `paramsSchema` ревизии (D-58 профиль) на уровне реестра — ошибка → `TOOL_RESULT validation-failed (params-schema)` с перечислением нарушений.

#### Scenario: оркестратор создаёт подзадачу

- **WHEN** metaTool `create_subtask(parent_task_id, ..., workflow_key="billing-impl", params={...})`
- **THEN** подзадача создаётся с `parent_task_id` и owner = session.owner; возвращён `taskId`

### Requirement: Инструмент `configure_trigger` возвращает URL

`configure_trigger(name, workflow_key, params?, tags?)` SHALL: создать триггер через `TriggerRegistry` (M2 L.1); вернуть модель `{ triggerId, url }` где url = capability-URL. Без `?source` параметра, без payload — как и REST.

#### Scenario: orchestrator создаёт триггер

- **WHEN** metaTool `configure_trigger(name="…", workflow_key="…")`
- **THEN** TOOL_RESULT `{ triggerId, url }` — orchestrator может зарегистрировать URL во внешней системе программно

### Requirement: Защита metaTools от injection

Гейт metaTools SHALL снимать только ограничение USER-source (D-59) и MUST NOT снимать проверки прав/декларации. Если субагент (sub-orchestrator) сам spawn'ит подзадачу — owner и лимит depth пересчитываются по sub-orchestrator's session.owner. Nudging параллельно — без изменений.

#### Scenario: субагент-orchestrator

- **WHEN** субагент-orchestrator (depth=1) вызывает `create_task`
- **THEN** owner = parent session.owner (т.е. alice); глубина пересчитывается для будущих spawn

### Requirement: `permissions_jsonb.metaTools` в агенте

`agent.permissions_jsonb.metaTools` SHALL быть boolean-полем в ревизии агента (по умолчанию false). Правка ревизии = INSERT новой строки (D-31 иммутабельность). Агентство-по-умолчанию (`make-billing`, `feature-delivery`) — `metaTools = true` в bootstrap-данных; конкретные рабочие агенты — false.

#### Scenario: bootstrap orchestrator-агент

- **WHEN** ревизия агента `make-billing` создаётся в БД вручную (MVP без bootstrap)
- **THEN** `permissions_jsonb.metaTools = true` — оркестратор сразу готов к workflow-операциям

### Requirement: Отсутствие metaTools в дефолтном агенте

Дефолтный рабочий агент (например, `coder`) SHALL иметь `permissions_jsonb.metaTools = false` — попытки `create_task` запрещены (не входят в tool-declarations; явный вызов → `forbidden`).

#### Scenario: coder пытается create_task

- **WHEN** агент `coder` (без metaTools) вызывает `create_task`
- **THEN** инструмент отсутствует в manifest; TOOL_RESULT принудительно `forbidden`
