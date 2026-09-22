## 1. Contract-first: OpenAPI + wire-контракт §5

- [x] 1.1 Расширить `src/main/resources/api/openapi.yaml`: `GET /sessions/{id}/workspace/files` (параметр `path`; ответы 200/404/413/422; коды `session-not-found`, `file-not-found`, `path-invalid`, `extension-not-allowed`, `payload-too-large` в каталоге §6). Перегенерация (openapi-generator 7.25) + stub-контроллер `WorkspaceFilesController` → 501 до пачки U.
- [x] 1.2 `docs/design/api-contracts.md` §5 — **rewrite wire-контракта**: `register { sessionId, basePath, client: { version, tools[] } }` (D-84, без taskId/binding), `tool` — free-form имя, `error { code, message }`-фрейм, close-коды 4401/4403/4409 с `workspace-occupied`/`superseded`/`wrong-session-kind`/`session-not-found`/`duplicate-tool-name`, heartbeat — сервер инициирует `ping`. §6 — новые коды + `tool-not-available`/`params-schema`/`tool-timeout` для WS error-фреймов. §8 — `workspace/files` перенесён из «вне MVP».
- [x] 1.3 Поправка D-12: `CLIENT_EXEC` workspace-type — **зарезервирован** (в graph-валидаторе остаётся, не исполняется релеем, серверный fallback). Правки `workflow-domain.md`/`glossary.md` — в task 6.4.
- [x] 1.4 `PARKED_CLIENT` — отсрочка: `openspec/specs/session-api/spec.md` (M2-строка «с M4 (релей)» → «зарезервировано, присвоение — вне M4»), `api-contracts.md` §2/§8, `roadmap.md` M4-объём — правки в task 6.4.

## 2. Пачка T — Зависимости, архитектура и каркас релея

- [x] 2.1 `pom.xml` — добавить `spring-boot-starter-websocket` (Boot-managed).
- [x] 2.2 ArchUnit: канон направлений — `api → relay`, `relay → execution`; `execution ↛ relay` (доменные классы `execution` используют только интерфейс `ClientToolBridge` из собственного пакета; реализация — в `relay`, Spring-связывание). SPI `ClientToolBridge` (в `execution`): `isClientSession(sessionId)`, `resolve(sessionId, toolName) → Optional<ToolDescriptor>`, `invoke(sessionId, callId, toolName, args) → ToolResult`. `noCycles`, `noDomainModuleDependsOnApi` распространить на `relay`. Негативные фикстуры + тест `relayViolationIsCaught`.
- [x] 2.3 `config/RelayProperties` (`heartbeat-interval` 15s, `tool-call-timeout` 5m) + `WorkspaceDownloadProperties` (`max-bytes` 10MB, `allow-extensions`) + `ConfigPropertiesBindingTest`.
- [x] 2.4 `relay/RelayConnectionRegistry` — `ConcurrentHashMap<sessionId, RelayConnection>`: register с takeover (тот же principal — старое в 4409 `superseded`; иной — `workspace-occupied`), idempotent re-register с того же соединения, `unregister` CAS по connection-identity (D-78).
- [x] 2.5 `relay/RelayWebSocketHandler` — handshake-стейт-машина (4401 / `hello`→`welcome` / 4403 до hello / `protocol-mismatch`); dispatch фреймов; `ConcurrentWebSocketSessionDecorator` на исходящие; heartbeat-планировщик (ping, разрыв по 2×interval).

## 3. Пачка U — Workspace-download + canonical-гвард

- [x] 3.1 `api/WorkspaceFilesController` (реализация сгенерированного интерфейса): резолв относительно `workspaces/sessions/{sessionId}`, гвард, safe-лист, pre-stat 413, streaming.
- [x] 3.2 `WorkspacePathGuard` (в `api` или `common`): посегментный symlink-чек + canonical-резолв + containment + `NOFOLLOW_LINKS` на финальный компонент. Unit-тесты: `..`-эскейп, symlink-наружу, symlink-внутри (запрещён — NOFOLLOW), каталог, абсолютный путь, case-insensitive расширения.
- [x] 3.3 Интеграционные тесты на SERVER-сессии: живой файл, 404/422/413/422-extension; каталог-фикстуры в тестовом workspace.

## 4. Пачка V — client-tool-bridge и маршрутизация

- [x] 4.1 `relay/ClientToolRegistry` — оверлей `sessionId → tools[]` (name, description, inputSchema, source); lifecycle = соединение; резолв по parent-цепочке (sub-сессии → root). D-80.
- [x] 4.2 `relay/ClientToolAdapter` (реализует `execution`-ный `ToolCallback`): валидация args по `inputSchema` → `tool.call` в соединение → ожидание на `CompletableFuture` с `tool-call-timeout` → ERROR tool-timeout; completion-map + tombstones; дублирующий `tool.result` игнорируется.
- [x] 4.3 `AgentTurnEngine`: toolset сессии (connection резолвится по parent-цепочке → CLIENT); в CLIENT нативные файловые (`bash`, `read_file`, `write_file`, `edit_file`, `glob`, `grep`) **не резолвятся** (манифест + executeToolCall-гейт); порядок серверные колбэки → клиентский оверлей → `tool-not-available`; async-классификация только по серверным колбэкам.
- [x] 4.4 `SubagentSpawner` — видимость оверлея дочерними sub-сессиями (тест: spawn из CLIENT-root видит клиентский инструмент; task-сессия — SERVER, оверлея нет).
- [x] 4.5 Интеграционные тесты: декларация `client.mcp:jira` (сервер не ходит к MCP); `duplicate-tool-name` → 4409; кривые args → ERROR params-schema; `tool-not-available` после disconnect; галлюцинированный bash в CLIENT не идёт на сервер.

## 5. Пачка W — Разрывы, cancel, рестарт-скан

- [x] 5.1 Разрыв соединения: in-flight `tool.call` → синтетический `TOOL_RESULT LOST «потеряно при отключении исполнителя»`; оверлей очищается; регистрация/разрыв в логах с MDC (D-77).
- [x] 5.2 `tool.cancel` на stop: клиенту уходит cancel; синтетический CANCELLED в журнал; интеграция с `SubtreeCanceller` (каскад по поддереву — клиентские вызовы детей тоже отменяются).
- [x] 5.3 Рестарт-скан: закрытие in-flight клиентских вызовов LOST «операция потеряна при перезапуске» + обнуление реестра соединений; тест рестарт-скан + WS-disconnect.

## 6. Пачка X — Приёмка «Роуминг» и закрытие

- [x] 6.1 Тестовый WS-клиент (`tests/relay/TestRelayClient`): hello/register/tool.call/result/cancel/ping; два инстанса на одну сессию для «офисов».
- [x] 6.2 `AcceptanceWorkspaceRoamingTest`: alice → root FREE-сессия с оркестратором; офис A регистрируется, оркестратор вызывает клиентские инструменты (включая декларированный bash → исполняется клиентом); disconnect → LOST + `tool-not-available` на следующие вызовы; офис B — takeover (4409 `superseded` на старое) → оркестратор продолжает работу; spawn_subagent видит клиентские инструменты; create_task-сессия — SERVER (нативный bash на сервере); финал SUCCESS.
- [x] 6.3 `docs/design/decisions.md` — D-72, D-77, D-78, D-80, D-81, D-82, D-83, D-84, D-85.
- [x] 6.4 Доки-синк: `roadmap.md` (M4 — server-side + поправка «CLI отменён, клиент — Web Desktop отдельным этапом»; убрать PARKED_CLIENT из объёма M4); `execution-model.md` (источники: native/metaTools/MCP/client); `agent-tools.md` (клиентский оверлей вместо ClientRelayWorkspaceTools); `client-cli.md` (CLI отменён — пометка supersede); `architecture.md` (слой relay, убрать ClientRelayWorkspaceTools); `workflow-domain.md` + `glossary.md` (CLIENT_EXEC — зарезервирован, не исполняется релеем); `api-contracts.md` §2/§8 (PARKED_CLIENT — зарезервировано, вне M4); `openspec/specs/session-api/spec.md` (та же правка, при архиве — sync).
- [x] 6.5 `apply-notes.md` — сводка пачек, тесты, отклонения (PARKED_CLIENT не реализован, task-grace вне M4, download только серверного workspace).
- [x] 6.6 `mvn clean verify` — зелёный; `openspec validate m4-clients-relay --strict`.

## 7. Архив

- [x] 7.1 `openspec archive m4-clients-relay --yes` — синк 4 спек в `openspec/specs/` (3 create + 1 update agent-turn).
- [x] 7.2 `AGENTS.md` — M4 завершён, следующий Web Desktop (Electron + VueJS).
- [x] 7.3 Commit + push.
