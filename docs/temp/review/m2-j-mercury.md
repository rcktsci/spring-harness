# M2 Batch J Review - Mercury

**Дата:** 2026-09-18  
**Объект:** STATE sessions, metaTools gate, session plumbing

## Findings

### 1. D-53 PARTIAL UNIQUE (task_id, state_code)

**StateSessionServiceImpl:** `CREATE_STATE_SESSION` SQL использует `ON CONFLICT (task_id, state_code) WHERE kind='STATE' DO NOTHING RETURNING id`.

**Миграция 005:** PARTIAL UNIQUE индекс `uidx_session__task_id_state_code (task_id, state_code) WHERE kind='STATE'` уже существует из M1.

**Семантика:** INSERT с RETURNING, если conflict — find existing. Атомарность по БД-уровню.

✅ **Correct**

### 2. D-59 metaTools-гейт

**Caller.instructionSource()**: Возвращает `InstructionSource.USER` (M2: только HTTP-точка входа — JWT).

**InstructionSource enum**: USER, TOOL_RESULT, SYSTEM.

**AgentTurnEngine**: 
- `instructionSource(session)` возвращает `Caller.instructionSource()`
- `transitionTool.declaration()` добавляется только для `SessionKind.STATE`
- `transitionTool` проверит instructionSource в реализации

✅ **Correct**

### 3. D-44 covers vs task_event_seq

**SessionMessage.payload.covers**: `{from: seq, to: seq}` (seq-интервалы, COMPACT)

**Task.task_event_seq**: bigint counter, инкрементируется при каждом переходе (task-engine)

**Разделение:**
- Session: COMPACT скрывает события через covers
- Task: task_event_seq — курсор для SSE задач

✅ **Correct** (разные вещи, не путаются)

### 4. D-45 watermark

**AgentTurnEngine**: `renderedWatermark` — это `last_consumed_seq` для session. Задачного потребления нет (задачи не имеют watermark).

✅ **Correct** (не применимо к задачам)

### 5. workflow-domain §1 — task.current_state

**TaskRegistry.createTask**: начальное состояние — source state ревизии (без входящих рёбер).

✅ **Correct**

### 6. ArchUnit

**StateSessionService**: `task` → `session` (разрешено: task не зависит от session, а использует TaskRegistry.ownerUserId)

**AgentTurnEngine**: `execution` → `session` (разрешено: execution → session → workflow/task)

✅ **Correct**

## Dev Notes

| Dev заметка | Статус |
|-------------|--------|
| StateSessionServiceImpl: seed SYSTEM message | ✅ |
| Caller: instructionSource plumbing | ✅ |
| AgentTurnEngine: transitionTool gated by STATE | ✅ |
| InstructionSource enum | ✅ |

## Verdict

approve
