## Context

M4 заморозил серверные контракты: REST (`/api/v1/sessions*`, `/tasks*`, `/workspace/files`), SSE (session/task events), WS-релей (`/api/v1/relay` с register-by-session и клиентским toolset-оверлеем). Web Desktop — первый настоящий потребитель: backend-изменений нет. Проект — Maven-монолит; JS-стека нет. Папка `web-desktop/` — отдельный npm-пакет в monorepo (изолированно от Java-сборки).

## Goals / Non-Goals

**Goals:**
- Electron + Vue 3 + Vite + TS приложение в `web-desktop/`.
- SSO-логин (Keycloak OAuth + PKCE), JWT в safeStorage.
- WS-клиент релея: регистрация на сессии, локальное исполнение стандартного файлового набора, cancel, heartbeat, reconnect/takeover.
- Чат: список сессий, лента всех MessageKind, отправка, compact/stop, SSE через fetch-stream.
- Дерево сессий/задач с проваливанием в субагентов и task SSE.
- Артефакты: скачивание/открытие по известному пути (браузер файлов — non-goal, нет каталог-эндпоинта).
- Vitest + Playwright-electron e2e против stub-сервера (полнодублирующего §5) + автоматический smoke против docker-compose.

**Non-Goals:**
- MCP-бриджинг (`client.mcp:<server>` — проксирование внешних MCP пользователя) — эволюция.
- Auto-update (electron-updater) — внутреннее приложение.
- Браузерный/мобильный клиент; `auth/ticket` — не нужен: WS/REST/SSE живут в main (D-91), токен в renderer не попадает.
- Мультиоконность; серверные доработки.

## Decisions

### D-86: Electron + Vue 3 + Vite + TypeScript

**Решение**: Electron (main + preload + renderer), Vue 3.5 + `<script setup>`, Vite (electron-vite), TypeScript strict, Pinia, markdown-рендер `markdown-it` + `DOMPurify` (sanitize).

**Альтернативы**: Tauri (Rust + webview) — меньше размер, но Rust-инфраструктура не нужна проекту (Java-shop); «чистый» web-app — нет доступа к локальной ФС/child_process (нужно для локальных инструментов и safeStorage); нативные приложения — несоразмерно.

**Почему**: владельцу нужен desktop-клиент, исполняющий локальные инструменты — только Electron даёт web-стек + Node-доступ; Vue — по директиве.

### D-87: SSE через fetch + ReadableStream

**Решение**: подписка на session/task events через `fetch(..., { signal, headers: { 'Last-Event-ID': ... } })` + ручной парсинг SSE-стрима; `Last-Event-ID` хранится в памяти и передаётся **заголовком** при реконнекте (канонично по контракту; `?since=` — фолбэк, заголовок приоритетнее); ping-комментарии `: ping` игнорируются; первый ивент при коннекте — снапшот `session.status`/`task.status` — клиент поглощает как обычный ивент.

**Альтернативы**: `EventSource` — не поддерживает Bearer-заголовок (только cookie/query-токен — токен в URL небезопасен, логи); WS для SSE — отдельный канал, контракт уже SSE.

**Почему**: контракт (M1) уже SSE; fetch-stream — единственный путь с Bearer без серверных изменений.

### D-88: Локальное исполнение — машина пользователя, без path-guard

**Решение**: локальный `bash`/файловые инструменты исполняются в `basePath` (выбор пользователя per session; дефолт `~/harness-workspaces/{sessionId}`); path-guard на клиенте НЕ делается — это машина пользователя; риски принимает владелец («мои инструменты»). Серверный canonical-гвард (D-72) остаётся для download-эндпоинта — это другое (серверный workspace). Дефолт `confirmCommands` — `always` (D-93); D-88 — risk-аппрув владельца отдельной строкой.

**Альтернативы**: клиентский sandbox (контейнер/chroot) — плодить слои безопасности против самого себя; confirm-each-command always без per-session consent — избыточный UX.

**Почему**: владелец явно хочет «оркестратор вызывает МОИ локальные тулы»; безопасность — perimeter SSO + доверенный пользователь + per-session consent и `confirmCommands` (D-93). Известное ограничение: серверный `tool-call-timeout` (5 мин, §5.4) — потолок жизни клиентского вызова; `tool.progress` его НЕ продлевает (контракт не даёт механизма); клиентский таймаут bash обязан быть < серверного, при истечении клиентского — процесс убивается, `tool.result` шлётся штатно.

### D-89: Генерация TS-типов из openapi.yaml

**Решение**: `openapi-typescript` (или `openapi-generator` javascript-axios — по объёму) генерирует типы/клиент из `src/main/resources/api/openapi.yaml` в `web-desktop/src/api/generated/`; маппинг MessageKind/runtimeStatus/TaskStatus — типобезопасный enum-мост.

**Альтернативы**: ручные типы — быстро рассинхрон с замороженным контрактом.

**Почему**: contract-first последователен: одна спека → Java-клиент тестов и TS-клиент desktop'а.

### D-90: Сборка и дистрибуция

**Решение**: `electron-vite build` + `electron-builder` (targets: win-nsis, linux-appimage/dmg-по-запросу); конфиг `publish: null` (no auto-update); дев-сервер против `harness.server.baseUrl` из `.env`/Settings. Менеджер пакетов — **pnpm**; скрипты: `dev`, `build`, `test` (Vitest), `e2e` (Playwright + electron), `lint`.

**Альтернативы**: ручной webpack — устаревший подход; electron-forge — хуже интеграция с Vite.

**Почему**: минимальный pipeline для внутреннего приложения одной VM.

### D-91: Структура и владение сетью — main держит секреты и клиентов

**Решение**: `web-desktop/{package.json, electron.vite.config.ts, electron-builder.yml, src/{main,preload,renderer}}`. **Main владеет всеми секретами и сетевыми клиентами**: JWT (safeStorage), REST-клиент, WS-релей-клиент, SSE-подписки — всё в main; renderer — чистая Vue-UI, общается с main через типизированный IPC-мост (preload `contextBridge`). Renderer НЕ имеет токена и НЕ открывает WS/SSE напрямую. Инициатива локального исполнения — **серверная** (`tool.call` через WS в main): в режиме `confirmCommands` поток main → renderer (команда на подтверждение) → confirm → main исполняет → `tool.result`. Renderer: `views/{Login,Chat,Tree,Artifacts,Settings}`, `stores/` (Pinia), UI-composables; TS-типы — generated (openapi) + ручные WS-фрейм-типы (`src/main/ws-frames.ts` по api-contracts §5).

**Альтернативы**: renderer держит JWT + клиенты — невозможно в Chromium (WS с Bearer) и небезопасно (XSS-утечка секрета).

**Почему**: изоляция по умолчанию; секрет не покидает main; renderer остаётся sandboxed.

### D-92: Sandbox renderer и CSP

**Решение**: `webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false }`; CSP `default-src 'self'` (никаких remote-ресурсов); markdown-санитайз — DOMPurify.

**Альтернативы**: sandbox off ради Node-зависимых npm-пакетов — ломает изоляцию; UI-слой в ней не нуждается.

**Почему**: defence-in-depth на машине пользователя; DOMPurify + CSP закрывают prompt-инъекции через markdown.

### D-93: `confirmCommands` — дефолт `always`

**Решение**: дефолт `confirmCommands` = `always` для вновь подключаемых сессий: разрешение выдаётся per-session на экране первой регистрации («разрешить оркестратору выполнять команды в X») и может быть отозвано в Settings; `never` — только явным переключением пользователя. D-88 (исполнение на голом хосте без контейнера) выносится owner-аппруву отдельной risk-строкой — до impl.

**Альтернативы**: дефолт `never` — тихое arbitrary-исполнение под prompt-injection оркестратора без ведома владельца; always без per-session consent — избыточный UX.

**Почему**: асимметрия рисков (серверный bash — в Docker, D-30; клиентский — голый хост); AGENTS требует явного признания угрозы владельцем до снятия барьера.

## Risks / Trade-offs

- **Новый стек в Java-репо**: CI/мусор в `mvn clean verify` исключён — `web-desktop/` вне Maven-модулей; отдельный `.gitignore`/node_modules.
- **Версии Electron и безопасность**: periodic refresh; contextIsolation + no nodeIntegration + **`sandbox: true`** + **CSP** (renderer рендерит агентский markdown — sanitize: DOMPurify отдельно) — обязательны.
- **Локальный bash — поверхность для prompt-инъекций**: принято владельцем (D-88, risk-аппрув отдельной строкой); `confirmCommands` дефолт `always` (D-93) смягчает.
- **SSE fetch-stream — нет авто-`retry`**: реализуем вручную (по M1-константе `retry: 5000`); `Last-Event-ID` — заголовком (приоритетнее `?since=`), `: ping` — игнорировать, снапшот-первый-ивент — поглощать.
- **e2e против stub-сервера**: стаб обязан честно реализовать §5 целиком (4401/4403/4409, heartbeat, cancel-гонка, takeover) — иначе e2e зелёный на неверном протоколе; smoke — автоматический Playwright-скрипт против docker-compose (живой Keycloak, тестовый realm/user).
- **`safeStorage` может быть недоступен** (Linux без keyring): понятная ошибка логина, никогда — тихая деградация в plain-text.
- **Целевые ОС**: primary — Windows (win-nsis); Linux — secondary (appimage/dmg по запросу); инструментальный слой переносимо-нейтрален, но shell-семантика (`cmd.exe /c` vs sh) — per-OS конфиг.
- **Размер дистрибутива Electron (~150 МБ)**: внутреннее приложение — принято.
- **Параллельная разработка UI и backend-фиксов**: backend заморожен (M5 = чистый потребитель); если в ходе разработки выяснятся контрактные дыры — мини-амендмент через отдельный change, не в M5.
