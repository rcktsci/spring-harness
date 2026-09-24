# Клиент Web Desktop spring-harness

> **Supersedes** `client-cli.md` (attach-CLI отменён в M4; первым полноценным клиентом стал Web Desktop — Electron + Vue 3, см. `roadmap.md` M5 и D-86…D-93).

Десктоп-клиент (Electron + Vue 3) для взаимодействия с серверной частью spring-harness. Контракты — `api-contracts.md` (REST/SSE) и релейный протокол §5 (WS). Документ описывает клиентский UX и архитектуру; за серверной стороной — `decisions.md`.

## 1. Архитектура (D-91)

```
┌─────────────────────────┐                ┌─────────────────────────┐
│ renderer (Vue 3)        │  contextBridge  │ main (Electron)         │
│ • нет JWT/сетей          │ ──────────────► │ • JWT в safeStorage     │
│ • нет Node API           │  preload bridge │ • REST/SSE/WS-клиенты  │
│ • sandboxed              │                │ • local-tools (bash…)   │
│ • DOMPurify + CSP (D-92) │                │ • релей + тул-оверлей   │
└─────────────────────────┘                └────────────┬────────────┘
                                                      │ HTTPS / WSS
                                                      ▼
                                                server (REST/SSE/WS)
```

- `web-desktop/` — отдельный npm-пакет в monorepo; pnpm + Vite (`electron-vite`).
- Main держит секреты и все сетевые клиенты (REST, WS-релей, SSE).
- Renderer получает API через типизированный `window.harness` (`preload` `contextBridge`); не открывает WS/SSE напрямую.
- CSP `default-src 'self'`; dev — `ws://localhost:*/http://localhost:*` только для HMR.
- За генерацию TS-типов отвечает `openapi-typescript` (`src/api/generated/openapi.d.ts`).

## 2. Реализованные сценарии (batch A–E)

| # | Сценарий | Где |
|---|---|---|
| 2.1 | SSO через Keycloak OAuth2 + PKCE (visible `BrowserWindow` + loopback-redirect); `safeStorage` для токенов; silent refresh на 401 | `src/main/auth.ts` |
| 2.2 | Settings: server baseUrl, Keycloak issuer/clientId, `confirmCommands` (default `always`, D-93), тема; запись в `userData/config.json` | `src/renderer/src/views/SettingsView.vue` |
| 2.3 | WS-релей: handshake `hello`/`welcome` с Bearer-JWT, ping-watchdog (2×interval), reconnect-exponential-backoff, 4401→silent refresh, 4403→fatal, 4409→`superseded`-takeover; toolset-оверлей (parent-chain, D-80/D-84/D-85); cancel во время spawn | `src/main/relay-client.ts` |
| 2.4 | `confirmCommands=always` (D-93): per-session consent на первой регистрации + режим «никогда»; `TOOL_CONFIRM` IPC | `src/main/relay-client.ts` + `src/renderer/src/composables/useRelay.ts` |
| 2.5 | Список сессий: `GET /sessions?mine=true`, поиск с debounce 300 мс, cursor-paging, создание через выбор агента из `GET /agents` | `src/renderer/src/composables/useSessions.ts` + `src/renderer/src/components/SessionList.vue` |
| 2.6 | Лента чата: все `MessageKind` (USER/ASSISTANT/SYSTEM/TOOL_CALL/TOOL_RESULT/COMPACT + ASYNC_ACCEPTED), markdown-рендер (markdown-it + DOMPurify), сворачиваемые tool-блоки, плейсхолдер «ожидает результат», `late`-маркер; начальная загрузка пейджингом `since=0` по `nextCursor` до хвоста | `src/renderer/src/lib/feed.ts` + `src/renderer/src/components/ChatFeed.vue` |
| 2.7 | Отправка + команды: кнопки Compact/Stop в статус-баре (confirm для Stop при `TURN_RUNNING`), индикатор «агент работает…», черновик per-session | `src/renderer/src/views/ChatView.vue` |
| 2.8 | SSE: fetch + `ReadableStream` в main, `Last-Event-ID`→`?since=` реконнект (retry 5000), снапшот при коннекте, `: ping` игнорируется; `SessionSseClient` поверх общего `SseStream` | `src/main/sse.ts` + `src/main/sse-stream.ts` |
| 2.9 | Дерево сессий: `GET /sessions/{id}/tree` для root + STATE-субсессий (taskId/stateCode); polling по `tree.refresh-interval` (10 с); проваливание → открыть чат субагента + breadcrumb | `src/renderer/src/composables/useSessionTree.ts` + `src/renderer/src/components/SessionTree.vue` |
| 2.10 | Панель задачи: `GET /tasks/{id}` (statusProjection) + история walked по opaque-курсору + комментарии + live `task.transition`/`task.status`/`subtask.terminal`/`task.comment` через `TaskSseClient` | `src/renderer/src/composables/useTask.ts` + `src/renderer/src/components/TaskPanel.vue` |
| 2.11 | Артефакты: `GET /sessions/{id}/workspace/files` с UX-валидацией пути (абсолютный/`..` → отказ без сетевого запроса; сервер — security boundary, D-72); save-as через `dialog.showSaveDialog`; open-in-OS через temp-копию `userData/cache/artifacts/<sha256[:16]>-<basename>` и `shell.openPath`; prune по `artifactCacheMaxAgeMs` (7 суток); обработка 404/413/422 → пользовательские сообщения | `src/main/artifact.ts` + `src/renderer/src/views/ArtifactsView.vue` |
| 2.12 | Кликабельные пути в `TOOL_RESULT`: эвристика по расширениям из конфига + исключение URL/symlink/абсолютные; клик → переход на `/artifacts?sessionId=&path=` | `src/renderer/src/lib/artifact-paths.ts` + `src/renderer/src/components/ChatFeed.vue` |

## 3. UX-минимум

- Поток: события рендерятся по `seq`; `TOOL_CALL/TOOL_RESULT` — сворачиваемые блоки; `COMPACT` — маркер «граница раунда»; `ASYNC_ACCEPTED` — плейсхолдер «принято, в полёте» (M3); поздний `TOOL_RESULT` с `late=true` заменяет плейсхолдер.
- Статус-бар чата: `runtimeStatus` (`IDLE`/`TURN_RUNNING`/`PARKED_ASYNC`/`PARKED_CLIENT`), индикатор «агент работает…» при `TURN_RUNNING|PARKED_ASYNC`; relay-статус отдельно («подключён (N инструментов)» / «в другом месте» для `code==='superseded'`).
- Ввод во время хода — обычный `POST /messages`; ассистент подхватит в следующем раунде.
- Артефакты: кликабельный путь в выводе tool → `/artifacts?sessionId=&path=`; save-as через нативный dialog.
- Дерево: breadcrumb `root › … › current`; клик на STATE-узле → открыть чат субагента и развернуть правую панель задачи.

## 4. Числовые параметры (все из конфига, никакого хардкода)

| Где | Параметр | Default |
|---|---|---|
| Session list | `sessionListLimit` | 50 |
| Session list search | `sessionSearchDebounceMs` | 300 |
| Chat history | `chatPageLimit`, `chatHistoryMaxPages` | 100 / 500 |
| SSE | `sseRetryDefaultMs` | 5 000 |
| Tree | `treeRefreshIntervalMs` | 10 000 |
| Task history | `taskHistoryLimit`, `taskHistoryMaxPages` | 50 / 200 |
| Task SSE | `taskSseRetryDefaultMs` | 5 000 |
| Artifact cache | `artifactCacheMaxAgeMs`, `artifactExtensionHint` | 7 суток / `*.txt,*.md,…` |
| Relay | `relayToolCallTimeoutMs` | 295 000 (`<` server 300 000) |
| Relay | `relayPingWatchdogMultiplier` | 2 |
| Relay | `confirmCommands` | `always` (D-93) |

## 5. Безопасность (D-88 / D-92 / D-93)

- **Renderer = sandbox**: `webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false }`; CSP `default-src 'self'`; markdown санитайзится DOMPurify.
- **Локальные инструменты на голом хосте**: bash/файловые исполняются в `basePath` без path-guard; владелец принимает риск отдельной строкой (D-88 — owner-risk-apprув); `confirmCommands=always` смягчает prompt-injection.
- **Серверный canonical-гвард** для `/sessions/{id}/workspace/files` — единственная защита от path-traversal в workspace; UX-валидация в renderer — лишь удобство.
- **Secrets**: JWT и refresh — только в OS keychain через `safeStorage`; plain-text fallback запрещён директивой владельца.

## 6. Тестовое покрытие

- 19 unit-файлов / 166 unit-тестов (Vitest, см. `pnpm verify`):
  - relay-client, local-tools, ws-frames (batch C);
  - sse-parser, sse-client, chat-feed (batch D);
  - path-utils, artifact-paths, session-tree, components/{session-tree,task-panel}, task-sse-client, workspace-fetch, artifact (batch E + ревью-фиксы).
- e2e: `tests/e2e/electron-smoke.spec.ts` — Playwright-electron против in-test stub-сервера (`tests/e2e/stub-server.ts`) — full §5 + §3.1/§3.2 (см. `web-desktop/docs/smoke.md`).

## 7. Невходит в M5 (эволюция, см. AGENTS.md)

- MCP-бриджинг (`client.mcp:<server>` проксирование внешних MCP пользователя).
- Auto-update (electron-updater).
- Мультиоконность (multi-window).
- Capability-URL для SSE/WS браузера (D-42 — `auth/ticket`, не нужен пока клиент — Electron).
