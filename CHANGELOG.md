# Changelog

All notable changes to spring-harness are documented in this file. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> `0.1.0` is the first release of the product. The whole product — backend and web-desktop — carries one SemVer number. The release date is the date of the release commit; the `v0.1.0` tag is pushed to `origin` right after it lands in `main`.

## [Unreleased]

## [0.1.0] - 2026-09-26

First release. Combines everything shipped in M1–M5, the deployment bundle, and the CI pipeline that publishes the backend image to GitHub Container Registry.

### Added
- **M1 — Session core**: SSO gate, `SessionStore` (append-only, visibility rendering), `LlmGateway`, agent loop with sync tools, per-session container workspaces (D-30), wake via EVENT+POLL under ShedLock (D-40), REST/SSE message API, safeguard auto-compaction, minimal attach with streaming. ([archived change](openspec/changes/archive/2026-09-18-m1-session-core))
- **M2 — Workflow engine**: `WorkflowRegistry` with graph validation, tasks (revision pin, `task_transition_history`, status projections), STATE sessions with unique-pair resume, meta-tool `transition`, system states BASH_SCRIPT / WAIT_WEBHOOK / WAIT_TASKS, REST+SSE tasks, triggers and webhook capability URLs (D-05/D-25/D-26). ([archived change](openspec/changes/archive/2026-09-20-m2-workflow-engine))
- **M3 — Agent layer**: agent revisions and LLM profiles, `spawn_subagent` with cascade subtree cancel, `read_compacted`, async tools (window → `ASYNC_ACCEPTED` → late `TOOL_RESULT`, restart scan, `AsyncTimeoutWatcher`), orchestrator meta-tools via `permissions_jsonb.metaTools` (D-70), MCP client on Spring AI MCP SDK 2.0.0 (D-63). Acceptance scenario "Make the billing" green. ([archived change](openspec/changes/archive/2026-09-21-m3-agent-layer))
- **M4 — Clients and relay**: WS relay at `/api/v1/relay` (handshake, heartbeat, takeover with close codes 4401/4403/4409), client toolset as a runtime-only overlay through the parent chain, canonical-path guard for server-side workspace downloads (D-72). Acceptance scenario "Roaming" green (`AcceptanceWorkspaceRoamingTest`). ([archived change](openspec/changes/archive/2026-09-22-m4-clients-relay))
- **M5 — Web Desktop**: Electron + Vue 3 + Vite + TypeScript strict client. SSO PKCE with safeStorage and silent refresh, `confirmCommands=always` (D-93), `RelayClient` implementing the full §5 wire protocol, local tools `bash` / `read_file` / `write_file` / `edit` / `glob` / `grep`, session list and tree, all `MessageKind` feed (markdown-it + DOMPurify, collapsible tool blocks, placeholders, late markers, `since=0` paging), per-session drafts, SSE through `fetch` + `ReadableStream`, task panel with status projection + history + comments, artifacts (UX validation + save-as + open-in-OS through temp cache `<sha256[:16]>-<basename>` + handling of 404/413/422), Playwright-electron e2e against an in-test stub server. ([archived change](openspec/changes/archive/2026-09-24-m5-web-desktop))
- **Deployment bundle**: `docker-compose.yml` for the orchestrator and Postgres 18, `docker/Dockerfile.orchestrator` (multi-stage: builder on `rcktsci/java-tooling:25`, runtime on `bellsoft/liberica-openjre-alpine-musl:25`), `docker/Dockerfile` for the per-session helper image, `README.md` with the deploy-from-zero walkthrough, host grants (`HARNESS_DOCKER_GID`, workspace chown) per D-95.
- **CI**: `.github/workflows/backend-image.yml` — push to `main` builds and publishes `ghcr.io/rcktsci/spring-harness-orchestrator` with tags `latest`, `main`, and `sha-<short>`; push of tag `v*` additionally publishes `vX.Y.Z` and `X.Y.Z`. Layer cache via `type=gha`, single platform `linux/amd64`, no JDK/Maven on the runner. `.dockerignore` keeps `api/` and `src/` in the build context (openapi-generator reads `api/openapi.yaml`).
- **Release tooling**: `CHANGELOG.md` (this file), SemVer policy with version and immutability gates on tag runs.

[0.1.0]: https://github.com/rcktsci/spring-harness/releases/tag/v0.1.0
