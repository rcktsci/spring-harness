# M4 «Клиенты и релей» — apply-notes

> Change `m4-clients-relay`, пачки 1/T/U/V/W/X. Итог: server-side WS-релей + клиентский
> toolset + скачивание workspace; клиент в M4 — только тестовый WS-клиент; CLI отменён,
> первый реальный клиент — Web Desktop (Electron + VueJS, M5).

## Пачки и результат

| Пачка | Объём | Тесты (verify) |
|---|---|---|
| 1 | contract-first: `workspace/files` в `openapi.yaml` + регенерация + stub-контроллер; rewrite api-contracts §5/§6/§8 | 493 |
| T | релей-инфраструктура: `spring-boot-starter-websocket`, SPI `ClientToolBridge`/`ToolDescriptor`, ArchUnit `relay`, `RelayConnectionRegistry`, `RelayWebSocketHandler` (handshake/heartbeat), `RelayProperties`/`WorkspaceDownloadProperties` | 502 |
| U | `GET /sessions/{id}/workspace/files` + `WorkspacePathGuard` (canonical/NOFOLLOW/pre-stat 413/safe-лист) | 539 |
| V | `ClientToolRegistry` (оверлей, parent-chain) + `ClientToolAdapter` + маршрутизация в `AgentTurnEngine` (гейт нативных файловых в CLIENT, async только сервер) | 552 |
| W | разрыв → LOST; `tool.cancel` на stop/каскад `SubtreeCanceller` → CANCELLED; рестарт-скан | 559 |
| X | тестовый WS-клиент `TestRelayClient` + `AcceptanceWorkspaceRoamingTest`; ADR, доки-синк, архив | **560** |

`mvn clean verify` — зелёный, `560 tests, 0 failures, 3 skipped` (3 — symlink-кейсы `WorkspacePathGuardTest` под `assumeTrue`: на Windows-dev без Developer Mode скипаются, на Linux/CI исполняются).
`openspec validate m4-clients-relay --strict` — valid.

## Что сделано

- **WS-релей** `/api/v1/relay` (api-contracts §5): handshake `hello`→`welcome`, close 4401 (нет/кривой JWT, WS-close вместо HTTP 401 — `RelayHandshakeInterceptor` + permitAll relay-цепочка), 4403 (фрейм до hello / `protocol-mismatch`), `register { sessionId, basePath, client: { version, tools[] } }` → `registered`/`error` (4409: `session-not-found`/`wrong-session-kind`/`workspace-occupied`/`duplicate-tool-name`/`superseded`), `tool.call`/`result`/`progress`/`cancel`, `ping`/`pong` (инициирует сервер, разрыв 2×interval), `ConcurrentWebSocketSessionDecorator`.
- **Клиентский toolset** (specs client-tool-bridge/client-relay): runtime-оверлей на сессию, parent-chain видимость, `source` информативен (D-82), валидация args по `inputSchema` (D-58), completion-map + tombstones + `tool-call-timeout`, `tool.cancel`/CANCELLED, разрыв → LOST, рестарт-скан, `tool-not-available`.
- **Скачивание workspace** (specs workspace-download): canonical-гвард (D-72), safe-лист, pre-stat 413, потоковая отдача `InputStreamResource`.
- **Архитектура** (D-85): SPI `ClientToolBridge` в `execution`, реализация в `relay`, ArchUnit `api → relay`, `relay → {execution, session}`, `execution ↛ relay`.

## Отклонения и решения (осознанные)

1. **`PARKED_CLIENT` не реализован** (решение propose J-5/D-84): значение enum зарезервировано; при разрыве клиента авто-парковки нет — вызовы становятся `tool-not-available`/LOST. Вне M4 также task-grace-таймеры и ERROR-переходы задач по отключению клиента.
2. **CLI отменён** (поправка к roadmap): attach-CLI не делается; первый клиент — Web Desktop (Electron + VueJS, M5). В M4 клиентская часть — тестовый WS-клиент `tests/relay/TestRelayClient`.
3. **Download — только серверного workspace** (§8): для CLIENT-сессий серверный каталог может быть пуст (файлы у клиента) — задокументировано; скачивание клиентских локальных файлов вне M4.
4. **disconnect → toolset снова SERVER** (V-9, D-84: toolset производен от соединения): после разрыва следующая Turn-манифестация — SERVER (нативные серверные инструменты доступны); вызов клиентского имени без соединения → `tool-not-available`.
5. **W-5**: `SubtreeCanceller.closePendingToolCalls` (сессии без живого Turn) не шлёт `tool.cancel` и не трогает in-memory реестр — такие future'ы закрываются `tool-call-timeout`/`detach`; для живых Turn'ов отмена идёт через `TurnCancellation`-interruptor.
6. **Приёмка 6.2 — охват**: `AcceptanceWorkspaceRoamingTest` покрывает реальный WS-путь (hello/register), исполнение клиентских инструментов клиентом (в т.ч. `bash` — не серверным контейнером), takeover офисом B (A — close 4409 `superseded`), продолжение, disconnect → `tool-not-available`, parent-chain видимость суб-сессии (реальный `createChildSession` — путь `SubagentSpawner`), SSE-снапшот `runtimeStatus`, финал COMPLETED. Полный `spawn_subagent`/`create_task`-контур через metaTools и task-сессия-SERVER — покрыты unit/integration тестами V/W (`ClientToolRegistryTest`, `ClientToolTurnWireMockTest.subtreeStop...`, parent-chain) и внесены в бэклог M5-приёмки.
7. **WS-handshake-аутентификация**: relay-путь в отдельной `SecurityFilterChain @Order(0)` с `permitAll`; Bearer-JWT проверяет `RelayHandshakeInterceptor` тем же `JwtDecoder` + SSO-гейт (чтобы отдать WS-close 4401, а не HTTP 401 до апгрейда).
8. **WS-close heartbeat-разрыва**: `1001 going-away` (в §5 код явно не задан); `tool.cancel`-гонка stop-vs-dispatch — клиент игнорирует cancel для неизвестного `callId` (§5.3, W-2).
9. **`not-implemented`** — переходный код stub'а batch-1 удалён из каталога при реализации пачки U (Java-машинерия — в U, enum/§6 — в U-fix).

## Артефакты

- Спеки в `openspec/specs/`: `client-relay`, `client-tool-bridge`, `workspace-download` (create), `agent-turn` (update).
- ADR: D-72, D-77, D-78, D-80…D-85 (`docs/design/decisions.md`).
- Доки-синк: `roadmap.md`, `execution-model.md`, `agent-tools.md`, `client-cli.md` (supersede), `architecture.md`, `workflow-domain.md`, `glossary.md`, `api-contracts.md` §2/§8.
