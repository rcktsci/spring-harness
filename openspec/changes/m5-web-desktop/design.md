## Context

M4 заморозил серверные контракты: REST (`/api/v1/sessions*`, `/tasks*`, `/workspace/files`), SSE (session/task events), WS-релей (`/api/v1/relay` с register-by-session и клиентским toolset-оверлеем). Web Desktop — первый настоящий потребитель: backend-изменений нет. Проект — Maven-монолит; JS-стека нет. Папка `web-desktop/` — отдельный npm-пакет в monorepo (изолированно от Java-сборки).

## Goals / Non-Goals

**Goals:**
- Electron + Vue 3 + Vite + TS приложение в `web-desktop/`.
- SSO-логин (Keycloak OAuth + PKCE), JWT в safeStorage.
- WS-клиент релея: регистрация на сессии, локальное исполнение стандартного файлового набора, cancel, heartbeat, reconnect/takeover.
- Чат: список сессий, лента всех MessageKind, отправка, compact/stop, SSE через fetch-stream.
- Дерево сессий/задач с проваливанием в субагентов и task SSE.
- Артефакты: браузер + save-as/open.
- Vitest + Playwright-electron e2e против stub-сервера.

**Non-Goals:**
- MCP-бриджинг (`client.mcp:<server>` — проксирование внешних MCP пользователя) — эволюция.
- Auto-update (electron-updater) — внутреннее приложение.
- Браузерный/мобильный клиент, `auth/ticket`.
- Мультиоконность; серверные доработки.

## Decisions

### D-86: Electron + Vue 3 + Vite + TypeScript

**Решение**: Electron (main + preload + renderer), Vue 3.5 + `<script setup>`, Vite (electron-vite), TypeScript strict, Pinia, markdown-рендер `markdown-it` + `DOMPurify` (sanitize).

**Альтернативы**: Tauri (Rust + webview) — меньше размер, но Rust-инфраструктура не нужна проекту (Java-shop); «чистый» web-app — нет доступа к локальной ФС/child_process (нужно для локальных инструментов и safeStorage); нативные приложения — несоразмерно.

**Почему**: владельцу нужен desktop-клиент, исполняющий локальные инструменты — только Electron даёт web-стек + Node-доступ; Vue — по директиве.

### D-87: SSE через fetch + ReadableStream

**Решение**: подписка на session/task events через `fetch(..., { signal })` + ручной парсинг SSE-стрима; `Last-Event-ID` хранится в памяти и передаётся в `?since=` при реконнекте.

**Альтернативы**: `EventSource` — не поддерживает Bearer-заголовок (только cookie/query-токен — токен в URL небезопасен, логи); WS для SSE — отдельный канал, контракт уже SSE.

**Почему**: контракт (M1) уже SSE; fetch-stream — единственный путь с Bearer без серверных изменений.

### D-88: Локальное исполнение — машина пользователя, без path-guard

**Решение**: локальный `bash`/файловые инструменты исполняются в `basePath` (выбор пользователя per session; дефолт `~/harness-workspaces/{sessionId}`); path-guard на клиенте НЕ делается — это машина пользователя; риски принимает владелец («мои инструменты»). Серверный canonical-гвард (D-72) остаётся для download-эндпоинта — это другое (серверный workspace).

**Альтернативы**: клиентский sandbox (контейнер/chroot) — плодить слои безопасности против самого себя; confirm-each-command always — плохой UX.

**Почему**: владелец явно хочет «оркестратор вызывает МОИ локальные тулы»; безопасность — perimeter SSO + доверенный пользователь. Режим `confirmCommands` (always/never) — для осторожных.

### D-89: Генерация TS-типов из openapi.yaml

**Решение**: `openapi-typescript` (или `openapi-generator` javascript-axios — по объёму) генерирует типы/клиент из `src/main/resources/api/openapi.yaml` в `web-desktop/src/api/generated/`; маппинг MessageKind/runtimeStatus/TaskStatus — типобезопасный enum-мост.

**Альтернативы**: ручные типы — быстро рассинхрон с замороженным контрактом.

**Почему**: contract-first последователен: одна спека → Java-клиент тестов и TS-клиент desktop'а.

### D-90: Сборка и дистрибуция

**Решение**: `electron-vite build` + `electron-builder` (targets: win-nsis, linux-appimage/dmg-по-запросу); конфиг `publish: null` (no auto-update); дев-сервер против `harness.server.baseUrl` из `.env`/Settings. Менеджер пакетов — **pnpm**; скрипты: `dev`, `build`, `test` (Vitest), `e2e` (Playwright + electron), `lint`.

**Альтернативы**: ручной webpack — устаревший подход; electron-forge — хуже интеграция с Vite.

**Почему**: минимальный pipeline для внутреннего приложения одной VM.

### D-91: Структура проекта web-desktop

**Решение**: `web-desktop/{package.json, electron.vite.config.ts, electron-builder.yml, src/{main,preload,renderer}}`; renderer: `views/{Login,Chat,Tree,Artifacts,Settings}`, `stores/` (Pinia), `api/` (generated + clients), `composables/` (useSse, useRelay, useSession); общие TS-типы WS-фреймов — ручные (WS не входит в OpenAPI) в `src/api/ws-frames.ts` по api-contracts §5.

**Почему**: изоляция от Maven-сборки; чёткое разделение main (Node-поверхность) / renderer (UI).

## Risks / Trade-offs

- **Новый стек в Java-репо**: CI/мусор в `mvn clean verify` исключён — `web-desktop/` вне Maven-модулей; отдельный `.gitignore`/node_modules.
- **Версии Electron и безопасность**: periodic refresh; contextIsolation + no nodeIntegration — обязателен.
- **Локальный bash — поверхность для prompt-инъекций**: принято владельцем (D-88); `confirmCommands`-режим смягчает.
- **SSE fetch-stream — нет авто-`retry`**: реализуем вручную (по M1-константе `retry: 5000`).
- **e2e против stub-сервера**: не покрывает реальные Keycloak/WS-сервер полностью — smoke-прогон против живого сервера — manual/CI отдельным скриптом.
- **Размер дистрибутива Electron (~150 МБ)**: внутреннее приложение — принято.
- **Параллельная разработка UI и backend-фиксов**: backend заморожен (M5 = чистый потребитель); если в ходе разработки выяснятся контрактные дыры — мини-амендмент через отдельный change, не в M5.
