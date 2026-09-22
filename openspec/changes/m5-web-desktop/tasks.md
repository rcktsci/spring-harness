## 1. Пачка A — Scaffold и shell

- [ ] 1.1 `web-desktop/` scaffold: package.json, electron.vite.config.ts, tsconfig (strict), eslint+prettier, electron-builder.yml (win-nsis + linux-appimage, publish: null), `.gitignore` (node_modules, dist, out).
- [ ] 1.2 main: `index.ts` (окно 1200×800, state persistence, single-instance lock), menu, tray (конфиг), логи (electron-log, ротация, `log-level`).
- [ ] 1.3 preload: `contextBridge` API (contextIsolation, no nodeIntegration) — типизированный контракт main↔renderer.
- [ ] 1.4 renderer: Vue 3 + Pinia + router-minimal; каркас views (Login/Chat/Tree/Artifacts/Settings); компонент-скелет.
- [ ] 1.5 CI/скрипты: `pnpm test` (Vitest), `pnpm e2e` (Playwright-electron), `pnpm lint`; README папки.

## 2. Пачка B — SSO, конфиг, типы

- [ ] 2.1 Keycloak OAuth2 + PKCE: скрытый BrowserWindow, loopback redirect, обмен кода; safeStorage для токенов; silent refresh на 401; logout (очистка + Keycloak logout URL).
- [ ] 2.2 Settings: `server.baseUrl`, Keycloak-параметры, `confirmCommands`, тема; `userData/config.json`; смена baseUrl → переподключение.
- [ ] 2.3 D-89: генерация TS-типов/клиента из `src/main/resources/api/openapi.yaml` (openapi-typescript); ручные WS-фрейм-типы по api-contracts §5 (`src/api/ws-frames.ts`).
- [ ] 2.4 Vitest: конфиг-бдиндинг, token-storage mock, PKCE-флоу (unit на verifier/challenge).

## 3. Пачка C — relay-клиент и локальные инструменты

- [ ] 3.1 `useRelay` composable + WS-клиент: connect (Bearer), hello/welcome, handshake timeout, reconnect backoff (1s→30s), pong на ping, 4401 → silent refresh.
- [ ] 3.2 register: sessionId + basePath (`~/harness-workspaces/{sessionId}` по умолчанию, выбор пользователем) + декларация стандартного набора; обработка `registered`/`workspace-occupied` (диалог takeover)/`session-not-found`/`wrong-session-kind`/`duplicate-tool-name`/`superseded`.
- [ ] 3.3 Локальные инструменты: `bash` (child_process.spawn, cwd=basePath, таймаут, stdout+stderr, exitCode), read/write/edit (fs, лимиты, `ambiguous`/`not-found`), glob, grep, truncated-маркеры; `tool.progress` для длинных выводов; `tool.cancel` (SIGTERM→SIGKILL).
- [ ] 3.4 Подтверждение первой регистрации («разрешить оркестратору выполнять команды в X»); режим `confirmCommands` (always/never).
- [ ] 3.5 Vitest: WS-клиент против in-test WS-сервера (ws-стаб); все фреймы; takeover; cancel-во-время-spawn; все инструменты.

## 4. Пачка D — Чат и SSE

- [ ] 4.1 Список сессий (`GET /sessions?mine=`): бейджи runtimeStatus, поиск, курсор-пагинация; создание сессии (выбор агента).
- [ ] 4.2 Лента: все MessageKind (USER/ASSISTANT/SYSTEM/TOOL_CALL/TOOL_RESULT/COMPACT/ASYNC_ACCEPTED), markdown (markdown-it + sanitize), сворачиваемые tool-блоки, late-маркеры, подгрузка по курсору.
- [ ] 4.3 Отправка + команды: `/compact`, `/stop` (с подтверждением), индикатор TURN_RUNNING, черновик per-session.
- [ ] 4.4 SSE fetch-stream (`useSse`): message.created/session.status, Last-Event-ID реконнект, отписка при переключении.
- [ ] 4.5 Статус релея в UI: «подключён (N инструментов)» / «в другом месте» (4409) / кнопки подключить/отключить.
- [ ] 4.6 Vitest + component-тесты ленты (рендер всех kind), SSE-мокстрим.

## 5. Пачка E — Дерево сессий/задач и артефакты

- [ ] 5.1 Дерево активной сессии (`GET /sessions/{id}/tree`): узлы sub-сессий (taskId, stateCode, бейджи); обновление по событиям; проваливание (открытие чата STATE-сессии + breadcrumb).
- [ ] 5.2 Панель задачи: статус/переходы (task SSE: transition/status/subtask.terminal/comment), история, добавление комментариев.
- [ ] 5.3 Артефакты: браузер файлов workspace, save-as, open-in-OS (temp-копия), обработка 422/413.
- [ ] 5.4 Vitest: дерево (мок tree), task SSE-мокстрим, артефакты (мок fetch-ответы).

## 6. Пачка F — e2e, smoke, доки, архив

- [ ] 6.1 Playwright-electron e2e против stub-сервера (HTTP+WS+SSE в тесте): логин → новая сессия → регистрация релея → оркестратор (stub-LLM ответы) вызывает локальный bash → tool.result → лента показывает → spawn sub-session → дерево → артефакт save-as → stop.
- [ ] 6.2 Smoke-скрипт против живого сервера (docker-compose): пошаговый мануал + скрипт-чеклист (в `web-desktop/docs/smoke.md`).
- [ ] 6.3 `docs/design/decisions.md` — D-86…D-91.
- [ ] 6.4 Доки-синк: `roadmap.md` (M5 финальная редакция), `architecture.md` (слой web-desktop, потребитель контрактов), `client-cli.md` → переименовать/суперседеть в «Web Desktop» (полнокровный клиент), `operations.md` (сборка/дистрибуция desktop), `agent-tools.md` (источники: native/metaTools/MCP/desktop-client).
- [ ] 6.5 `apply-notes.md` — сводка пачек, тесты, отклонения (MCP-бриджинг, auto-update, мультиоконность — вне M5).
- [ ] 6.6 `openspec validate m5-web-desktop --strict`; Playwright e2e зелёный.

## 7. Архив

- [ ] 7.1 `openspec archive m5-web-desktop --yes` — 5 новых спек в `openspec/specs/`.
- [ ] 7.2 `AGENTS.md` — M5 завершён; следующий шаг — эволюция (раннеры, селективная компакция, MCP-сервер наружу, адаптеры Jira/Trello/GitLab) по roadmap.
- [ ] 7.3 Commit + push.
