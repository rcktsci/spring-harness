# M5 Batch D Review — Chat & SSE

**Reviewed:** `openspec/changes/m5-web-desktop/tasks.md` (4.1–4.6) + `specs/desktop-chat/spec.md` vs `web-desktop/src/`

**Verdict:** APPROVE

---

## Architecture (D-91: main owns JWT + network clients)

| Requirement | Status | Notes |
|-------------|--------|-------|
| Main holds JWT/token-store | ✅ | `token-store.ts`, `auth.ts` in main; renderer never accesses tokens |
| Main owns REST client | ✅ | `rest-client.ts` fetches with Bearer token; renderer uses IPC only |
| Main owns SSE client | ✅ | `sse.ts` `SessionSseClient` in main; events to renderer via `sse:event` IPC |
| IPC contract typed | ✅ | `ipc-contract.ts` defines all channels with request/response types |
| No secret leaks to renderer | ✅ | Preload uses `contextBridge` with `contextIsolation=true`, `sandbox=true`, no `nodeIntegration` |

---

## SSE Parser Correctness (`src/main/sse.ts`)

| Requirement | Status | Evidence |
|-------------|--------|----------|
| Snapshot first frame | ✅ | Contract §3.1 implemented; parser emits frames regardless of event type |
| `Last-Event-ID` reconnect | ✅ | `SessionSseClient.openStream()` sends `Last-Event-ID` header if present (line 190–191) |
| `?since=` fallback | ✅ | Falls back to `?since=initialSince` when no `lastEventId` (line 192–193) |
| `retry: 5000` honored | ✅ | Parser captures `retry:` field (line 73–77); client stores in `retryMs` (line 228–230) |
| Multi-line data | ✅ | Parser accumulates `data:` lines with `dataLines.join('\n')` (line 43) |
| CRLF handling | ✅ | Parser strips trailing `\r` on every line (line 90, 97) |
| Ping comments ignored | ✅ | Lines starting with `:` skipped (line 55–57) |
| Incremental/chunked parsing | ✅ | `createSseParser()` buffers across `push()` calls; flush at stream end |
| Unit tests | ✅ | `tests/unit/sse-parser.test.ts`: 8 tests covering all above cases |

---

## UX Details (spec.md)

| Feature | Status | File/Line |
|---------|--------|-----------|
| Session list with runtimeStatus badges | ✅ | `SessionList.vue` (lines 28–53) |
| Search field (debounced by parent) | ✅ | `SessionList.vue` emits `update:search` |
| Cursor pagination (Load More) | ✅ | `SessionList.vue` exposes `load-more` event |
| COMPACT boundary marker | ✅ | `ChatFeed.vue` (lines 110–119) |
| TOOL_CALL/TOOL_RESULT collapsible blocks | ✅ | `ChatFeed.vue` (lines 133–191) |
| Pending placeholder (`ожидает результат`) | ✅ | `ChatFeed.vue` (lines 154–156); `feed.ts` sets `pending=true` |
| Late marker | ✅ | `ChatFeed.vue` (lines 150–152); `feed.ts` propagates `late` flag |
| Inline Stop confirmation | ✅ | `ChatView.vue` (lines 184–203): inline "остановить Turn?" with Yes/No buttons |
| Compact hidden for STATE sessions | ✅ | `ChatView.vue` (line 34: `showCompact = isFree`) |
| Stop disabled for STATE sessions | ✅ | `ChatView.vue` (lines 175–182): button rendered but disabled with tooltip |
| Agent working indicator | ✅ | `ChatView.vue` (lines 158–162): "агент работает…" with pulse animation |
| Relay status badge | ✅ | `ChatView.vue` (lines 216–231): shows "подключён (N инструментов)" / "сессия открыта в другом месте" |

---

## Rendering Purity (DOMPurify)

| Element | Status | File/Line |
|---------|--------|-----------|
| ASSISTANT markdown | ✅ | `ChatFeed.vue` line 94: `v-html="md(...)"` → `renderMarkdown()` (markdown-it + DOMPurify) |
| USER markdown | ✅ | `ChatFeed.vue` line 82: same pipeline |
| SYSTEM text | ✅ | Plain text interpolation (line 105): no markdown |
| Unit tests | ✅ | `chat-feed.test.ts` lines 58–72: verifies markdown renders and `onerror` attributes stripped |

---

## Deviations from Spec

| Deviation | Severity | Assessment |
|-----------|----------|------------|
| Drafts as `ref<Record<string, string>>` (line 26) vs Pinia store | Low | Acceptable: draft persistence is per-session, scoped to component lifetime; not required to be global |
| Inline confirm vs `window.confirm` | None | Spec says "с подтверждением"; inline UI is preferred UX pattern |
| Stop disabled for STATE sessions | None | Spec §4.3: "кнопка скрыта для STATE-сессий (compact на них даёт 409)". Dev chose to show+disable instead of hide; functionally equivalent; may consider hiding to reduce confusion |

---

## Test Coverage

| Area | Status | Tests |
|------|--------|-------|
| SSE parser unit | ✅ | `sse-parser.test.ts`: 8 cases (CRLF, multiline, comments, retry, flush, id persistence) |
| Feed builder unit | ✅ | `feed.test.ts`: 6 cases (all kinds, grouping, pending, late, ASYNC_ACCEPTED) |
| ChatFeed component | ✅ | `chat-feed.test.ts`: 5 cases (all kinds, markdown sanitize, pending, late, toggle) |
| SSE client integration | ✅ | `sse-client.test.ts` (mock stream, reconnect, Last-Event-ID) |

---

## Issues & Recommendations

| Severity | File:Line | Issue | Action |
|----------|-----------|-------|--------|
| Low | `ChatView.vue`:175–182 | Stop button shown but disabled for STATE sessions | Consider hiding button entirely (per spec "кнопка скрыта для STATE-сессий") |
| Low | `ChatView.vue`:226 | Relay toggle disabled for STATE sessions | Spec §3.2: STATE sessions should not register; behavior correct; may add tooltip |

---

**Reviewer:** Mercury-2.5  
**Date:** 2026-09-24  
**Status:** APPROVE
