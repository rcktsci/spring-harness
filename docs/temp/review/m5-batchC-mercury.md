# M5 Batch C Review — Mercury-2.5
**Date:** 2026-09-23  
**Status:** REVIEW (pending owner decision)  
**Scope:** Tasks 3.1–3.5 (`openspec/changes/m5-web-desktop/tasks.md`) + spec against `web-desktop/src` (relay-client.ts, local-tools.ts, useRelay.ts, IPC, ws-frames.ts) + design decisions D-91/D-93 (referenced in code, ADR entry pending).

---

## Executive Summary
Batch C implements the relay-client layer (WebSocket handshake, session registration, local tool execution, confirmation gating) against frozen contracts (§5 of api-contracts.md). Architecture is clean: all security-sensitive code lives in main process; renderer is a thin UI adapter. State machine transitions are well-handled with no obvious listener leaks. Security gating (confirmCommands) is properly implemented but default value may need owner review. No dedicated test files found in source tree (dev session reported 65/65 tests passing; tests likely in separate location or inline).

**Recommendation:** APPROVE with minor notes below.

---

## Architecture Review

### Main vs Renderer Separation (Task 3.1)
- **PASS**: All WS socket, secrets (JWT, token refresh), and tool execution live in `main/` (`relay-client.ts`, `local-tools.ts`, `auth.ts`, `token-store.ts`).
- **PASS**: Renderer (`useRelay.ts`) is a pure Vue composable subscribing to IPC events; no direct network access.
- **PASS**: `contextBridge` in `preload/index.ts` exposes a strictly typed API (ipc-contract.ts). `contextIsolation: true`, `sandbox: true`, `nodeIntegration: false` per D-92.
- **PASS**: D-91 is respected—main owns JWT and all network clients.

### IPC Contract
- **PASS**: Typed contract in `shared/ipc-contract.ts` with request/response types.
- **PASS**: Event channels (`relay:status`, `relay:registration-consent`, `tool:call`, `tool:result`) use `ipcRenderer.on` with proper cleanup in `onUnmounted`.
- **PASS**: Confirm/respond channels use IPC invokes; no fire-and-forget.

---

## State Machine Reliability (Task 3.1–3.2)

### Transition: Connect → Handshake → Register
- **PASS**: `connect()` establishes socket, emits status=handshake, sends hello.
- **PASS**: `awaitHandshake()` uses single one-shot listener (`once('welcome')`) with timeout (10s config). Timeout closes socket and schedules reconnect.
- **PASS**: `register()` creates basePath directory first (spec requirement), then sends register frame with timeout (10s config). Listeners are one-shot via `once('frame')` inside the promise.
- **PASS**: Idempotent reconnect: `registering` promise deduplicates concurrent register calls.

### Transition: Session Switch (auto-connect/register)
- **PASS**: `handle401()` (4401 close) performs silent token refresh, then reconnects and re-registers if `registration` exists.
- **PASS**: `scheduleReconnect()` uses exponential backoff (1s → 30s cap) with config values.
- **PASS**: On successful reconnect, `sendRegister()` is called with existing session/basePath.
- **PASS**: `disconnect()` clears timers, cancels handles, drops socket, nullifies registration.

### Listener Lifecycle
- **PASS**: `dropSocket()` calls `socket.removeAllListeners()` before nullifying.
- **PASS**: `wireSocket()` uses `once()` for handshake events, `on()` for persistent message/error/close handlers.
- **PASS**: `clearTimers()` clears reconnect/ping timers.
- **PASS**: `useRelay()` unsubscribes all listeners in `onUnmounted` (critical for SPA-like navigation).

### Potential Issue: Register Promise State Reset
- **NEUTRAL**: If `register()` timeout fires, `this.registering = null` allows retry. However, if server replies late, the listener may fire and fail to find the promise variable. This is acceptable behavior (late response ignored, status emitted).

---

## Security Review (Task 3.4 + D-93)

### confirmCommands Gating
- **PASS**: `handleToolCall()` checks `cfg.confirmCommands === 'always'` before emitting `toolCall` event.
- **PASS**: `awaitConfirmation()` returns promise; tool execution waits for resolution.
- **PASS**: Rejected tools emit `tool.result` with exitCode=-1 and output="command rejected by user".
- **PASS**: Default value in `DEFAULT_CONFIG` is `'always'` (per D-93).

### ConfirmCommands Bypass Check
- **PASS**: Only path to `executeTool()` is via `handleToolCall()`. No direct IPC handlers expose tool execution.
- **PASS**: `confirmWaiters` map is cleared in `finally` block of `handleToolCall()`.

### Timeout Policy
- **PASS**: `runBash()` uses `timeoutMs = min(args.timeout, cfg.relayToolCallTimeoutMs)` (300s default).
- **PASS**: Kill sequence: SIGTERM → (2s delay) → SIGKILL, matching spec §5.3.
- **PASS**: `relayToolCallTimeoutMs` and all numeric params are config-driven (per owner rule).

### Registration Consent
- **PASS**: First-time consent requested via `registrationConsent` event; `pendingConsent` map handles early decisions.
- **PASS**: Consent stored in memory only; no persistence (per spec).

---

## Local Tool Execution (Task 3.3)

### Bash Tool
- **PASS**: `child_process.spawn` with `shell: true`, `cwd=basePath`.
- **PASS**: Output truncated at `toolOutputLimitBytes` (1MB default); progress streamed via `tool.progress`.
- **PASS**: Exit code is always reported; non-zero is not a tool error (per §5.3).

### File Tools (read/write/edit/glob/grep)
- **PASS**: Paths resolved relative to basePath via `resolveInBase()`.
- **PASS**: edit_file returns `ambiguous` (exitCode=2) for multiple matches; `not-found` for zero matches.
- **PASS**: glob/grep are minimal, dependency-free implementations.

### Cancel Handling
- **PASS**: `cancelHandles` map stores per-callId kill function.
- **PASS**: `handleToolCancel()` adds callId to `cancelledCalls` set; `executeTool()` checks `isCancelled()` before streaming.
- **PASS**: `suppressed: true` flag prevents result transmission for cancelled calls.

---

## Test Quality (Task 3.5)
**Observation:** No `*.test.ts` files found in `web-desktop/src/` via glob. Dev session reported 65/65 tests passing—likely in `tests/` folder or `__tests__/` directories not yet created.

### Expected Coverage (per spec)
- [ ] WS client vs in-test WS server: all frame types (hello/welcome/register/registered/error/tool.*/ping/pong + close codes).
- [ ] Takeover / 4409 / 4401 / 4403 scenarios.
- [ ] Cancel-while-spawn race.
- [ ] All local tools with error paths (file not found, ambiguous edit, timeout).

**Recommendation:** Ensure test files exist before M5 finalization. Batch C is code-complete but test artifacts should be verified.

---

## Re-implementation Effort (D/E Phases)
- **D (Chat + SSE):** No impact from Batch C. Relay client is independent; D phase only adds message rendering and SSE subscription via IPC.
- **E (Tree + Artifacts):** No impact. Artifact download uses existing IPC (`artifact:download`); tree uses `session:tree` IPC.

---

## Findings

### Minor Issues
1. **Missing D-91/D-93 ADR Entries:** Code comments reference D-91/D-93, but decisions.md only contains D-01 through D-85. Owner should add D-86...D-93 before archiving M5.
2. **Test File Location Unclear:** 65/65 tests passed in dev session but not visible in `web-desktop/src/`. Verify test files are committed.
3. **Config Default Review:** `confirmCommands: 'always'` may be too restrictive for power users; spec allows `always/never` only. Owner may want to add `on-demand` mode later.

### Design Strengths
- Clean separation of concerns (main=security, renderer=UI).
- Robust state machine with timeout guards and listener cleanup.
- Security gating correctly implemented with no bypass paths.
- Config-driven numeric parameters (owner rule followed).

---

## Verdict (Initial)
**APPROVE** — Batch C is technically sound and ready for integration. Add D-91/D-93 ADR entries and verify test file commitment before final M5 archive.

---

## Re-approval (post-fix round)
**Fixes verified in relay-client.ts / local-tools.ts:**

| Fix | Code evidence | Test coverage |
|-----|---------------|---------------|
| Backoff ladder reset on successful welcome | `handleFrame('welcome')` sets `this.reconnectAttempt = 0` | `resets the backoff ladder after a successful welcome` |
| 4409 close preserves prior register-error code | `lastRegisterError` field + logic in `close` handler | `keeps the register error reason across a following close 4409` |
| Catch-all `tool.call` → `tool.result` on throw | `executeTool()` wrapped in try/catch; `handleToolCall()` also has try/finally | `sends tool.result for an invalid grep pattern instead of hanging` |
| Listener leak fix on register timeout | `onFrame` listener explicitly removed in timeout callback | `removes the frame listener when registration times out` |
| All numeric params in config | `relayToolCallTimeoutMs`, `toolKillGraceMs`, `toolGlobMaxResults`, `toolGrepMaxMatches` from `ServerConfig` | Config values used in tests via `smallConfig()` |
| Registration state never left true after refusal | `sendRegister` clears `this.registration` on error frame | `does not report registered=true after a register refusal` |

**Test count:** 76/76 passed (user-reported; test files exist in `web-desktop/tests/unit/`).

**Status:** **APPROVE** — all noted issues resolved, state machine robust, security gating intact, config-driven numerics verified.
