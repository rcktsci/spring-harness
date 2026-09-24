# M5 Batch E Review — Mercury-2.5

**Date:** 2026-09-23  
**Base:** 4190384  
**Scope:** openspec/changes/m5-web-desktop tasks.md 5.1–5.4, specs/desktop-session-tree, specs/desktop-artifacts  
**Status:** **APPROVE** (with minor notes for Batch F)

---

## Summary

Batch E implements session tree, task panel, and artifact handling for Web Desktop. The implementation respects the architecture boundaries (main holds tokens & REST clients, renderer accesses via IPC), implements UX-only path validation (server D-72 remains the security boundary), and provides basic error handling with cache cleanup.

All test files use proper mocks and cover happy-path scenarios. Missing coverage exists for several fail-paths that should be addressed before M5 archival.

---

## Findings

### 1. Architecture & Security

| Severity | Location | Finding | Action |
|----------|----------|---------|--------|
| LOW | `web-desktop/src/preload/index.ts:116–122` | Artifact IPC (`artifact.open`, `artifact.download`) correctly delegated to main process | **No action** — architecture respected |
| LOW | `web-desktop/src/main/artifact.ts:12–19` | Token & fetch logic isolated in main (`rest-client.ts`) | **No action** — D-91 respected |
| LOW | `web-desktop/src/renderer/src/lib/path-utils.ts:26–42` | Renderer path validation (normalizeRelativePath) duplicates main logic; shared via test mocks | **NOTE:** Consider extracting shared validation to `shared/` to avoid duplication and ensure parity |

### 2. Reliability

| Severity | Location | Finding | Action |
|----------|----------|---------|--------|
| MEDIUM | `web-desktop/src/main/artifact.ts:178–182` | `shell.openPath` error handling throws generic `Error` without distinguishing OS-specific errors (e.g., file removed after cache copy) | **NOTE:** Add structured error type or code for UI differentiation |
| LOW | `web-desktop/src/main/artifact.ts:90–96` | Cache prune uses `mtimeMs` (modified time) rather than creation time; on Windows, file replacement updates mtime | **NOTE:** Acceptable for M5 (simple cache); track for future audit |
| LOW | `web-desktop/src/main/artifact.ts:112–116` | Atomic rename: writes to `.partial` then renames; Windows rename overwrites existing file | **NOTE:** Acceptable behavior (rename = move + replace); no race condition since target path is deterministic per sessionId+path |

### 3. UX

| Severity | Location | Finding | Action |
|----------|----------|---------|--------|
| LOW | `web-desktop/src/renderer/src/views/ArtifactsView.vue:14–19` | Validation error messages are generic ("путь должен быть относительным..."); no distinction between absolute vs `..` | **NOTE:** Consider separate messages for absolute path vs `..` usage |
| LOW | `web-desktop/src/renderer/src/components/SessionTree.vue:50–55` | Task label shows `taskId` (8 chars) + `stateCode`; no human-readable task description | **NOTE:** Acceptable for M5; consider `GET /tasks/{id}` integration for full task info |
| MEDIUM | `web-desktop/src/renderer/src/components/ChatFeed.vue:74–103` | Path extraction from tool output uses heuristic (substring match); no regex anchoring to workspace-relative paths | **NOTE:** Could produce false positives for paths with common substrings |

### 4. Test Coverage

| Severity | Location | Finding | Action |
|----------|----------|---------|--------|
| HIGH | `web-desktop/tests/unit/` | No unit tests for artifact main module (`artifact.ts`) | **REJECT condition for M5 archival:** Add tests for `normalizeRelativePath`, `saveArtifactAs`, `openArtifact`, `pruneCache` |
| HIGH | `web-desktop/tests/unit/task-sse-client.test.ts` | No tests for malformed SSE frames (invalid JSON in data) | **NOTE:** Add malformed frame handling test |
| HIGH | `web-desktop/tests/unit/task-sse-client.test.ts` | No tests for 401/403/404/500 HTTP errors on SSE connect | **NOTE:** Add reconnect-on-error test |
| MEDIUM | `web-desktop/tests/unit/` | No tests for empty session tree response | **NOTE:** Add component test for `SessionTree` with empty `items` |
| MEDIUM | `web-desktop/src/renderer/src/composables/useTask.ts:100–140` | SSE frame parsing in `useTask` silently ignores malformed JSON (try/catch at 137–139) | **NOTE:** Add error logging when frame parsing fails |

### 5. Batch F Implications

| Severity | Location | Finding | Action |
|----------|----------|---------|--------|
| MEDIUM | `web-desktop/src/main/task-sse.ts` & `web-desktop/src/main/sse.ts` | Session and task SSE clients have duplicated SSE stream handling (fetch + ReadableStream + parser + reconnect) | **NOTE for F:** Consider extracting shared `SseClient` base class |
| HIGH | `web-desktop/src/renderer/src/composables/useTask.ts` & `web-desktop/src/renderer/src/composables/useChat.ts` | Task SSE and session SSE are handled separately in composables; no unified `onSseEvent` pattern | **NOTE for F:** Align status-trigger handling to enable shared composable logic |

---

## Verdict

**APPROVE** for development continuation.

**Blocks for M5 archival (Batch F):**
1. Add artifact module unit tests (`main/artifact.ts`)
2. Add malformed SSE frame handling test
3. Add HTTP error handling tests for SSE clients

**Notes for Batch F:**
- Extract shared SSE client logic
- Align composable patterns for cross-composable status-trigger
- Consider shared path validation in `shared/`

---

## Files Inspected

- `openspec/changes/m5-web-desktop/tasks.md`
- `openspec/changes/m5-web-desktop/specs/desktop-session-tree/spec.md`
- `openspec/changes/m5-web-desktop/specs/desktop-artifacts/spec.md`
- `web-desktop/src/main/task-sse.ts`
- `web-desktop/src/main/artifact.ts`
- `web-desktop/src/main/sse-parser.ts`
- `web-desktop/src/main/rest-client.ts`
- `web-desktop/src/preload/index.ts`
- `web-desktop/src/renderer/src/composables/useSessionTree.ts`
- `web-desktop/src/renderer/src/composables/useTask.ts`
- `web-desktop/src/renderer/src/components/SessionTree.vue`
- `web-desktop/src/renderer/src/components/TaskPanel.vue`
- `web-desktop/src/renderer/src/components/ChatFeed.vue`
- `web-desktop/src/renderer/src/views/ArtifactsView.vue`
- `web-desktop/src/renderer/src/lib/path-utils.ts`
- `web-desktop/tests/unit/`

## Re-approval

**Date:** 2026-09-24  
**Trigger:** Kimi-reviewer fix round + cheap batch-F notes addressed

### Changes Verified

| Finding | Fix | Status |
|---------|-----|--------|
| HIGH: No tests for artifact main module | `tests/unit/artifact.test.ts` — 18 tests covering `safeBasename`, `validateClientPath`, `isRelativeArtifactPath`, `ensureCacheDir`, `writeToCache`, `pruneCache`, `openArtifact`, `saveArtifactAs` | **Fixed** |
| HIGH: No tests for malformed SSE frames | `tests/unit/sse-parser.test.ts` — 5 tests: garbage bytes, partial line at flush, empty `id:`, unknown field, non-numeric retry | **Fixed** |
| HIGH: No tests for HTTP errors on SSE connect | `tests/unit/sse-client.test.ts` — 3 tests: 401, 500, 204 with reconnect logic | **Fixed** |
| MEDIUM: Duplicate SSE stream handling | `src/main/sse-stream.ts` extracted; `sse.ts` + `task-sse.ts` now wrap shared `SseStream` | **Fixed** |
| Kimi notes (ChatView route.query, pollInterval, path-utils exit/dead field) | Closed by fix round | **Fixed** |

### Verification

- `pnpm verify` — 166/166 pass  
- `pnpm lint` — clean  
- `pnpm typecheck` — clean  
- `pnpm build` — clean

---

## Verdict (Re-approval)

**APPROVE** — M5 Batch E ready for archival.

All archival blockers resolved. Test coverage sufficient for main module (artifact), SSE parser (malformed input), and SSE client (HTTP errors). Shared `SseStream` extracted to eliminate duplication. Ready for `openspec archive m5-web-desktop`.

---

## Files Inspected (Updated)

- `openspec/changes/m5-web-desktop/tasks.md`
- `openspec/changes/m5-web-desktop/specs/desktop-session-tree/spec.md`
- `openspec/changes/m5-web-desktop/specs/desktop-artifacts/spec.md`
- `web-desktop/src/main/sse-stream.ts`
- `web-desktop/src/main/sse.ts`
- `web-desktop/src/main/task-sse.ts`
- `web-desktop/src/main/artifact.ts`
- `web-desktop/src/main/sse-parser.ts`
- `web-desktop/src/main/rest-client.ts`
- `web-desktop/src/preload/index.ts`
- `web-desktop/src/renderer/src/composables/useSessionTree.ts`
- `web-desktop/src/renderer/src/composables/useTask.ts`
- `web-desktop/src/renderer/src/components/SessionTree.vue`
- `web-desktop/src/renderer/src/components/TaskPanel.vue`
- `web-desktop/src/renderer/src/components/ChatFeed.vue`
- `web-desktop/src/renderer/src/views/ArtifactsView.vue`
- `web-desktop/src/renderer/src/lib/path-utils.ts`
- `web-desktop/tests/unit/artifact.test.ts`
- `web-desktop/tests/unit/sse-parser.test.ts`
- `web-desktop/tests/unit/sse-client.test.ts`
