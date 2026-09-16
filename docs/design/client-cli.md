# Клиент attach-CLI spring-harness

> Первый клиент (D-19: CLI → WebUI). Java-приложение, отдельный репозиторий/модуль. Контракт — `api-contracts.md`.

## 1. Команды

| Команда | Что делает |
|---|---|
| `login` | OIDC (Authorization Code + PKCE через localhost-redirect, либо Device Flow); сохранение refresh-токена в системном хранилище |
| `sessions [--folder --mine --kind --q]` | список сессий (`runtimeStatus`, `lastActivityAt`) |
| `new <agentKey> [--title --folder]` | создать FREE-сессию |
| `attach <sessionId>` | основной режим: живой SSE-поток + поле ввода; курсор от последнего seq (роуминг: та же сессия с любой машины) |
| `tree [sessionId]` | поддерево сессий (субагенты: агент, статус, lastSeq) |
| `attach <sessionId> --inject "…"` / в режиме attach | написать в чужую/субагентскую сессию (атрибуция `[username]:`) |
| `stop <sessionId>` | отмена Turn'а + поддерева |
| `fork <sessionId> --at <seq>` / `rewind --to <seq>` / `compact` | операции свободных сессий |
| `tasks [--status --mine --tags]` / `task <id> [--history --tree --comments]` | мониторинг задач, история переходов, поддерево подзадач |
| `relay --task <taskId> [--binding <logicalKey>]` | режим исполнителя CLIENT_EXEC: без `--binding` — показать доступные биндинги задачи (`workspaceBinding.logicalKey` из SessionDto/TaskDto); с ним — регистрация workspace с `basePath = cwd`, приём `tool.call`, исполнение, `tool.result` |

## 2. UX-минимум

- Поток: события рендерятся построчно; `TOOL_CALL/TOOL_RESULT` — сворачиваемые блоки; `COMPACT` — маркер «история сжата».
- Статусная строка: `runtimeStatus` (`TURN_RUNNING` = «думает», `PARKED_ASYNC` = «ждёт инструмент», `PARKED_CLIENT` = «ждёт исполнителя»).
- Ввод во время хода — обычный `POST /messages` (модель увидит в следующем раунде — steer-семантика).
- Переподключение SSE: авто-ретрай с `?since=` последнего seq; билет одноразовый на соединение — при реконнекте CLI прозрачно берёт новый (`POST /auth/ticket`).

## 3. Невходит в CLI v1

WebUI-специфика (скачивание артефактов кнопкой — REST уже есть), управление workflow/триггерами (через оркестратор-сессию), управления ревизиями агентов.
