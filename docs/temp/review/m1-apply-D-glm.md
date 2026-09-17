# Ревью пачки D (задачи 7.1–7.6, ядро TurnManager) — GLM-5.3-Flash

Дата: 2026-09-17. Объект: `execution/**` новые (ShedLockConfig, SessionLockManager, TurnManager/Impl, AgentTurnEngine, PollWakeJob, RestartScanRunner, ActiveTurnRegistry, TurnCancellation, SessionPromptBuilder, TurnPayloads, NativeAgentTools), новые `session/**` (broadcaster, SessionEvent/Listener, SessionRuntimeStatus), изменения SessionStore(+Impl), LlmInvoker (+toolCallbacks), WorkspaceTools/Manager/Tools (cancel), 10 тест-файлов, application-test.yml.
Эталоны: specs/agent-turn (по сценариям), design.md D-M1-4/D-M1-5, execution-model §1/§2/§3/§6, data-model §5, glossary.
Сборки/Docker не запускались (запрещено); прогон разработчика 156/0/1skip принят. Все выводы — статический анализ.

## Findings

### minor

**D-1. Микро-окно поглощения сообщения между решением о завершении и `finishTurn`** — `src/main/java/se/rocketscien/harness/execution/AgentTurnEngine.java:132-137` + `SessionStoreImpl.finishTurn`.
В ветке «нет tool-calls» движок читает `after.lastSeq()` (DB-чтение #1), сравнивает с базой раунда и вызывает `finishTurn`, который выполняет `UPDATE … SET last_consumed_seq = last_seq` (DB-чтение #2 уже внутри UPDATE). USER-допись, закоммиченная между #1 и UPDATE, поглощается: Turn COMPLETED, `last_seq == last_consumed_seq`, POLL/EVENT не разбудят — сообщение пролежит в журнале без ответа до следующего сообщения (потом отрендерится полным журналом). То же окно на пути FAILED (AgentTurnEngine.java:90-92: между SYSTEM-дописью и finishTurn) и CANCELLED. Спека: «события, пришедшие во время хода, SHALL подхватываться дополнительным раундом» — в этом окне требование нарушается; штатная проверка `lastSeq - roundBasisSeq == appendedByRound` окно не закрывает, т.к. сравнение уже сделано.
Предложение: `finishTurn` возвращает потреблённый `last_seq` (RETURNING) — при расхождении с `roundBasisSeq + appendedByRound` движок делает дополнительный раунд вместо завершения (только на COMPLETED-пути); либо проверка+finish в одной транзакции с `SELECT … FOR UPDATE` строки сессии.

**D-2. CANCELLED потребляет батч — судьба сообщения, дописанного во время отменяемого Turn'а, не зафиксирована ни тестом, ни решением** — `SessionStoreImpl.finishTurn` (`last_consumed_seq := last_seq` для любого исхода; контракт SessionStore.java:56-60).
Дописанное во время Turn'а USER-сообщение при stop: (а) на нормальном пути попало бы в доп. раунд; (б) на пути отмены — движок пишет синтетические CANCELLED-результаты и завершается, `finishTurn(CANCELLED)` потребляет батч → сообщение не подхватывается ни доп. раундом, ни POLL, ждёт следующего сообщения. Сама семантика защитная (та же, что для FAILED — против retry-шторма POLL) и документирована в Javadoc контракта, НО: спека §Отмена это прямо не оговаривает (в отличие от FAILED), в decisions.md/apply-notes решения нет, теста «USER во время Turn'а + stop → сообщение обрабатывается следующим Turn'ом» нет (покрыт только «сообщение ПОСЛЕ отмены», TurnCancellationTest.stopOnIdleSessionIsHarmlessAndNextMessageStillWorks).
Предложение: строка в decisions.md/apply-notes + тест на mid-cancel сообщение (следующий Turn обязан его увидеть в рендере).

**D-3. stop во время LLM-стрима не прекращает поток** — `AgentTurnEngine.java:85-87` (`MessageAggregator…blockLast()`) + `TurnCancellation` (не связан с подпиской).
llm-gateway spec: «после отмены поток прекращается» — возможность есть (dispose Flux, тест пачки C cancellationStopsTheStream), но движок не подключает отмену к подписке: стрим после stop дочитывается до конца (лишние токены/до 60s), CANCELLED выставится после завершения. Спека agent-turn требует проверок «между вызовами» и прерывания только bash — формально не нарушено, но неиспользованная отменяемость стрима = бесплатная трата при stop во время длинной генерации.
Предложение: `registerInterrupt` → `Disposable.dispose()` подписки стрима (аналогично bash-прерывателю), синтетический ASSISTANT не писать, Turn — CANCELLED.

**D-4. Отклонения пачки D не записаны в apply-notes/decisions** — `openspec/changes/m1-session-core/apply-notes.md` не дополнен.
По правилу владельца сущностные решения фиксируются: (1) `SimpleLock.extend` вместо `LockExtender`; (2) EVENT-хук = контракт `tryStart`, вайринг append→tryStart — в 8.3 (риск: забыли — EVENT-контур тихо отсутствует, деградация до poll-interval; доказательство — 10.2); (3) kill -9 смоделирован пост-фактум, полный рестарт-тест — 10.3; (4) CANCELLED потребляет батч (см. D-2). Записать однострочно.

### nit

**D-5. Грейс exec-таймаута `+5s` захардкожен** — `ContainerWorkspaceTools.bash` (`effective.plusSeconds(5)`; тянется с пачки C): число в коде → в конфиг (например, `harness.docker.exec-grace`).

**D-6. `WorkspaceTools.bash(+cancellation)` default-метод молча игнорирует отмену** — приём корректный (M4-релей унаследует безопасный дефолт), но реализациям-не-переопределяторам стоит напоминать `@ApiStatus`/Javadoc- warn при появлении второй реализации.

**D-7. Javadoc `findEligibleSessionIds` обещает «index-only»** — SELECT id требует heap-fetch (partial index по `(last_seq)` не покрывает id). Корректность не страдает; поправить формулировку или покрыть включением id.

**D-8. `requestStop` на другом инстансе — только флаг БД** — честно задокументировано в ActiveTurnRegistry; на текущем уровне (один инстанс, D-39) — норма, отметить в decisions при выходе за одиночный инстанс.

## Заявленные отклонения — вердикты

| # | Отклонение | Вердикт |
|---|---|---|
| 1 | `SimpleLock.extend()` вместо `LockExtender` | **Корректно, approve.** `LockExtender` работает через ThreadLocal-регистрацию `LockingTaskExecutor` и при прямом `LockProvider.lock()` неприменим — `SimpleLock.extend` это ровно тот же вызов, что делает LockExtender внутри. Heartbeat конфигурируем (`harness.lock.heartbeat-interval`), гонка extend↔unlock закрыта mutex (SessionLockManager.java:88-111), неудача продления → warn D-40. Покрытие: `heartbeatExtendsLockUntil` на реальном таймере (200ms), 1-победитель из 4, unlock→немедленное истечение. |
| 2 | EVENT-хук = контракт `tryStart`, без автовейка в `appendEvent` | **Допустимо, с оговоркой D-4.** Источник события в M1 — POST /messages (8.3, ещё не существует): спека возлагает инициацию на источник, контракт `tryStart` + тайминг-тест (`wakeEventStartsTurnWellBeforePollInterval` < poll-interval) готовы. Вайринг — обязательный пункт 8.3; контроль — 10.2 (end-to-end attach). |
| 3 | 7.6 kill -9 пост-фактум, полный тест — 10.3 | **Достаточно.** Механика скана покрыта исчерпывающе: свободный лок → LOST с точной причиной «операция потеряна при перезапуске» + wake; живой лок → пропуск; осиротевшие контейнеры удалены/живые сохранены (UUID-суффикс-парсинг, `harness-task-*` M2 не задет). Что даст 10.3: реальный подъём контекста после kill -9 (ApplicationReadyEvent-вайринг тривиален). Согласен с переносом при условии, что 10.3 остаётся гейтом фазы. |
| 4 | CANCELLED потребляет батч | **Семантика защитно-обоснована, но не зафиксирована** — см. D-2 (нужны решение в docs + тест на mid-cancel сообщение). |
| 5 | `WorkspaceTools` default-метод для bash+отмены | **Approve.** Минимально-инвазивное расширение контракта; дефолт — безопасный no-op-cancel; реализация ContainerWorkspaceTools переопределяет (PID-группа + killUntilDead). См. nit D-6. |
| 6 | `finishTurn`/pending-сканы: атомарность и гонки | finishTurn атомарен одним UPDATE (consume+outcome+reset ✓); pending-сканы консистентны: «pending» = TOOL_CALL без ЛЮБОГО TOOL_RESULT с тем же callId (CANCELLED/LOST считаются результатами — рестарт-скан не дублирует). Гонка допись↔finish — см. D-1 (единственное найденное окно). |

## Проверено и валидно (без замечаний)

1. **Сценарии спеки agent-turn — 12/12 покрыты тестами**: сообщение запускает Turn сразу (< poll-interval); сессия пропущена → POLL подбирает (PollWakeJobTest); конкурентный запуск — ровно один Turn (5 конкурентов, verify(2) запроса); TTL-истечение/чистка (`pollCleansExpiredSessionLockRowsOnly` — только просроченные, живой продлённый лок не задет); полный цикл с инструментом (журнал USER→ASSISTANT→TOOL_CALL→TOOL_RESULT→ASSISTANT, реальный вывод bash «hello-from-tool», verify(2)); сообщение во время хода → доп. раунд (детерминированный sleep-подход + verify тела второго запроса с «второй вопрос»); рестарт между записью и исполнением → синтетический LOST; stop во время bash → процесс реально убит (проверка по PID-файлу) + TOOL_RESULT CANCELLED + CANCELLED < 60s; stop на IDLE безвреден; сообщение после отмены штатно; повторный stop идемпотентен (журнал не растёт); живой Turn рестарт-сканом не тронут.
2. **Конкурентный tryStart**: пред-проверка seq — только оптимизация; взаимоисключение даёт `tryAcquire` (ShedLock, `usingDbTime` — едино с критерием чистки). Окна двойного исполнения нет.
3. **unlock в finally**: runTurn — finally { IDLE-статус, unregister, lock.close() }; close отменяет heartbeat и снимает лок под mutex; heartbeat-поток daemon, `shutdownNow` в PreDestroy; turnExecutor.shutdown в PreDestroy.
4. **Write-ahead**: ASSISTANT+TOOL_CALL журналируются до исполнения (execution-model §1); внутреннее исполнение инструментов Spring AI не участвует (коллбэки — только схемы; поведение закреплено end-to-end тестом полного цикла с подсчётом запросов — регрессия «встроенного» исполнения была бы поймана формой журнала и verify(2)).
5. **Рендер→промпт**: SessionPromptBuilder корректно сливает ASSISTANT+TOOL_CALL, маппит TOOL_RESULT по provider tool_call_id (fallback callId), COMPACT → SYSTEM-пересказ; юнит-тесты на все ветки.
6. **Broadcaster**: переупорядочивающий буфер по seq (резерв seq row-lock'ом раньше afterCommit-публикации) — доставка строго по возрастанию, дубли/опоздавшие отбрасываются, после коммита (TransactionSynchronization.afterCommit); юнит-тесты на порядок/дубли/отписку/устойчивость к падению подписчика.
7. **Скоуп**: только 7.x; broadcaster in-memory (D-M1-5), никаких M3/M4 (нет spawn_subagent, async-окна bash, CLIENT_EXEC); tasks 7.1–7.6 = [x], 8.x не тронуты; хардкод чисел — только D-5 (+5s грейс).

## Fixes approval

Ре-аппрув по `docs/temp/review/m1-apply-D-judge.md`. Сборки не запускались (запрещено); прогон разработчика `mvn clean verify` 164/0/0/1skip принят. Все проверки — по факту файлов.

| Находка | Судья | Статус по файлам | Итог |
|---|---|---|---|
| D-1 (minor) микро-окно поглощения при finishTurn | D-J-2 (D-45) | SessionStore.finishTurn(sessionId, outcome, consumedSeq): `last_consumed_seq = GREATEST(last_consumed_seq, ?)`; движок ведёт `renderedWatermark` (AgentTurnEngine.java:75,90-92); COMPLETED — watermark = финальный ASSISTANT (AgentTurnEngine.java:154) → USER из микро-окна остаётся непотреблённым и поднимает новый Turn | **Approve (закрыто)**. |
| D-2 (minor) CANCELLED потребляет батч без теста/решения | D-J-2 (D-45) | decisions.md — запись D-45 (watermark виденного, отвергнутые альтернативы, обоснование); CANCELLED — watermark = последний рендер (AgentTurnEngine.java:144), собственные CANCELLED-результаты и свежий USER остаются непотреблёнными; контракт SessionStore задокументирован по исходам; тест `userMessageDuringCancellationWindowSurvivesAndStartsNewTurn` | **Approve (закрыто)** — ровно то, что требовал (решение + тест на mid-cancel сообщение). |
| D-3 (minor) stop не прерывает LLM-стрим | D-J-4 | AgentTurnEngine.callModel: ручная подписка (CountDownLatch+AtomicReference), `registerInterrupt → disposable.dispose()`, закртите гонки «cancel до subscribe» повторной проверкой (:208-210), `doOnCancel` разблокирует ожидание; отменённый ответ не журналируется. Тест `stopDuringLlmStreamDisposesSubscriptionAndCancelsQuickly` | **Approve (закрыто)**. |
| D-4 (minor) отклонения не записаны | D-J-5 (+отклонённая таблица судьи) | apply-notes.md — раздел «Пачка D (7.1–7.6) и её девиации»: broadcaster in-memory + lastDeliveredSeq не источник истины (реконнект — из SessionStore по since, контракт 8.5), SimpleLock.extend, EVENT-хук = tryStart с вайрингом в 8.3 (контроль 10.2), kill -9 пост-фактум (гейт 10.3), watermark D-45 | **Approve (закрыто)**. |
| D-5 (nit) `+5s` грейс захардкожен | не гейтило | без изменений | Backlog (nit, не блокирует). |
| D-6/D-7/D-8 (nit) | не гейтило | без изменений | Backlog (nits, не блокируют); D-7 «index-only» формулировка осталась. |

### Судейские пункты вне моего списка (проверены попутно)

- **D-J-1 (heartbeat-хардненинг)** ✓: `heartbeat()` гасит Throwable — тики при транзиентном сбое продолжаются (SessionLockManager.java:111-113); неудачный extend → `lost=true`, heartbeat прекращён; `close()` при lost НЕ вызывает unlock (чужой лок не снят) (:122-131). Тесты: `heartbeatThrowableDoesNotKillSubsequentTicks`, `heartbeatExtendFailureMarksLockLostAndCloseKeepsForeignLock`.
- **D-J-3 (порядок OpenAI-протокола)** ✓: SessionPromptBuilder — ToolGroup (assistant+tool_calls → ToolResponseMessage строго за ним), interleaved USER/SYSTEM/COMPACT удерживаются в `heldAfterGroup` и выпускаются после группы (:47-123); тест `interleavedUserGoesAfterToolResponseGroup` проверяет порядок, не только успех.
- **D-J-6 (чистка sess-* по времени БД)** ✓: `CLEANUP_SQL = … lock_until < timezone('utc', now())` — сравнение целиком в БД/UTC, Java-время не участвует; тест `cleanupComparesLockUntilInDbUtc`.
- **D-J-2 требования к FAILED/CANCELLED** ✓: `failedTurnIsNotRewokenByPoll` (FAILED: watermark=SYSTEM — retry-штора снята), `messageDuringTurnIsSeenByExtraRound` (регресс).
- Отклонения судьи (D-R-1: ERROR/LOST — результат, не FAILED; D-R-2: мои отклонения 1/2/3/5 приняты) — согласен.

### Итог ре-аппрува

**APPROVE.** D-J-1…D-J-6 закрыты по факту (+тесты под каждый, включая 8 новых), мои D-1…D-4 закрыты (D-45 закреплён в decisions.md), nit-хвосты (D-5…D-8) — backlog. Условие «verify зелёный» принято по прогону разработчика (164/0/0/1skip).

## Summary

Ядро TurnManager сделано добротно и точно по D-M1-4/D-M1-5: два контура wake с контрактной точкой EVENT, ShedLock-mutex сессий (конкурентный tryStart — 1 победитель из 5, без окна двойного исполнения), heartbeat через SimpleLock.extend с конфиг-интервалом и реальным таймер-тестом, unlock в finally, write-ahead до инструментов, рестарт-скан только по свободным локам с осиротевшими контейнерами по UUID, broadcaster со строгим порядком seq, отмена с реальным убийством процесса bash — и все 12 сценариев спеки закрыты тестами (включая детерминированный «сообщение во время хода»). Четыре minor: окно поглощения сообщения между решением о завершении и finishTurn (D-1), не зафиксированное решение «CANCELLED потребляет батч» без теста на mid-cancel сообщение (D-2), stop не прерывает LLM-стрим (D-3), незаписанные отклонения в apply-notes/decisions (D-4); плюс четыре nit. Все шесть заявленных отклонений принимаю (D-2 — после документирования). Вердикт: к аппруву после D-4 (запись решений) и желательного D-2-теста; D-1/D-3 можно в пачку E.
