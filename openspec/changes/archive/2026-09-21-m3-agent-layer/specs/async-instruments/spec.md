## Purpose

Async-инструменты с временным окном: `ASYNC_ACCEPTED(callId)` плейсхолдер → поздний `TOOL_RESULT` через канал событий сессии; первый финальный результат по `call_id` выигрывает; рестарт-скан синтетически закрывает долгие вызовы.

## ADDED Requirements

### Requirement: Async-инструмент — окно и плейсхолдер

Инструмент, объявленный `async-capable`, при превышении окна (конфиг `harness.async.window.default-ms`) SHALL быть заменён синхронно в журнале на `TOOL_CALL(callId) + ASYNC_ACCEPTED(callId)` (`ASYNC_ACCEPTED` — допустимое значение `MessageKind`, enum расширяется миграцией 018; модель видит, что инструмент «принят, не закончен»). Sync-результаты раунда фиксируются в этом же раунде; если в раунде есть async-вызов(ы), не уложившиеся в окно, Turn завершает раунд и паркует сессию (`PARKED_ASYNC`). Фон-исполнитель доводит инструмент вне Turn'а; по завершении — `TOOL_RESULT(callId, late=true)` через сессионный канал `message.created`/`session_message.seq` (M1 `InMemorySessionEventBroadcaster`, инкремент `last_seq` сессии); поздний результат поднимает новый Turn, в рендер которого входят и плейсхолдер, и поздний результат.

#### Scenario: bash в окне

- **WHEN** модель вызвала `bash` и окно не превышено
- **THEN** журнал фиксирует TOOL_CALL → bash исполняется синхронно → TOOL_RESULT синхронно

#### Scenario: bash превысил окно

- **WHEN** модель вызвала `bash`, окно превышено
- **THEN** в журнале две записи — TOOL_CALL(callId) и ASYNC_ACCEPTED(callId); ход возвращается агенту; bash продолжается в фоне; результат позже — TOOL_RESULT(callId, late=true) и wake

#### Scenario: первый финальный результат выигрывает

- **WHEN** для одного callId пришло два результата (например, реальный + синтетический LOST от рестарт-скана)
- **THEN** в журнале фиксируется только первый, второй no-op

### Requirement: Late `TOOL_RESULT` через EventBus

Поздний `TOOL_RESULT` SHALL публиковаться через `InMemorySessionEventBroadcaster` (existing M1) как `message.created`/`session_message.seq` — запись журнала инкрементирует `last_seq` сессии и вызывает wake существующим `TurnManager.tryStart`-путём. После парковки (раунд с async, не уложившимся в окно, — Turn завершён, `PARKED_ASYNC`) поздний результат всегда поднимает новый Turn с `instructionSource=TOOL_RESULT`. M2 `task_event_seq` (счётчик задач, канал `tasks/{id}/events`) на сессионном канале не используется.

#### Scenario: late-result после парковки

- **WHEN** поздний TOOL_RESULT приходит в запаркованную сессию (Turn завершён)
- **THEN** поднимается новый Turn; модель видит плейсхолдер ASYNC_ACCEPTED, поздний результат и финальную обработку

#### Scenario: late-result при активном Turn'е

- **WHEN** поздний TOOL_RESULT приходит, пока Turn активен (например, USER разбудил сессию во время фона)
- **THEN** активный Turn подхватывает результат дополнительным раундом (M1-семантика событий во время хода)

### Requirement: Рестарт-скан закрывает долгие async

При старте процесса рестарт-скан SHALL для каждой сессии без живого лока `sess-{id}` найти `TOOL_CALL` без парного финального `TOOL_RESULT` (финальные статусы — `OK|ERROR|CANCELLED|LOST`; `ASYNC_ACCEPTED` — placeholder «в полёте», финальным НЕ считается) и закрыть синтетическим `TOOL_RESULT LOST «операция потеряна при перезапуске»`. Если в той же сессии позже придёт реальный результат — первый финальный уже записан (правило «первый выигрывает», D-64), no-op.

#### Scenario: долгий async-tool на рестарте

- **WHEN** сессия имеет TOOL_CALL(callId) без результата и свободный лок после рестарта
- **THEN** дописывается синтетический TOOL_RESULT LOST с reason «restart»; в следующем раунде модель видит причину

### Requirement: Async-контракт `MessageDto.late`

`MessageDto.late` SHALL быть boolean-полем для `TOOL_RESULT|SYSTEM` сообщений (true если результат прибыл после соответствующего раунда Turn'а). В M1 было зарезервировано «отсутствует» — в M3 аддитивно реализуется.

#### Scenario: late TOOL_RESULT имеет late=true

- **WHEN** журнал читается клиентом после позднего результата
- **THEN** в MessageDto поле `late: true`

### Requirement: Лимит `harness.late-result.timeout-ms`

Если `TOOL_RESULT` не пришёл в течение `harness.late-result.timeout-ms` (дефолт 15 минут) от создания `ASYNC_ACCEPTED` — фоновый `AsyncTimeoutWatcher` (ShedLock-джоба; расписание — конфиг) SHALL закрыть вызов синтетическим `TOOL_RESULT LOST «операция потеряна — превышен верхний лимит»` под программным локом сессии `sess-{id}` (D-64). На рестарте процесса ту же проверку выполняет рестарт-скан. Защита от утечки `TOOL_CALL` без результата.

#### Scenario: поздний результат не пришёл

- **WHEN** прошло 15 минут с ASYNC_ACCEPTED, TOOL_RESULT нет
- **THEN** синтетический LOST с timeout-причиной
