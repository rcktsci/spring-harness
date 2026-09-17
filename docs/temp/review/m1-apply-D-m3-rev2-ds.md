# Ревью пачки D (задачи 7.1–7.6, ядро TurnManager) — DeepSeek-V4.1-Flash, рев. 2

> Фокус: race-conditions и атомарность, SQL/JPA-корректность, безопасность, семантика ShedLock.
> Сборка/Docker не запускались (использован прогон разработчика: `mvn clean verify` — 156/0/1).
> Проверено против `specs/agent-turn/spec.md`, `docs/design/execution-model.md` §1–§6,
> `data-model.md` §5, `openspec/changes/m1-session-core/design.md` D-M1-4/D-M1-5.
> Для проверки семантики `SimpleLock.extend` дизассемблированы классы ShedLock 7.10.1
> (`shedlock-provider-jdbc-template`, `shedlock-sql-support`) — см. раздел «Проверено».

## Findings (severity, файл:строка, дефект, предложение)

### M1 — MEDIUM: heartbeat молча умирает при любом исключении `lock.extend`

`SessionLockManager.java:88-100`. `heartbeat()` не оборачивает `lock.extend(...)` в try/catch.
`ScheduledExecutorService.scheduleAtFixedRate` при необработанном исключении **подавляет все
последующие запуски задачи** (javadoc `ScheduledThreadPoolExecutor`), а исключение уходит в
`ScheduledFuture`, т.е. не логируется воркером. Проверено по байткоду ShedLock 7.10.1:
`JdbcTemplateStorageAccessor.updateRecord` бросает `LockException` (unchecked) на «Unexpected
exception» (любая `DataAccessException` вне DuplicateKey/ConcurrencyFailure/TransactionSystem —
транзиентный обрыв соединения с БД и т.п.). Итог: после **одного** транзиентного сбоя продление
лока для данной сессии прекращается навсегда; при переживании TTL (`session-ttl`) лок освободится,
Turn продолжит работу, а POLL/EVENT сможет запустить второй конкурентный Turn — то есть риск D-40
(«замороженный владелец») реализуется от рядового сбоя БД, без единого WARN.
Предложение: весь `heartbeat()` в `try/catch (Throwable)` + WARN; при ошибке не терять `lock`
(как сейчас), повторять попытку на следующем тике. (ShedLock `LockExtender` внутри
`LockingTaskExecutor` ловит ошибки — здесь его защита не применима, т.к. лок взят напрямую.)

### M2 — MEDIUM: USER-событие между TOOL_CALL и TOOL_RESULT ломает протокол tool-calls

`SessionPromptBuilder.java:45-89` (case USER 48-51, flush 94-102; case TOOL_RESULT 77-85),
совместно с `AgentTurnEngine.java:104-156` (write-ahead ASSISTANT+TOOL_CALL, затем исполнение,
затем допись TOOL_RESULT). Если пользователь пишет сообщение, пока исполняется длинный
инструмент (bash), журнал получает порядок
`ASSISTANT → TOOL_CALL → USER → TOOL_RESULT`, что рендерится в
`AssistantMessage(tool_calls=[...]) → UserMessage → ToolResponseMessage`. OpenAI-совместимые
провайдеры (strict OpenAI — гарантированно, 400) требуют, чтобы каждый `tool_call` ассистента
был закрыт `role:tool`-ответами **непосредственно** после него; USER в разрыве недопустим.
Это ровно тот сценарий, который спека объявляет обязательным («сообщение пришло во время хода →
дополнительный раунд»), и именно он покрыт тестом `TurnEngineWireMockTest.messageDuringTurnIsSeenByExtraRound`
(порядок журнала asserted на строке 106-107), но WireMock не валидирует протокол — зелёный тест
маскирует дефект. На реальном провайдере дополнительный раунд уйдёт в FAILED после ретраев.
Предложение: в рендере привязывать `TOOL_RESULT`-ответы к их ASSISTANT-блоку (выносить их сразу
после закрывающего assistant-сообщения, а промежуточные non-tool события — после tool-ответов),
либо в движке буферизовать TOOL_RESULT-ы так, чтобы журнальный и промптовый порядок совпадали.
Обязательно добавить тест-сценарий с проверкой **порядка сообщений промпта** (не только журнала)
для interleaved USER.

### M3 — MEDIUM: чистка `sess-*`-локов сравнивает `TIMESTAMP(UTC)` с `now()` (session TimeZone)

`PollWakeJob.java:45-46`: `DELETE FROM shedlock WHERE name LIKE 'sess-%' AND lock_until < now()`.
Колонка `shedlock.lock_until` создана как `TIMESTAMP` (без tz, миграция A, changeSet 13), а
ShedLock с `usingDbTime()` **пишет в неё UTC-wall-clock**: в байткоде
`PostgresSqlServerTimeStatementsSource` insert/update/extend используют
`timezone('utc', CURRENT_TIMESTAMP)`. Наш же cleanup сравнивает с `now()` (`timestamptz`), который
при сравнении c `timestamp` переинтерпретируется в **TimeZone серверной сессии**. Если TimeZone
сессии ≠ UTC (не дефолт Postgres-образа, а GUC/инфра), живые продлённые локи будут удаляться
раньше времени (`session-ttl` смещается на величину offset), открывая окно для параллельных
Turn'ов; либо просроченные локи будут висеть дольше. В тестах проходит, потому что
Testcontainers-Postgres работает в UTC.
Предложение: сравнивать в единой шкале с ShedLock — `lock_until < timezone('utc', CURRENT_TIMESTAMP)`.

### L1 — LOW: `stop`, пришедший между взятием лока и `resetCancelRequested`, проглатывается

`TurnManagerImpl.java:66-77`. Порядок: `tryAcquire` (строки 67) → … → `activeTurns.register` (72)
→ `resetCancelRequested` (76). Если `requestStop` попадёт в окно после `tryAcquire`, но до
`register`, то в БД будет `cancel_requested=true`, а в `ActiveTurnRegistry` ещё пусто → немедленного
прерывания нет; затем `resetCancelRequested` (76) **стирает** флаг, и Turn спокойно доходит до
COMPLETED. Для пользователя «stop сразу после старта хода» = no-op, хотя по спеке Turn уже
активен (лок взят). Окно узкое, но детерминированно воспроизводимо таймингом.
Предложение: регистрировать `TurnCancellation` (или проверять флаг) до/вокруг сброса; например,
сбрасывать флаг **до** `register`, а после `register` повторно проверять `cancel_requested` и
вызывать `cancellation.cancel()`.

### L2 — LOW: `finishTurn` может «съесть» сообщение, пришедшее после проверки «новых событий нет»

`AgentTurnEngine.java:131-142` (проверка `after.lastSeq() - roundBasisSeq == appendedByRound`) →
`:137 finishTurn`, а сам `finishTurn` — `SessionStoreImpl.java:127-132`
(`SET last_consumed_seq = last_seq`). Между `findSession`/проверкой и UPDATE есть окно: конкурентный
`appendEvent` успевает закоммитить USER-событие, `finishTurn` под row-lock выставляет
`last_consumed_seq = last_seq` (включая это событие). После этого: POLL не подберёт
(`last_seq = last_consumed_seq`), а EVENT-wake от API (`tryStart`) сделает no-op (условие
`lastSeq <= lastConsumedSeq`). Сообщение не обработано никем — нарушение «события во время хода
подхватываются». Окно узкое (микросекунды), но это реальный lost-update.
Предложение: условное завершение `UPDATE session SET last_consumed_seq = last_seq, ... WHERE id=?
AND last_seq = :expectedSeq`; 0 обновлённых строк → вернуться в цикл и обработать новый батч.

### L3 — LOW: `RestartScanRunner` не полностью соответствует «ошибка пункта не валит старт»

`RestartScanRunner.java:44-52,54-62`. Javadoc обещает «ошибка одного пункта не валит старт
процесса», но `restartScan()` вызывает `closePendingToolCalls()` без внешнего try/catch, а внутри
него вне try/catch находится первый запрос `sessionStore.findSessionIdsWithPendingToolCalls()`
(строка 55). Сбой БД на этом запросе уйдёт из `@EventListener(ApplicationReadyEvent)` наверх (а
`removeOrphanContainers()` в этом случае вообще не выполнится). `removeOrphanContainers` обёрнут —
несогласованность.
Предложение: обернуть тело `closePendingToolCalls()` (и/или каждый шаг `restartScan()`) в try/catch
с WARN, как уже сделано для контейнеров.

### L4 — LOW: синхронный `killUntilDead` в `cancel()` блокирует поток вызывающего stop

`TurnCancellation.java:38-47` + `ContainerWorkspaceTools.java:220-222,261-275`. `cancel()`
запускает interruptor'ы **синхронно на потоке вызывающего**; interruptor — `killUntilDead`, который
циклично (шаг `state-poll-interval`) делает `containers.exec` и завершается только при успешном
убийстве или по `execDeadline` (= `effective bash-timeout` + 5с, cap `bash-timeout-cap` = 5m).
`requestStop` вызывается синхронно из REST `POST /stop` (задача 8.4) — если stop пришёл до появления
PID-файла (или контейнер/exec ещё не стартовали), HTTP-поток может блокироваться на минуты, при
повторных stop — исчерпание сервлетного пула. Штатный случай (PID-файл уже есть) отрабатывает за
1–2 итерации.
Предложение: выполнять interruptor'ы асинхронно (отдельный executor/виртуальный поток), либо
ограничить цикл добивания коротким окном, а долгое добивание оставить фоновому пути.

### L5 — LOW: нет теста сценария «зависший процесс не блокирует навсегда» (истечение TTL)

Спека `agent-turn` требует сценарий «лок истекает по TTL, сессия подбирается повторно». В
`SessionLockManagerTest` есть winner/busy/heartbeat/unlock, но нет теста перехвата по истечении
`session-ttl` (в тестовом профиле TTL не сокращён — 10m). `SessionLockManagerTest.heartbeatExtendsLockUntil`
проверяет продление, но не деградацию. Добавить тест (например, с укороченным `session-ttl` в
отдельном профиле/проперти): после истечения TTL второй `tryAcquire` берёт лок; `extend` старого
владельца → empty.

### I1 — INFO: `TOOL_CALL` без `callId` вечно считается «зависшим»

`SessionStoreImpl.java:152-168` (`tr.payload_jsonb ->> 'callId' = sm.payload_jsonb ->> 'callId'`):
при `callId = NULL` равенство `NULL = NULL` не истинно, такой TOOL_CALL навсегда попадает в
`findSessionIdsWithPendingToolCalls`. Движок всегда пишет `callId`, так что на практике не
проявляется, но defensive-фильтр (`AND sm.payload_jsonb ->> 'callId' IS NOT NULL`) не помешает.

### I2 — INFO: нет валидации `heartbeat-interval < session-ttl/2`

`LockProperties` без валидации. При misconfig (`heartbeat-interval` ≥ `session-ttl`) лок истечёт
до первого продления. Дефолты 30s / 10m безопасны (20×), но стартовая валидация
(`@ConfigurationProperties` + assertion) закрыла бы класс ошибок конфигурации.

## Проверено и валидно (без замечаний)

1. **ShedLock `extend` не «возвращает» чужой лок** — гипотеза «старый владелец вернёт лок обратно»
   опровергнута: extend-SQL содержит `WHERE name = :name AND locked_by = :lockedBy`
   (дизассемблирован `PostgresSqlServerTimeStatementsSource`: `UPDATE ... SET lock_until = CASE ...
   END WHERE name = :name AND locked_by = :lockedBy`). `locked_by` — на инстанс `LockProvider`,
   поэтому после перехвата другим инстансом/процессом extend старого владельца вернёт `empty` и
   steal-back невозможен. Поведение `heartbeat` (не сбрасывать `lock` при empty) корректно.
2. **`reserveSeqByRowLock` (SessionStoreImpl:232-247)** — `UPDATE ... RETURNING` под
   `@Transactional`, тот же DataSource через `JdbcTemplate` = участник транзакции; row-lock строки
   сессии сериализует конкурентные дописи, откат транзакции откатывает и seq, и INSERT.
   Подтверждено `SessionAppendTest.concurrentAppendsHaveNoGapsAndNoDuplicates` (2×25) и
   `failedAppendRollsBackSeqReservation`.
3. **`finishTurn` атомарен относительно дописи**: `SET last_consumed_seq = last_seq` — одно
   SQL-выражение; при конкуренции Postgres сериализует UPDATE строки через row-lock и
   переоценивает `last_seq` (read committed + EvalPlanQual). Дыр/рассинхрона нет (см. L2 — это
   отдельная семантика потери, а не атомарность).
4. **TOCTOU `requestCancel` vs `appendEvent`** — флаг в БД читается при каждой проверке
   (`isCancelled` → `findSession`), отдельного неатомарного кэша нет; `ActiveTurnRegistry` — лишь
   ускоритель in-flight прерывания. Штатно корректно.
5. **`notifyListenersAfterCommit` (SessionStoreImpl:364-385)** — публикация строго в `afterCommit`
   при активной синхронизации; self-invocation (4-арг → 5-арг) безопасен, т.к. внешний вызов идёт
   через Spring-proxy `SessionStore` и транзакция уже открыта (класс-level `@Transactional`).
   Все вызовы `appendEvent` идут через бин (`AgentTurnEngine`, `RestartScanRunner`, тесты).
6. **Миграция ↔ entity**: `session` (cancel_requested/last_seq/last_consumed_seq/last_turn_outcome,
   CHECK 'COMPLETED'|'FAILED'|'CANCELLED'), `session_message` (kind VARCHAR(16), PK
   `(session_id, seq)`, `payload_jsonb NOT NULL`), `shedlock` (name/lock_until/locked_at/locked_by)
   — соответствуют `SessionEntity`, `SessionMessageEntity`, ожиданиям ShedLock. Все существующие
   enum-значения ≤ 16 символов.
7. **Enum-параметры JPQL** `sm.kind IN (:toolCall, :toolResult)` — Hibernate 6 штатно; `renderVisible`,
   `compactCovers`, `selectByIntervals` не затронуты пачкой.
8. **`WorkspaceTools.bash(..., cancellation)` default** — делегирует в 4-арг версию, контракт не
   ломает другие реализации; `ContainerWorkspaceTools` переопределяет.
9. **PID-файл/убийство группы** — `/tmp/harness-exec-<ULID>.pid`, PID берётся из `echo $$` (число),
   путь цитируется (`"$1"`), инъекции нет; `kill -TERM -<pgid>` в контейнере. Безопасно.
10. **Отсутствие заноса M3/M4**: `SessionRuntimeStatus` — только IDLE/TURN_RUNNING;
    `ToolStatus.ASYNC_ACCEPTED` объявлен как «reserved M3» и не используется; `ToolResult` без
    late-поля; `bash` sync; отмена — через контейнер (не SSE).
11. **`NativeAgentTools` dummy-лямбды** — используются только как схемы для провайдера; внутреннее
    исполнение Spring AI не запускается (`ChatModel.stream` + `MessageAggregator`, не `ChatClient`),
    диспетчеризация — `execute(...)` со switch; неизвестный tool → `ToolResult.error`. Корректно.
12. **`AgentTurnEngine` callId-модель** — внутренний ULID (`idGenerator.newUlid()`) отделён от
    провайдерского `toolCall.id()`; `TOOL_RESULT` в журнал пишется с внутренним `callId`, а
    `SessionPromptBuilder` маппит его обратно на провайдерский id через `providerIds`. Корректно
    (кроме M2 — порядка).
13. **Regression C-m3 не сломан**: `splitByUtf8Bytes` → `dockerProperties.writeChunkBytes()`;
    `BoundedOutputStream`/`setOnOverflow` в `WorkspaceContainerManager.exec`; bash — через
    `ContainerWorkspaceTools.bash(..., cancellation)`.
14. **`WorkspaceContainerManager.exec` cancellation-путь**: свой `CountDownLatch` + `close()`-override
    (единственный latch на завершение/ошибку/overflow); при отмене `exit>=128` форсируется
    `WorkspaceContainerException`, которая в `bash` превращается в синтетический CANCELLED. Логика
    согласована.
15. **Тестовое покрытие 7.1–7.6**: happy и error-path покрыты (`SessionLockManagerTest`,
    `TurnEngineWireMockTest` — full cycle/extra round/retries exhausted/concurrent start,
    `PollWakeJobTest` — pickup/cleanup/no-op, `TurnCancellationTest` — in-flight bash/idle/idempotent/
    message-after-cancel, `RestartScanTest` — LOST/live-lock/orphans, unit-тесты builder/broadcaster).
    Моки внутрь контекста не внедряются (WireMock — внешний HTTP-бэкенд; единый контекст через
    `BaseExecutionTest`/`BaseApplicationTest`).

## Summary

- **MEDIUM: 3** (M1 heartbeat silent death; M2 tool-protocol violation on «message during turn»;
  M3 timezone-mismatch в чистке sess-локов).
- **LOW: 5** (L1 stop/reset race; L2 lost-update в `finishTurn`; L3 незавёрнутый рестарт-скан;
  L4 синхронный блокирующий interruptor; L5 нет теста TTL-перехвата).
- **INFO: 2** (I1 NULL `callId`; I2 нет валидации heartbeat/ttl).
- **HIGH: 0.**

**Вердикт: REJECT (требуются фиксы M1 и M2 обязательными; M3 — в этот же цикл).**
Нормативные проверки (атомарность seq/finishTurn, post-commit-доставка, ShedLock steal-back,
занос M3/M4, regression C-m3, покрытие 7.1–7.6) — валидны. Блокирующими считаю:
M2 — спек-обязательный сценарий «сообщение во время хода» падает на strict-провайдерах (тест со
WireMock этого не видит); M1 — молчаливая деградация mutex-лока от единственного транзиентного
сбоя БД (прямо бьёт в осознанно принятый D-40-риск). После фиксов и re-run `mvn clean verify`
готов пере-ревьюить.

Not verified: реальный прогон против strict OpenAI-провайдера (только WireMock); TimeZone
production-БД (M3 — по конфигу среды).
