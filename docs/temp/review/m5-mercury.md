# M5 Web Desktop — Mercury Review

## Status
- **Phase**: PROPOSE (planning artifacts)
- **Reviewer**: Mercury-2.5
- **Date**: 2026-09-22

## Summary
M5 proposes a clean, implementable Electron+Vue desktop client that consumes frozen M4 server contracts. The proposal follows contract-first principles, isolates JS from Maven, and targets the single-instance VM deployment model.

## Findings

### ✅ Alignment with AGENTS.md

| Requirement | Status | Notes |
|-------------|--------|-------|
| Single instance per VM | ✅ | `single-instance lock` in D-86 |
| No bloat | ✅ | Electron (~150MB) accepted as trade-off for local tool execution |
| Config-driven params | ✅ | All numeric limits via `electron-builder.yml` / `Settings` |
| Contract-first | ✅ | `openapi-typescript` from frozen `api/openapi.yaml` |
| No server changes | ✅ | Explicitly stated in What Changes |

### ✅ Stack & Architecture

| Aspect | Decision | Assessment |
|--------|----------|------------|
| Electron main + preload + renderer | D-86 | Correct isolation (contextIsolation, no nodeIntegration) |
| Vue 3.5 + Pinia + TypeScript strict | D-86 | Follows directive |
| SSE via fetch+ReadableStream | D-87 | Only path with Bearer header (EventSource limitation) |
| Local tool execution in basePath | D-88 | Owner-accepted risk; server canonical-guard for downloads |
| Type generation from openapi.yaml | D-89 | Contract-first consistent |

### ⚠️ Risks & Gaps

#### 1. Batch Timeline & Dependencies
| Batch | Duration Estimate | Critical Path |
|-------|-------------------|---------------|
| A (Scaffold + Shell) | 3-4 days | Blocker for all downstream |
| B (SSO + Config + Types) | 4-5 days | PKCE flow, Keycloak integration |
| C (Relay + Local Tools) | 5-6 days | WS protocol, tool execution, cancel |
| D (Chat + SSE) | 4-5 days | Message rendering, fetch-stream |
| E (Tree + Artifacts) | 3-4 days | Depends on B+C |
| F (e2e + Archive) | 2-3 days | Final integration |

**Total**: ~21-27 days (3.5-4 weeks)

**Gap**: No explicit buffer for:
- Keycloak OIDC quirks (redirect URI, PKCE verifier lifecycle)
- Electron build issues on Windows (NSIS installer quirks)
- Playwright-electron e2e stub complexity

#### 2. Security Surface
| Concern | Mitigation | Status |
|---------|------------|--------|
| Prompt injection via bash tools | Owner acceptance (D-88) + `confirmCommands` | ✅ |
| JWT storage | OS keychain via `safeStorage` | ✅ |
| contextIsolation | Explicit in D-86 | ✅ |
| Local file access | No path-guard (D-88) | ⚠️ Accepted risk |

**Note**: D-88 correctly distinguishes:
- Local tools (basePath, user's machine, no guard)
- Server workspace downloads (canonical-guard D-72)

#### 3. Contract Coverage
| API | Source | Generated |
|-----|--------|-----------|
| REST (`/sessions/*`, `/tasks/*`, `/workspace/*`) | `openapi.yaml` | `openapi-typescript` |
| WS (`/api/v1/relay`) | `api-contracts.md` §5 | Manual (`ws-frames.ts`) |
| SSE (session/task events) | `api-contracts.md` | Manual (fetch-stream) |

**Gap**: Manual WS types may drift if api-contracts changes. Consider:
- Adding schema validation tests for WS frames
- Documenting manual type mapping in `ws-frames.ts`

### 🔍 Implementation Notes

#### Batch A: Shell
- Single-instance lock: use `app.requestSingleInstanceLock()` + `app.on('second-instance')`
- State persistence: window bounds in `userData` via `electron-store` or custom JSON

#### Batch B: SSO
- PKCE: generate `code_verifier` (43-128 chars), derive `code_challenge` (S256)
- Loopback redirect: use `http://127.0.0.1:0` to let OS assign port, capture via `BrowserWindow` URL handler

#### Batch C: Relay
- Backoff: `1s → 2s → 4s → 8s → 16s → 30s (max)`
- Takeover: on `workspace-occupied`, show modal; confirm → retry register → server sends 4409 to old peer

#### Batch D: Chat
- SSE reconnect: store `Last-Event-ID` in memory; pass `?since=` on reconnect
- Markdown: `markdown-it` + `dompurify` for sanitization

#### Batch E: Tree
- Task SSE: subscribe to `task.transition`, `task.status`, `subtask.terminal`
- Parent-chain navigation: maintain breadcrumb from STATE → FREE root

### 📋 Required Reviews
1. **Cross-check**: DeepSeek-V4.1-Flash (stack, SSO, security)
2. **Cross-check**: GLM-5.3-Flash (batch timeline, e2e stub complexity)
3. **Judge fixes**: Address findings above

### 📌 Decision
- **Recommendation**: **APPROVE** pending cross-checks
- **Changes needed**:
  - Add buffer to batch F for e2e stub development
  - Document WS frame schema in `ws-frames.ts`
  - Add unit tests for PKCE verifier/challenge generation (Batch B)

---
**Next steps**: Cross-check → Judge fixes → Owner approval → Implementation
