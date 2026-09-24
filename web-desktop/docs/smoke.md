# Smoke-тестирование Web Desktop

> Полное smoke-описание: запуск, ожидаемые результаты, ручной фолбэк, debug.

## 1. Автоматический smoke (Playwright-electron + docker-compose)

Скрипт `scripts/smoke-docker.{ps1,sh}` поднимает **полный сценарий**:

1. (опц.) `pnpm run build` (electron-vite)
2. Playwright-electron driver запускает собранный desktop с `HARNESS_E2E_*` env
3. Desktop проходит full сценарий:
   - логин bypass / PKCE через живой Keycloak → токен в `safeStorage`
   - список сессий (`GET /api/v1/sessions?mine=true`) — рендер
   - создание FREE-сессии (`POST /api/v1/sessions`)
   - чат: ввод промпта → `POST /api/v1/sessions/{id}/messages`
   - SSE-подписка: snapshot `session.status`, `message.created`-кадры
   - оркестратор пушит `tool.call bash` через SSE → desktop (auto-confirm в smoke) исполняет `bash` локально → `tool.result` через WS-релей
   - финальный `ASSISTANT` через SSE → отображение в ленте
   - переход на `/artifacts?sessionId=&path=hello.md` → `GET /api/v1/sessions/{id}/workspace/files` → локальный save-as

### Предусловия

- Docker + docker-compose на PATH
- docker-compose файл серверного стенда в репозитории поднят (например, `dev/docker-compose.yml` с Keycloak + PostgreSQL + orchestrator)
- Тестовый realm/user в Keycloak создан (по умолчанию `harness/tester/tester`)

### Запуск

```bash
cd web-desktop
HARNESS_E2E_SERVER_BASE_URL=http://localhost:8080 \
HARNESS_E2E_KEYCLOAK_ISSUER=http://localhost:8080/realms/harness \
HARNESS_E2E_KEYCLOAK_CLIENT_ID=spring-harness-web-desktop \
HARNESS_E2E_KEYCLOAK_USER=tester \
HARNESS_E2E_KEYCLOAK_PASSWORD=tester \
pwsh scripts/smoke-docker.ps1   # или bash scripts/smoke-docker.sh
```

Окружение пробрасывается в Electron; desktop читает токен через реальный PKCE-redirect на Keycloak.

### Ожидаемый результат

```
[smoke] server=http://localhost:8080 issuer=http://localhost:8080/realms/harness
[smoke] building electron-vite bundle...
[smoke] running Playwright-electron (full scenario)...
Running 1 test using 1 worker
  ✓  1 [electron-smoke] › tests\e2e\electron-smoke.spec.ts:15:1 › chat + bash tool-call + artifact save-as against stub server (X.XXXs)
1 passed (Xm Xs)
[smoke] OK
```

Если тест красный — см. §4.

## 2. Stub-server e2e (быстрый, без Electron)

Не требует display server / Keycloak / docker. Запускается против in-test stub-сервера (`tests/e2e/stub-server.ts`), реализующего §5 + §3.1/§3.2 + D-72:

```bash
cd web-desktop
pnpm run e2e:stub
```

Проверяет scripted SSE-flow (USER → TOOL_CALL → TOOL_RESULT → ASSISTANT) и WS-релей §5: `hello/welcome`, `register/registered`, 4409 takeover, 401 на bad token.

## 3. Ручной smoke (dev-режим)

Минимальный ручной сценарий:

```bash
cd web-desktop
pnpm run dev   # electron-vite dev с HMR
```

В открывшемся окне:

1. **Settings** → заполнить `server.baseUrl` (например `http://localhost:8080`), Keycloak `issuer`, `clientId`. Сохранить.
2. **Help → Open Logs** → проверить, что лог пишется в `userData/logs/main.log`.
3. **Tray**: клик по иконке → Show/Quit.
4. **Login**: открыть Keycloak в системном браузере, ввести `tester/tester` → закрыть auth-окно. `userData/tokens.enc` появится.
5. **Chat**: список сессий слева. `+` → выбор агента → создать. Открыть сессию → ввести промпт → дождаться assistant.
6. **Compact / Stop**: в статус-баре; Stop запрашивает подтверждение при `TURN_RUNNING`.
7. **Tree**: переключиться на `/tree` → дерево подсессий (если есть).
8. **Artifacts**: перейти на `/artifacts`, ввести `sessionId` + `hello.md` → Save as → проверить файл на диске.

## 4. Debug

- **Логи**: `userData/logs/main.log` (electron-log, ротация по `logMaxSizeBytes`); уровень — Settings → `log-level` (info по умолчанию).
- **Renderer console**: View → Toggle DevTools (только в dev).
- **E2E trace**: `HARNESS_E2E_BUILT=0 pnpm exec playwright test --trace=on` — артефакт в `test-results/`.
- **Stub-server лог**: установить `DEBUG=stub:*` или просто смотреть stdout Playwright — спеки делают `console.log` через `-`.
- **WS-frame dump**: `HARNESS_RELAY_DEBUG=1` (если включим в `relay-client.ts`) → `log.debug` каждого фрейма.

## 5. Окружение для Playwright-electron на CI

- **Linux**: требуется Xvfb (`xvfb-run --auto-servernum --server-args='-screen 0 1280x800x24' pnpm exec playwright test --project=electron-smoke`).
- **Windows**: работает без Xvfb; но `pnpm e2e` не стартует если нет `\\.\DISPLAY` (headless).
- **macOS**: нативно, никаких обёрток.

В CI по умолчанию `pnpm verify` запускает только Vitest + lint + typecheck + build. **Полный Playwright-electron прогон** — отдельный шаг (`pnpm e2e:electron`).
