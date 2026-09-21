# session-api Specification (delta for M4)

## ADDED Requirements

### Requirement: SessionDto.runtimeStatus — PARKED_CLIENT реализован

`runtimeStatus` SHALL принимать значения `IDLE | TURN_RUNNING | PARKED_ASYNC | PARKED_CLIENT`. `PARKED_CLIENT` — сессия, чья задача находится в CLIENT_EXEC-состоянии и ждёт подключённого исполнителя: присваивается при входе задачи в CLIENT_EXEC-состояние и снимается при успешной регистрации исполнителя (переход в `IDLE`/`TURN_RUNNING`); восстанавливается при разрыве соединения, пока задача остаётся в CLIENT_EXEC. Значение перестаёт быть «зарезервированным» (M2-формулировка) и присваивается реально.

#### Scenario: задача вошла в CLIENT_EXEC

- **WHEN** задача root-сессии переходит в CLIENT_EXEC-состояние
- **THEN** SessionDto.runtimeStatus = PARKED_CLIENT; SSE-снапшот `session.status` рассылается подписчикам

#### Scenario: исполнитель зарегистрировался

- **WHEN** клиент успешно зарегистрировался на workspace задачи
- **THEN** runtimeStatus = IDLE (или TURN_RUNNING, если немедленно стартует Turn)

#### Scenario: исполнитель отключился

- **WHEN** WS-соединение исполнителя разрывается, задача всё ещё в CLIENT_EXEC
- **THEN** runtimeStatus возвращается в PARKED_CLIENT
