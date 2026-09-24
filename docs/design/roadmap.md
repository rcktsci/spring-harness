# Дорожная карта spring-harness

> Фазы = вертикальные срезы; каждая — отдельный openspec-change (`/opsx:propose`) со ссылками на дизайн-доки. Правило приёмки фазы: интеграционные тесты (Testcontainers Postgres + WireMock LLM/MCP) + ревью субагентами по корпоративному циклу.

## M1 — Ядро сессий (фундамент)

**Объём**: identity (SSO-гейт по groups-claim + синхронизация users), `SessionStore` (append-only, рендер видимости), `LlmGateway` (ChatModel из LlmModel, стриминг, отмена), агентный цикл (sync-инструменты), helper-образ + docker-java + `ContainerWorkspaceTools` (per-session контейнеры — D-30), TurnManager (wake EVENT+POLL; **локи сессий — ShedLock `sess-{id}`, D-40**), REST/SSE сессий-сообщений, страховочная компакция, **минимальный attach (стриминг + отправка)**. Начальные llm_credentials/agent — вручную в БД.
**Критерий готовности**: FREE-сессия через минимальный attach работает end-to-end: сообщение → модель → инструменты **в helper-контейнере** → стриминг; рестарт сервера не теряет сессии; контейнер сессии поднимается/уничтожается корректно.

> **Порядок клиентов гибкий (D-42)**: WebUI может пойти сразу после M1 — раньше attach-CLI. Решение — по ходу, владельцем. Билеты SSE/WS — часть Web Desktop-фазы; скачивание workspace-файлов реализовано в M4 (api-contracts §8).

## M2 — Workflow-движок

**Объём**: `WorkflowRegistry` + валидация графов, задачи (пин ревизии, `task_transition_history`, статус-проекции), STATE-сессии (уникальность пары, резюм), **минимальный мета-инструмент `transition` (обязательный reason)**, системные состояния BASH_SCRIPT / WAIT_WEBHOOK (stateless) / WAIT_TASKS (scope-выражения, теги, зависимости, цикл-валидация), подзадачи и `blocked_by`, REST+SSE задач, **триггеры + вебхук-эндпоинты (capability-URL, D-05/D-25/D-26; решение владельца — в M2)**.
**Критерий**: сценарий «двухфазное ревью с возвратом» на тестовом графе проходит (агент переводит задачу инструментом `transition`), включая таймауты и error-пути bash-состояний.

## M3 — Агентский слой

**Объём**: ревизии агентов + llm-профили, `spawn_subagent` + `read_compacted`, async-инструменты (окно, плейсхолдер, поздние результаты, рестарт-скан, LOST-контур контейнеров), отмена-поддерево, оркестратор-агент (инструменты §2b agent-tools).
**Критерий**: сценарий «Сделай биллинг» (оркестратор режет на подзадачи, стейджи WAIT_TASKS, ревью-цикл) на CI-стенде.

## M4 — Клиенты и релей (server-side)

**Объём**: WS-релей `/api/v1/relay` (handshake, регистрация на **сессию** с декларацией инструментов, takeover, `tool.call`/`result`/`cancel`/`progress`, серверный heartbeat — D-83/D-84), клиентский toolset как runtime-оверлей (parent-chain, гейт нативных файловых, D-80/D-85), скачивание серверного workspace (api-contracts §8, D-72), приёмка «Роуминг».
**CLI отменён**: attach-CLI не делается — первым клиентом будет **Web Desktop (Electron + VueJS) отдельным этапом**; в M4 клиентская часть — только тестовый WS-клиент. `PARKED_CLIENT` — зарезервированное значение enum, присвоение вне M4.
**Критерий**: `AcceptanceWorkspaceRoamingTest` — root FREE-сессия с оркестратором, офисы A/B (takeover офиса A → close 4409 `superseded`), клиентские инструменты исполняются клиентом (не серверным контейнером), disconnect → `tool-not-available`, суб-сессия (spawn-путь) видит оверлей по parent-chain; финал SUCCESS.

## M5 — Web Desktop (Electron + VueJS) — DONE (2026-09)

Полноценный десктоп-клиент, чистый потребитель замороженных M4-контрактов (`api-contracts.md`). Реализовано по шести пачкам (A–F, см. `openspec/changes/m5-web-desktop/`):

- **A**: scaffold — Electron + Vue 3 + Vite + TS strict; окно 1200×800, tray, electron-log, single-instance lock.
- **B**: SSO PKCE + safeStorage + silent refresh; Settings (baseUrl, Keycloak, `confirmCommands=always` по D-93, тема); генерация TS-типов из `openapi.yaml` (D-89); WS-фрейм-схема по §5.
- **C**: WS-релей-клиент (full §5: handshake, ping-watchdog, 4401→silent refresh, 4403→fatal, 4409→`superseded`-takeover, parent-chain toolset-оверлей, cancel-во-время-spawn) + локальные инструменты (`bash`/`read_file`/`write_file`/`edit`/`glob`/`grep` с truncated-маркерами и SIGTERM→SIGKILL grace) + первый-регистрация-consent.
- **D**: список сессий (`GET /sessions?mine=`), лента всех `MessageKind` (markdown-it + DOMPurify, сворачиваемые tool-блоки, плейсхолдер, late-маркер, пейджинг `since=0` до хвоста), отправка + Compact/Stop (confirm при TURN_RUNNING), `confirmCommands=always`-индикатор «агент работает…», черновик per-session, SSE через `fetch` + `ReadableStream` (`SessionSseClient` поверх общего `SseStream`), релей-статус в UI.
- **E**: дерево сессий (`GET /sessions/{id}/tree`) + проваливание в STATE-сессии + breadcrumb + polling `tree.refresh-interval`; панель задачи (`GET /tasks/{id}` + история + комментарии + `TaskSseClient` §3.2); артефакты (UX-валидация + save-as через dialog + open-in-OS через temp-кэш `<sha256[:16]>-<basename>` + обработка 404/413/422; кликабельные пути в `TOOL_RESULT`).
- **F**: Playwright-electron e2e против in-test stub-сервера (REST + WS §5 + SSE §3.1/§3.2) с полным сценарием (логин → создание сессии → оркестратор → tool-call `bash` → результат → spawn sub-session → дерево → артефакт save-as → stop); smoke-скрипт против docker-compose (живой Keycloak); доки-синк (D-86…D-93 в `decisions.md`; `client-cli.md` → `web-desktop-client.md`); `apply-notes.md` + archive.

Ключевые ADR: D-86 (стек), D-87 (SSE через fetch), **D-88 (локальное исполнение — owner-risk-apprув)**, D-89 (генерация типов), D-90 (сборка), D-91 (main владеет секретами и клиентами), D-92 (sandbox + CSP), D-93 (`confirmCommands` дефолт `always`).

**Критерий**: e2e против stub-сервера зелёный (`pnpm e2e`); `pnpm verify` зелёный (19 unit-файлов, 166 unit-тестов); `openspec validate m5-web-desktop --strict` без ошибок; архив через `openspec archive m5-web-desktop --yes`.

## Эволюция (не фазы, закладки — см. AGENTS.md после M5)

Раннеры (вынос TurnManager в отдельные контейнеры), селективная компакция (агентский инструмент поверх COMPACT), MCP-сервер наружу (D-63 уже в ядре — нужен операторский UI для настройки `harness.mcp.servers`), ротация capability-секретов (HMAC + `revoked_at` уже есть — нужна cron-джоба), адаптеры задач в Jira/Trello/GitLab (требуют OAuth-флоу пользователя), multi-instance состояний (горизонтальное масштабирование — ShedLock-реестр переезжает на Redis).

> **Порядок эволюции** — за владельцем. Известные триггеры: рост нагрузки (раннеры), потребность в интеграциях (MCP/Jira/Trello/GitLab), запрос на веб-клиент (мультиоконность/браузер — `auth/ticket` по D-42).