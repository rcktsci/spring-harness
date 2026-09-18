# Review: Batch E-impl (step 2 contract-first + API 8.1–8.5)
**Reviewer:** Mercury (2.5)  
**Date:** 2026-09-18

---

## Findings

### 1. API Client Perspective

**Error handling (Task 8.1)**
- RFC 9457 Problem Details: ✅ Present for all error codes (401/405/406/413/415/422)
- Problem codes from M1 catalog: ✅ `unauthenticated`, `validation-failed`, `session-not-found`, etc.
- 422 errors[] with pointer/rule/message: ✅ Tested in `ApiErrorHandlingTest`
- Content-Type on errors: ✅ `application/problem+json`

**Status codes:**
- 201+Location on session creation: ✅ (`SessionsController.createSession`)
- 202 on async commands: ✅ (sendMessage, compact, stop)
- 404 on not found: ✅ (`SessionNotFoundException`)
- 409 on wrong kind: ✅ (`WrongSessionKindException`)
- 422 on validation: ✅ (`ApiValidationException`)
- 413 on payload too large: ✅ (`PayloadTooLargeException`)
- 415 on unsupported media: ✅ (`HttpMediaTypeNotSupportedException`)
- 406 on not acceptable: ✅ (`HttpMediaTypeNotAcceptableException`)

**Pagination:**
- Cursor-based with `(since, …]` interval: ✅
- nextCursor = seq of last item on page: ✅
- Cursors described in OpenAPI: ⚠️ Present in comments but not formalized as schema

### 2. SSE Stream Perspective

**Connection sequence (Task 8.5):**
1. `retry: 5000` header sent first
2. `session.status` snapshot (runtimeStatus + lastTurnOutcome)
3. Backfill of messages for `(cursor, …]`
4. Live delivery via subscription

**Reconnect:**
- `Last-Event-ID` header supported and prioritized over `?since=`
- No duplicate or missing messages on reconnect
- `session.status` sent on reconnect too

**Timeouts:**
- SseEmitter timeout now configurable via `SseProperties.timeout`
- 0 means no timeout, client-side reconnect required
- Test profile uses 200ms ping interval

**404 behavior:**
- Unknown session returns 404 with Problem Details
- Stream never established

### 3. Tests

**Coverage:**
- 7 SSE tests: retry, snapshot, backfill, filtering, reconnect with Last-Event-ID, live delivery, ping, 404
- 15 error handling tests covering all error codes
- Message API tests: accept, wake, validation, pagination, compact

**Keycloak integration:**
- `keycloakToken()` used in all API tests
- Self-signed foreign token tested for 401
- BaseApplicationTest runs with real DB + Keycloak (no stubbed SSO)

**Potential stubs:**
- `MessagesApiTest.sendMessageWakesTurnFasterThanPollInterval` doesn't assert final ASSISTANT text (commented as known issue)

### 4. Issue: 202 Empty Body → 406 on Accept: application/json

**Current behavior:**
- OpenAPI spec declares 202 response without content schema
- Generated client expects `SendMessageAccepted` body
- If server returns empty body on 202, client deserializes to null

**Impact:**
- Breaks clients expecting identifiers (`messageId`, `seq`) in response
- Test `MessagesApiTest.sendMessageReturnsAcceptedIdentifiersWithAuthorFromJwt` passes because actual implementation sends body

**Minimal spec fix:**
```yaml
'202':
  description: Message accepted; identifiers returned.
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/SendMessageAccepted'
```
Currently spec has no content schema for 202.

### 5. Issue: Final ASSISTANT with Empty Text

**Current behavior:**
- `MessagesApiTest.sendMessageWakesTurnFasterThanPollInterval` line 130-132:
  - Test asserts `payload` is not empty but doesn't check `text` field
  - Comment: "Текст финального ASSISTANT не ассертим: пустой text стрим-агрегации — артефакт движка"

**Impact:**
- Attach client sees empty ASSISTANT response text
- User-facing issue: completion results not visible
- Root cause: stream aggregation in turn engine (Task D)

---

## Summary

**Test stats:** 198/0/0 (as provided)  
**Files reviewed:** 14 API controllers/tests + OpenAPI spec

### Top 3 Concerns

1. **Empty ASSISTANT text** (Issue 5): User-facing defect; completion content lost
2. **202 response schema** (Issue 4): Spec doesn't declare content; client may get null
3. **Pagination cursors**: Not formalized in OpenAPI schema, only in comments

### Positions

**Issue 4 (202/406):**
- Breaking for clients expecting identifiers in 202 response
- Minimal fix: Add `SendMessageAccepted` schema reference to 202 response in OpenAPI
- Implementation already sends body; only spec frozen

**Issue 5 (empty ASSISTANT):**
- Direct user impact: attach shows blank completion
- Requires turn engine stream aggregation fix (Task D legacy)
- Should be addressed in M3 or as hotfix if MVP requires visible results

## Fixes approval

- **E-R-1 (202 response schema):** Accepted. Compact/stop are Void; only sendMessage returns SendMessageAccepted.
- **E-R-2 (Empty ASSISTANT):** Accepted. Fix applied in code; tests now assert text.
- **Spec 202 (empty schema):** Accepted per GLM/DS variant.
