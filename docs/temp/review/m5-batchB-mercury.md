# M5 Batch B Review (Web Desktop — SSO/Config/Types)

**Review target:** tasks 2.1–2.4a against `web-desktop/src/`  
**Base commit:** 7dd8c65  
**Reviewer:** Mercury-2.5  
**Date:** 2026-09-23

## Verdict: APPROVE

Batch B is production-ready. Architectural boundaries (main↔preload↔renderer) are strict; token storage never falls back to plain text; the generated OpenAPI types cover the REST surface needed for batches C–E; unit tests are thorough and aligned with the RFC/contract.

---

## Findings

| Severity | File:Line | Issue | Action |
|----------|------------|-------|--------|
| **Info** | `web-desktop/src/main/auth.ts:147–158` | Login window explicitly sets `sandbox: true, contextIsolation: true, nodeIntegration: false` and relies on preload's typed `contextBridge`. This satisfies D-91 (main owns JWT + network clients) and D-92 (CSP, sandboxed renderer). | — |
| **Info** | `web-desktop/src/main/token-store.ts:35–38,62–66` | `assertSafeStorage()` enforces OS keychain before any I/O; throws `SafeStorageUnavailableError` when unavailable. Spec requirement "safeStorage недоступен — отказ с понятным сообщением" is met. | — |
| **Info** | `web-desktop/src/main/ws-frames.ts:13–17,89–130` | Manual frame types mirror api-contracts §5; `parseRelayFrame` returns `undefined` for malformed/client-only frames; close codes (4401/4403/4409) and registration errors are enumerated. Unit tests in `ws-frames.test.ts` cover all frame types and error codes. | — |
| **Info** | `web-desktop/src/api/generated/openapi.d.ts:1–950+` | Types generated via openapi-typescript from `src/main/resources/api/openapi.yaml` include `MessageKind` (with ASYNC_ACCEPTED), `SessionDto` (runtimeStatus, taskId, stateCode), `ProblemCode` (including M4 download errors). This is sufficient for batches C–E. | — |
| **Info** | `web-desktop/src/shared/ipc-contract.ts:48–66` | `DEFAULT_CONFIG` contains `confirmCommands: 'always'` (D-93 default), `tokenClockSkewSeconds`, window sizes, logging params — all numeric values are config-bound, no hardcoded literals. | — |
| **Info** | `web-desktop/tests/unit/pkce.test.ts:23–27` | Test validates RFC 7636 Appendix B test vector for PKCE challenge computation. | — |
| **Info** | `web-desktop/tests/unit/token-store.test.ts:82–87` | Test explicitly verifies that saved tokens are encrypted and never written as plaintext to disk. | — |

---

## Architecture Checklist (Main/Preload/Renderer)

| Requirement | Status | Evidence |
|-------------|--------|----------|
| Main owns secrets (JWT) | ✅ | `auth.ts`, `token-store.ts` only in main |
| Renderer never touches secrets | ✅ | `preload/index.ts` exposes only state/status via IPC |
| Context isolation + sandbox | ✅ | `auth.ts:153–157`, D-92 |
| Typed IPC contract | ✅ | `ipc-contract.ts`, `preload/index.ts` |
| Config via `ServerConfig` | ✅ | `ipc-contract.ts`, `config.ts` |

---

## Coverage vs. Tasks 2.1–2.4a

| Task | Implemented | Tests |
|------|-------------|-------|
| 2.1 Keycloak OAuth2 + PKCE | ✅ visible window, loopback redirect, silent refresh, logout | ✅ pkce.test.ts, token-store.test.ts |
| 2.2 Settings (baseUrl, Keycloak params, confirmCommands, theme) | ✅ ServerConfig + load/saveConfig | ✅ config-binding.test.ts |
| 2.3 TS types from openapi.yaml | ✅ generated/openapi.d.ts + ws-frames.ts | ✅ ws-frames.test.ts |
| 2.4 Vitest: config binding, PKCE verifier/challenge | ✅ | ✅ pkce.test.ts |
| 2.4а WS frame schema unit tests | ✅ ws-frames.test.ts | ✅ all frame types + close codes |

---

## Ready for Batches C–E

- **REST surface:** full `paths` + `components/schemas` in `openapi.d.ts`
- **WS transport:** manual types in `ws-frames.ts` with comprehensive unit coverage
- **Auth/Session flow:** silent refresh, token expiry with clock skew, safeStorage enforcement
- **Config:** all numeric parameters via `ServerConfig`

No blockers. Batch B is approved.
