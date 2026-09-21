# M3 Batch Q Review - Mercury

**Дата:** 2026-09-20  
**Объект:** mcp-client (spec.md)

## Findings

### 1. MCP Server Configuration

**Specification:**
- `harness.mcp.servers[*]` in `application.yml`
- Server schema: `{ name, url, transport: stdio|http|sse, auth, secretRef }`
- `McpClientRegistry` lazy-init at cold-start

**Design:**
- D-63: Spring AI MCP client
- `harness.mcp.auth.proxy-url` for SSO auth

✅ **Correct**

### 2. Agent Tool Filtering

**Specification:**
- `agent.tools_jsonb.mcp` = `[{ server, include, exclude }]`
- Include/exclude filters applied to manifest
- Invalid server → 422

**Design:**
- Consistent with M2 tools_jsonb
- Namespace: `{server}.{tool}`

✅ **Correct**

### 3. Manifest Generation

**Specification:**
- MCP tools added to manifest alongside native/metaTools
- Namespace `{server_name}.{original_tool_name}` for uniqueness
- Collision detection → `forbidden (mcp-name-collision)`

**Design:**
- MCP tools appear in tool-declarations
- Fail-fast on collision

✅ **Correct**

### 4. MCP Tool Adapter

**Specification:**
- MCP → Agent protocol: `callId, tool, status, output?, exitCode?, truncated?, late?`
- Sync/async capable MCP servers → `ASYNC_ACCEPTED`

**Design:**
- Consistent with agent-tools.md §5
- MCP servers can be async-capable

✅ **Correct**

### 5. Auth Refresh

**Specification:**
- 401 → `McpAuthRefresher` → retry (1x)
- 401 after retry → `TOOL_RESULT auth-refresh-failed`
- `harness.mcp.auth.proxy-url` for SSO

**Design:**
- Auth-refresh at cold-start
- 401 handled in adapter

✅ **Correct**

### 6. Default MCP

**Specification:**
- `harness.mcp.servers` = `[]` by default in M3
- Only enabled when explicitly configured in `application.yml`
- Manifest excludes MCP tools if not configured

**Design:**
- MVP: MCP optional (R2)
- Acceptance M3 will test WireMock MCP

✅ **Correct**

## Dev Notes

| Dev заметка | Статус |
|-------------|--------|
| Spring AI MCP client | ✅ |
| tools_jsonb.mcp filtering | ✅ |
| Manifest namespace collision detection | ✅ |
| Auth-refresh via proxy-url | ✅ |
| MCP disabled by default | ✅ |

## Verdict

approve
