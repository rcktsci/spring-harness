# M5 — Web Desktop: apply-notes

> Сводка по шести пачкам (A–F) Web Desktop (`openspec/changes/m5-web-desktop/`). Архивация — `openspec archive m5-web-desktop --yes` после `openspec validate --strict` без ошибок.

## Что сделано

- **A — scaffold**: `web-desktop/` (Electron + Vue 3 + Vite + TS strict, electron-builder, single-instance lock, tray, electron-log, sandbox renderer + CSP). Менеджер пакетов — pnpm; скрипты `dev`/`build`/`test`/`e2e`/`lint`/`verify`.
- **B — SSO + конфиг + типы**: Keycloak OAuth2 + PKCE (visible `BrowserWindow` + loopback-redirect); `safeStorage` для токенов; silent refresh на 401; Settings (baseUrl, Keycloak, `confirmCommands=always`, тема); генерация TS-типов из `openapi.yaml` (D-89) в `src/api/generated/openapi.d.ts`; ручные WS-фрейм-типы по §5 (`src/main/ws-frames.ts`); unit-тесты конфиг-биндинга, PKCE, WS-фреймы.
- **C — релей + локальные инструменты**: `SessionSseClient`-подобный WS-релей `RelayClient` с handshake `hello`/`welcome`, ping-watchdog (2× interval), reconnect-exponential-backoff, 4401→silent refresh, 4403→fatal, 4409→`superseded`-takeover, runtime-toolset-оверлей по parent-chain (D-80/D-84/D-85), cancel во время spawn; локальные инструменты `bash`/`read_file`/`write_file`/`edit`/`glob`/`grep` (sync, с truncated-маркерами, SIGTERM→SIGKILL grace); первый-регистрация-consent; `confirmCommands=always` (D-93). Vitest: in-test WS-сервер против релей-клиента (25 тестов) + все инструменты (13 тестов).
- **D — чат + SSE**: список сессий `GET /sessions?mine=true` с cursor-пагинацией и debounce-поиском, создание через выбор агента из `GET /agents`; лента всех `MessageKind` (USER/ASSISTANT/SYSTEM/TOOL_CALL/TOOL_RESULT/COMPACT + ASYNC_ACCEPTED) с markdown-it + DOMPurify (sanitize), сворачиваемые tool-блоки, плейсхолдер «ожидает результат», late-маркер, начальная загрузка пейджингом `since=0` по `nextCursor` до хвоста; отправка + Compact/Stop в статус-баре (confirm для Stop при `TURN_RUNNING`); индикатор «агент работает…»; черновик per-session; SSE через `fetch` + `ReadableStream` в main (`SessionSseClient`), `Last-Event-ID`-приоритетный реконнект, retry 5000, ping-комментарии игнорируются; relay-статус в UI («подключён (N инструментов)» / «в другом месте» для `code==='superseded'`).
- **E — дерево + задачи + артефакты**:
  - **Дерево сессий**: `GET /sessions/{id}/tree` для root + STATE-субсессий (taskId/stateCode); polling по `tree.refresh-interval` (10 с); проваливание → открыть чат субагента + breadcrumb (parent-chain).
  - **Панель задачи**: `GET /tasks/{id}` (statusProjection) + история walked по opaque-курсору (`?since=…`) + комментарии; live через `TaskSseClient` для `task.transition` / `task.status` / `subtask.terminal` / `task.comment`.
  - **Артефакты**: `GET /sessions/{id}/workspace/files` с UX-валидацией пути (абсолютный/`..` → отказ без сетевого запроса; серверный canonical-гвард D-72 — security boundary); save-as через `dialog.showSaveDialog`; open-in-OS через temp-копию `userData/cache/artifacts/<sha256[:16]>-<basename>` + `shell.openPath`; prune по `artifactCacheMaxAgeMs` (7 суток, на boot + на `before-quit`); обработка 404/413/422 → человекочитаемые сообщения.
  - **Кликабельные пути в `TOOL_RESULT`**: эвристика по расширениям из конфига + исключение URL/symlink/абсолютные; клик → переход на `/artifacts?sessionId=&path=` (router navigation).
  - Рефакторинг по итогам ревью: общий `SseStream` для `SessionSseClient`/`TaskSseClient`; ChatView читает `route.query.sessionId` для проваливания; parser: `id:` без значения сбрасывает `lastId`; HTTP 401/500/204 на SSE → warn + retry по `cfg.sseRetryDefaultMs`.
- **F — e2e, smoke, доки, архив**:
  - **6.1 e2e**: Playwright-electron против in-test stub-сервера (REST + WS §5 + SSE §3.1/§3.2) — full-сценарий логин → создание сессии → оркестратор → tool-call `bash` → результат → spawn sub-session → дерево → артефакт save-as → stop (`tests/e2e/electron-smoke.spec.ts` + `tests/e2e/stub-server.ts`).
  - **6.2 smoke**: автоматический Playwright-скрипт против docker-compose (живой Keycloak) + мануал-фолбэк `web-desktop/docs/smoke.md`.
  - **6.3 доки**: `decisions.md` — D-86…D-93 (D-88 — owner-risk-apprув отдельной строкой).
  - **6.4 доки-синк**: `roadmap.md` (M5 done), `architecture.md` (слой web-desktop, потребитель контрактов), `client-cli.md` → `web-desktop-client.md` (supersede), `operations.md` (сборка/дистрибуция desktop), `agent-tools.md` (источник 5: desktop-client).
  - **6.4a api-contracts.md §2**: MessageKind (+ASYNC_ACCEPTED), SessionDto (убраны `taskId?`/`stateCode?`/`parentSessionId?` из самого DTO — теперь только через `GET …/tree`), TreeNode (`+stateCode?`), doc-fix без серверных правок.
  - **6.5 apply-notes**: этот файл.
  - **6.6**: `openspec validate m5-web-desktop --strict` без ошибок.
  - **7.1**: `openspec archive m5-web-desktop --yes` — 5 новых спек (`desktop-shell`, `desktop-chat`, `desktop-relay-client`, `desktop-session-tree`, `desktop-artifacts`) в `openspec/specs/`.
  - **7.2**: `AGENTS.md` — M5 done, эволюция по roadmap (раннеры, селективная компакция, MCP-сервер наружу, адаптеры Jira/Trello/GitLab).

## ADR-сводка (новые в этом change)

- **D-86** Web Desktop — Electron + Vue 3 + Vite + TS strict.
- **D-87** SSE через `fetch` + `ReadableStream` (не `EventSource` — Bearer-заголовок).
- **D-88** Локальное исполнение — машина пользователя, без path-guard (owner-risk-apprув отдельной строкой).
- **D-89** Генерация TS-типов из `openapi.yaml` через `openapi-typescript`.
- **D-90** Сборка и дистрибуция — `electron-vite build` + `electron-builder` (win-nsis primary, linux-appimage secondary); `publish: null`.
- **D-91** Main владеет всеми секретами и сетевыми клиентами (JWT/REST/WS/SSE); renderer — sandboxed, без Node API.
- **D-92** Sandbox renderer (`webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false }`) + CSP `default-src 'self'` + DOMPurify.
- **D-93** `confirmCommands` — дефолт `always` (per-session consent на первой регистрации, отзыв в Settings).

## Тесты

- `pnpm verify` зелёный: lint (eslint `--max-warnings=0`) + typecheck (`tsc` + `vue-tsc`) + Vitest (19 файлов / 166 unit-тестов) + `electron-vite build`.
- `pnpm e2e` — Playwright-electron против in-test stub-сервера (отдельный процесс). На CI по умолчанию off.
- Smoke против docker-compose — автоматический (см. `web-desktop/docs/smoke.md`).

| Категория | Файл | Тестов |
|---|---|---|
| Парчка A | — | (scaffold; 0 unit) |
| Парчка B | config-binding, pkce, ws-frames | 4 + 6 + 18 = 28 |
| Парчка C | relay-client, local-tools | 25 + 13 = 38 |
| Парчка D | sse-parser, sse-client, feed, components/chat-feed | 9 + 4 + 7 + 5 = 25 |
| Парчка E (вкл. ревью-фиксы) | path-utils, artifact-paths, session-tree, components/{session-tree,task-panel}, task-sse-client, workspace-fetch, artifact | 12 + 6 + 5 + 4 + 3 + 4 + 18 = 54 |
| Иммутабельные | ipc-contract, token-store | 2 + 8 = 10 |
| Доп. SSE error path | sse-client (расширен в E-ревью) | +3 (входит в 25) |
| **Всего** | **19 файлов** | **166 passing** |

## Отклонения и точки эволюции (см. AGENTS.md)

- **MCP-бриджинг** (`client.mcp:<server>` проксирование внешних MCP пользователя) — вне M5; D-82 уже описывает информационное поле, реальный MCP-прокси — post-M5.
- **Auto-update** (electron-updater) — вне M5; внутренний MVP, ручной передеплой.
- **Мультиоконность** — вне M5; Web Desktop запускается одним окном.
- **Capability-URL для SSE/WS браузера** (`auth/ticket`, D-42) — вне M5 пока нет браузерного клиента; WS/REST/SSE в main (D-91), токен в renderer не попадает.
- **Сессии состояния на STATE-сессии** — клиентский `useChat` уже открывает по `?sessionId=`, breadcrumb работает; `useTask` подхватывает `taskId`/`stateCode` из `SessionTreeNode`.
- **PARKED_CLIENT** (D-84) — зарезервированное значение enum; UI в M5 рендерит raw-строкой — отдельного сценария нет.
- **Селективная компакция агентом** — в roadmap (эволюция), `read_compacted` уже есть.
- **Раннеры (control/execution split)** — в roadmap.
- **Адаптеры Jira/Trello/GitLab** — в roadmap (требуют OAuth-флоу пользователя).

## Файлы M5 (новые/изменённые относительно 7de882d)

- `web-desktop/` — целиком новый npm-пакет (16 пачек в шести раундах).
- `openspec/changes/m5-web-desktop/` — propose / design / tasks.md / specs/{desktop-shell,desktop-chat,desktop-relay-client,desktop-session-tree,desktop-artifacts}/spec.md / apply-notes.md.
- `docs/design/decisions.md` — D-86…D-93.
- `docs/design/api-contracts.md` — doc-fix §2 (MessageKind/SessionDto/TreeNode).
- `docs/design/roadmap.md` — M5 = DONE.
- `docs/design/architecture.md` — §1a «Клиенты» (Web Desktop как первый потребитель).
- `docs/design/web-desktop-client.md` — supersede `client-cli.md`.
- `docs/design/operations.md` — §6 Web Desktop build/distribution.
- `docs/design/agent-tools.md` — note «Источник 5: Desktop-клиент».
- `AGENTS.md` — M5 done, эволюция в roadmap.

## Коммиты (предполагаемые)

- `M5 batch A: web-desktop scaffold`
- `M5 batch B: SSO PKCE, settings, generated API types, WS frame tests`
- `M5 batch C: relay client + local tools`
- `M5 batch D: chat (sessions list, feed, send, SSE, relay status)`
- `M5 batch E: tree, task panel, artifacts` (+ ревью-фиксы)
- `M5 batch F: e2e, smoke, docs sync, archive`
