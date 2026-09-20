# agent-turn Specification (delta for M3)

## RENAMED Requirements

- FROM: ### Requirement: Агентный цикл с синхронными инструментами
- TO: ### Requirement: Агентный цикл Turn'а (sync + async-capable)

## MODIFIED Requirements

### Requirement: Агентный цикл Turn'а (sync + async-capable)

Turn SHALL исполнять раунды: рендер видимого контекста → вызов модели → фиксация ответа и tool-calls в журнал → исполнение синхронных инструментов → допись результатов → следующий раунд. Модель «закончила» ⟺ в ответе нет tool-calls; тогда Turn завершается со статусом COMPLETED, `last_consumed_seq` доводится до `last_seq`. События, пришедшие во время хода (новое сообщение), SHALL подхватываться дополнительным раундом. При ошибке LLM, исчерпавшей ретраи, Turn завершается статусом FAILED: в журнал дописывается SYSTEM-событие с причиной ошибки и `last_consumed_seq` доводится до `last_seq` (потребление батча — защита от повторного POLL-запуска); автоматических повторов Turn'а нет — повторная попытка только новой пользовательской записью.

Turn SHALL поддерживать async-capable инструменты: если в раунде модель вернула инструмент(ы) с `async-capable=true` и хотя бы один из них не уложился в `harness.async.window.default-ms` (конфиг, дефолт 30 с), ход завершается с записью в журнал `TOOL_CALL(callId) + ASYNC_ACCEPTED(callId)` для каждого (`ASYNC_ACCEPTED` — допустимое значение `MessageKind`; enum расширяется миграцией 075); модель получает ASYNC_ACCEPTED в этом же раунде. По завершении Turn (если остались pending async) — сессия переходит в `session.runtimeStatus = PARKED_ASYNC` (состояние сессии, значение зарезервировано в api-contracts §2; исход раунда `last_turn_outcome = COMPLETED` при `pending_tool_calls > 0`; enum TurnOutcome НЕ расширяется), wake придёт позже через сессионный канал `message.created` (поздний TOOL_RESULT).

Поздний TOOL_RESULT SHALL идти через сессионный канал `message.created`/`session_message.seq` (M1 `InMemorySessionEventBroadcaster`), инкрементируя `last_seq` сессии, и вызывает wake существующим `AgentTurnEngine.run`-путём (M2 J-1: USER-source rewake + новый Turn с early-exit pending): поднимает новый Turn (если предыдущий COMPLETED) или встраивается в активный (если pending). Маркер `late=true` в `MessageDto` для поздних. Правило «первый финальный результат выигрывает» — финальные статусы `OK|ERROR|CANCELLED|LOST`; синтетические LOST/CANCELLED из рестарт-скана тоже считаются финальными; `ASYNC_ACCEPTED` — placeholder «в полёте», финальным не считается.

#### Scenario: полный цикл с инструментом

- **WHEN** модель в ответе вызывает инструмент и после получения результата отвечает финальным текстом
- **THEN** в журнале последовательно фиксируются ASSISTANT+TOOL_CALL, TOOL_RESULT, финальный ASSISTANT; Turn — COMPLETED

#### Scenario: сообщение пришло во время хода

- **WHEN** пользователь дописывает сообщение, пока Turn активен
- **THEN** после текущего раунда исполняется дополнительный раунд, видящий новое сообщение

#### Scenario: bash превысил окно в активном Turn'е

- **WHEN** модель вызвала bash и окно превышено
- **THEN** журнал: TOOL_CALL + ASYNC_ACCEPTED; session.runtimeStatus = PARKED_ASYNC (last_turn_outcome = COMPLETED, pending>0); на поздний TOOL_RESULT → wake → новый Turn

#### Scenario: bash превысил окно, Turn уже COMPLETED

- **WHEN** bash вернулся после COMPLETED
- **THEN** финальный TOOL_RESULT (late=true) → message.created → новый Turn видит результат

#### Scenario: смешанный раунд (sync + async)

- **WHEN** модель в одном раунде вызвала sync-инструмент и async-capable инструмент, превысивший окно
- **THEN** sync TOOL_RESULT пишется сразу; async — TOOL_CALL + ASYNC_ACCEPTED; session.runtimeStatus = PARKED_ASYNC (pending>0); поздний результат поднимает следующий раунд

#### Scenario: рестарт во время async

- **WHEN** процесс упал, сессия имеет ASYNC_ACCEPTED(callId) без результата
- **THEN** рестарт-скан (M1 + M3 расширение) записывает синтетический TOOL_RESULT LOST

## ADDED Requirements

### Requirement: `spawn_subagent` в Turn'е

Агенту-оркестратору SHALL быть доступен `spawn_subagent` в каждом Turn'е (мета-инструмент, см. `subagent-lifecycle`). Вызов строго синхронный — блокирующий: родительский виртуальный поток ждёт завершения субагента (условие завершения — `subagent-lifecycle`); окно `harness.async.window` на spawn НЕ распространяется. Результат (`TOOL_RESULT` с output субагента) фиксируется только на ходу родителя, где spawn вызван. Если родительский Turn всё же завершился до возврата (отмена/рестарт) — поздний TOOL_RESULT идёт через `message.created` и поднимает новый Turn.

#### Scenario: оркестратор spawn'ит в активном Turn'е

- **WHEN** `spawn_subagent` вызван в активном раунде
- **THEN** дочерняя сессия стартует синхронно; родительский поток блокируется до завершения субагента; spawn возвращает TOOL_RESULT на этом же ходу

#### Scenario: родительский Turn завершён до возврата spawn (отмена/рестарт)

- **WHEN** родительский Turn COMPLETED, spawn-результат не успел вернуться
- **THEN** message.created с late TOOL_RESULT поднимает новый Turn родителя

### Requirement: `read_compacted` доступен агенту

В manifest инструментов агента SHALL входить `read_compacted(compact_message_id)` (см. `subagent-lifecycle`). Резолв `id → seq` — по `session_message.id`, только в пределах текущей сессии; cross-session-чтение в M3 не поддерживается. Ответ — оригинальные записи, скрытые COMPACT-покрытием.

#### Scenario: агент читает скрытое

- **WHEN** агент вызывает `read_compacted(id)` где id указывает на покрытое сообщение
- **THEN** TOOL_RESULT с оригиналом (лимит по `harness.compact.read-max-bytes`)

#### Scenario: id вне сессии

- **WHEN** `read_compacted(id)` где id не из текущей сессии
- **THEN** TOOL_RESULT not-found (cross-session-чтение в M3 не поддерживается)
