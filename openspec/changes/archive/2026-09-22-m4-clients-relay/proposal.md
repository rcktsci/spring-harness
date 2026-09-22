## Why

M1–M3 закрыли ядро сессий, workflow-движок и агентский слой, но весь исполнительный контур — серверный: инструменты работают в Docker-контейнерах сервера. Владелец хочет подключаться к новой сессии с оркестратором клиентом и давать тому **свои локальные инструменты** (включая транслированные из клиентских MCP-серверов клиента), а сессиям задач, идущим по workflow на ролевых агентах, оставаться строго серверными. Попутно закрывается §8 api-contracts: скачивание workspace-файлов.

## What Changes

- **WS-релей `/api/v1/relay`** (новый): фреймы `hello`/`welcome`, `register { sessionId, basePath, client: { version, tools[] } }` → `registered` | `error` (close 4409: `workspace-occupied` / `superseded`), `tool.call`/`tool.progress`/`tool.result`/`tool.cancel`, `ping`/`pong` (инициирует сервер). Bearer-JWT (D-41), разрыв по 2×heartbeat. Единица маршрутизации — **сессия** (D-84, supersede D-12: CLIENT_EXEC как тип workspace состояния задачи больше не используется релеем).
- **client-tool-bridge** (новый): клиент декларирует `tools[]` (name, description, inputSchema, `source: "client"|"client.mcp:<server>"` — информативно, сервер к MCP-серверам клиента не ходит, D-82) — сервер регистрирует их как **runtime-оверлей** манифеста сессии (в БД не персистится, D-80). Оверлей виден root-сессии и её sub-сессиям (`spawn_subagent` — parent-цепочка); сессии задач отдельного `session_id` — SERVER автоматически.
- **Резолв инструментов**: в CLIENT-toolset нативные файловые инструменты (`bash`, `read_file`, `write_file`, `edit_file`, `glob`, `grep`) **не резолвятся** (не только из манифеста — гейт на уровне резолвера); порядок: серверные колбэды (metaTools, MCP) → клиентский оверлей → `TOOL_RESULT ERROR tool-not-available`. Отключение клиента: оверлей очищается, вызовы клиентских инструментов → `tool-not-available` (без авто-парковки — решение владельца).
- **`GET /api/v1/sessions/{id}/workspace/files?path=`** (новый, §8): отдача **серверного** workspace-файла с canonical-path-гвардом (посегментная symlink-проверка, canonical-резолв, containment, NOFOLLOW на финальный компонент), pre-stat перед стримом → честный 413, safe-лист расширений.
- **`agent-turn`** (MODIFIED): порядок резолва с клиентским оверлеем; `tool.cancel` на stop для in-flight клиентских вызовов; рестарт-скан закрывает осиротевшие клиентские вызовы LOST.
- **Отмена CLI** (поправка к roadmap): attach-CLI не делается; первым клиентом будет Web Desktop (Electron + VueJS) — отдельный этап. В M4 клиентская часть — только тестовые WS-клиенты.

## Capabilities

### New Capabilities

- `client-relay`: WS-протокол релея — handshake, регистрация на сессию с декларацией инструментов, takeover/идемпотентность, двунаправленная маршрутизация tool-фреймов, heartbeat, семантика разрыва (незавершённые `tool.call` → синтетический `TOOL_RESULT LOST`).
- `client-tool-bridge`: клиентские инструменты как runtime-оверлей; видимость по parent-цепочке; гейт нативных файловых в CLIENT на уровне резолвера; `tool-not-available`; валидация args по inputSchema.
- `workspace-download`: скачивание файлов серверного workspace с canonical-path-гвардом, safe-лист, pre-stat 413, streaming.

### Modified Capabilities

- `agent-turn`: порядок резолва инструментов с клиентским оверлеем; `tool.cancel` в релей при stop; рестарт-скан для клиентских вызовов.

## Impact

- **Код**: новый слой `relay` (WS-обработчик, реестр соединений по sessionId с CAS-identity, completion-map, адаптер как `ToolCallback`-реализация из `execution`); workspace-download в `api`; расширение `AgentTurnEngine.executeToolCall` (гейт нативных файловых в CLIENT) и сборки манифеста; ArchUnit-правила `relay → execution`.
- **API**: `openapi.yaml` — новый REST-эндпоинт `workspace/files`; WS-протокол — полный wire-контракт в api-contracts §5 (в OpenAPI не входит).
- **Зависимости**: добавляется `spring-boot-starter-websocket` (Boot-managed).
- **Конфиг**: `harness.relay.heartbeat-interval`, `harness.relay.tool-call-timeout`, `harness.workspace.download.max-bytes`, `harness.workspace.download.allow-extensions`.
- **БД**: миграций нет — оверлей runtime-only.
- **Документы**: `api-contracts.md` §5 (rewrite wire-контракта) + §6 (новые коды) + §8; `agent-tools.md` (источник «клиентский оверлей»); `client-cli.md` (CLI отменён); `architecture.md` (слой relay); `execution-model.md` (четыре источника инструментов); `decisions.md` (D-72, D-77, D-78, D-80…D-84).
- **Тесты**: тестовый WS-клиент; приёмка «Роуминг» — reconnect и продолжение работы (без скачивания; download покрывается отдельными тестами на SERVER-сессии).

## Non-goals

- Web Desktop (Electron + VueJS) — отдельный этап.
- `PARKED_CLIENT` — не реализуется (остаётся зарезервированным значением enum); авто-парковка и grace-таймеры задач — вне M4.
- `auth/ticket` для браузерного WS — Web Desktop-фаза.
- HYBRID-toolset — владелец отказался.
- Download клиентских локальных файлов (возможно только для серверного workspace).
