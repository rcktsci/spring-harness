# desktop-session-tree Specification

## Purpose

Дерево сессий и задач Web Desktop: FREE-root сессии + их STATE-sub-сессии и задачи; проваливание в субагентов по parent-chain; задачные статусы и переходы через task SSE.

## ADDED Requirements

### Requirement: Дерево сессий

UI SHALL отображать дерево активной FREE-сессии (`GET /api/v1/sessions/{id}/tree`): корень — FREE-сессия; дочерние узлы — STATE-сессии субагентов (с `parentSessionId`, `taskId`, `stateCode`, раскрытые узлы). Узел показывает: агент, runtimeStatus-бейдж, `stateCode` (из TreeNode; `statusProjection` — только в панели задачи через `GET /tasks/{id}`). Дерево SHALL обновляться: (а) при переключении на сессию, (б) по таймеру, пока сессия активна (интервал — конфиг `tree.refresh-interval`, дефолт 10 с), (в) при смене `session.status` (SSE).

#### Scenario: оркестратор spawn'ил субагента

- **WHEN** активная сессия получает sub-session (spawn_subagent)
- **THEN** узел субагента появляется в дереве при следующем обновлении (таймер/переключение/session.status)

#### Scenario: проваливание

- **WHEN** пользователь кликает узел субагента
- **THEN** открывается чат этой STATE-сессии (лента сообщений по тому же контракту); breadcrumb показывает путь к root

### Requirement: Задачи и переходы

UI SHALL отображать задачи оркестратора (`GET /api/v1/tasks?...`): статус (CURRENT/TERMINAL-проекция), suspended-флаг, текущее состояние; подписка на task SSE (`GET /api/v1/tasks/{id}/events`) — переходы `task.transition`, `task.status`, `subtask.terminal`, комментарии задачи; семантика та же, что session SSE (§3.2): при коннекте — снапшот `task.status`, курсор `since=task_event_seq` / Last-Event-ID при реконнекте, `: ping` игнорируется. Комментарии можно добавлять (`POST /api/v1/tasks/{id}/comments`).

#### Scenario: задача перешла в состояние

- **WHEN** приходит task.transition
- **THEN** статус задачи в дереве/панели обновляется; переход добавляется в историю

#### Scenario: комментарий

- **WHEN** пользователь добавляет комментарий к задаче
- **THEN** отправляется POST, комментарий появляется в панели задачи

### Requirement: Переключение активной сессии

UI SHALL позволять переключать активную сессию (клик в списке сессий или узле дерева): лента чата, SSE-подписка и статус релея переключаются; состояние ввода сохраняется per-session (черновик).

#### Scenario: переключение

- **WHEN** пользователь переключается на другую сессию
- **THEN** лента, подписка и индикатор релея обновляются; черновик предыдущей сессии сохранён
