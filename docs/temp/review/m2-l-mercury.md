# M2 Batch L Review - Mercury

**Дата:** 2026-09-18  
**Объект:** workflow, triggers, webhooks, capability-URL

## Findings

### 1. workflow-domain.md §7 — stateless capability-URL

**WebhookSignatureVerifier:**
- `verify(kind, entityId, token)`: `HMAC-SHA256(secret, kind + ':' + entityId)`
- Constant-time comparison with `MessageDigest.isEqual`
- No DB lookup for tasks (stateless)

✅ **Correct**

### 2. api-contracts §4.3 — triggers

**TriggerRegistry:**
- `create(CreateTriggerCommand)`: pin rev, paramsSchema validation
- `list(TriggerSearchCriteria)`: cursor-based pagination, mine filter
- `revoke(id)`: `UPDATE SET revoked_at = now()` → 410 trigger-revoked

**TriggersController:** CRUD endpoints at `/api/v1/triggers`

✅ **Correct**

### 3. api-contracts §4.4 — webhooks

**WebhookHandlers:**
- `handleTaskWebhook()`: verify token → check WAIT_WEBHOOK → `TaskWebhookPort.onWebhookArrived()`
- `handleTriggerWebhook()`: verify token → check revoked_at → `TaskRegistry.createTask()`

**WebhooksController:** `/api/webhooks/tasks/{taskId}/{token}` and `/api/webhooks/triggers/{triggerId}/{token}`

✅ **Correct**

### 4. D-25 — trigger entity

**data-model §6:** `trigger` table with `revoked_at`. DELETE = revoke (revoked_at set).

✅ **Correct**

### 5. D-26 — capability-URL

**WebhookSignatureVerifier:** HMAC in path token (`kind:entityId` format). Stateless for tasks.

✅ **Correct**

### 6. D-29 — suspend+stop

**Not in batch L** — handled in batch K.

✅ **N/A**

### 7. L.5 — sweep

**WorkflowsController:**
- CRUD on workflows and revisions
- `createWorkflow()` validates key uniqueness (dev fix D-31

- `createWorkflowRevision()` pins to workflow
- `caller.userId()` for owner (D-59)

**No new architectural decisions** — uses existing WorkflowRegistry, ApiMappers, LimitsProperties.

✅ **Correct**

## Dev Notes

| Dev заметка | Статус |
|-------------|--------|
| TaskNotWaitingWebhookException (409) | ✅ (dev fix D-33) |
| WebhookHandlers in api.impl | ✅ |
| WebhooksController @RequestMapping("/api") | ✅ |

## Verdict

approve
