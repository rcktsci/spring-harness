# Smoke-тестирование Web Desktop

> Полное smoke-описание: запуск, ожидаемые результаты, ручной фолбэк, debug.

## 1. Автоматический smoke (Playwright-electron + in-test stub)

Playwright-electron driver запускает собранный desktop против **in-test stub-сервера**
(`tests/e2e/stub-server.ts`, поднимается самим спеком). Ни docker-compose,
ни Keycloak, ни живой бэкенд для этого прогона не нужны; нужен только GUI-сеанс
(native Windows/macOS, Xvfb на Linux-CI).

Сценарии (2 теста в `tests/e2e/electron-smoke.spec.ts`):

**Тест 1 — авто-подключение + consent + полный tool-цикл + save-as** (`confirmCommands=never` через `HARNESS_E2E_AUTO_CONFIRM_COMMANDS=1`):

1. (опц.) `pnpm run build` (electron-vite) — если не задан `HARNESS_E2E_BUILT=0`
2. Запуск Electron с `HARNESS_E2E_*` env (token bypass + адрес стаба) и свежим `--user-data-dir`
3. Открытие FREE-сессии (`li.session-item` «e2e-root») → **авто-подключение релея** →
   consent-диалог первой регистрации (`[data-testid="consent-dialog"]`) → «Разрешить»
4. Статус-бар релея → «подключён (6 инструментов)»
5. Промпт «ping» → стаб шлёт WS `tool.call bash` (с ретраями до регистрации) →
   desktop исполняет локально → `tool.result` → `TOOL`-блок в ленте с выводом
   `hello-from-client` → финальный `ASSISTANT` → бейдж `IDLE`
6. Артефакты: `dialog.showSaveDialog` подменяется в main (`app.evaluate`), Save as
   пишет файл на диск — ассерт содержимого из workspace

**Тест 2 — диалог подтверждения команды (D-93, `confirmCommands=always`):**

1. Тот же вход; consent → «Отклонить» → в строке релея причина отказа
   (`…declined…`), кнопка остаётся «Подключить»
2. «Подключить» → consent повторно → «Разрешить» → «подключён (6 инструментов)»
3. Промпт «deny me» → диалог подтверждения команды (`tool-confirm-dialog`) →
   «Отклонить» → `TOOL`-блок: `command rejected by user`, исполнения не было
4. Промпт «approve me» → диалог → «Разрешить» → `TOOL`-блок с `hello-from-client`

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
Running 2 tests using 1 worker
  ok 1 [electron-smoke] › tests\e2e\electron-smoke.spec.ts:…:1 › auto-connect + consent + bash tool cycle + artifact save-as (confirmCommands=never) (X.XXXs)
  ok 2 [electron-smoke] › tests\e2e\electron-smoke.spec.ts:…:1 › tool confirm dialog: deny skips execution, approve runs (confirmCommands=always) (X.XXXs)
  2 passed (X.Xs)
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
5. **Chat**: список сессий слева. `+` → выбор агента → создать. Открытие FREE-сессии
   автоматически подключает релей: при первой регистрации появится consent-диалог
   («Разрешить/Отклонить»); отказ виден в строке релея, «Подключить» — повторный запрос.
   При `confirmCommands=always` (дефолт) каждая команда оркестратора требует
   подтверждения в диалоге (Разрешить/Отклонить). STATE-сессии: строка релея показывает
   «релей доступен только для root-сессий». Ввести промпт → дождаться assistant.
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
