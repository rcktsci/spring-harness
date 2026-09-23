# M5 Batch A Review — Web Desktop Scaffold

**Reviewer:** Qwen3.8-27B (subagent)
**Date:** 2026-09-23
**Scope:** `web-desktop/` vs `openspec/changes/m5-web-desktop/tasks.md` 1.1–1.5 and `design.md` D-86…D-93
**Method:** static review only. Build/test/lint NOT run (dev session reported `pnpm lint`/`test`/`build` pass; `out/` artifacts inspected). Reviewed `out/main/index.js`, `out/preload/index.js`, `out/renderer/index.html` as build evidence.
**Verdict:** 🚫 **REJECT** — 2 MEDIUM findings must be fixed before APPROVE (cheap fixes). All tasks 1.1–1.5 and the explicit checklist otherwise pass.

---

## Checklist (explicit items from the task)

| Item | Result | Evidence |
|------|--------|----------|
| strict tsconfig | ✅ | `tsconfig.web.json:9` `strict:true` + `noUncheckedIndexedAccess`; `tsconfig.node.json:8` same |
| `publish: null` | ✅ | `electron-builder.yml:48` |
| `showTray` default true | ✅ | `src/shared/ipc-contract.ts:53`; unit test asserts it (`tests/unit/ipc-contract.test.ts:18`) |
| single-instance | ✅ | `src/main/index.ts:18–32` (`requestSingleInstanceLock` + `second-instance` focus) |
| state persistence | ✅ | window bounds → `window-state.json` (`src/main/window.ts:29–98`); config → `config.json` (`src/main/config.ts`) |
| contextBridge contract | ✅ | `src/preload/index.ts:65`; typed `HarnessApi`; `env.d.ts:16` exposes `window.harness` |
| no secrets/tokens in renderer | ✅ | grep `token|jwt|Bearer|secret|password|localStorage` over `src/renderer/**` → only the SettingsView doc string (`SettingsView.vue:34`) |
| pnpm scripts | ✅ | `package.json:13–30` dev/build/test/e2e/lint/typecheck/verify |
| `.gitignore` (node_modules/dist/out) | ✅ | `web-desktop/.gitignore:1–3` |
| tasks.md 1.1–1.5 checkboxes match fact | ✅ | all 5 genuinely implemented (see table below) |

## tasks.md 1.1–1.5 verification

| Task | Status | Notes |
|------|--------|-------|
| 1.1 scaffold | ✅ | package.json, electron.vite.config.ts, strict tsconfig×3, eslint flat + prettier, electron-builder (win-nsis + linux AppImage, mac `[]`), `.gitignore` |
| 1.2 main | ⚠️ | index/window/menu/tray/logger/config all present. Window 1200×800 from `DEFAULT_CONFIG`; single-instance + state persistence OK; electron-log rotation via `maxSize` (verified rotation logic in `node_modules/electron-log/src/node/transports/file/index.js:49,69`). See MEDIUM-1 for hardcoded numbers. |
| 1.3 preload | ✅ | `sandbox/contextIsolation/nodeIntegration` correct (`window.ts:69–71`); CSP present; typed contract; main-side ownership structurally in place (real JWT/clients deferred to B — expected) |
| 1.4 renderer | ✅ | Vue 3 + Pinia + vue-router, router-minimal, 5 view skeletons, App shell |
| 1.5 CI/scripts | ✅ | `test` (vitest), `e2e` (playwright), `lint` present; README present. e2e is a placeholder (MINOR-4) |

## D-86…D-93 verification

| Decision | Result | Notes |
|----------|--------|-------|
| D-86 Electron+Vue+Vite+TS+Pinia | ✅ | stack matches; markdown-it/DOMPurify deferred to bundle D (allowed) |
| D-87 SSE via fetch stream | n/a | bundle D — out of scope here |
| D-88 local exec on host | n/a | bundle C — out of scope |
| D-89 openapi-typescript | n/a | bundle B — out of scope |
| D-90 pnpm + builder + targets | ✅ | pnpm scripts + win-nsis/linux-appimage + `publish:null` |
| D-91 main owns secrets/network | ✅ | renderer holds no token, opens no WS/SSE; all ops via `window.harness` IPC; main-side handlers stubbed |
| D-92 sandbox + contextIsolation + CSP | ⚠️ | `sandbox:true`, `contextIsolation:true`, `nodeIntegration:false` correct; **CSP deviates** — see MEDIUM-2 |
| D-93 confirmCommands default `always` | ✅ | `ipc-contract.ts:58` `confirmCommands:'always'`; Settings exposes always/never |

---

## Findings

### MEDIUM-1 — Hardcoded numeric parameters contradict the frozen README claim and the owner rule
**Severity:** MEDIUM
**Where:** `src/main/window.ts:63–64`, `src/main/window.ts:88`, `src/main/logger.ts:19`
**What:** The README states (lines 66–68) *"All numeric defaults … come from `DEFAULT_CONFIG` … no hardcoded magic numbers in code paths."* That is factually false:
- `window.ts:63` `minWidth: 800,` and `window.ts:64` `minHeight: 600,` — hardcoded.
- `window.ts:88` debounce `500` ms for window-state save — hardcoded.
- `logger.ts:19` `log.transports.file.maxSize = 5 * 1024 * 1024;` — hardcoded.

AGENTS.md owner rule: *"Все числовые параметры — конфиг; хардкод чисел запрещён."* These are numeric parameters living outside `DEFAULT_CONFIG`/`config.json`.
**Fix:** Add `windowMinWidth`, `windowMinHeight`, `windowStateDebounceMs`, `logMaxSizeBytes` (or an equivalent) to `ServerConfig`/`DEFAULT_CONFIG` and consume them; or, if the owner accepts them as fixed UI/tuning constants, correct README:66–68 so the frozen doc is not false. (Numerals must not stay both hardcoded *and* claimed config-driven.)

### MEDIUM-2 — CSP `connect-src ws:` is over-broad and deviates from D-92
**Severity:** MEDIUM
**Where:** `src/renderer/index.html:5`
**What:** The CSP contains `connect-src 'self' ws: http://localhost:*`. D-92 freezes *"CSP `default-src 'self'` (никаких remote-ресурсов)"*, and D-91 freezes *"renderer никогда не открывает WS/SSE напрямую"*. The bare `ws:` scheme allows a WebSocket to **any** host from the renderer — relaxing a frozen security control for no functional need in production (`loadFile`; only dev HMR needs `ws://localhost:*`). A future renderer XSS (markdown path arrives in bundle D) would gain an outbound channel despite D-91.
**Fix:** Replace `ws:` with `ws://localhost:*` (dev HMR only) and drop `http://localhost:*` where not required; ideally emit a stricter production CSP vs. a dev-tuned one (e.g. via the html transform / `session.defaultSession.webRequest` headers) so the shipped renderer permits no outbound network at all.

### MINOR-1 — `resources/` (tray icon) absent; tray falls back to an invisible empty image
**Severity:** MINOR
**Where:** `src/main/tray.ts:16–23`; README:29
**What:** `resources/tray.png` does not exist. `createTray` logs a warning and uses `nativeImage.createEmpty()`, so the tray (default on) is invisible. Graceful degradation is correct, but shipping default `showTray:true` with no icon yields a confusing empty tray. Also `electron-builder.yml` `files` does not include `resources/**`, so even a later-added icon would not be packaged (`asarUnpack: resources/**` references it, but it is never bundled).
**Fix:** Add a placeholder `resources/tray.png` and include `resources/**` in electron-builder `files` (and build/icon.ico for NSIS), or set the scaffold default bearing in mind the icon is pending.

### MINOR-2 — electron-builder output dir is nested inside the `files` glob
**Severity:** MINOR
**Where:** `electron-builder.yml:7` (`output: out/${version}`) vs `:11` (`files: - out/**/*`)
**What:** `electron-builder` writes installers under `out/<version>/`, but `files: out/**/*` sweeps everything under `out/`. On a repeated build the previous installer output can be re-included into the asar (classic electron-builder gotcha), bloating the package.
**Fix:** Point `directories.output` at a separate dir (e.g. `dist/${version}`) or add `- '!out/${version}/**'` to `files`.

### MINOR-3 — IPC contract advertises channels with no main-side handler
**Severity:** MINOR
**Where:** `src/shared/ipc-contract.ts:13–46` vs `src/main/index.ts:90–107`; consumers `LoginView.vue:13` (`auth.loginStart`), `ArtifactsView.vue:22` (`artifact.download`), plus all SESSION_*/RELAY_*/SSE_*/TOOL_* channels
**What:** `registerIpcStubs` registers only CONFIG_GET/SET/OPEN_LOGS, AUTH_LOGIN_STATE, APP_QUIT. Invoking any other exposed channel rejects with *"No handler registered for …"*. The scaffold views are labelled as deferred, so this is expected, but clicking "Sign in" or "Download" currently surfaces a raw IPC error.
**Fix (optional for A):** register explicit `not-implemented` handlers for the advertised channels, or note in README that only config/quit are live in batch A.

### MINOR-4 — e2e is a no-op placeholder
**Severity:** MINOR
**Where:** `tests/e2e/smoke.spec.ts:10–12`
**What:** `expect(true).toBe(true)` does not launch Electron. Acceptable for scaffold (real Playwright-electron scenarios are bundle F per tasks 6.1/6.2), but `pnpm e2e` currently proves nothing.
**Fix:** none required for A; keep the TODO marker (present) so bundle F replaces it.

### MINOR-5 — placeholder external URL in Help menu
**Severity:** MINOR
**Where:** `src/main/menu.ts:72` `shell.openExternal('https://github.com/')`
**What:** "Documentation" opens an unrelated placeholder URL.
**Fix:** point at the project docs location or remove the item until a real target exists.

---

## Positive notes

- Build artifacts are consistent with source: `out/main/index.js` retains single-instance + sandbox options + IPC stubs; `out/preload/index.js` retains the full typed `contextBridge` surface; `out/renderer/index.html` preserves the CSP meta tag.
- `externalizeDepsPlugin` used for main/preload; preload emitted as CJS (`electron.vite.config.ts:25–43`) — correct for a sandboxed preload.
- `setWindowOpenHandler` denies all `window.open` (`window.ts:100–103`).
- Config persistence is atomic (`config.ts:33–35` write-temp + rename).
- No `unsafe-eval`/`script-src 'unsafe-inline'` in CSP; `object-src 'none'`, `base-uri 'self'`, `frame-ancestors 'none'` present.

## Summary

The scaffold is well-structured and satisfies the explicit checklist and tasks 1.1–1.5; D-91/D-93 are correctly established and D-92 is nearly met. Approval is withheld on two MEDIUMs: a false "no hardcoded numbers" claim tied to an owner rule (MEDIUM-1) and a CSP relaxed beyond the frozen D-92 (MEDIUM-2). Both fixes are small; after them this batch should ship.

---

## Re-approval

**Round:** fix round 1 (dev session applied MEDIUM-1/2 + all 5 MINOR; dev reported `pnpm lint`/`test`/`build` pass).
**Re-review method:** static, by files only; build artifacts re-inspected as evidence. Build/test NOT run by reviewer.
**Verdict:** ✅ **APPROVE**

### Fix verification

| Finding | Status | Evidence |
|---------|--------|----------|
| MEDIUM-1 hardcoded numbers | ✅ Fixed | `ipc-contract.ts:56,59,60,61` — `logMaxSizeBytes: 5_242_880`, `windowMinWidth: 800`, `windowMinHeight: 600`, `windowStateDebounceMs: 500` added to `DEFAULT_CONFIG`/`ServerConfig`. Consumers: `window.ts:63–64` (`cfg.windowMinWidth/MinHeight`), `window.ts:88` (`cfg.windowStateDebounceMs`), `logger.ts:15,19` (`initLogger(level, maxSize)`), `index.ts:14` (`initLogger(config.logLevel, config.logMaxSizeBytes)`). No literals left in code paths. README:66–69 now accurately states the values live in `DEFAULT_CONFIG`. Built `out/main/index.js:63,66,68,142,165,271` confirms. |
| MEDIUM-2 CSP over-broad | ✅ Fixed | `index.html:5` prod CSP is now strict `connect-src 'self'` (no `ws:`/`http://localhost:*`). Dev-only widening via `devCspPlugin` (`electron.vite.config.ts:12–23`, `apply: 'serve'`) → `connect-src 'self' ws://localhost:* http://localhost:*`. Built `out/renderer/index.html:5` retains the strict production form — the dev directives did **not** leak into the bundle. D-92 now honoured. |
| MINOR-1 tray icon + packaging | ✅ Fixed | `resources/tray.png` present; `electron-builder.yml:12` includes `resources/**/*`, `:21–22` `asarUnpack: resources/**`. README:29 updated. |
| MINOR-2 builder output nesting | ✅ Fixed | `electron-builder.yml:7` `output: dist/${version}` (outside the `out/**/*` `files` glob); `.gitignore:3` covers `dist/`. |
| MINOR-3 unhandled IPC channels | ✅ Fixed | `index.ts:89–105` — every `IPC.*` channel not in the implemented set is registered with an explicit `throw new Error('not implemented in batch A: <channel>')`. README:86–88 documents it. |
| MINOR-4 no-op e2e | ✅ Fixed | `smoke.spec.ts:12` `test.skip(...)` + explanatory TODO; README:89–90 documents bundle F scope. |
| MINOR-5 placeholder docs URL | ✅ Fixed | `menu.ts` no longer references `shell.openExternal`/`https://github.com/`; Help = Open Logs + `role: 'about'`. |

### Residual notes (non-blocking)

- `devCspPlugin` rewrites the CSP via a regex over `index.html` (`electron.vite.config.ts:17–20`). It is `apply: 'serve'` only and the production bundle verified clean, so it is acceptable; if the base CSP string ever changes shape the regex silently no-ops (dev HMR would then fail loudly, not silently expose). No action required for batch A.
- `window.ts:72` still uses `import.meta.dirname ?? __dirname`; the fallback is unreachable in ESM/Node≥20.18 but harmless. Cosmetic only.

### Conclusion

Both MEDIUMs and all five MINORs are resolved with no new findings introduced; the explicit checklist and tasks 1.1–1.5 remain satisfied, D-91/D-92/D-93 are now fully met. **APPROVE** — batch A is ready to close and batch B may start.
