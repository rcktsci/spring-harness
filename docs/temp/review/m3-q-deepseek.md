# Ревью M3 batch Q: MCP-клиент (D-63)

> Ревьюер: DeepSeek-V4.1-Flash (субагент).
> Дата: 2026-09-21.
> Объект: `mcp/{McpClientRegistry,McpAuthRefresher,McpToolAdapter,McpToolCallback,McpToolDescriptor,McpToolResult}`, `config/McpProperties`, `execution/AgentTurnEngine` (манифест/dispatch/async), `execution/impl/AsyncToolExecutor` (+executeSupply), `pom.xml`, `application.yml`, `tests/execution/McpToolsTest`, `apply-notes.md` §Q.
> Контекст: `agent-tools.md §3`, спека `mcp-client`, D-21/D-41/D-63, AGENTS.md (Jackson 2 в main-runtime запрещён).
> Сборки не запускались; резолв зависимостей проверен по локальному `D:\.java\.m2`.
> Severity: **MEDIUM**; **MINOR/NIT**.

## Сводка

| Severity | Кол-во |
|---|---|
| MEDIUM | 1 |
| MINOR | 3 |
| NIT | 1 |
| **Итого** | **5** |

---

## Проверка фокусных пунктов

### (1) D-21/D-63 — MCP только клиент — ✅
- `McpClientRegistry`/`McpToolCallback` используют исключительно `io.modelcontextprotocol.client.*` (`McpClient`, `McpSyncClient`, `HttpClientStreamableHttpTransport`); server-side (`io.modelcontextprotocol.server.*`) отсутствует. Транспорт — только `http` (streamable HTTP), `stdio`/`sse` явно отклоняются (`buildClient`: «не поддерживается в M3»). Конфиг `harness.mcp.servers[*]` (name/url/transport/auth/secretRef/endpoint) — из `application.yml`; `harness.mcp.servers: []` по умолчанию, холодный старт без соединений (`openConnectionOnStartup(false)`, ленивый `ensureClient`). Тест `coldStartIsLazyAndManifestIsCached`.

### (2) D-41/AGENTS — Jackson 2 в main-runtime не тащится — ✅ (проверено по pom+m2)
- `pom.xml` добавляет только `io.modelcontextprotocol.sdk:mcp:2.0.0`. Резолв по `D:\.java\.m2`:
  - `mcp-2.0.0.pom` → `mcp-json-jackson3:2.0.0` + `mcp-core:2.0.0` (compile) — **никакого `mcp-json-jackson2`/`com.fasterxml.jackson.core:jackson-databind`**;
  - `mcp-json-jackson3` → `tools.jackson.core:jackson-databind:3.0.3` (Jackson 3) + `com.networknt:json-schema-validator:3.0.0` (см. Q-4);
  - `mcp-core` → slf4j-api, `com.fasterxml.jackson.core:jackson-annotations` (только аннотации, разрешены), reactor-core, `jakarta.servlet-api` (provided).
- Код использует Jackson 3 (`tools.jackson.databind.*`, `JacksonMcpJsonMapper` из `io.modelcontextprotocol.json.jackson3`). Нарушений нет. (NIT: строка `<mcp-sdk.version>` в pom без отступа.)

### (3) Auth-refresh — секретный URL/прокси — ✅ (с NIT Q-5)
- Токен MCP — у прокси: `harness.mcp.auth.proxy-url` (env `HARNESS_MCP_AUTH_PROXY_URL`); приложение шлёт `POST proxyUrl {"server", "secretRef"}` и получает `{"token"}`; bootstrap-токен — из env по `secretRef`, в логах не светится (`log.info` только имя сервера; в `McpAuthException` — только сообщение/имя). 401/403 → ровно один refresh + пересборка клиента, повторный 401/403 → `auth-refresh-failed`. Тест `McpToolsTest` (auth-refresh путь).

### (4) Manifest dedup + collision — ⚠️ см. Q-1…Q-3
- Config-level дедупликация имён серверов — fail-fast в конструкторе (`MCP-name-collision`), как в спеке `mcp-client §MCP-серверы в конфиге`. Namespace `{server}.{tool}`. **Но:** форма `tools_jsonb.mcp` в коде не совпадает со спекой (Q-1), `include/exclude` приоритет инвертирован (Q-2), tool-level `mcp-name-collision` (спека §Manifest) не реализован (Q-3).

---

## Findings

### Q-1 [MEDIUM]. Форма `agent.tools_jsonb.mcp`: спека — массив объектов `{server, include?, exclude?}`, код/apply-notes — map `{servers[], include[], exclude[]}`
- **Где:** `execution/AgentTurnEngine.mcpManifestCallbacks` (`raw instanceof Map<?,?> mcpConfig`, `mcpConfig.get("servers"/"include"/"exclude")`); `apply-notes.md` §Q п.7 («`tools_jsonb.mcp = {servers[], include[], exclude[]}`»); спека `specs/mcp-client/spec.md` §«`tools_jsonb.mcp` в ревизии агента».
- **Цитата:** спека: «`agent.tools_jsonb.mcp` SHALL быть **массивом объектов** `{ server: string, include: [tool-name]?, exclude: [tool-name]? }`… Include/exclude — белый/чёрный список имён инструментов сервера»; код/apply-notes: единый объект с глобальными `servers`/`include`/`exclude` (фильтры не per-server).
- **Проблема:** реализация отклоняется от замороженной спеки (per-server-фильтры невозможны; `include`/`exclude` применяются глобально ко всем серверам агента), а спека не поправлена — contract-first-рассинхрон. Тест `manifestIncludesNamespacedMcpToolsWithFilters` закрепляет именно map-форму.
- **Предложение:** выбрать и синхронизировать: **(а)** привести спеку к фактической map-форме (`tools_jsonb.mcp = {servers[], include[], exclude[]}`) — минимально, соответствует apply-notes §Q п.7; либо **(б)** реализовать массив per-server-объектов и пер-server фильтры, как в спеке. Зафиксировать в дельте и тесте.

### Q-2 [MINOR]. Приоритет `include`/`exclude` инвертирован относительно спеки
- **Где:** `AgentTurnEngine.mcpManifestCallbacks` (`if (exclude.contains(...)) continue;` до include-whitelist); `apply-notes.md` §Q п.7 («exclude сильнее include»); спека `mcp-client` §«`tools_jsonb.mcp`» («Если оба указаны — **белый приоритетнее**»).
- **Проблема:** код отбрасывает исключённое раньше проверки include → чёрный список сильнее; спека декларирует приоритет белого. Поведение зависит от выбора — рассинхрон.
- **Предложение:** привести код и спеку к единому правилу (и закрепить тестом «инструмент и в include, и в exclude → ?»).

### Q-3 [MINOR]. Tool-level `mcp-name-collision` из спеки не реализован
- **Где:** спека `mcp-client` §Manifest («если два сервера дают инструмент с одинаковым именем — `forbidden (mcp-name-collision)` при загрузке (fail-fast)»); код — только config-level дубль имён серверов.
- **Проблема:** из-за namespace `{server}.{tool}` межсерверная коллизия имён недостижима — правило спеки фактически мертво; при этом внутри одного сервера дубликаты не проверяются. Неясно, требуется ли правило.
- **Предложение:** убрать/переформулировать правило в спеке (namespace устраняет коллизию) либо добавить проверку дублей `namespacedName` в манифесте.

### Q-4 [MINOR]. Транзитивный `com.networknt:json-schema-validator:3.0.0` приходит в main-runtime
- **Где:** `mcp-json-jackson3:2.0.0` → `com.networknt:json-schema-validator:3.0.0` (compile); D-58 явно отклонял эту зависимость как «тяжёлую» для нашего профиля.
- **Проблема:** тяжёлая транзитивная зависимость в рантайме (используется MCP SDK для валидации inputSchema). Прямого нарушения нет (Jackson 3, не Jackson 2), но стоит зафиксировать факт/решение (exclude не требуется SDK'ом для наших вызовов?).
- **Предложение:** задокументировать в apply-notes Q; при желании — `exclusions` и проверка, что SDK не использует её в вызываемых путях.

### Q-5 [NIT]. `McpAuthRefresher` — `RestClient.create()` без таймаутов
- **Где:** `McpAuthRefresher.refresh` (`RestClient.create().post()…`).
- **Проблема:** обновление токена синхронно в потоке Turn'а; без connect/read-timeout возможен долгий/вечный висяк при недоступном прокси.
- **Предложение:** задать таймауты (конфиг, напр. `harness.mcp.auth.timeout`).

---

## Позитив (проверено)

- MCP — только клиент, http-transport, ленивая инициализация, без соединений на старте (D-21/D-63).
- Стек: `mcp:2.0.0` → Jackson 3 только; Jackson 2 databind в main-runtime не добавляется (проверено по pom в `D:\.java\.m2`).
- Auth: токены у прокси, bootstrap из env, секреты не логируются; ровно один auth-retry → `auth-refresh-failed`.
- Стандартный контракт результата (`McpToolAdapter`: text/structuredContent/isError, лимит вывода + `truncated`); async-capable MCP (`_meta["async-capable"]`) через окно `AsyncToolExecutor.executeSupply`; namespace `{server}.{tool}`; config-level коллизия — fail-fast.

## Вердикт

**REJECT — 5 находок (1 MEDIUM: Q-1 форма `tools_jsonb.mcp` спека ↔ код/apply-notes; 3 MINOR: Q-2 приоритет include/exclude, Q-3 tool-level collision, Q-4 транзитивный networknt json-schema-validator; 1 NIT: Q-5 таймауты RestClient).** Блокер — Q-1 (contract-first: форма конфигурации агента разошлась со спекой).