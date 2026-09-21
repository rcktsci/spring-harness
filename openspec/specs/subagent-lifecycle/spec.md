# subagent-lifecycle Specification

## Purpose
Жизненный цикл субагентов через metaTool `spawn_subagent`: дочерняя сессия с `parent_session_id`, синхронный возврат финального ответа, owner-наследование, лимит depth, каскад отмены по поддереву. `read_compacted` для доступа к оригиналам, скрытым COMPACT.

## Requirements

### Requirement: `spawn_subagent` (metaTool, синхронный)

Агенту SHALL быть доступен metaTool `spawn_subagent(agent_key, prompt, params?)`. Вызов строго синхронный — **блокирующий до завершения субагента** (или до depth-limit / spawn-limit): родительский поток ждёт; результат — ТОЛЬКО на ходу родителя, где spawn вызван; если родительский Turn завершён до возврата (отмена/рестарт) — `message.created` с tool_result вызывает wake/новый Turn. Создаёт дочернюю сессию `parent_session_id = currentSessionId`, `owner_user_id` наследуется от родительской. Финальный ответ (ASSISTANT последнего хода, в котором нет tool-call) → `TOOL_RESULT` родителю. Гейт metaTools: для обычных агентов инструмент НЕ доступен (D-38 §2b — только оркестраторы, см. `orchestrator-meta-tools`); для оркестраторов (`permissions_jsonb.metaTools=true`) — доступен без D-59 ограничения `instructionSource = USER`.

#### Scenario: оркестратор спавнит субагента

- **WHEN** оркестратор-агент вызывает `spawn_subagent(agent_key=analyst, prompt="…", params=…)`
- **THEN** создаётся sub-session с `parent_session_id`; субагент отрабатывает; финальный ASSISTANT → TOOL_RESULT родителю с output

#### Scenario: обычный агент пытается спавнить

- **WHEN** агент без `metaTools=true` пытается `spawn_subagent`
- **THEN** инструмент скрыт из tool-declarations; явный вызов → `TOOL_RESULT forbidden (no-metaTools)`

### Requirement: Лимит `harness.spawn.max-depth`

При создании дочерней сессии SHALL проверяться `depth = parent.depth + 1` (root = 0); превышение `harness.spawn.max-depth` (конфиг, дефолт 2) → дочерняя сессия не создаётся, инструмент возвращает `TOOL_RESULT forbidden (depth-limit)`. `depth` хранится в колонке `session.depth` (NOT NULL DEFAULT 0) — миграция `074_create_column_session_depth.xml`, решение D-61 (у `session` нет `params_jsonb`).

#### Scenario: depth-limit достигнут

- **WHEN** depth родителя = `max-depth` и субагент пытается спавнить
- **THEN** TOOL_RESULT «forbidden (depth-limit)»; дочерняя сессия не создаётся

### Requirement: Завершение субагента

Субагентская сессия SHALL считаться завершённой для родителя когда: (а) модель вернула ход без tool-call (нет pending → финал ASSISTANT или завершающий SYSTEM) **И** `pending_tool_calls == 0` (D-10); (б) сессия пришла в `TERMINAL` (отменена/завершена явно). Финальный output → `TOOL_RESULT` в родительскую сессию.

#### Scenario: субагент завершился нормально

- **WHEN** субагент достиг финального ASSISTANT без tool-call
- **THEN** родительский Turn видит TOOL_RESULT с output; продолжает работу

#### Scenario: субагент отменён stop'ом

- **WHEN** родительский сеанс вызывает stop(session), субагент тоже stop'ит
- **THEN** TOOL_RESULT «subtree-cancelled»; родитель продолжает

### Requirement: Отмена поддерева сессий (пользовательская)

`POST /api/v1/sessions/{id}/stop` SHALL отменить не только свою сессию, но **всё поддерево** по `parent_session_id` (рекурсивно). Незакрытые async `TOOL_CALL` отменённых — синтетический `TOOL_RESULT CANCELLED`. Stop идемпотентен; повторный stop на отменённом поддереве — no-op+`202`.

#### Scenario: stop родителя каскадирует

- **WHEN** пользователь stop'ит root-сессию с N субагентами (N≥1)
- **THEN** все N сессий получают cancel_requested; активные Turn'ы завершаются CANCELLED; незакрытые TOOL_CALL → синтетические CANCELLED; ответ 202

#### Scenario: stop субагента сам по себе

- **WHEN** пользователь stop'ит конкретного субагента (mid-tree)
- **THEN** только эта сессия и её поддерево отменяются; корень и боковые субагенты живы

### Requirement: `read_compacted` (metaTool, идемпотентный)

Агенту SHALL быть доступен metaTool `read_compacted(compact_message_id)`. Резолвит `id → seq` по `session_message.id` **только в пределах текущей сессии** (cross-session-чтение — вне M3, точка эволюции: потребует глобальной уникальности ULID + явного параметра сессии + отдельного решения). Возвращает **оригинальные** сообщения, скрытые COMPACT-событием, чей `covers` включает диапазон `[seq, …]`. COMPACT-событие НЕ модифицируется; `read_compacted` снимает покрытие только для ответа инструмента. Гейт: обычным агентам доступен в их собственной сессии; id чужой сессии → `not-found`.

#### Scenario: read_compacted возвращает оригинал

- **WHEN** агент вызывает `read_compacted(id)` где id указывает на USER/ASSISTANT под COMPACT-покрытием
- **THEN** в TOOL_RESULT содержимое оригинального сообщения (лимит — конфиг `harness.compact.read-max-bytes`)

#### Scenario: id не найден

- **WHEN** `read_compacted(invalid_id)`
- **THEN** `TOOL_RESULT not-found (no-such-message)` без падения

### Requirement: Owner и workspace дочерней сессии

Дочерняя сессия SHALL наследовать `owner_user_id` от родительской (не от ключа агента/не от JWT — потому что родитель уже мог быть создан любым механизмом). Workspace — наследуется (тот же workspace-binding, что у родителя) **ИЛИ создаётся субагентский контейнер с собственным workspace (конфиг `harness.spawn.workspace-strategy: inherit|new`).

#### Scenario: owner-наследование

- **WHEN** родительская сессия принадлежит alice и spawn'ит субагента
- **THEN** дочерняя `owner = alice` (не субагентский ключ и не JWT субагента)
