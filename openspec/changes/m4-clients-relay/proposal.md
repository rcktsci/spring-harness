## Why

M1–M3 закрыли ядро сессий, workflow-движок и агентский слой, но весь исполнительный контур — серверный: инструменты работают в Docker-контейнерах сервера. Владелец хочет подключаться к новой сессии с оркестратором клиентом и давать тому **свои локальные инструменты** (включая транслированные из клиентских MCP-серверов), а сессиям задач, идущим по workflow на ролевых агентах, оставаться строго серверными. Попутно закрывается §8 api-contracts: скачивание workspace-файлов (роуминг «одна сессия — два офиса»).

## What Changes

- **WS-релей `/api/v1/relay`** (новый): фреймы `hello`/`welcome`, `register { taskId, binding, basePath, client: { version, tools[] } }` → `registered { workspaceId }` | `error workspace-occupied` (close 4409), `tool.call`/`tool.progress`/`tool.result`/`tool.cancel`, `ping`/`pong`. Bearer-JWT (D-41), разрыв по 2×heartbeat.
- **client-tool-bridge** (новый): клиент декларирует `tools[]` (name, description, inputSchema, `source: "client"|"client.mcp:<server>"`) — сервер регистрирует их как **runtime-оверлей** манифеста сессии (в БД не персистится), маршрутизация `tool.call` обратно в WS; идемпотентность по `callId` (первый финальный выигрывает — та же семантика, что у async/MCP).
- **Иерархия `workspace.toolset`** (новое поведение, ADR D-79): root FREE-сессия с зарегистрированным WS-клиентом → `CLIENT` (оркестратор **и его `spawn_subagent`-sub-сессии** могут вызывать клиентские инструменты); сессии задач (`create_task` → собственный `session_id` с ролевым агентом) → `SERVER` **жёстко**, override запрещён, клиентских инструментов там нет. Отключение клиента: toolset **остаётся CLIENT** с пустым оверлеем — клиентские инструменты дают `TOOL_RESULT ERROR tool-not-available`. `PARKED_CLIENT` присваивается только при входе графа в CLIENT_EXEC-состояние (не автоматически на tool.call); вызов клиентского инструмента без подключённого клиента — `ERROR tool-not-available`.
- **`GET /api/v1/sessions/{id}/workspace/files?path=`** (новый, §8): отдача файлов workspace с canonical-path-гвардом (realpath по каждому сегменту, отклонение symlink-escape, лимит тела).
- **`SessionDto.runtimeStatus`**: `PARKED_CLIENT` перестаёт быть «зарезервированным» значением — теперь он присваивается/снимается реально (MODIFIED).
- **Инструменты агента** (MODIFIED `agent-turn`): разрешение инструмента — серверный `ToolCallback` → client_provided оверлей → иначе `ERROR tool-not-available`.
- **Отмена CLI** (поправка к roadmap): attach-CLI не делается; первым клиентом будет Web Desktop (Electron + VueJS) — отдельный этап (бывший M5 WebUI). В M4 клиентская часть — только **тестовые WS-клиенты** для приёмки.

## Capabilities

### New Capabilities

- `client-relay`: WS-протокол релея исполнителя CLIENT_EXEC — handshake, регистрация с декларацией инструментов, двунаправленная маршрутизация tool-фреймов, heartbeat, семантика разрыва (незавершённые `tool.call` → синтетический `TOOL_RESULT LOST`; задача → `PARKED_CLIENT`), идемпотентность re-register.
- `client-tool-bridge`: клиентские инструменты как runtime-оверлей манифеста; иерархия toolset (root+клиент = CLIENT / task-сессии = SERVER); правила резолва и отказа `tool-not-available`; `source` — информативное поле.
- `workspace-download`: скачивание файлов workspace с canonical-path-гвардом и лимитами.

### Modified Capabilities

- `session-api`: `PARKED_CLIENT` — реальное присвоение/снятие `runtimeStatus` (ранее зарезервированное значение).
- `agent-turn`: порядок резолва инструментов (`server callback` → `client_provided` → `tool-not-available`) и маршрутизация вызова в релей.

## Impact

- **Код**: новый модуль `relay` (WS-обработчик, реестр соединений по `(taskId, binding)`, маршрутизация tool-фреймов); workspace-download в `api`/`workspace`; расширение `AgentTurnEngine.executeToolCall` и сборки манифеста; ArchUnit-слой `relay`.
- **API**: `openapi.yaml` — новый REST-эндпоинт `workspace/files`; WS-протокол документирован в api-contracts §5 (в OpenAPI не входит). **BREAKING**: отсутствующее ранее поведение — новых версий существующих контрактов не вводится.
- **Конфиг**: `harness.relay.*` (heartbeat-интервал, grace-период переподключения, таймаут tool.call), `harness.workspace.download.*` (лимит тела, allow-list).
- **БД**: миграций нет — оверлей клиентских инструментов runtime-only.
- **Зависимости**: WebSocket-поддержка Spring (уже в classpath через Boot starter) — новых артефактов не планируется.
- **Документы**: `api-contracts.md` §5/§8, `roadmap.md` (M4 — server-side + поправка «CLI отменён, клиент — Web Desktop»), `decisions.md` (D-79…D-83), `execution-model.md` (источники инструментов: два → три).
- **Тесты**: WS test-клиент в тестовом стенде; acceptance «Роуминг» (два офиса, reconnect, скачивание файлов, финал SUCCESS) — на живом Keycloak + WireMock-LLM.

## Non-goals

- Web Desktop (Electron + VueJS) — отдельный этап.
- `auth/ticket` для браузерного WS — Web Desktop-фаза.
- HYBRID-toolset — владелец отказался (только SERVER/CLIENT).
- Гибридный toolset в одной сессии, persisting клиентских инструментов в БД.
