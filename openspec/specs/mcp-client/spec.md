# mcp-client Specification

## Purpose
MCP-клиент: интеграция Spring AI MCP client, привязка MCP-серверов к ревизии агента через `tools_jsonb.mcp`, инжекция manifest инструментов серверов в tool-declarations модели. Глобальный пул серверов + фильтры include/exclude.

## Requirements

### Requirement: MCP-серверы в конфиге

Система SHALL загружать `harness.mcp.servers[*]` из `application.yml`. Каждый сервер: `{ name, url, transport: stdio|http|sse, auth: {type: oauth-bearer|api-key|...}, secretRef: "HARNESS_MCP_<NAME>_TOKEN" }`. Токены — у владельца секрета (env / vault), агенту не передаются.

#### Scenario: старт приложения

- **WHEN** Spring Boot стартует и в `application.yml` перечислены MCP-серверы
- **THEN** `McpClientRegistry` инициализирует клиенты лениво при первом обращении агента (cold-start economy)

### Requirement: `tools_jsonb.mcp` в ревизии агента

`agent.tools_jsonb.mcp` SHALL быть массивом объектов `{ server: string, include: [tool-name]?, exclude: [tool-name]? }`. Если `server` неизвестен (нет в `harness.mcp.servers`) — ошибка при создании ревизии (422). Include/exclude — белый/чёрный список имён инструментов сервера. Если оба указаны — белый приоритетнее (нет инструмента в include → не входит).

#### Scenario: ревизия агента указывает MCP-сервер

- **WHEN** `agent.tools_jsonb = { mcp: [{ server: "jira", include: ["create_issue", "transition_issue"] }] }`
- **THEN** в manifest инструментов этого агента попадают только `create_issue` и `transition_issue` с сервера `jira`

### Requirement: Manifest инструментов в tool-declarations

При сборке prompt'а для модели инструменты MCP-серверов SHALL быть включены в `tools` наравне с нативными `WorkspaceTools` (единый manifest). Имя инструмента = `{server_name}.{original_tool_name}` (namespace — через точку). Дедупликация: если два сервера дают инструмент с одинаковым именем — `forbidden (mcp-name-collision)` при загрузке (fail-fast).

#### Scenario: manifest для оркестратора

- **WHEN** orchestrator-агент с `metaTools=true` и `mcp=[{server:"jira"}]` стартует Turn
- **THEN** в `tools` массиве — нативные (read_file, write_file, bash, glob, grep, edit_file) + metaTools (create_workflow, ... , transition) + MCP-инструменты (`jira.create_issue`, ...)

### Requirement: Результат инструмента MCP

Результат MCP-инструмента SHALL мапиться в стандартный контракт `callId, tool, status, output?, exitCode?, truncated?, late?` (по agent-tools.md §5). Если MCP-сервер возвращает нестандартную форму — adapter оборачивает. Синхронные MCP-вызовы могут быть объявлены async-capable на стороне сервера (manifest-атрибутом); окна те же.

#### Scenario: MCP-вызов завершён

- **WHEN** MCP-сервер ответил результатом (sync или после окна async)
- **THEN** журнал TOOL_RESULT сформирован по общему контракту; модель видит стандартное представление

### Requirement: Auth-прокси и обновление токенов

Токены MCP-серверов SHALL управляться внешним SSO-прокси (корпоративная инфраструктура); приложение запрашивает токен у прокси (`harness.mcp.auth.proxy-url`), прокси возвращает действующий bearer с TTL. При 401 от MCP-сервера — `McpAuthRefresher` запрашивает новый токен (один retry); повторный 401 — `TOOL_RESULT auth-refresh-failed`.

#### Scenario: токен протух

- **WHEN** MCP-сервер ответил 401
- **THEN** запрашивается свежий токен у прокси; запрос повторяется (1 раз); при повторной 401 — TOOL_RESULT auth-refresh-failed

### Requirement: Default MCP — пусто

В M3 `harness.mcp.servers` SHALL быть пуст по умолчанию; конкретные серверы добавляются в `application.yml` владельцем. Manifest ревизий без `tools_jsonb.mcp` (не указан или пуст) SHALL не содержать MCP-инструментов.

#### Scenario: агент без MCP

- **WHEN** `agent.tools_jsonb = { native: [...] }` (mcp отсутствует)
- **THEN** в manifest — только native; MCP-клиент не открывает соединений на старте
