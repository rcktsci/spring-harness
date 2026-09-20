# M3 Batch P Review - Mercury

**Дата:** 2026-09-20  
**Объект:** orchestrator-meta-tools (spec.md)

## Findings

### 1. metaTools Gate

**Requirements:**
- `agent.permissions_jsonb.metaTools` (boolean, default=false)
- metaTools-enabled agents get 6 orchestrator tools: `create_workflow`, `edit_workflow`, `create_task`, `create_subtask`, `set_dependency`, `configure_trigger`
- D-59 gate (`instructionSource=USER`) still applies — metaTools bypass this gate

**Design:**
- D-62: `agent.permissions_jsonb.metaTools=true` for bootstrap orchestrators (make-billing)
- D-69: org-bias — subagents inherit `metaTools=false`

✅ **Correct**

### 2. create_task Tool

**Specification:**
- `create_task(title, description, workflow_key, params?, tags?)`
- Resolves `workflow_key` → latest revision
- Creates task with `owner_user_id = parent session.owner_user_id`
- Validates `paramsSchema` (D-58 limited profile)
- Returns `{ taskId }`

**Design:**
- Consistent with M2 TaskRegistry.createTask

✅ **Correct**

### 3. configure_trigger Tool

**Specification:**
- `configure_trigger(name, workflow_key, params?, tags?)`
- Creates trigger via TriggerRegistry (M2 L.1)
- Returns `{ triggerId, url }` where url = capability-URL
- No `?source` parameter — external REST callback

**Design:**
- D-25: triggers with rev+revoke
- D-57: pin rev+revoke

✅ **Correct**

### 4. No Injection into Subagents

**Requirement:**
- `metaTools` boolean not inherited by subagents
- Subagents spawned by orchestrators remain `metaTools=false`
- D-69 org-bias: only explicit bootstrap orchestrators have metaTools

**Design:**
- D-69: subagent'` ≠ subagent'`, `metaTools=false`

✅ **Correct**

## Dev Notes

| Dev заметка | Статус |
|-------------|--------|
| metaTools gate (permissions_jsonb) | ✅ |
| D-59 bypass for metaTools | ✅ |
| 6 orchestrator tools | ✅ |
| org-bias subagents (metaTools=false) | ✅ |

## Verdict

approve
