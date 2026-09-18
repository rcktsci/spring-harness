# workspace-tools Specification

## ADDED Requirements

### Requirement: Task-контейнер для состояний BASH_SCRIPT

Для исполнения bash-скриптов workflow-состояний SHALL использоваться отдельный контейнер `harness-task-<taskId>` (по аналогии с `harness-<sessionId>`): контейнер задаётся через workspace-биндинг исполнения состояния, workspace — `SERVER_DIR / mode=PATH / path=<taskId>` по умолчанию, переопределяется `state.workspace`. Lifecycle контейнера — задача; ресурсные лимиты — конфиг. Контейнер задачи изолирован от контейнера любой сессии — bash-состояние не загрязняет workspace сессии (D-30/D-50).

#### Scenario: bash-состояние исполняется в task-контейнере

- **WHEN** задача входит в BASH_SCRIPT-состояние и движок запускает скрипт
- **THEN** скрипт исполняется в `harness-task-<taskId>`, контейнеры `harness-<sessionId>` сессий не затрагиваются

#### Scenario: изоляция workspace

- **WHEN** bash-скрипт задачи пишет файлы в workspace
- **THEN** файлы попадают в workspace задачи (path = taskId по биндингу), а не в workspace сессии
