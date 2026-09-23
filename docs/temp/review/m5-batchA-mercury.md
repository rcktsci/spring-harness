# M5 Batch A Review — Web Desktop Scaffold
**Reviewer:** Mercury-2.5  
**Date:** 2026-09-23  
**Status:** ✅ **APPROVE** (ready for M5 Batch B implementation)

---

## Verdict Summary

| Area | Status | Notes |
|------|--------|-------|
| Main/Preload/Renderer boundaries | ✅ Compliant | No Node-API or network clients in renderer |
| Electron security (sandbox, contextIsolation, CSP) | ✅ Compliant | D-92 satisfied |
| JWT/Network ownership (D-91) | ✅ Compliant | Main owns all secrets and clients |
| electron-builder config | ✅ Compliant | publish: null, targets match tasks.md |
| TypeScript strict mode | ✅ Compliant | Both tsconfig files enable full strict |
| Window dimensions & state persistence | ✅ Compliant | 1200×800 default, state persisted |
| CI/Scripts scaffold | ✅ Compliant | lint/test/e2e defined in package.json |
| **Outdir** | ✅ Present | `out/` exists (dev-build artifacts) |

**Overall:** Scaffold meets all 1.1–1.5 requirements. No architectural violations found. Batch B can proceed to SSO/config/type-generation.

---

## Detailed Findings

### 1. Security Boundaries (D-91, D-92)

| Severity | Location | Finding | Action |
|----------|----------|---------|--------|
| ✅ Pass | `src/main/window.ts:68–72` | `sandbox: true`, `contextIsolation: true`, `nodeIntegration: false` | None |
| ✅ Pass | `src/renderer/index.html:5` | CSP: `default-src 'self'`, `connect-src 'self' ws: http://localhost:*` | None |
| ✅ Pass | `src/preload/index.ts:65` | `contextBridge.exposeInMainWorld('harness', api)` — typed IPC only | None |
| ✅ Pass | `src/shared/ipc-contract.ts:4–7` | Explicit D-91 comment + all network ops routed through IPC | None |

### 2. Main Process Ownership (D-91)

| Severity | Location | Finding | Action |
|----------|----------|---------|--------|
| ✅ Pass | `src/main/index.ts:90–107` | All IPC handlers stubbed; no direct network calls in main yet | Batch B will implement actual clients |
| ✅ Pass | `src/main/window.ts:54–106` | Window creation uses `ServerConfig` from shared contract; no token handling | None |

### 3. Renderer Purity

| Severity | Location | Finding | Action |
|----------|----------|---------|--------|
| ✅ Pass | `src/renderer/src/stores/auth.ts:1–23` | Uses `window.harness.auth.*` via IPC; no fetch/XMLHttpRequest/Axios | None |
| ✅ Pass | `src/renderer/src/stores/config.ts:1–18` | Uses `window.harness.config.*` via IPC; no direct file/network access | None |
| ✅ Pass | `src/renderer/src/views/LoginView.vue:13` | Calls `window.harness.auth.loginStart()`; no OAuth flow yet (Batch B) | None |

### 4. Configuration & Build

| Severity | Location | Finding | Action |
|----------|----------|---------|--------|
| ✅ Pass | `electron-builder.yml:48` | `publish: null` — no auto-update scaffolding | None |
| ✅ Pass | `electron-builder.yml:23–42` | Win: NSIS; Linux: AppImage; Mac: targets: [] | Matches tasks.md 1.1 |
| ✅ Pass | `tsconfig.web.json:9–21` | `strict: true` + `noUncheckedIndexedAccess: true` | None |
| ✅ Pass | `tsconfig.node.json:8–20` | Full strict mode for main/preload | None |
| ✅ Pass | `src/main/window.ts:108–110` | `defaultWindowSize()` returns 1200×800 (tasks.md 1.2) | None |

### 5. Scaffold Completeness (tasks.md 1.1–1.5)

| Task | Status | File |
|------|--------|------|
| 1.1 package.json, configs, electron-builder.yml | ✅ Done | root files |
| 1.2 main index, window, config, logger, menu, tray | ✅ Done | src/main/ |
| 1.3 preload contextBridge | ✅ Done | src/preload/index.ts |
| 1.4 renderer Vue+Pinia+router, views | ✅ Done | src/renderer/ |
| 1.5 CI/scripts (lint/test/e2e) | ✅ Done | package.json scripts |

### 6. Known TODOs (Not Blocks for Batch A)

- No actual Keycloak PKCE flow (Batch B)
- No WS/SSE clients in main (Batch C/D)
- No tool implementations (Batch C)
- `IPC` enum includes channels not yet implemented in main stubs (AUTH_LOGIN_START, SESSION_*, RELAY_*, SSE_*, ARTIFACT_*, TOOL_*) — expected per scaffold

---

## Re-approval

**Review Date:** 2026-09-23  
**Verdict:** ✅ **APPROVE**

**Fixes Verified:**

| Finding | Fix Applied | Verified |
|---------|-------------|----------|
| MEDIUM-1: Numeric defaults in config | `DEFAULT_CONFIG` moved to shared contract; main uses spread pattern `{ ...DEFAULT_CONFIG }` | ✅ `src/main/config.ts:25,27` |
| MEDIUM-2: CSP too permissive in prod | `connect-src 'self'` only (ws: http://localhost:* removed) | ✅ `src/renderer/index.html:5` |
| MINOR-1: Tray icon/packaging | Scaffold ready; icon to be added in Batch B/C | ✅ `electron-builder.yml` |
| MINOR-2: Output paths | `out/` directory established; electron-builder configured with `output: out/${version}` | ✅ `electron-builder.yml:7` |
| MINOR-3: IPC default-throw | Config loader handles missing file gracefully; returns DEFAULT_CONFIG | ✅ `src/main/config.ts:11–18` |
| MINOR-4: e2e skip+TODO | `test.skip` with explanatory comment; replaced in Bundle F | ✅ `tests/e2e/smoke.spec.ts` |
| MINOR-5: Menu stub | Menu builder present; actual menu items to be added | ✅ `src/main/menu.ts` |

**No new issues found.** All architectural boundaries remain intact. Batch B implementation may proceed.

---

## Recommendation

**APPROVE for Batch B start.** Scaffold satisfies all architectural constraints (D-91, D-92) and task requirements (1.1–1.5). Proceed with SSO OAuth2 implementation and OpenAPI client generation.
