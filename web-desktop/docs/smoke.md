# Smoke-тестирование Web Desktop

> Полное smoke-описание: запуск, ожидаемые результаты, ручной фолбэк, debug.

## 1. Автоматический smoke (Playwright-electron + in-test stub)

Playwright-electron driver запускает собранный desktop против **in-test stub-сервера**
(`tests/e2e/stub-server.ts`, поднимается самим спеком). Ни docker-compose,
ни Keycloak, ни живой бэкенд для этого прогона не нужны; нужен только GUI-сеанс
(native Windows/macOS, Xvfb на Linux-CI).

Сценарий:

1. (опц.) `pnpm run build` (electron-vite) — если не задан `HARNESS_E2E_BUILT=0`
2. Запуск Electron с `HARNESS_E2E_*` env: token bypass + авто-подтверждение
   команд + адрес стаба (спек проставляет их сам)
3. Desktop открывает список сессий (стаб отдаёт FREE root-сессию)
4. Открытие сессии → чат
5. Пользователь пишет промпт → `POST /messages`
6. Стаб отвечает через SSE: `tool.call bash` → desktop исполняет локально →
   `tool.result` через WS-релей
7. Стаб пушит финальный `ASSISTANT` → появление в ленте
8. Артефакты: `GET /api/v1/sessions/{id}/workspace/files` через save-as-диалог

Живой стенд (docker-compose + реальный Keycloak) для e2e **не реализован**;
`scripts/smoke-docker.{ps1,sh}` — заготовка, параметры которой спек
перетирает своими значениями.

### Запуск

```bash
cd web-desktop
pnpm run e2e:electron
```

### Ожидаемый результат

```
Running 1 test using 1 worker
  ✓  1 [electron-smoke] › tests\e2e\electron-smoke.spec.ts:…:1 › chat + bash tool-call + artifact save-as against stub server (X.XXXs)
1 passed (Xm Xs)
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
- **`Error: Electron uninstall` при `pnpm dev`**: бинарник Electron не скачан — postinstall-скрипты зависимостей блокируются pnpm по умолчанию. Allowlist лежит в `pnpm-workspace.yaml` (`onlyBuiltDependencies: [electron]`); лечится `pnpm rebuild electron` (или `node node_modules/electron/install.js`). На медленной сети помогает `ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/`.

## 5. Окружение для Playwright-electron на CI

- **Linux**: требуется Xvfb (`xvfb-run --auto-servernum --server-args='-screen 0 1280x800x24' pnpm exec playwright test --project=electron-smoke`).
- **Windows / macOS**: нативный GUI-сеанс, дополнительных обёрток не нужно.
- На CI `pnpm install` обязан вытащить бинарник Electron (allowlist в `pnpm-workspace.yaml`); без этого `electron-smoke` падает с «Electron uninstall».

В CI по умолчанию `pnpm verify` запускает только Vitest + lint + typecheck + build. **Полный Playwright-electron прогон** — отдельный шаг (`pnpm e2e:electron`).
