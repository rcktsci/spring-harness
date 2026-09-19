# Ревью M2 batch J: STATE-сессии + metaTool transition + bootstrap AGENT + SSE задач

> Ревьюер: DeepSeek-V4.1-Flash (субагент).
> Дата: 2026-09-18.
> Объект: `session/{StateSessionService,impl/StateSessionServiceImpl}`, `execution/InstructionSource`, `execution/TransitionMetaTool`, `execution/AgentTurnEngine` (гейт/лимит), `execution/impl/{AgentStateBootstrapper,TaskWakeBroadcaster,TaskWakeDispatcher,TaskEngineImpl,TaskRegistryImpl}`, `execution/{TaskEventBroadcaster,TaskEventListener}`, `task/TaskEvent`, `api/TaskEventsController`, `api/{ApiMappers,Caller,ProblemCodes,ApiExceptionHandler}`, `config/{SseProperties,TaskProperties}`, `application.yml`, тесты J.
> Контекст: `execution-model §7.2`, `workflow-domain §3`, спеки `agent-turn`/`task-engine`/`session-store`/`session-api`, api-contracts §3.2, D-45/D-52/D-59, `apply-notes.md`.
> Сборки не запускались; сверка по исходникам.
> Severity: **MEDIUM** — функциональный пробел; **MINOR** — риск/документация; **NIT** — формулировка.

## Сводка

| Severity | Кол-во |
|---|---|
| MEDIUM | 1 |
| MINOR | 3 |
| NIT | 2 |
| **Итого** | **6** |

---

## (1) execution-model §7.2 — атомарность STATE-сессии — ✅

`StateSessionServiceImpl.findOrCreate` (`@Transactional`): `INSERT … ON CONFLICT (task_id, state_code) WHERE kind='STATE' DO NOTHING RETURNING id` (гонка двух бутстрапов — победитель создаёт, проигравший резюмирует) + `insertSeed` (seq=1, тот же `now()`) + `last_seq=1` в одной транзакции; состояния «сессия есть, seed не записан» нет. Тесты: `concurrentFindOrCreateHasSingleWinnerAndSingleSeed`, `firstEntryCreatesSessionWithSeedAtomically`, `secondEntryResumesExistingSessionWithoutDuplicateSeed`.

## (2) D-52 гейт metaTools + D-59 supersession D-41 + Caller.instructionSource — ✅ (см. J-1)

`InstructionSource{USER, TOOL_RESULT, SYSTEM}`; `AgentTurnEngine.executeTransition`: gate `instructionSource == USER` → иначе ToolResult.error (переход не происходит); затем `session.kind == STATE`; затем лимит `harness.task.transition.max-per-turn` (дефолт 1). `Caller.instructionSource()` (D-59) → USER для HTTP-точек входа. `decisions.md` D-59 (supersession D-41 в границах M2) — из пачки propose. **J-1:** источник резолвится один раз на Turn.

## (3) workflow-domain §3: задача в AGENT-state → STATE-сессия; резюм по (task_id, state_code) — ✅

`AgentStateBootstrapper.bootstrap` (вызывается диспетчером на AGENT-wake и POLL-wake): `agent_key` → последняя ревизия (`AgentRevisionRepository`) → `StateSessionService.findOrCreate` → `turnManager.tryStart`. Резюм — UPSERT по PARTIAL UNIQUE; пин ревизии фиксируется созданием. Тесты `AgentStateBootstrapperTest`: `agentStateWithoutSessionBootstrapsSessionAndStartsTurn`, `repeatedBootstrapResumesSameSessionWithoutDuplicateSeed`.

## (4) SSE задач (api-contracts §3.2) — ✅

`TaskEventsController`: первый кадр `retry: 5000` + снапшот `task.status` с `taskEventSeq` (регенерированный `TaskStatusEvent` содержит поле — GLM N-5 закрыт); бэкфилл `broadcaster.backlog(taskId, cursor)` → live-подписка; `Last-Event-ID` приоритетен над `?since=`; `id:` = task_event_seq; `task.transition`/`task.status`/`subtask.terminal` (на потоке родителя)/`task.comment` (author username) + `ping`; неизвестная задача → 404 task-not-found. Тесты `TaskEventsSseTest`: snapshot→live, реконнект без дублей/пропусков, `subtask.terminal` на родителе, `task.comment`, 404.

## (5) Backlog policy — ограниченный, переполнение — ✅

`TaskWakeBroadcaster`: per-task `backlog` (`ArrayDeque`, cap `harness.sse.task-backlog`, дефолт 512) — при переполнении `pollFirst()` (старейшие вытесняются); `pending`-буфер переупорядочивания по `task_event_seq`; снапшот статуса — не из backlog (D-J-5-стиль). Публикация строго `afterCommit`.

## (6) apply-notes J — ❌ отсутствует (J-2)

Секции «Пачка J» нет (`apply-notes.md` заканчивается пачкой I, стр. 253). Заметки и 4 отклонения dev не задокументированы.

## (7) 4 отклонения dev — task_event_seq и комментарии/stop — ✅ по существу (J-2 — документация)

`task_event_seq` инкрементируется транзакционно **всеми** эмиттерами: переходы (`TaskEngineImpl` CAS `… + 1 RETURNING`), `stop` (`cancelByStop` + per-node), комментарии (`addComment` → `incrementEventSeq`), и на родителе — `subtask.terminal` (`incrementEventSeq(parent)`). Это соответствует замороженной спеке session-api («все события задачи, включая не-transition, нумеруются сквозным счётчиком») и data-model §4 («durable-счётчик событий задачи»). Парный `task.status` перехода делит `seq` перехода (отдельного расхода нет) — задокументировано в Javadoc `TaskEvent`. Функционально корректно (регресс `taskEventSeq==1` в J.2).

## (8) Тесты J.2 — реальный сценарий USER→transition→task→запись — ✅

`AgentTransitionToolTest` (WireMock-LLM, реальный Turn-движок, Postgres): USER-ход → `transition` применяется транзакционно (history + `current_state` + `task_event_seq`); ход от `TOOL_RESULT` → gate отклоняет (история пуста, output содержит `instructionSource`); пустой reason отклонён; второй вызов в Turn → лимит. `StateSessionServiceTest`, `AgentStateBootstrapperTest`, `TaskEventsSseTest` — см. выше.

---

## Findings

### J-1 [MEDIUM]. `instructionSource` резолвится один раз на Turn → USER-сообщение, пришедшее во время хода, не разблокирует metaTool

- **Где:** `execution/AgentTurnEngine.java` — `InstructionSource instructionSource = instructionSource(session);` до цикла раундов; `instructionSource(session)` = `findPendingKinds(session.id(), session.lastConsumedSeq())` (снимок на старте Turn'а).
- **Проблема:** по спеке agent-turn «события, пришедшие во время хода, подхватываются дополнительным раундом». Если Turn поднят `TOOL_RESULT`/`SYSTEM`, а USER-сообщение приходит уже во время хода, дополнительный раунд видит USER, но `instructionSource` остаётся `TOOL_RESULT` → вызов `transition` блокируется гейтом, хотя инструкция — пользовательская. USER-событие при этом потребляется watermark'ом (D-45) и не поднимет новый Turn → намерение пользователя о переходе теряется. Обратный случай (USER-старт, затем TOOL_RESULT-раунд) — переход разрешён, что, вероятно, ожидаемо.
- **Предложение:** определять источник как «в этом Turn'е виден/потреблён USER» — пересчитывать перед исполнением tool-call (или при каждом раунде), а не однократно на старте; либо явно зафиксировать per-Turn-семантику в спеке/Javadoc как решение. Тест: Turn от TOOL_RESULT + USER-сообщение во время хода → transition применяется.

### J-2 [MINOR]. Секция «Пачка J» в `apply-notes.md` отсутствует

- **Где:** `openspec/changes/m2-workflow-engine/apply-notes.md` (последняя секция — I).
- **Проблема:** не зафиксированы решения/4 отклонения dev пачки J (per-Turn гейт, расход `task_event_seq` комментарием/stop, `task.status` делит seq, in-memory backlog, `Caller.instructionSource` как plumbing) и итог тестов. Практика D/H/I.
- **Предложение:** добавить секцию «Пачка J» с решениями/отклонениями и числом тестов.

### J-3 [MINOR]. `TransitionMetaTool.declaration()` — FunctionToolCallback-заглушка + `taskId` в `inputType`

- **Где:** `TransitionMetaTool.declaration()` (`FunctionToolCallback.builder(NAME, (TransitionArgs unused) -> "")`), `record TransitionArgs(UUID taskId, …)`.
- **Проблема:** исполнение перехватывается `AgentTurnEngine` по имени, заглушка не вызывается — но объявленный `taskId` в схеме аргументов (при описании «do not pass it») модели может передать; несовпадение отклоняется, но лишний параметр — источник путаницы. Если бы заглушка вызвалась напрямую — молчаливый no-op.
- **Предложение:** убрать `taskId` из `TransitionArgs` (адаптер резолвит из сессии, как в спеке), либо оставить с явным описанием optional.

### J-4 [MINOR]. Рост `streams` в `TaskWakeBroadcaster` без эвикции

- **Где:** `TaskWakeBroadcaster.streams` (`ConcurrentHashMap<UUID, TaskStream>`).
- **Проблема:** по одной `TaskStream` на каждую задачу, эмитившую событие, — не удаляется никогда (backlog пустеет, но поток остаётся); при долгой работе — рост памяти. Аналог M1-брокера, но для задач масштаб больше.
- **Предложение:** удалять поток при отсутствии подписчиков и пустом backlog (или TTL), либо зафиксировать как MVP-риск.

### J-5 [NIT]. Неверное `rule=` в ошибке пустого `toState`

- **Где:** `TransitionMetaTool.execute` — `"wrong-transition: toState обязателен (rule=reason-required)"`.
- **Предложение:** для `toState` — иной rule (например `to-state-required`) или нейтральное сообщение.

### J-6 [NIT]. `task.status` перехода не расходует собственный `seq`

- **Где:** `TaskEngineImpl` — Transition и Status делят `task_event_seq`; `TaskEvent` Javadoc.
- **Проблема:** формально «все события нумеруются» — но парный статус-кадр `id` не имеет отдельного значения; это осознанное упрощение (снапшот восстанавливает статус), задокументировано. Отметить в apply-notes J.

---

## Позитив (проверено)

- STATE-сессия создаётся атомарно (insert+seed+last_seq) с корректной гонкой через PARTIAL UNIQUE; резюм идемпотентен.
- Гейт metaTools (USER-only) + лимит на Turn реализованы и покрыты реальным e2e-сценарием (WireMock LLM + движок).
- Bootstrap AGENT-state (findOrCreate + turnManager.tryStart) и POLL-страховка связаны; J.3 закрыт.
- SSE задач: снапшот с `taskEventSeq`, backlog с вытеснением, Last-Event-ID, все 4 типа кадров + ping; реконнект без дублей/пропусков.
- `task_event_seq` расходуется всеми эмиттерами (переходы/stop/комментарии/терминал подзадачи) транзакционно — курсор монотонный и бездырочный.

## Вердикт

**REJECT — 6 находок (1 MEDIUM: J-1 per-Turn `instructionSource` теряет USER-инструкцию, пришедшую во время хода; 3 MINOR: J-2 нет apply-notes J, J-3 declaration/`taskId`, J-4 рост `streams`; 2 NIT: J-5, J-6).** Все 8 пунктов чек-листа, кроме заметок J (J-2), по существу выполнены; блокер приёмки — J-1.

---

# Re-approval M2 batch J (2026-09-18)

> Проверены: `AgentTurnEngine` (возврат `InstructionSource`), `TurnManagerImpl.rewakeForUserIntent`, `TransitionMetaTool`, `TaskWakeBroadcaster` + `TaskEvent.BacklogOverflow` + `TaskEventsController`, `apply-notes.md` §«Пачка J», тесты. Сборки не запускались.

## Статусы находок

| # | Sev | Статус | Проверка |
|---|---|---|---|
| J-1 | MEDIUM | **частично закрыто (остаток)** | `AgentTurnEngine.run` возвращает `InstructionSource`; `TurnManagerImpl` после unlock не-USER-хода при непрочитанном USER делает `tryStart` (новый Turn с `source=USER`). Тест `userArrivingDuringToolResultTurnStartsRewokenTurnWithUserGate` покрывает случай, когда USER **остался непрочитанным** (ход CANCELLED). **Остаток:** в COMPLETED-не-USER-ходе mid-turn USER рендерится доп. раундом и потребляется watermark'ом (`lastConsumedSeq` = seq финального ASSISTANT > seq USER) → `rewakeForUserIntent` не срабатывает (`lastSeq <= lastConsumedSeq`), USER-sourced Turn не создаётся, а `transition` был отклонён гейтом как TOOL_RESULT → USER-намерение по-прежнему теряется. См. R-1 |
| J-2 | MINOR | **закрыто** | `apply-notes.md` §«Пачка J» (стр. 255+): 4 отклонения dev, фиксы ревью (J-1…J-6 + GLM nit), число тестов (367) |
| J-3 | MINOR | **закрыто** | `declaration()` — `@ToolParam(required=false)`, описание «taskId is optional and resolved from the current state session»; внутреннее исполнение Spring AI отключено (marker), реальное — на Turn'е |
| J-4 | MINOR | **закрыто** | `TaskWakeBroadcaster.streams` — eviction при уходе последнего подписчика; при переполнении backlog живым подписчикам эмитится `TaskEvent.BacklogOverflow` → SSE `notify-dropped-events` (клиент ресинхронизируется снапшотом); `TaskEventsController` обрабатывает новый тип |
| J-5 | NIT | **закрыто** | пустой `toState` → `rule=to-state-required`; `reason` → `reason-required` |
| J-6 | NIT | **закрыто** | Javadoc `TaskEvent.Status`/`TaskEventsController` + apply-notes §J.4: парный `task.status` делит seq перехода, счётчик нумерует события, а не кадры |

## Остаточная находка

### R-1 [MEDIUM]. J-1 закрыт не для COMPLETED-не-USER-хода: потреблённый mid-turn USER не порождает USER-sourced Turn

- **Где:** `AgentTurnEngine.run` (`source = instructionSource(session)` — снимок на старте; `finishTurn(COMPLETED, lastAppendedSeq)`); `TurnManagerImpl.rewakeForUserIntent` (условие `lastSeq > lastConsumedSeq`).
- **Сценарий:** Turn поднят `TOOL_RESULT`; во время исполнения инструмента (или стриминга модели) дописан USER; дополнительный раунд рендерит USER и вызывает `transition` → гейт `instructionSource=TOOL_RESULT` отклоняет; модель дописывает финальный ASSISTANT (seq > seq USER) → `COMPLETED` с `lastConsumedSeq = seq(ASSISTANT)` → `lastSeq <= lastConsumedSeq` → `rewakeForUserIntent` не срабатывает. USER-инструкция обработана в ходе с неверным источником, `transition` не применён, нового Turn'а нет.
- **Почему не покрыто тестом:** тест `userArrivingDuringToolResultTurnStartsRewokenTurnWithUserGate` намеренно доводит ход до `CANCELLED` (watermark — по рендеру, USER не потреблён), обходя именно COMPLETED-потребление.
- **Предложение:** (a) не потреблять USER, прибывший после инициализации не-USER-хода (оставлять непрочитанным, чтобы его wake/POLL поднял USER-Turn); либо (b) пересчитывать `instructionSource`/разрешение гейта при наличии USER в текущем батче (per-round), а не однократно на старте; либо (c) явно зафиксировать per-Turn-семантику в спеке D-52 и добавить тест COMPLETED-сценария (текущий тест её не проверяет). Рекомендую (b): гейт проверяет «USER присутствует в потреблённом этим Turn'ом батче».

## Проверка

- J-2…J-6 закрыты; новые артефакты (`TaskEvent.BacklogOverflow`, eviction) согласованы и обработаны.
- `Caller.instructionSource()/sessionOwner()` — plumbing; фактическая логика источника в execution (обосновано отсутствием SecurityContext на виртуальном потоке).
- Остальные пункты чек-листа J (атомарная STATE-сессия, гейт/лимит, bootstrap+резюм, SSE, backlog, J.2-e2e) — без регрессий.

**REJECT — 1 незакрытая (R-1, MEDIUM: J-1 не покрывает COMPLETED-не-USER-ход с потреблённым mid-turn USER).** J-2…J-6 приняты; блокер — R-1.

---

# Re-approval 2 (final) M2 batch J (2026-09-18)

> Проверены: `AgentTurnEngine` (TurnState/trackPendingUser/cappedConsumption/executeTransition), `TurnManagerImpl.rewakeForUserIntent`, `AgentTransitionToolTest.completedToolResultTurnKeepsUserIntentPendingAndRewakes`. Сборки не запускались.

## Статус R-1

| # | Sev | Статус | Проверка |
|---|---|---|---|
| R-1 | MEDIUM | **закрыто** | `AgentTurnEngine`: `TurnState{baselineSeq, minPendingUserSeq, userIntentSeq}`; `trackPendingUser` фиксирует минимальный seq USER, **нового** для не-USER-хода (рендер доп. раунда); `executeTransition` при гейт-блокировке (source≠USER) и наличии отрендеренного USER помечает `userIntentSeq=minPendingUserSeq`; `cappedConsumption` в COMPLETED — `last_consumed_seq = min(consumedSeq, userIntentSeq − 1)`, т.е. USER **не потребляется**; `TurnManagerImpl.rewakeForUserIntent` (source≠USER, `lastSeq > lastConsumedSeq`, pending USER) → `tryStart` нового Turn'а с `instructionSource=USER`. Тест `completedToolResultTurnKeepsUserIntentPendingAndRewakes`: TOOL_RESULT-ход + mid-turn USER + blocked transition (TOOL_RESULT `ERROR`) → COMPLETED с `last_consumed_seq == userSeq−1` → re-wake → ход 2 (USER) применяет `transition`, `history=1`. |

## Проверка

- Покрыты оба сценария J-1: непрочитанный USER (CANCELLED, предыдущий тест) и **потребляемый** mid-turn USER в COMPLETED-ходе (cap watermark) — оба ведут к USER-sourced re-wake и применению перехода.
- Нет ложных re-wake: cap срабатывает только при фактической гейт-блокировке `transition` (модель изъявила намерение); если модель USER не обрабатывала переходом — потребление обычное, лишний Turn не поднимается.
- USER-ход с mid-turn USER по-прежнему разрешает `transition` (source=USER, `trackPendingUser`/cap не применяются); лимит `max-per-turn` и гейт STATE-сессии не тронуты.
- J-2…J-6 не регрессировали (apply-notes §J, `@ToolParam(required=false)`, eviction + `BacklogOverflow`/`notify-dropped-events`, `to-state-required`, Javadoc).

**APPROVE — 0 незакрытых.** Пачка J принята.