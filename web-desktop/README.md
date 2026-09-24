# Spring Harness Web Desktop

Electron + Vue 3 desktop client for the Spring Harness backend. Pure consumer
of frozen server contracts (REST `/api/v1/sessions*`, SSE, WS `/api/v1/relay`).
No server-side changes live in this package.

## Stack

- Electron 44 (main / preload / renderer)
- Vue 3.5 + Pinia 4 + vue-router 5
- Vite 7 via `electron-vite` 5
- TypeScript 5.9 (strict)
- electron-log 5 (rotated file + console)
- Vitest 5 (unit) + Playwright 1.63 (electron e2e stub)
- ESLint 10 (flat) + Prettier 3

## Layout

```
web-desktop/
  src/
    main/        Electron main: window, menu, tray, logging, config,
                 Keycloak PKCE auth, safeStorage token store, ws-frames
    preload/     contextBridge API surface (typed IPC contract)
    renderer/    Vue 3 SPA (views + stores)
    shared/      IPC channel names + payload types (single source of truth)
    api/generated/  TS types from openapi.yaml (do not edit)
  tests/
    unit/        Vitest suites (config binding, pkce, token store, ws-frames)
    e2e/         Playwright-electron smoke stub (real scenarios in bundle F)
  resources/     Tray icon + app icon (generated, committed)
  build/         electron-builder static assets (icon.ico/icon.png)
  electron.vite.config.ts
  electron-builder.yml
  eslint.config.js
  tsconfig.{json,node.json,web.json}
  vitest.config.ts
  playwright.config.ts
```

## Commands

```sh
pnpm install
pnpm dev          # electron-vite dev (HMR for main + preload + renderer)
pnpm build        # production bundle into ./out
pnpm generate:api # regenerate TS types from the frozen openapi.yaml
pnpm typecheck    # tsc --noEmit + vue-tsc
pnpm lint         # ESLint flat config, --max-warnings=0
pnpm format       # Prettier write
pnpm test         # Vitest (unit)
pnpm e2e          # Playwright-electron (smoke stub only for now)
pnpm package:win    # NSIS installer via electron-builder
pnpm package:linux  # AppImage via electron-builder
pnpm verify       # lint + typecheck + test + build
```

`generate:api` writes `src/api/generated/openapi.d.ts` from
`../api/openapi.yaml` (frozen contract, D-89). The
folder is excluded from lint/prettier — never hand-edit it.

## Architecture invariants (frozen)

- **D-91**: main owns every secret and every network client (JWT in
  `safeStorage`, REST, WS, SSE). The renderer talks to main only through the
  typed `window.harness` bridge exposed by `src/preload/index.ts`.
- **D-92**: `webPreferences: { sandbox: true, contextIsolation: true,
  nodeIntegration: false }` and a strict CSP in `src/renderer/index.html`
  (`default-src 'self'`). Renderer never has the JWT, never opens a WS.
- **D-93**: `confirmCommands` defaults to `always`; users opt into `never`
  explicitly per session.

All numeric defaults (window 1200×800 / minimums, window-state debounce,
log level and rotation size) live in `DEFAULT_CONFIG` in
`src/shared/ipc-contract.ts` and are persisted in `<userData>/config.json`;
no hardcoded magic numbers in code paths.

## Security posture

- Renderer is sandboxed: `sandbox: true`, `contextIsolation: true`,
  `nodeIntegration: false` (D-92).
- CSP in `src/renderer/index.html` is strict in production:
  `default-src 'self'` and `connect-src 'self'` — the shipped renderer
  opens no outbound connection. During `pnpm dev` only, a Vite plugin
  (`devCspPlugin` in `electron.vite.config.ts`) widens `connect-src` to
  `ws://localhost:* http://localhost:*` so Vite HMR works. The production
  bundle never contains the dev directives.
- Renderer holds no token and opens no socket; all network is owned by
  main (D-91).

## Known gaps (batch A/B scope)

- Relay, SSE, session and artifact IPC channels resolve to an explicit
  `not implemented in batch B: <channel>` error. Real handlers arrive in
  bundles C/D/E.
- `pnpm e2e` is a skipped placeholder (`tests/e2e/smoke.spec.ts`); real
  Playwright-electron scenarios are bundle F (tasks 6.1/6.2).

## Out of scope (M5 non-goals)

- MCP bridging (`client.mcp:<server>`)
- `electron-updater` auto-update
- Mobile / browser build targets
- Multi-window (single-instance lock + main window only)
