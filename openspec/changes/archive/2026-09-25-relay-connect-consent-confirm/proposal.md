## Why

На живом стенде (сессия `000cb467`) tool-call не может завершиться: `TOOL_CALL bash` → `TOOL_RESULT ERROR «Failed to prepare workspace directory»`, а в клиентском main.log — ноль relay-событий: релей вообще не подключался (в UI «не подключён» и ручная кнопка «Подключить»). Разбор кода показал три дыры клиентской стороны M5:

1. **Нет авто-подключения при открытии сессии** — `useRelay.connect/register` зовутся только из ручной кнопки ChatView; сценарий спеки desktop-relay-client «регистрация на новую сессию WHEN пользователь открывает FREE root-сессию» не реализован (стартовая авторегистрация сохранённой сессии в main есть, но активная сессия никогда не сохраняется, потому что register из renderer не вызывается).
2. **Consent-диалог первой регистрации не рендерится** — `useRelay.consent` наполняется из `relay.onRegistrationConsent`, но ни один компонент его не показывает; `respondConsent()` не вызывается никогда → первая регистрация висит вечно.
3. **Диалог подтверждения команды (D-93, дефолт `confirmCommands=always`) не рендерится** — `useRelay.toolConfirm` держит отложенный вызов, `respondToolConfirm()` не вызывается → вызов нельзя ни разрешить, ни отклонить; агент получает `tool-timeout`.

Плюс e2e-спека `electron-smoke.spec.ts` не является стражем: селекторы не из реального DOM (`button[data-session-id]` вместо `li.session-item`), «save-as» ничего не пишет на диск, WS `tool.call` из stub-сервера не отправляется вовсе (tool-цикл не выполняется), отсутствие файла не ловится.

## What Changes

- **Авто-подключение релея при открытии FREE-сессии** (renderer): открытие root-сессии → `connect` → `register(sessionId, kind, basePath)`, `basePath` — дефолт `~/harness-workspaces/{sessionId}` (D-88); STATE-сессии не регистрируются; активная сессия сохраняется (`relayActiveSessionId`, уже реализовано в main) и восстанавливается при старте (уже реализовано в main).
- **Pending-consent re-fetch**: запрос согласования, отправленный main до монтирования renderer (гонка стартовой авторегистрации), не теряется — renderer при монтировании запрашивает незакрытый consent по IPC.
- **Глобальные диалоги** (`RelayDialogs.vue` поверх любого view): согласование первой регистрации (sessionId, basePath, список инструментов → Разрешить/Отклонить) и подтверждение команды (tool, args, basePath → Разрешить/Отклонить; D-93).
- **e2e-страж**: `electron-smoke.spec.ts` переписан под реальный DOM; stub-сервер доработан до отправки WS `tool.call` зарегистрированному соединению; сценарии: consent-диалог при авто-подключении, полный tool-цикл (`confirmCommands=never`), диалог подтверждения команды (отклонение и разрешение при `confirmCommands=always`), реальный save-as c записью файла на диск.

## Capabilities

### Modified Capabilities

- `desktop-relay-client`: требование «Регистрация на сессии с декларацией» дополнено авто-подключением со стороны renderer и pending-consent re-fetch; требование «Безопасность локального исполнения» дополнено обязательным UI согласования регистрации и UI подтверждения команды.
- `desktop-chat`: требование «Активная сессия и релей-статус» дополнено авто-подключением при открытии FREE-сессии и глобальными диалогами поверх любого view.

### New Capabilities

Нет.

## Impact

- **Код (renderer)**: `composables/useRelay.ts` (синглтон-состояние, `ensureConnected`), новый `components/RelayDialogs.vue`, `App.vue` (монтаж диалогов), `views/ChatView.vue` (авто-подключение, показ причины в relay-статусе).
- **Код (main)**: `relay-client.ts` (pending-consent), `shared/ipc-contract.ts` + `main/index.ts` + `preload/index.ts` (канал `relay:pending-consent`).
- **Тесты**: unit `use-relay.test.ts` (новый), `relay-client.test.ts` (pending-consent), e2e `stub-server.ts` + `electron-smoke.spec.ts` (переписаны).
- **Backend**: без изменений (чистый клиентский фикс; серверные контракты §5 не меняются).
- **Документы**: `web-desktop-client.md` — сценарии 2.4/статус-бар уточнены постфактум при закрытии change (docs-синк).

## Non-goals

- Локализация сообщений регистрации (`REGISTRATION_ERROR_TEXT` остаётся английским — тексты закреплены unit-тестами main).
- Выбор basePath пользователем в момент авто-подключения (дефолт D-88; выбор — отдельная эволюция).
- `auth/ticket`, MCP-бриджинг, авто-update — вне задачи.
- Серверные доработки любого рода.
