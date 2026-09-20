# session-api Specification (delta for M3)

## ADDED Requirements

### Requirement: `MessageDto.late` реализовано

`MessageDto.late` SHALL быть boolean-полем в `MessageDto` (для сообщений вида `TOOL_RESULT|SYSTEM`) — true, если запись прибыла после соответствующего раунда Turn'а (поздний результат async-инструмента). В M1 было зарезервировано «отсутствует» — в M3 реализуется реально.

#### Scenario: late TOOL_RESULT

- **WHEN** агент читает журнал и среди записей есть поздний TOOL_RESULT
- **THEN** `MessageDto.late: true`

#### Scenario: синхронный результат

- **WHEN** синхронный TOOL_RESULT в том же раунде
- **THEN** `MessageDto.late: false` (или отсутствует) — норма
