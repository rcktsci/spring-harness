## 1. Contract-first: OpenAPI + WS-протокол

- [ ] 1.1 Расширить `src/main/resources/api/openapi.yaml`: `GET /sessions/{id}/workspace/files` (параметр `path`, ответы 200/404/413/422, коды `session-not-found`, `file-not-found`, `path-invalid`, `extension-not-allowed`, `payload-too-large`), новые коды в `components.schemas`/error-каталог. WS-протокол в OpenAPI не входит (документация в `api-contracts.md` §5 — обновить `register` полями `client.version`/`client.tools[]` и `tool-not-available`).
- [ ] 1.2 Перегенерировать сгенерированные интерфейсы/DTO (openapi-generator 7.25); контроллер-стаб `WorkspaceFilesController` → 501, пока не реализован в пачке U.
- [ ] 1.3 `docs/design/api-contracts.md` §5 — финальная редакция WS-протокола (handshake, register с декларацией, tool-фреймы, heartbeat, grace, коды закрытия 4401/4403/4409); §8 — перенос `workspace/files` из «вне MVP» в контракт.
- [ ] 1.4 Mini-amendment спек (при необходимости): `session-api` `SessionDto.runtimeStatus` — присвоение `PARKED_CLIENT` (уже в дельте).

## 2. Пачка T — Архитектура и каркас релея

- [ ] 2.1 ArchUnit: новый слой `relay` — доступен из `api`/`execution`; сам `relay` → `{session, task, workspace, common}`; `noDomainModuleDependsOnApi` и циклы распространить на `relay`. Негативные фикстуры + тест `relayImplViolationIsCaught`.
- [ ] 2.2 `config/RelayProperties` (`harness.relay.heartbeat-interval`, `session-grace-period`, `tool-call-timeout` — дефолты 15s/60m/5m) + `WorkspaceDownloadProperties` (`max-bytes`, `allow-extensions`) + `ConfigPropertiesBindingTest`.
- [ ] 2.3 `relay/RelayConnectionRegistry` — in-memory `ConcurrentHashMap<(taskId, binding), RelayConnection>` с CAS-занятостью; методы `register`, `unregister`, `find(sessionId)`, `find(taskId, binding)`. D-78.
- [ ] 2.4 `relay/RelayWebSocketHandler` — handshake-стейт-машина: 4401 без JWT, `hello`/`welcome`, 4403 до hello / protocol-mismatch; маршрутизация текстовых фреймов в `RelayFrameDispatcher`.

## 3. Пачка U — Workspace-download + canonical-гвард

- [ ] 3.1 `workspace/download/WorkspaceDownloadController` (реализация сгенерированного интерфейса): резолв пути относительно `workspaces/sessions/{sessionId}`, canonical-гвард, safe-лист, лимит, streaming.
- [ ] 3.2 `workspace/download/WorkspacePathGuard`: канонический резолв сегментов + symlink-резолв; unit-тесты на `..`-эскейп, symlink-наружу, symlink-внутри, каталог, абсолютный путь.
- [ ] 3.3 Интеграционные тесты: живой файл, 404/422/413/422-extension; отдельный тестовый каталог workspace (как в ContainerWorkspaceTools).

## 4. Пачка V — client-tool-bridge и маршрутизация

- [ ] 4.1 `relay/ClientToolRegistry` — хранение оверлея `sessionId → tools[]` (name, description, inputSchema, source); lifecycle = соединение. D-80.
- [ ] 4.2 `relay/ClientToolAdapter` — `ToolCallback`-обёртка над клиентским инструментом: валидация args по `inputSchema` (переиспользовать валидатор MCP/native), отправка `tool.call` в соединение, ожидание результата по `callId` с `tool-call-timeout` → ERROR tool-timeout; идемпотентность по callId (first-final-wins).
- [ ] 4.3 `AgentTurnEngine` — сборка манифеста: toolset сессии (root+клиент = CLIENT — нативные файловые НЕ подключаются, добавляется клиентский оверлей; task-сессии = SERVER — оверлей не подключается); порядок резолва серверный колбэк → клиентский оверлей → `ERROR tool-not-available`. `source` informative only.
- [ ] 4.4 `SubagentSpawner` — наследование toolset CLIENT дочерними sub-сессиями (test: spawn из CLIENT-root видит клиентский инструмент; spawn из SERVER-task — нет).
- [ ] 4.5 Интеграционные тесты: декларация с `client.mcp:`-источником; дублирующее имя → 4409; кривые args → ERROR params-schema; tool-not-available после disconnect.

## 5. Пачка W — PARKED_CLIENT, heartbeat, разрывы и grace

- [ ] 5.1 `PARKED_CLIENT` lifecycle: присвоение при входе задачи в CLIENT_EXEC-состояние (task-engine executor), снятие при register, восстановление при disconnect; SSE-снапшот `session.status` рассылается.
- [ ] 5.2 Heartbeat: `ping`/`pong` по `heartbeat-interval`, разрыв по 2×; in-flight `tool.call` → синтетический `TOOL_RESULT LOST «потеряно при отключении исполнителя»`; задача → `PARKED_CLIENT`.
- [ ] 5.3 `tool.cancel` на stop: клиенту уходит cancel; синтетический CANCELLED в журнал; интеграция с `SubtreeCanceller` (cancel каскадно по поддереву — клиентские вызовы детей тоже).
- [ ] 5.4 Grace- джоба: истечение `session-grace-period` → ERROR-переход по графу (если есть ребро), иначе остаться в CLIENT_EXEC.
- [ ] 5.5 Рестарт-скан: закрытие in-flight клиентских вызовов LOST «операция потеряна при перезапуске» + обнуление реестра соединений (тест рестарт-скан + WS-disconnect).

## 6. Пачка X — Приёмка «Роуминг» и закрытие

- [ ] 6.1 Тестовый WS-клиент в тестовом стенде (`tests/relay/TestRelayClient`) — programmatic WebSocket client: hello/register/tool.call/result/cancel/ping; для «офисов» — два инстанса на один workspace.
- [ ] 6.2 `AcceptanceWorkspaceRoamingTest`: alice → root FREE-сессия с оркестратором; оркестратор (через metaTools) создаёт задачу с CLIENT_EXEC-состоянием; офис A регистрируется, исполняет bash/READ-вызовы; disconnect → LOST + PARKED_CLIENT; офис B: `GET /workspace/files` скачивает файлы; reconnect → новые tool.call; ERROR на клиентский инструмент в отключённом состоянии; финал SUCCESS. Проверки: переходы, SSE-снапшоты, download-файлы, идемпотентность callId.
- [ ] 6.3 `docs/design/decisions.md` — D-72, D-77, D-78, D-79, D-80, D-81, D-82, D-83.
- [ ] 6.4 `docs/design/roadmap.md` — M4 финальная редакция (CLI отменён, клиент — Web Desktop отдельным этапом; server-side done); `execution-model.md` — источники инструментов: native/metaTools/MCP/client.
- [ ] 6.5 `apply-notes.md` — сводка пачек, тесты, отклонения.
- [ ] 6.6 `mvn clean verify` — зелёный; ArchUnit расширен; `openspec validate m4-clients-relay --strict`.

## 7. Архив

- [ ] 7.1 `openspec archive m4-clients-relay --yes` — синк 5 спек в `openspec/specs/` (3 create + 2 update).
- [ ] 7.2 `AGENTS.md` — M4 завершён, следующий Web Desktop (Electron + VueJS).
- [ ] 7.3 Commit + push.
