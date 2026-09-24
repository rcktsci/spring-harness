# desktop-shell Specification

## Purpose
Electron main-process приложения Web Desktop: окно, меню/tray, SSO-логин через Keycloak с безопасным хранением JWT, конфиг сервера, lifecycle и координация подсистем (relay-клиент, SSE-подписки, IPC к renderer).

## Requirements

### Requirement: Запуск и окно

Приложение SHALL запускаться одним главным окном (1200×800 дефолт, состояние сохраняется между запусками — размер/position в `userData`). Меню: File (Quit), Edit (стандартные), View (DevTools в dev-сборке), Help (о программе, ссылка на docs). Tray-иконка с контекстным меню (Show/Quit) — конфиг `showTray` (дефолт true). Повторный запуск — фокус существующего окна (single-instance lock).

#### Scenario: первый запуск

- **WHEN** пользователь запускает приложение впервые
- **THEN** открывается окно 1200×800; при отсутствии сохранённой сессии — экран логина

#### Scenario: повторный запуск

- **WHEN** приложение уже запущено и пользователь запускает второй экземпляр
- **THEN** второй экземпляр завершается, первое окно получает фокус

### Requirement: SSO-логин и хранение токена

Приложение SHALL реализовать Keycloak OAuth2 Authorization Code + PKCE: открытие **видимого** `BrowserWindow` с URL авторизации (из конфига — пользователь видит форму Keycloak и вводит креды), перехват redirect на localhost-loopback, обмен кода на JWT, закрытие окна после redirect. Полученные access/refresh-токены SHALL храниться через `safeStorage` (OS keychain); если safeStorage недоступен (нет keychain на ОС/окружении) — отказ с понятным сообщением (plain-text хранение токенов запрещено). При истечении access-токена — silent refresh (без окна, fetch code exchange); при отказе refresh — возврат на экран логина. Выход (Logout) — очистка хранилища + open redirect logout Keycloak.

#### Scenario: логин

- **WHEN** пользователь нажимает «Войти» и проходит Keycloak-авторизацию
- **THEN** приложение получает JWT, сохраняет его в safeStorage, открывает главный вид

#### Scenario: истёк access-токен

- **WHEN** REST/WS-вызов падает с 401
- **THEN** приложение делает silent refresh; при успехе — повтор запроса; при отказе — экран логина

#### Scenario: logout

- **WHEN** пользователь выбирает Logout
- **THEN** токены удаляются из safeStorage, открывается Keycloak logout URL, приложение возвращается на экран логина

### Requirement: Конфиг сервера

Конфиг приложения SHALL содержать: `server.baseUrl` (дефолт — из сборки, редактируемый в Settings), Keycloak-параметры (`issuer`, `clientId`, `redirectUri`), UI-настройки (тема, `showTray`). Конфиг хранится в `userData/config.json`; secrets — только в safeStorage (нельзя хранить токены в plain-text конфиге).

#### Scenario: смена сервера

- **WHEN** пользователь меняет baseUrl в Settings
- **THEN** приложение переподключает REST/WS к новому серверу (с перепроверкой токена/SSO-домена)

### Requirement: IPC-мост и изоляция

Main и renderer SHALL общаться через типизированный `contextBridge` (preload): renderer не имеет прямого доступа к Node-API (`contextIsolation: true`, `nodeIntegration: false`, `sandbox: true`, CSP `default-src 'self'` — D-92). **Main владеет JWT и всеми сетевыми клиентами** (REST/WS/SSE — D-91); renderer получает/отправляет данные только через IPC. Команды: login-state, config get/set, session REST (list/get/messages/send/compact/stop), relay-управление (connect/register/disconnect + события), SSE-подписки, local-tool execution, download, open-in-OS, quit.

#### Scenario: оркестратор вызвал локальный bash

- **WHEN** сервер шлёт `tool.call` (WS в main); включён confirmCommands
- **THEN** main показывает команду в renderer через IPC; после confirm main исполняет и шлёт `tool.result` (renderer не инициирует исполнение сам — инициатива серверная, D-91)

### Requirement: Логи приложения

Main SHALL писать логи (electron-log или эквивалент) в `userData/logs/` с ротацией; уровень — конфиг (`log-level`: info по умолчанию). Логи включают relay-handshake, local-tool вызовы (команда + exitCode, не вывод), ошибки сети.

#### Scenario: пользователь отправляет логи

- **WHEN** пользователь выбирает Help → Open Logs
- **THEN** открывается папка `userData/logs/` в файловом менеджере ОС
