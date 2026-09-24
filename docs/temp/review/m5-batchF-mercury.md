# M5 Batch F — Mercury Review

**Date:** 2026-09-24  
**Batch:** M5 (Web Desktop), batch F — e2e/smoke/docs/archive  
**Base:** edbfd2b (archived)  
**Reviewer:** Mercury-2.5

---

## Verdict: **APPROVE**

**Summary:** All focus areas pass. Stub-server faithfully implements §3.1/§3.2/§5 wire contract with negative-path assertions. E2E bypass is prod-safe via env gating. Docs are in sync with apply-notes. Minor spec-deviation findings are infra-only and do not block approval.

---

## Focus Areas

### 1. stub-server.ts Implementation vs api-contracts.md

| Contract | stub-server.ts | Verdict | Notes |
|----------|----------------|---------|-------|
| **§3.1 SSE** (`/sessions/{id}/events`) | ✓ `session.status` snapshot at connect (line 190–207) | PASS | |
| **§3.1 SSE `retry`** | — missing `retry: 5000` in frames (line 179–188) | MINOR | Infra stub only; client-side SSE client handles `sseRetryDefaultMs` |
| **§3.1 SSE ping** | — missing periodic `: ping` comment | MINOR | Infra stub only; real server sends ping per `relay.heartbeat-interval` |
| **§3.2 Task SSE** | ✓ `task.status` snapshot at connect (line 436–459) | PASS | |
| **§5 WS handshake** | ✓ hello→welcome (lines 533–543), 4401 for bad token (line 501) | PASS | |
| **§5 register** | ✓ registered + 4409 codes (lines 546–583) | PASS | |
| **§5 tool.result** | ✓ echo → final ASSISTANT (lines 586–643) | PASS | |
| **§5 tool.cancel** | ✓ no-op acknowledgment (lines 645–648) | PASS | Spec: just acknowledge |
| **Close 4403** | — missing for protocol errors | MINOR | Infra stub only; real WS handler validates hello-first |

**Conclusion:** Stub implements full wire contract surface (§5 + §3.1/§3.2). Minor omissions (retry, ping, 4403) are infra-only stub simplifications; production server handles them.

---

### 2. E2E Assertions Coverage

| Test File | Coverage | Verdict |
|-----------|----------|---------|
| `web-desktop/tests/e2e/stub-server.spec.ts` | REST (sessions, messages) + WS relay (hello, register, tool.result, 4401, 4409) + SSE (session.status, USER, TOOL_CALL, TOOL_RESULT, ASSISTANT) + Workspace file D-72 path validation (lines 142–159) | **PASS** |

**Verdict:** E2E tests cover wire contract **and** negative paths. Not happy-path only:
- 4401 for bad token (line 220–232)
- 4409 superseded (line 199–217)
- D-72 path traversal rejection (line 157–159)

---

### 3. HARNESS_E2E_* Bypass in token-store

**File:** `web-desktop/src/main/token-store.ts` (lines 46–53)

```typescript
if (process.env['HARNESS_E2E_ENABLED'] === '1' && process.env['HARNESS_E2E_TOKEN']) {
  return { accessToken: process.env['HARNESS_E2E_TOKEN'], expiresAt: ... };
}
```

**Verdict:** **SAFE for prod**  
- Gated by two env vars: `HARNESS_E2E_ENABLED=1` AND `HARNESS_E2E_TOKEN`  
- Neither env var is set in production (dev-only testing)  
- Fallback to `safeStorage` (D-91) when bypass inactive

---

### 4. Docs Sync

| Document | Status | Notes |
|----------|--------|-------|
| `docs/design/decisions.md` | **PASS** | D-86…D-93 present; D-88 has owner-risk-appruv |
| `docs/design/web-desktop-client.md` | **PASS** | Supersede block for client-cli.md (line 3); content matches apply-notes |
| `docs/design/api-contracts.md` §2 | **PASS** | MessageKind(+ASYNC_ACCEPTED), SessionDto (taskId/stateCode/parentSessionId in /tree only), TreeNode(+stateCode?) — doc-fix without server changes |
| `api/openapi.yaml` | **PASS** | Used for TS type generation (D-89); no drift detected |

---

### 5. apply-notes.md

**File:** `openspec/changes/archive/2026-09-24-m5-web-desktop/apply-notes.md`

**Verdict:** **PASS**  
- Accurately summarizes batches A–F  
- Test counts match (166 unit tests, 19 files)  
- Docs sync changes documented (§2 doc-fix, supersede client-cli.md)  
- Archive validation passed (`openspec validate --strict`)

---

### 6. AGENTS.md

**File:** `AGENTS.md`

**Verdict:** **PASS**  
- M5 marked complete  
- Evolution roadmap updated (runners, selective compaction, MCP-server-out, adapters)

---

## Findings Summary

| Severity | File:Line | Finding | Action |
|----------|-----------|---------|--------|
| MINOR | stub-server.ts:179–188 | Missing `retry: 5000` in SSE frames | Infra-only; real server handles |
| MINOR | stub-server.ts:514–522 | Missing periodic `: ping` SSE comment | Infra-only; real server handles |
| MINOR | stub-server.ts:528–531 | Missing close 4403 for protocol errors | Infra-only; real WS handler validates hello-first |

**Note:** All findings are stub-specific simplifications. They do not affect production server correctness or e2e test validity.

---

## Appendix: E2E Test Matrix

| Scenario | File | Result |
|----------|------|--------|
| Full SSE flow (WS → tool.result → ASSISTANT) | stub-server.spec.ts:19–140 | ✓ |
| Workspace file download D-72 | stub-server.spec.ts:142–159 | ✓ |
| WS relay 4401/4409 | stub-server.spec.ts:161–237 | ✓ |

---

**Review complete. APPROVED.**
