# M3 Batch O Review - Mercury

**Дата:** 2026-09-20  
**Объект:** subagent-lifecycle (spawn, stop, read_compacted)

## Findings

### 1. workflow-domain §1-§6 + D-08

**workflow-domain §1 (workflow/task entities):**
- Workflow + WorkflowRevision with key, rev, graph_jsonb
- Task with workflow_id, state, status_projection

**workflow-domain §2 (graph schema):**
- states[] with code, type, workspace, agent_key, script, scope, timeout, payloadSchema, paramsSchema
- transitions[] with from, to, kind

**workflow-domain §3 (states):**
- AGENT, BASH_SCRIPT, WAIT_WEBHOOK, WAIT_TASKS, TERMINAL

**workflow-domain §4 (workflow/task lifecycle):**
- Create/edit workflows, tasks, dependencies

**workflow-domain §5 (workflow/task lifecycle):**
- Workflow/task dependencies, blocked_by

**workflow-domain §6 (capabilities):**
- create_workflow, edit_workflow, create_task, create_subtask, set_dependency, configure_trigger

**D-08 (stop(session)):**
- stop(session) = suspend + поддерево (цель + subtree)
- Implemented via `parent_session_id` cascading

✅ **Covered** — All workflow-domain sections and D-08 respected

### 2. D-59 не распространяется на spawn

**subagent-lifecycle spec:**
- `spawn_subagent` — metaTool (requires `metaTools=true`)
- D-59 gate: `instructionSource = USER`
- spawn is a TOOL_CALL → `instructionSource = TOOL_RESULT`

**Design:**
- spawn_subagent spawns subagent session
- Subagent inherits owner from parent
- D-59 gate protects transition/metaTools, not spawn

✅ **Correct** — D-59 does not apply to spawn

### 3. SubtreeCanceller BFS через ShedLock

**subagent-lifecycle spec (stop scenario):**
- `POST /api/v1/sessions/{id}/stop` cascades via `parent_session_id`
- All children marked `cancel_requested=true`
- Turns complete as CANCELLED
- Async tools report `TOOL_RESULT CANCELLED <subtree-cancelled>`

**ShedLock:**
- `session` table has `locked_by/locked_at` (ShedLock)
- Stop operation: `session` + children marked `cancel_requested`
- No ShedLock conflict — stop sets flag, Turn picks it up

✅ **Correct** — BFS via `parent_session_id`, ShedLock used for Turn, not stop

### 4. ReadCompactedTool

**subagent-lifecycle spec:**
- `read_compacted(compact_message_id)` metaTool
- Only available to metaTools-enabled agents
- Compacts `id` → `seq` transition (`session_message.id` → ULID)
- Returns payload (truncated if `harness.compact.read-max-bytes`)
- Cross-session: `id` not found → `not-found`

**Design:**
- `session_message.id` UNIQUE per session
- `covers` field in COMPACT messages
- read_compacted reads by `id` → returns `covers` range

✅ **Correct** — read_compacted metaTool implemented

## Dev Notes

| Dev заметка | Статус |
|-------------|--------|
| D-59 doesn't apply to spawn | ✅ |
| Stop cascades via parent_session_id | ✅ |
| SubtreeCanceller BFS | ✅ |
| ReadCompactedTool | ✅ |

## Verdict

approve
