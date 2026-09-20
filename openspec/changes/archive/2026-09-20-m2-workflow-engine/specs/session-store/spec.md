# session-store Specification

## ADDED Requirements

### Requirement: Создание STATE-сессии (резюм по task_id и state_code)

Движок задачи SHALL создавать STATE-сессию пары `(task_id, state_code)` при входе задачи в AGENT-состояние: `kind=STATE`, пин ревизии агента на момент входа (`agent_revision_id`). Повторный вход в ту же пару SHALL резюмировать существующую сессию (PARTIAL UNIQUE `(task_id, state_code) WHERE kind='STATE'` — гарантия единственности без race; UPSERT-семантика: существует → подхватить, иначе → создать). Создание SHALL быть атомарным: в одной транзакции insert сессии + seed-`SYSTEM`-сообщение + `last_seq` — состояния «сессия есть, seed не записан» не существует (execution-model §7.2). Превращение FREE→STATE и обратно невозможно.

#### Scenario: первый вход в состояние

- **WHEN** задача входит в AGENT-состояние пары (task, code) впервые
- **THEN** в одной транзакции создаются сессия и seed-SYSTEM-сообщение, `last_seq` установлен, сессия готова к wake

#### Scenario: повторный вход (резюм)

- **WHEN** задача возвращается в ту же пару (task, code) — цикл в графе
- **THEN** существующая сессия подхватывается (новая не создаётся), диалог продолжается
