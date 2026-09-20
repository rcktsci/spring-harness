# M3 Agent Layer Review - Mercury

**Дата:** 2026-09-20  
**Объект:** m3-agent-layer change artifacts

## Findings

### 1. AGENTS.md правила (numbers in config, no bloat, sustainability)

✅ **Correct**

- All numeric parameters are in config (`application.yml`/@ConfigurationProperties): `harness.spawn.max-depth: 2`, `harness.async.window.default-ms: 30000`, `harness.late-result.timeout-ms: 900000`, `harness.compact.read-max-bytes: 16384`
- No hardcoded numbers in design
- Sustainability: `inherit` workspace strategy default (avoid VM sprawl), depth limit prevents infinite subagent loops, async timeout defaults prevent resource leaks

### 2. workflow-domain §6 — stop(session)=цель+поддерево

**tasks.md 2.3 / subagent-lifecycle spec:**
- `POST /api/v1/sessions/{id}/stop` cascades via `parent_session_id` (BFS)
- All children marked `cancel_requested=true`, turns complete as CANCELLED
- Async tools report `TOOL_RESULT CANCELLED <subtree-cancelled>`

✅ **Correct** — D-08/D-29 respected

### 3. async-инструменты + wake-eventual vs suspend-cancel

**async-instruments spec / agent-turn spec:**
- `ASYNC_ACCEPTED(callId)` sent immediately, Turn → `PARKED_ASYNC`
- Late `TOOL_RESULT` published via `task_event_seq` (M2 wake)
- If stop called mid-turn: `PARKED_ASYNC` turns complete as CANCELLED
- Orphan cleanup: sub-sessions without parent → no-op, orphan containers cleaned on stop parent

✅ **No conflict** — wake-eventual and suspend-cancel are orthogonal

### 4. org-bias subagent (D-69) — обычный агент не metaTools

**orchestrator-metaTools spec 5.1:**
- Default `agent.permissions_jsonb.metaTools = false`
- Spawned subagents inherit `metaTools=false` from parent
- Orchestrators (make-billing bootstrap) explicitly `metaTools=true`

✅ **Correct** — D-69 respected, org-bias maintained

### 5. R2 (MCP без серверов) — реальная угроза

**design.md Risks R2:**
- MCP is optional, disabled by default (`harness.mcp.servers: []`)
- Acceptance M3 will test with WireMock MCP
- Manifest only includes MCP tools if agent has `tools_jsonb.mcp` configured

✅ **Real threat** — MCP without servers is non-functional but acceptable MVP scope

### 6. Open Questions реалистичны

**design.md Open Questions:**
1. **LLM-приложение first-class:** Whether to treat apps as first-class like permissions/tools — realistic architectural question
2. **Acceptance MCP-интеграция:** WireMock MCP testing — practical QA concern
3. **configure_trigger URL:** Whether to include URL in trigger creation — product decision

✅ **Realistic** — all open questions are legitimate product/architecture decisions

## Dev Notes

| Dev заметка | Статус |
|-------------|--------|
| stop(session) cascade via parent_session_id | ✅ |
| async tools + wake-eventual vs suspend-cancel | ✅ |
| org-bias subagent (metaTools=false by default) | ✅ |
| R2 MCP without servers acknowledged | ✅ |
| All numbers in config | ✅ |

## Verdict

approve
