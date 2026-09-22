## Why

M4 закрыл серверную часть релея: WS-протокол, клиентский toolset-оверлей, скачивание workspace. Но потребителя нет — владелец подключается к сессии с оркестратором только через тестовый WS-клиент. M5 — первый настоящий клиент: **Web Desktop (Electron + VueJS)**, который даёт чат с оркестратором, дерево сессий/задач, проваливание в субагентов, скачивание артефактов и полноценный WS-клиент релея (исполнение `tool.call` локально — «мои инструменты»). Backend уже готов (`api-contracts.md` §1–§8), M5 — чистый потребитель, без серверных изменений.

## What Changes

- **Desktop-приложение** (новая папка `web-desktop/` в monorepo): Electron + Vue 3 + Vite + TypeScript. Main-process shell, preload-мост, renderer на Vue.
- **SSO-логин**: Keycloak OAuth redirect (видимое BrowserWindow) → JWT → secure-хранение (`safeStorage`); сервер URL — конфиг приложения.
- **WS-клиент релея** (`/api/v1/relay`): `hello`/`register { sessionId, basePath, client.tools[] }`/`tool.call`/`tool.progress`/`tool.result`/`tool.cancel`/`pong`; reconnect и takeover (4409 `superseded`); heartbeat-ответы.
- **Локальные инструменты**: стандартный файловый набор (`bash`, `read_file`, `write_file`, `edit_file`, `glob`, `grep`) исполняется desktop'ом локально (Node `child_process`/`fs`) и декларируется серверу — оркестратор и его субагенты получают CLIENT-toolset (D-84). `source: "client.mcp:<server>"`-декларации из внешних MCP-серверов пользователя — **вне M5** (эволюция).
- **Чат-интерфейс**: список сессий, лента сообщений (User/Assistant/TOOL_CALL/TOOL_RESULT, markdown, сворачиваемые tool-фреймы, `ASYNC_ACCEPTED`-плейсхолдеры, `late`-маркеры), отправка, `compact`/`stop`.
- **Дерево сессий/задач**: FREE-root + STATE-сессии и задачи; проваливание в субагентов (parent-chain навигация); статусы задач и переходы (task SSE).
- **Артефакты**: скачивание/открытие файлов серверного workspace (`GET /sessions/{id}/workspace/files`) по вводимому или кликабельному из ленты пути, save-as / open-in-OS. Каталог-листинг на сервере отсутствует — браузер файлов вне M5.
- **SSE через fetch-stream**: `EventSource` не позволяет задать Bearer-заголовок — используется `fetch` + `ReadableStream` (D-87).

## Capabilities

### New Capabilities

- `desktop-shell`: Electron main-process — окно, tray/меню, SSO-логин (Keycloak redirect → JWT → safeStorage), конфиг сервера, lifecycle, авто-подключение релея к активной сессии, логи приложения.
- `desktop-relay-client`: WS-клиент — handshake, регистрация с декларацией локальных инструментов, локальное исполнение `tool.call` (bash/read/write/edit/glob/grep), `tool.progress`/`tool.result`/`tool.cancel`, reconnect/takeover, heartbeat pong.
- `desktop-chat`: UI чата — список сессий, лента сообщений (рендер всех MessageKind, markdown, tool-фреймы, late/ASYNC_ACCEPTED), отправка сообщений, команды compact/stop, SSE-подписка (fetch-stream).
- `desktop-session-tree`: дерево сессий и задач — FREE/STATE-узлы, parent-chain навигация в субагентов, задачные статусы/переходы (task SSE), переключение активной сессии.
- `desktop-artifacts`: скачивание/открытие файлов серверного workspace по пути (ручной ввод или кликабельные ссылки из ленты), save-as / open-in-OS, UX-валидация относительности пути (не безопасность — серверный гвард D-72 достаточен).

### Modified Capabilities

Нет — Web Desktop чистый потребитель готовых контрактов (`session-api`, `client-relay`, `client-tool-bridge`, `workspace-download`, `task-engine`/`workflow-engine` SSE). Серверных изменений нет.

## Impact

- **Код**: новая папка `web-desktop/` (npm-пакет в monorepo): Electron main, preload, Vue-renderer, общие TS-типы из `openapi.yaml` (генерация `openapi-typescript` или ручные типы по контрактам — решается в design).
- **Сборка**: `electron-vite` + `electron-builder`; npm-скрипты в корне (`pnpm`/`npm` — выбрать в design); Java-сборка не меняется (Maven-проект не зависит от папки).
- **API**: без изменений; используются REST `/api/v1/sessions*`, `/tasks*`, `/workspace/files`, WS `/api/v1/relay`.
- **Зависимости**: новый JS-стек (electron, vue, vite, typescript, pinia, markdown-рендер) — всё в `web-desktop/package.json`, изолированно от Maven.
- **Документы**: `roadmap.md` (M5 финальная редакция), `architecture.md` (слой web-desktop), `decisions.md` (D-86…), `client-cli.md` — переименовать/суперседнуть в «Web Desktop» (документ клиента), `operations.md` (сборка/дистрибуция desktop).
- **Тесты**: Vitest (unit/component) + Playwright-electron e2e против stub-сервера; smoke против живого сервера — manual/CI.

## Non-goals

- **MCP-бриджинг** (`source: client.mcp:<server>` — проксирование внешних MCP-серверов пользователя) — эволюция после M5.
- **Мобильный/браузерный клиент**, `auth/ticket` — не нужен (Electron держит JWT).
- **Auto-update** (electron-updater) — внутреннее приложение одной VM; дистрибуция — сборка по запросу.
- **Мультиоконность** — одно окно, виды переключаются внутри.
- **Каталог-листинг workspace** (браузер файлов) — серверного эндпоинта нет; эволюция (мини-амендмент при необходимости).
- Серверные доработки любого рода.
