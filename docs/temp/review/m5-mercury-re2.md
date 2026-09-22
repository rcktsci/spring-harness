# M5 Web Desktop — Mercury Re-approval (Round 2)

**Phase**: PROPOSE (planning artifacts)
**Reviewer**: Mercury-2.5
**Date**: 2026-09-22

## Verdict

**APPROVE** — All must-fix findings from Judge/Qwen addressed in current specs.

## Must-Fix Findings Status

| Finding | Source | Status | Location |
|---------|--------|--------|----------|
| B-1: file browser removed | Judge | ✅ | proposal.md §45, desktop-artifacts/spec.md |
| B-2: ASYNC_ACCEPTED not MessageKind | Judge | ✅ | desktop-chat/spec.md:42 (TOOL_RESULT status) |
| H-1: no tool.result after cancel | Judge | ✅ | desktop-relay-client/spec.md:64 |
| H-2: workspace-occupied → message only | Judge | ✅ | desktop-relay-client/spec.md:35 |
| H-3: PARKED_CLIENT reserved | Judge | ✅ | desktop-chat/spec.md:11 |
| H-4: tree refresh rules | Judge | ✅ | desktop-session-tree/spec.md:11 |
| H-5: reconnect sends register only | Judge | ✅ | desktop-relay-client/spec.md:40 |
| H-6: visible BrowserWindow for SSO | Judge | ✅ | desktop-shell/spec.md:25 |
| H-7: path validation UX-only | Judge | ✅ | desktop-artifacts/spec.md:11 |
| M-1: since=0 pagination | Judge | ✅ | tasks.md:28 |
| M-2: basePath at register | Judge | ✅ | desktop-relay-client/spec.md:25 |
| M-3: compact/stop buttons | Judge | ✅ | desktop-chat/spec.md:44 |
| M-4: stateCode in tree | Judge | ✅ | desktop-session-tree/spec.md:11 |
| M-5: auto-connect moved to relay spec | Judge | ✅ | desktop-relay-client/spec.md:25 |

## New Findings

None. Current specs are clean with respect to contract alignment and AGENTS.md rules.

## Notes

- D-91/D-92 (network ownership, sandbox/CSP) already verified and approved in prior review
- All specs follow contract-first: REST from openapi.yaml, WS/SSE from api-contracts.md
- Design decisions D-86…D-93 properly captured
