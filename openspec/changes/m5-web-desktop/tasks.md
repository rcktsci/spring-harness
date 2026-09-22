## 1. Пачка A — Scaffold и shell

- [ ] 1.1 `web-desktop/` scaffold: package.json, electron.vite.config.ts, tsconfig (strict), eslint+prettier, electron-builder.yml (win-nsis + linux-appimage, publish: null), `.gitignore` (node_modules, dist, out).
- [ ] 1.2 main: `index.ts` (окно 1200×800, state persistence, single-instance lock), menu, tray (конфиг), логи (electron-log, ротация, `log-level`).
- [ ] 1.3 preload: `contextBridge` API (contextIsolation, sandbox: true, no nodeIntegration, CSP `default-src 'self'` — D-92) — типизированный контракт main↔renderer; **main владеет JWT + всеми сетевыми клиентами** (D-91).
- [ ] 1.4 renderer: Vue 3 + Pinia + router-minimal; каркас views (Login/Chat/Tree/Artifacts/Settings); компонент-скелет.
- [ ] 1.5 CI/скрипты: `pnpm test` (Vitest), `pnpm e2e` (Playwright-electron), `pnpm lint`; README папки.

## 2. Пачка B — SSO, конфиг, типы

- [ ] 2.1 Keycloak OAuth2 + PKCE: **видимое** BrowserWindow (форма логина Keycloak), loopback redirect, обмен кода; safeStorage для токенов; silent refresh на 401 (без окна); logout (очистка + Keycloak logout URL).
- [ ] 2.2 Settings: `server.baseUrl`, Keycloak-параметры, `confirmCommands`, тема; `userData/config.json`; смена baseUrl → переподключение.
- [ ] 2.3 D-89: генерация TS-типов/клиента из `src/main/resources/api/openapi.yaml` (openapi-typescript); ручные WS-фрейм-типы по api-contracts §5 (`src/api/ws-frames.ts`).
- [ ] 2.4 Vitest: конфиг-бдиндинг, token-storage mock, PKCE-флоу (unit на verifier/challenge).
- [ ] 2.4а WS-фрейм-схема: unit-тесты парсинга всех фреймов §5 (hello/welcome/register/registered/error/tool.*/ping/pong + close-коды) — защита от дрифта ручных типов `ws-frames.ts`.

## 3. Пачка C — relay-клиент и локальные инструменты

- [ ] 3.1 `useRelay` composable + WS-клиент: connect (Bearer), hello/welcome, handshake timeout, reconnect backoff (1s→30s), pong на ping, 4401 → silent refresh, 4403 → фатальная ошибка без реконнекта; lifecycle при переключении сессий (3.x — см. spec).
- [ ] 3.2 register: sessionId + basePath (`~/harness-workspaces/{sessionId}` по умолчанию, выбор пользователем; main-process создаёт каталог до отправки фрейма) + декларация стандартного набора; обработка `registered`/`workspace-occupied` (сообщение «занята другим пользователем», без takeover-диалога)/`session-not-found`/`wrong-session-kind`/`duplicate-tool-name`/`superseded`; авто-connect + register при старте с сохранённой активной сессией.
- [ ] 3.3 Локальные инструменты: `bash` (child_process.spawn, cwd=basePath, таймаут = min(args timeout, серверный tool-call-timeout), stdout+stderr, exitCode), read/write/edit (fs, лимиты, `ambiguous`/`not-found`), glob, grep, truncated-маркеры; `tool.progress` для длинных выводов; `tool.cancel` (SIGTERM→SIGKILL; cancel неизвестного callId — игнор).
- [ ] 3.4 Подтверждение первой регистрации («разрешить оркестратору выполнять команды в X»); режим `confirmCommands` (always/never) (spec: desktop-relay-client → Безопасность локального исполнения).
- [ ] 3.5 Vitest: WS-клиент против in-test WS-сервера (ws-стаб); все фреймы; takeover; cancel-во-время-spawn; все инструменты.

## 4. Пачка D — Чат и SSE

- [ ] 4.1 Список сессий (`GET /sessions?mine=`): бейджи runtimeStatus, поиск, курсор-пагинация; создание сессии (выбор агента).
- [ ] 4.2 Лента: все MessageKind (USER/ASSISTANT/SYSTEM/TOOL_CALL/TOOL_RESULT/COMPACT), markdown (markdown-it + DOMPurify sanitize), сворачиваемые tool-блоки, плейсхолдер «ожидает результат» (TOOL_RESULT status=ASYNC_ACCEPTED или TOOL_CALL без результата), late-маркеры; начальная загрузка пейджингом `since=0` по nextCursor до хвоста.
- [ ] 4.3 Отправка + команды: кнопки Compact/Stop в строке состояния (подтверждение для Stop), индикатор «агент работает…», черновик per-session.
- [ ] 4.4 SSE fetch-stream (`useSse` в main): message.created/session.status, снапшот при коннекте, ping-комментарии, Last-Event-ID реконнект (retry 5000), отписка при переключении; renderer получает события через IPC.
- [ ] 6.4а `docs/design/api-contracts.md` §2 — сверка SessionDto/runtimeStatus с openapi.yaml (устаревший текст после M4).
- [ ] 4.5 Статус релея в UI: «подключён (N инструментов)» / «в другом месте» (4409) / кнопки подключить/отключить.
- [ ] 4.6 Vitest + component-тесты ленты (рендер всех kind), SSE-мокстрим.

## 5. Пачка E — Дерево сессий/задач и артефакты

- [ ] 5.1 Дерево активной сессии (`GET /sessions/{id}/tree`): узлы sub-сессий (taskId, stateCode, бейджи); обновление при переключении + по таймеру `tree.refresh-interval` (10 с) + при смене session.status; проваливание (открытие чата STATE-сессии + breadcrumb).
- [ ] 5.2 Панель задачи: статус/переходы (task SSE: transition/status/subtask.terminal/comment), история, добавление комментариев; `statusProjection` — через `GET /tasks/{id}`.
- [ ] 5.3 Артефакты: ввод пути + UX-валидация относительности (без `..`/абсолютных), save-as, open-in-OS (temp `<hash(path)>-<basename>`, чистка кэша), обработка 422/413; кликабельные пути из ленты TOOL_RESULT.
- [ ] 5.4 Vitest: дерево (мок tree), task SSE-мокстрим, артефакты (мок fetch-ответы).

## 6. Пачка F — e2e, smoke, доки, архив

- [ ] 6.1 Playwright-electron e2e против stub-сервера (in-test HTTP+WS+SSE сервер, полнофтанно реализующий §3.1/§5): логин → новая сессия → регистрация релея → оркестратор (фиксированный LLM-ответ с tool-call `bash` из stub-сервера) вызывает локальный bash → tool.result → лента показывает → spawn sub-session → дерево → артефакт save-as → stop.
- [ ] 6.2 Smoke-скрипт против живого сервера (docker-compose): пошаговый мануал + скрипт-чеклист (в `web-desktop/docs/smoke.md`).
- [ ] 6.3 `docs/design/decisions.md` — D-86…D-92.
- [ ] 6.4 Доки-синк: `roadmap.md` (M5 финальная редакция), `architecture.md` (слой web-desktop, потребитель контрактов), `client-cli.md` → переименовать/суперседеть в «Web Desktop» (полнокровный клиент), `operations.md` (сборка/дистрибуция desktop), `agent-tools.md` (источники: native/metaTools/MCP/desktop-client).
- [ ] 6.5 `apply-notes.md` — сводка пачек, тесты, отклонения (MCP-бриджинг, auto-update, мультиоконность — вне M5).
- [ ] 6.6 `openspec validate m5-web-desktop --strict`; Playwright e2e зелёный.

## 7. Архив

- [ ] 7.1 `openspec archive m5-web-desktop --yes` — 5 новых спек в `openspec/specs/`.
- [ ] 7.2 `AGENTS.md` — M5 завершён; следующий шаг — эволюция (раннеры, селективная компакция, MCP-сервер наружу, адаптеры Jira/Trello/GitLab) по roadmap.
- [ ] 7.3 Commit + push.
