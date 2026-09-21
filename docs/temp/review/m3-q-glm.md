# Ревью пачки Q (MCP-клиент) — GLM-5.3-Flash

Дата: 2026-09-21. Объект: mcp/{McpClientRegistry, McpToolAdapter, McpAuthRefresher, McpToolCallback, McpToolDescriptor, McpToolResult}, McpProperties, AgentTurnEngine (mcpManifestCallbacks + MCP-ветка executeToolCall + async-классификация), pom (mcp 2.0.0 + mcp-json-jackson3), application.yml. Сборки не запускались.

**Вердикт: REJECT** — 3 находки (1 minor / 2 nit). Инфраструктура качественная и D-21/D-63-чистая; блокирует расхождение замороженной дельты mcp-client с реализацией (формат tools_jsonb.mcp + момент ошибки unknown-server) — при архиве спека разойдётся с кодом.

---

## (1) Ленивость + дедуп (Q.1) — ✅
Холодный старт без соединений (`initializedClients()` для наблюдаемости; клиент строится в `computeIfAbsent` при первом listTools/callTool); дедупликация имён серверов — fail-fast в конструкторе реестра (`MCP-name-collision`, контекст не поднимется с дубликатами); манифест кэшируется в холдере, сбрасывается при пересборке клиента; `closeGracefully` на PreDestroy.

## (2) Контракт результата (Q.2) — ✅
`McpToolAdapter` → стандартная форма `tool, status(ok/error), output?, truncated?` (callId/late проставляет движок — как у нативных, write-ahead/`asLate()`); извлечение вывода: текстовые content → конкатенация, иначе structuredContent → JSON (Jackson 3), `isError` → error; лимит `harness.limits.tool-output` + маркер `[truncated]`. Namespace `{server}.{tool}` — в манифесте и в dispatch (`isManagedTool`). MCP-инструменты с `_meta["async-capable"]=true` идут через окно/парковку/first-final-wins (обобщение `AsyncToolExecutor.executeSupply`) — spec-пункт «sync-вызовы могут быть async-capable» реализован.

## (3) Манифест-фильтры (Q.3) — ✅ по поведению, ⚠️ формат (см. M-1)
exclude сильнее include; include пуст → все; матчинг по namespaced и короткому имени; неизвестный сервер → fail-fast построения манифеста (Turn FAILED + SYSTEM-причина). Но:

### M-1 (minor). Формат `tools_jsonb.mcp` и момент ошибки unknown-server расходятся с замороженной дельтой mcp-client
- **Цитата**: specs/mcp-client: «`agent.tools_jsonb.mcp` SHALL быть **массивом объектов** `{ server, include: [...]?, exclude: [...]? }`. Если `server` неизвестен — **ошибка при создании ревизии (422)**»; реализация: `tools_jsonb.mcp = {servers: [...], include: [...], exclude: [...]}` (плоская структура, фильтры общие на все серверы), unknown-server → runtime-error **при построении манифеста** (Turn FAILED + SYSTEM).
- **Проблема**: (а) per-server фильтры из спеки не реализуемы в плоской форме (include «create_issue» заматчится на любом сервере; гранулярность потеряна); (б) «422 при создании ревизии» в MVP невозможен принципиально (ревизии агентов правятся вручную в БД, API создания ревизий нет) — но в apply-notes п.7 это названо «по букве задачи», тогда как задача 4.3 unknown-server-момент не определяла, а спека говорит про 422. При архиве дельта останется в спеке в виде, которому код не соответствует.
- **Предложение**: привести дельту mcp-client к реализованной форме (одна правка требования: плоская структура `{servers[], include[], exclude[]}`; unknown-server → fail-fast при построении манифеста/Turn, per-server-фильтры — точка эволюции) — либо реализовывать per-server (дороже). Первое — одна правка текста спеки; плоская форма закрывает сценарий манифеста.

## (4) Auth-refresh (Q.4) — ✅
Bootstrap-токен — из env по `secretRef` (в приложении не хранится); заголовок по `auth.type` (oauth-bearer → Bearer, api-key → X-Api-Key); 401/403 (детект по `McpHttpClientTransportAuthorizationException` в cause-цепочке либо 401/403) → `McpAuthRefresher.refresh` (прокси, POST {server, secretRef} → {token}, без автоповторов) → пересборка клиента (манифест-кэш сброшен) → **ровно один** повтор; повторная авторазница → `McpToolResult.error(auth-refresh-failed)`. Токены — у прокси/env, D-41/D-63 чисто.

## (5) SDK 2.0.0 + Jackson 3 — ✅
pom: `io.modelcontextprotocol.sdk:mcp` 2.0.0 (property поверх spring-ai-bom) + агрегатор тянет `mcp-core` и `mcp-json-jackson3` (`JacksonMcpJsonMapper` над `tools.jackson`) — Jackson 2 в main-runtime не появляется (mcp-core несёт только jackson-annotations — как и сгенерированные DTO, provided-паттерн). Реестр строит транспорт с `resumableStreams(false)`/`openConnectionOnStartup(false)` — простой request/response-профиль, осознанно.

## (6) ArchUnit — ✅ (с оговоркой-напоминанием)
Пакет `mcp` — технический, зависимость однонаправленная (execution → mcp), обратных импортов нет; адаптер возвращает mcp-локальный `McpToolResult`, преобразование в `execution.ToolResult` — на стороне движка (разорванный цикл слайсов — решение задокументировано, S.1 может формализовать слой). Как и `agent` — вне именованных слоёв до S.1 (напоминание, не блокер).

## (7) D-21 — ✅
Только клиент; MCP-сервер наружу — non-goal (re-evolution); никаких серверных компонентов в пачке.

## (8) D-63 — ✅
Spring AI-экосистемный MCP Java SDK (не кастомный JSON-RPC); конфиг в application.yml; `tools_jsonb.mcp` в ревизии агента; manifest-инжекция наравне с нативными.

---

## Nits

- **n-1**: MCP-вызов не прерывается `TurnCancellation` (транспорт не умеет) — задокументировано в apply-notes: прерванный Turn оставляет фоновому вызову обычную публикацию/LOST-страховку watcher'а. Приемлемо (call-timeout 60s ограничивает висение).
- **n-2**: тестовый профиль — call-timeout 20s «строго больше окна 10s, иначе SDK-таймаут абортит раньше парковки» — полезный грабли-комментарий; следить при смене окна.

## Позитив

- Дедуп/ленивость/auth-retry — ровно по спеке; единственная точка auth-логики (withAuthRetry) обслуживает и listTools, и callTool.
- Async-capable через `_meta` сервера — расширение без изменения контракта инструментов.
- Ограничение «MCP-вызов не прерывается отменой» задокументировано с компенсацией, а не замолчано.

## Вердикт

**REJECT** — 3 находки (1 minor: M-1 выровнять дельту mcp-client с реализованной плоской формой и моментом unknown-server; 2 nit). После правки текста дельты — approve.
