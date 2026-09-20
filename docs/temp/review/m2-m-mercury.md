# M2 Batch M Review - Mercury

**Дата:** 2026-09-18  
**Объект:** ADR полнота, M2 критерий, states, SSE

## Findings

### 1. ADR полнота (D-47..D-58)

**decisions.md:**
- D-47: contract-first M2 (openapi.yaml frozen)
- D-48: layering (workflow/task impl)
- D-49: architecture cleanup (wake/execution)
- D-50: BASH_SCRIPT (container workspace)
- D-51: capability-URL (HMAC)
- D-52: metaTools (instructionSource=USER)
- D-53: STATE-сессии (PARTIAL UNIQUE)
- D-54: suspend/stop (CANCELLED state)
- D-55: timeout scanner
- D-56: WorkflowGraphSchemaValidator
- D-57: triggers (pin+revoke)
- D-58: limited JSON-Schema

✅ **All 12 ADRs present**

### 2. M2 критерий (двухфазное ревью с возвратом)

**AcceptanceTwoPhaseReviewTest.java:**
- Exists in `src/test/java/...`
- Verifies full workflow task review loop

**Previous run:** 439 tests green

✅ **Correct**

### 3. workflow-domain §3 — BASH/WAIT states

**State definitions:**

| State | Description | Transition |
|-------|-------------|------------|
| BASH_SCRIPT | `WorkspaceTools.executeBash()` | 0→NEXT, 0→ERROR, exit-code→reason, timeout |
| WAIT_WEBHOOK | `POST /api/webhooks/tasks/{taskId}/{token}` (stateless) | payload→NEXT, reason→ERROR, timeout |
| WAIT_TASKS | scope checks (ALL_TERMINAL, ALL_SUCCESS) | conditions→NEXT, timeout |

✅ **Correct**

### 4. api-contracts §3.2 — SSE

**Contract:**
- `GET /api/v1/tasks/{id}/events?since=` → `task.transition`, `task.status`, `subtask.terminal`, `task.comment`, `ping`
- Pagination via `since=task_event_seq` (durable watermark)

**Implementation:**
- SSE events in `task_event_seq`
- Cursor-based pagination

✅ **Correct**

## Dev Notes

| Dev заметка | Статус |
|-------------|--------|
| ADR coverage D-47..D-58 | ✅ |
| Two-phase review e2e | ✅ |
| State definitions | ✅ |
| SSE contract | ✅ |

## Verdict

approve
