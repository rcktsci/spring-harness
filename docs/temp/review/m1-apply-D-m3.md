# Review m1-session-core — apply, пачка D (7.1–7.6 TurnManager) — судейский вердикт

> Прогон m3 по незакоммиченной пачке D: ShedLockConfig, SessionLockManager, TurnManager/Impl, AgentTurnEngine, PollWakeJob, RestartScanRunner, ActiveTurnRegistry, TurnCancellation, SessionPromptBuilder, TurnPayloads, NativeAgentTools, SessionEventBroadcaster/InMemory*Broadcaster, SessionStore+Impl (`finishTurn`, `requestCancel`, `resetCancelRequested`, `findPendingToolCalls`, appendEvent→broadcaster), изменения ContainerWorkspaceTools (bash+cancellation, PID-файл/killUntilDead).
> Ревью: GLM-5.3-Flash · DeepSeek-V4.1-Flash · Mercury-2.5. Сборки/Docker не запускались (запрет; принят прогон разработчика `mvn clean verify` 156 тестов / 0 падений / 1 skipped).

## Findings (severity, файл:строка, дефект с фактом, предложение)

### high

1. **`SessionLockManager.HeldLock` после истечения TTL может снять чужой лок** — `src/main/java/se/rocketscien/harness/execution/SessionLockManager.java:88-100` (`heartbeat()`) + `:102-111` (`close()`).
   **Факт:** (a) если TTL истёк и лок перехвачен другим инстансом — `lock.extend` вернёт empty; текущий код только логирует WARN и сохраняет ссылку. `close()` далее безусловно `lock.unlock()` — ShedLock-unlock матчит строку по имени без проверки владельца → «зомби» снимет лок легитимного нового владельца → окно для третьего Turn'а (D-40 риск реализуется от простого истечения). (b) `scheduleAtFixedRate` без обёртки try/catch в `heartbeat()` — первое же необработанное исключение ShedLock (`LockException` на транзиентный сбой БД, подтверждено байткодом 7.10.1) **подавляет все последующие запуски** (Java contract `ScheduledThreadPoolExecutor`), без лога. Консенсус 3/3: GLM F3, DS M1, Mercury HIGH.
   **Предложение:** (а) при `extend == empty` помечать HeldLock потерянным (`lost = true`), в `close()` не вызывать unlock (только отменять heartbeat); (б) весь `heartbeat()` в `try/catch (Throwable)` + WARN; повторять попытку на следующем тике. Тест: захват → `UPDATE shedlock SET lock_until = now()` (имитация перехвата/сбоя) → extend пуст → close не снимает чужой строки; лок живёт.

### medium

2. **CANCELLED поглощает батч: USER-сообщение, дописанное во время Turn'а, никогда не увидит модель** — `src/main/java/se/rocketscien/harness/session/SessionStoreImpl.java:127-132` (`finishTurn` делает `last_consumed_seq = last_seq` для ЛЮБОГО исхода, в т.ч. CANCELLED); контракт `SessionStore.java:56-60` декларирует это для «любого исхода».
   **Факт:** спека agent-turn (Requirement «Агентный цикл с синхронными инструментами») явно требует «События, пришедшие во время хода (новое сообщение), SHALL подхватываться дополнительным раундом». Реальный сценарий потери: write-ahead ASSISTANT+TOOL_CALL → last_seq=N; параллельно `SessionStore.appendEvent` (USER-допись не идёт через sess-лок — D-M1-4) → last_seq=N+1; stop приходит → bash прерывается → TOOL_RESULT CANCELLED → last_seq=N+2; `finishTurn(CANCELLED)` → last_consumed_seq=N+2 (=last_seq); cancel_requested=false; следующий EVENT/POLL видит last_seq==last_consumed → **early-return**. USER-сообщение в журнале видно (не потеря данных), но без ответа модели до нового ввода. Консенсус GLM F1 / Mercury MEDIUM coverage gap.
   **Предложение:** нельзя просто «не потреблять при CANCELLED» — тогда POLL сразу разбудит отменённую сессию, и модель продолжит прерванную работу без нового ввода (нарушает stop: «не имеет последующего эффекта»). Решение — за владельцем: (а) документировать текущее поведение в спеке + ADR; (б) различать «батч Turn'а» и «батч отмены» (доп. счётчик / маркер); (в) после CANCELLED — отдельный «reset» turn'а с явным EVENT-wake. Рекомендую (а) — наименее инвазивно; согласовать с владельцем. Дополнительно: тест на CANCELLED + USER-dopis во время bash (отсутствует в `TurnCancellationTest`).

3. **OpenAI-протокол: `ASSISTANT(tool_calls) → USER → ToolResponse` ломает strict OpenAI (400)** — `src/main/java/se/rocketscien/harness/execution/SessionPromptBuilder.java:45-89` (case USER 48-51, case TOOL_RESULT 77-85) + `AgentTurnEngine.java:104-156` (write-ahead → исполнение → TOOL_RESULT).
   **Факт:** если USER-дописан во время длинного bash'а, журнал получает порядок `ASSISTANT(tool_calls) → USER → TOOL_RESULT`. `SessionPromptBuilder` рендерит это в `AssistantMessage(tool_calls=[...]) → UserMessage → ToolResponseMessage` — OpenAI strict API требует, чтобы каждый `tool_call` ассистента был закрыт role:tool ответами **непосредственно** после; USER в разрыве = 400. Тест `TurnEngineWireMockTest.messageDuringTurnIsSeenByExtraRound` (журнал asserted правильно) **зелёный только потому, что WireMock не валидирует протокол**. На реальном провайдере дополнительный раунд уйдёт в FAILED после ретраев. Один ревьюер (DS M2), но **реальный контрактный баг**.
   **Предложение:** (а) в рендере привязывать `TOOL_RESULT` к своему ASSISTANT-блоку (выносить их сразу после закрывающего assistant-сообщения, а промежуточные non-tool события — после tool-ответов); (б) либо в движке буферизовать TOOL_RESULT'ы так, чтобы журнальный и промптовый порядок совпадали. Обязательно: тест на **порядок сообщений промпта** (не только журнала) для interleaved USER.

4. **`InMemorySessionEventBroadcaster.lastDeliveredSeq` сбрасывается при рестарте процесса** — `src/main/java/se/rocketscien/harness/session/InMemorySessionEventBroadcaster.java:59-75` (дренаж ждёт `lastDeliveredSeq + 1`) + `:97` (старт 0) + `:93-98` (in-memory state).
   **Факт:** состояние доставки in-memory. После рестарта процесса сессия с существующим журналом (например last_seq=56) получает новое событие seq=57 → буферизуется, дренаж ждёт seq=1 (не будет в этой жизни) → все последующие события копятся в `pending` (неограниченный рост), подписчикам (SSE 8.5) не доставляются. Комментарий «вечных дыр не бывает — резерв seq сериализован» верен только внутри жизни процесса. В пачке D подписчиков ещё нет, дефект латентный — но контракт закладывается именно сейчас, пачка 8.5 построит SSE поверх сломанного базиса. Один ревьюер (GLM F2), но **реальная регрессия для SSE-фазы**.
   **Предложение:** (а) `subscribe(sessionId, sinceSeq, consumer)` — базлайн от точки бэкфилла; (б) на первом событии для новой SessionState инициализировать `lastDeliveredSeq = seq - 1`. Тест: события после рестарта при pre-existing журнале доставляются.

5. **`PollWakeJob` сравнивает ShedLock `lock_until` (UTC) с `now()` в TZ сессии** — `src/main/java/se/rocketscien/harness/execution/PollWakeJob.java:45-46`.
   **Факт:** ShedLock пишет `lock_until` через `timezone('utc', CURRENT_TIMESTAMP)` (UTC); Postgres при сравнении `timestamptz < now()` приводит `now()` к типу колонки — если сессия в не-UTC TZ (например, Europe/Moscow UTC+3), `now()` сессии будет отличаться от UTC на 3 часа → живые локи могут быть удалены досрочно. Один ревьюер (DS M3), но **реальный баг для распределённых deployments** (DS проверено по shedlock-sql-support 7.10.1).
   **Предложение:** явный UTC в сравнении: `WHERE lock_until < (now() AT TIME ZONE 'UTC')` или `WHERE lock_until < timezone('utc', now())`. Тест: вставить живую sess-строку с `lock_until = now() + interval '5 minutes'`, poll не должен её удалить.

### low

6. **`TurnManagerImpl.runTurn` (`:66-91`) — `tryAcquire`/`register`/`publishStatus` вне try-catch, Future без `get()`** — `submit(...)` без `whenComplete`/обработчика → исключения из tryAcquire (например, недоступность БД) исчезают без лога. GLM F4. Фикс: обернуть всё тело `runTurn` в try/catch с error-логом.

7. **Нет guard'а «нечего потреблять» на верху цикла раундов** — `AgentTurnEngine.java:67-80` (проверка новых событий только после ответа модели). Между eligible-проверкой в `tryStart` и захватом лока в `runTurn` чужой Turn может завершиться и потребить батч → наш Turn всё равно выполнит полный LLM-раунд «в никуда» — лишний ASSISTANT в журнале и расход токенов. GLM F5. Фикс: в начале раунда `if (current.lastSeq() <= current.lastConsumedSeq()) return;` без смены `last_turn_outcome`.

8. **Тест EVENT-латентности тавтологичен при `poll-interval: 1h`** — `TurnEngineWireMockTest.wakeEventStartsTurnWellBeforePollInterval` (`isLessThan(pollIntervalMillis)` почти всегда true). GLM F6. Повторная проверка в пачке E по реальному пути «эндпоинт → tryStart».

9. **Чистка shedlock: чужая просроченная строка не ассертится** — `PollWakeJobTest.pollCleansExpiredSessionLockRowsOnly`. Деградация `DELETE` до `WHERE lock_until < now()` (без `LIKE 'sess-%'`) тест не поймает. GLM F7. Добавить `assertThat(remaining).contains(jobLockRow)`.

10. **Спец-сценарий «зависший процесс не блокирует навсегда» не покрыт** — `SessionLockManagerTest` покрывает конкуренцию/heartbeat/unlock, но не expiry-takeover. GLM F8. Дешёвый тест: захват лока → `UPDATE shedlock SET lock_until = now() - interval '1 second'` → `tryAcquire` успешен.

11. **Javadoc bash упоминает `setsid`, код `setsid` не использует** — `ContainerWorkspaceTools.java:188-189` против фактической команды (PID-файл + ставка на собственную PGID-группу docker-exec). GLM F9. Согласовать Javadoc с кодом или добавить `setsid`. Механика работает (тест `stopDuringBashKillsProcessAndCancelsTurn` подтверждает через pgrep).

12. **Coverage gap: EVENT + POLL одновременно для одной сессии** — спека сценарий «конкурентный запуск» покрыт частично (`concurrentTryStartRunsExactlyOneTurn` — 5 tryStart параллельно), но не «EVENT-wake + POLL-wake одновременно». Mercury MEDIUM. Дешёвый тест: дописать USER, запустить параллельно `turnManager.tryStart` и `pollWakeJob.poll()` — должен запуститься один Turn.

### nit

13. **`TurnManagerImpl.tryStart` (`:49-55`)** — `findSession` + early-return + `submit` — TOCTOU: между findSession и submit другой поток может дописать/потребить. Двойная проверка через `tryAcquire` в `runTurn` компенсирует, но не атомарна. Зафиксировать в Javadoc.

14. **`NativeAgentTools.declarations` — `FunctionToolCallback` с dummy-лямбдой `(ReadFileArgs unused) -> ""`** — Spring AI требует callback с телом для компиляции, мы игнорируем результат (исполнение идёт через `agentTools.execute(...)`). Хитро, но работает. Зафиксировать в Javadoc, что `ToolCallingManager` отключён (engine сам ведёт цикл инструментов по write-ahead).

15. **`SessionStoreImpl.notifyListenersAfterCommit`** — `TransactionSynchronizationManager.isSynchronizationActive()` ловит self-invocation: если `appendEvent` вызывается через `this.appendEvent(...)` внутри другого метода того же бина, прокси не сработает → synchronization не active → publish синхронно ДО коммита. Сейчас все вызовы идут через Spring-прокси — OK, но хрупко.

## Проверено и валидно (консенсус 3/3 или strong evidence)

- **Row-lock + `RETURNING` атомарность дописи** (`SessionStoreImpl.appendEvent`/`reserveSeqByRowLock`) — монотонный seq без дыр/дублей, `failedAppendRollsBackSeqReservation` доказывает атомарность.
- **Атомарность `finishTurn` (`UPDATE … SET last_consumed_seq = last_seq, last_turn_outcome = ?, cancel_requested = false`)** — единый UPDATE в одной транзакции; конкурентные appendEvent ждут на row-lock.
- **`SessionLockManager.concurrentTryAcquireYieldsExactlyOneWinner` (4 конкурента) + `TurnEngineWireMockTest.concurrentTryStartRunsExactlyOneTurn` (5 конкурентов)** — ShedLock-сериализация работает.
- **Heartbeat продлевает `lock_until`** — `heartbeatExtendsLockUntil` подтверждает.
- **`SimpleLock.extend` как замена `LockExtender`** — корректно (ShedLock API обновляет `lock_until` в БД); `LockExtender` требовал `LockingTaskExecutor` ThreadLocal, недоступен при прямом `LockProvider#lock`. Heartbeat конфигурируемый (`LockProperties.heartbeatInterval` из `harness.lock.heartbeat-interval`).
- **EVENT-хук через `tryStart`, не через `appendEvent`** — соответствует спеке (источник-контракт, не магазин); POLL-страховка покрывает пропуск.
- **Тест симулирует kill -9 записью в БД** — эквивалент состояния «журнал в БД, in-memory потеряно»; задача 7.6 явно отделяет симуляцию от реального kill -9 (10.3 приёмочный e2e). Достаточно.
- **`WorkspaceTools.bash(..., TurnCancellation)` default-метод** — обратная совместимость сохранена, граница модулей чистая (оба в `execution/`).
- **Pending-TOOL_CALL-сканы + `finishTurn` атомарность** — `findPendingToolCalls` корректен (NOT EXISTS), `appendLostResults` синтетически фиксирует, `closeIfLockFree` ShedLock-сериализован.
- **Bash-отмена через PID-файл + `killUntilDead`** — `TurnCancellationTest.stopDuringBashKillsProcessAndCancelsTurn` подтверждает (pgrep в контейнере после cancel = 1).
- **POLL → eligible-сессии, EVENT → direct tryStart** — оба пути вызывают `turnManager.tryStart(sessionId)`, ShedLock-сериализация общая. `PollWakeJobTest.pollPicksUpSessionMissedByEventWake` покрывает страховку.
- **`SessionStoreTurnStateTest` (6 unit-кейсов)** — атомарные операции Turn'а покрыты без поднятия Spring.
- **Сценарии спеки agent-turn 11/11** — каждый покрыт хотя бы одним тестом (`TurnEngineWireMockTest`, `TurnCancellationTest`, `PollWakeJobTest`, `RestartScanTest`, `SessionLockManagerTest`, `SessionPromptBuilderTest`).
- **Утечки scope M3/M4 нет** — `ToolStatus.ASYNC_ACCEPTED` зарезервирован (не используется), `SessionRuntimeStatus` только IDLE/TURN_RUNNING, `ToolResult` без `late`, bash синхронный, нет `PARKED_ASYNC/CLIENT`, нет SSE-эндпоинта/CLIENT_EXEC/transition/spawn_subagent.
- **Регрессии пачки C не сломаны** — `splitByUtf8Bytes` через `dockerProperties.writeChunkBytes()`, `BoundedOutputStream` hook в `WorkspaceContainerManager.exec`, `bash` через ContainerWorkspaceTools с cancellation.
- **ToolStatus: ASYNC_ACCEPTED зарезервирован** — enum-значение для M3, не используется в M1.
- **AgentTurnEngine write-ahead** (`:104-117`) — ASSISTANT + TOOL_CALL атомарно до исполнения инструмента.
- **`finishTurn` корректно потребляет батч для FAILED** — спека явно требует: «SYSTEM-событие причины + `last_consumed_seq := last_seq`». Только для CANCELLED поведение не заявлено явно (см. находку #2).

## Консенсус ревьюеров

| Тема | GLM | DS | Mercury | Консенсус |
|---|---|---|---|---|
| Heartbeat race при истечении TTL (lock stolen / steal-back) | **medium** F3 | **medium** M1 (исключения) | **high** | **3/3 → high** |
| CANCELLED + consumed batch (USER во время Turn'а теряется) | **medium** F1 | — | **medium** coverage gap | **2/3 → medium** |
| `InMemorySessionEventBroadcaster.lastDeliveredSeq` сбрасывается при рестарте | **medium** F2 | — | — | **1/3 → medium (латентный для 8.5)** |
| OpenAI-протокол: `ASSISTANT(tool_calls) → USER → ToolResponse` ломает strict OpenAI | — | **medium** M2 | — | **1/3 → medium (контракт)** |
| PollWakeJob TZ-сравнение (UTC vs session TZ) | — | **medium** M3 | — | **1/3 → medium** |
| Coverage gap: EVENT + POLL одновременно | — | — | **medium** | **1/3 → low** |
| Coverage gap: CANCELLED + consumed batch (тот же сценарий, другая рамка) | F1 | — | medium | входит в #2 |
| `TurnManagerImpl.runTurn` без try/catch, Future без `get()` | **low** F4 | — | — | **1/3 → low** |
| Нет guard'а «нечего потреблять» на верху цикла | **low** F5 | — | — | **1/3 → low** |
| Тест EVENT-латентности тавтологичен при `poll-interval: 1h` | **low** F6 | — | — | **1/3 → low** |
| Чистка shedlock: чужая просроченная строка не ассертится | **low** F7 | — | — | **1/3 → low** |
| Спец-сценарий «зависший процесс не блокирует навсегда» не покрыт | **low** F8 | — | — | **1/3 → low** |
| Javadoc bash `setsid` vs код | **low** F9 | — | — | **1/3 → low (doc)** |
| `tryStart` TOCTOU между findSession и submit | — | — | (в low) | **1/3 → nit** |
| `FunctionToolCallback` с dummy-лямбдой (Spring AI) | — | — | — | (мной) **nit** |
| Self-invocation `notifyListenersAfterCommit` хрупкость | — | — | — | (мной) **nit** |
| Атомарность row-lock + RETURNING | ✅ | ✅ | ✅ | **3/3** |
| Heartbeat продление | ✅ | ✅ | ✅ | **3/3** |
| `SimpleLock.extend` замена `LockExtender` | ✅ | (de facto) | ✅ | **2+/3** |
| EVENT через `tryStart` (не `appendEvent`) | ✅ | — | ✅ | **2/3** |
| kill -9 симуляция = БД-запись | ✅ | — | — | **1/3** |
| Default-метод `WorkspaceTools.bash(..., cancellation)` | ✅ | — | — | **1/3** |
| Pending-TOOL_CALL + finishTurn атомарность | ✅ | ✅ | ✅ | **3/3** |
| Bash cancel через PID-файл + killUntilDead | ✅ | ✅ | ✅ | **3/3** |
| Сценарии спеки 11/11 | ✅ | ✅ | ✅ | **3/3** |
| Утечки scope M3/M4 | ✅ | ✅ | ✅ | **3/3** |
| Регрессии пачки C | ✅ | ✅ | ✅ | **3/3** |

## Summary

**Находки:** **1 high** (heartbeat race + exception) · **4 medium** (CANCELLED потребляет батч + OpenAI-протокол + broadcaster lastDeliveredSeq + TZ-сравнение в PollWakeJob) · **7 low** (runTurn try/catch, top-of-cycle guard, тавтологический тест EVENT-латентности, shedlock cleanup coverage, kill-9 takeover coverage, Javadoc setsid, EVENT+POLL coverage) · **3 nit**.

**Консенсус 3/3 (high):**
1. **Heartbeat race при истечении TTL / исключении в `lock.extend`** — `SessionLockManager.HeldLock.heartbeat` не ловит `Throwable` (DS: `ScheduledThreadPoolExecutor` молча подавляет последующие тики после первого необработанного исключения), `close()` безусловно `unlock()` (Mercury/GLM: зомби снимает лок легитимного владельца). Прямой вход в риск D-40.

**Топ-3 (medium):**
2. **CANCELLED + consumed batch** — USER-сообщение, дописанное между write-ahead и finishTurn, теряется (сессия ineligible, POLL не разбудит). Спека явно требует «события, пришедшие во время хода, подхватываются дополнительным раундом» — но это касается активного Turn'а; поведение при CANCELLED не специфицировано. Нужно решение владельца + ADR.
3. **OpenAI-протокол нарушен** — `ASSISTANT(tool_calls) → USER → ToolResponse` отклоняется strict OpenAI (400). Тест зелёный только потому, что WireMock не валидирует. На реальном провайдере дополнительный раунд уйдёт в FAILED.
4. **`InMemorySessionEventBroadcaster.lastDeliveredSeq` сбрасывается при рестарте** — события застревают в pending, SSE-доставка ломается (латентно для 8.5, но контракт закладывается сейчас).
5. **PollWakeJob TZ-сравнение** — ShedLock пишет UTC, `now()` в TZ сессии; при не-UTC БД живые локи удаляются досрочно.

**Вердикт: REJECT** — обязательны фиксы:
- **#1 high** (heartbeat): `try/catch (Throwable)` + пометка `lost` на empty extend, не unlock чужого лока + тест takeover.
- **#2 medium** (CANCELLED+batch): решение владельца (документировать + ADR) + тест на CANCELLED+USER-dopis.
- **#3 medium** (OpenAI): фикс порядка промпта + тест порядка сообщений промпта.

Желательно в этой же пачке: #4 (broadcaster), #5 (TZ). Остальное — backlog.

## Fixes approval

Прогон m4 после правок фикс-цикла (D-J-1…D-J-6; D-R-1/D-R-2 отклонены). Проверено по `SessionLockManager.java`, `SessionStore.java`/`SessionStoreImpl.java`, `AgentTurnEngine.java`, `SessionPromptBuilder.java`, `PollWakeJob.java`, `apply-notes.md`, `docs/design/decisions.md` (D-45), `SessionLockManagerTest`, `TurnCancellationTest`, `SessionPromptBuilderTest`, `PollWakeJobTest`.

- **D-J-1 (heartbeat, high)** — `SessionLockManager.java:96-114`: `try { … } catch (Throwable t)` гасит сбои ShedLock без подавления последующих тиков; `lock.extend` empty → `lost = true` (`:106`) + WARN с пометкой «close() не снимает чужой лок (D-J-1)»; `close()` (`:123-131`) — `if (lock != null && !lost) lock.unlock()`. Тесты: `heartbeatExtendFailureMarksLockLostAndCloseKeepsForeignLock` (`:130-156`) — истёкший TTL → другой владелец перехватывает → close() чужой лок не снимает; `heartbeatThrowableDoesNotKillSubsequentTicks` (`:159-192`) — транзиентный `RuntimeException` не помечает `lost`, тики продолжаются. ✓
- **D-J-2 (D-45 watermark, medium)** — `SessionStore.finishTurn(UUID, TurnOutcome, long consumedSeq)` + `UPDATE session SET last_consumed_seq = GREATEST(last_consumed_seq, ?), last_turn_outcome = ?, cancel_requested = false` (SessionStoreImpl.java:127-132, защита от регрессии). `AgentTurnEngine.java:75` — `renderedWatermark` трекает максимальный `seq` отрендеренного; CANCELLED-выход передаёт watermark, не `last_seq`; FAILED — `systemEvent.seq()`; COMPLETED — `lastAppendedSeq`. D-45 внесён в `docs/design/decisions.md` (`:51`). Тест `userMessageDuringCancellationWindowSurvivesAndStartsNewTurn` (TurnCancellationTest.java:160-201) — USER в окне отмены не теряется, новый Turn подхватывает; `lastConsumedSeq < lastSeq` после CANCELLED; на следующем Turn LLM получает сообщение из окна. ✓
- **D-J-3 (OpenAI order, medium)** — `SessionPromptBuilder.java:47-122`: `ToolGroup` (assistant + toolCalls + responses) + `heldAfterGroup` для interleaved USER/SYSTEM/COMPACT; `flush()` (`:110-123`) выдаёт `AssistantMessage(tool_calls) → ToolResponseMessage(responses) → heldAfterGroup`. Порядок валиден при любом interleaving. Тест `interleavedUserGoesAfterToolResponseGroup` (SessionPromptBuilderTest.java:127-159) — `[Assistant(tool_calls), ToolResponse, UserMessage]` проверяется явно (`:149`). ✓
- **D-J-4 (stop диспозит LLM-стрим, medium)** — `AgentTurnEngine.callModel` (`:182-226`): `subscriptionRef` хранит Disposable; `cancellation.registerInterrupt(() -> subscription.dispose())` (`:191-196`); race-gate после subscribe (`:208-210`); `doOnCancel(done::countDown)`. Тест `stopDuringLlmStreamDisposesSubscriptionAndCancelsQuickly` (TurnCancellationTest.java:127-158) — медленный стрим 60s + stop → Turn отменяется <15s, ответ НЕ журналируется (`containsExactly("USER")`). ✓
- **D-J-5 (broadcaster in-memory, medium)** — `apply-notes.md:39-46` фиксируют: broadcaster живёт в границах процесса, восстановление потока — бэкфилл из SessionStore по `?since=`/Last-Event-ID (контракт SSE 8.5). Снимает мою находку #4 как документированное решение. ✓
- **D-J-6 (TZ-сравнение, medium)** — `PollWakeJob.java:32-33`: `DELETE FROM shedlock WHERE name LIKE 'sess-%' AND lock_until < timezone('utc', now())` — сравнение строго в БД, без Java-времени. Тест `PollWakeJobTest.java:104` ассертит SQL: `assertThat(PollWakeJob.CLEANUP_SQL).contains("timezone('utc', now())")`. ✓

Дополнительно:
- D-R-1 (Mercury: ошибка инструмента должна завершать Turn FAILED) — отклонён: `ERROR`/`LOST` TOOL_RESULT — это **результат** для модели, не провал Turn'а (write-ahead + первый финальный результат, спеки workspace-tools/agent-turn/execution-model §3).
- D-R-2 (SimpleLock.extend vs LockExtender, EVENT-контракт, kill-9 моделирование, default bash) — отклонён: обоснования корректны, границы фаз соблюдены; вайринг EVENT→tryStart — задача 8.3 (пачка E).

Прогон разработчика `mvn clean verify`: 164 теста / 0 падений / 1 skipped (+8 тестов с m3).

**Вердикт: APPROVE.** Пачка D готова к вливанию.
После устранения #1 — готов к APPROVE с оставшимися minor/nit на усмотрение владельца.
