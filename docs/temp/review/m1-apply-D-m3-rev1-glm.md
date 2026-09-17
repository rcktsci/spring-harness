# Ревью пачки D (m1-session-core, задачи 7.1–7.6) — GLM-5.3-Flash, rev 1

> Статический анализ; сборки/тесты/Docker не запускались (прогон разработчика: `mvn clean verify` — 156 тестов, 0 падений, 1 skipped).
> Эталоны: `openspec/changes/m1-session-core/specs/agent-turn/spec.md`, `docs/design/execution-model.md` §1/§2/§3/§6, `docs/design/data-model.md` §5, `openspec/changes/m1-session-core/design.md` (D-M1-4/D-M1-5), `docs/glossary.md`, AGENTS.md (D-39).

## Findings

### F1. MEDIUM — CANCELLED поглощает батч: USER-сообщение, дописанное во время хода, никогда не дойдёт до модели (отклонение №4)

- **Где:** `SessionStoreImpl.java:127-132` (finishTurn для любого исхода делает `last_consumed_seq = last_seq`); контракт декларирует это для «любого исхода» — `SessionStore.java:56-60`.
- **Дефект:** спека agent-turn («События, пришедшие во время хода (новое сообщение), SHALL подхватываться дополнительным раундом») и data-model §5 (`last_consumed_seq` = «последний seq, вошедший в **законченный** модельный ход») не оговаривают поглощение батча при CANCELLED. Реальный сценарий: юзер дописывает сообщение во время длинного bash'а, затем жмёт stop до начала доп. раунда → CANCELLED → батч потреблён → сессия ineligible → POLL не разбудит → сообщение висит в журнале без ответа модели до следующего сообщения юзера. Это не потеря данных (сообщение видно в UI), но нарушение обещания спеки для этого сообщения. Отклонение сознательное (тесты фиксируют: `SessionStoreTurnStateTest.java:31-42`, `TurnCancellationTest.java:76-77`), но в спеке/design/decisions не задокументировано.
- **Контр-аргумент против простого «не потреблять при CANCELLED»:** тогда POLL сразу разбудит отменённую сессию и модель продолжит прерванную работу без нового ввода — против явного намерения stop (spec: «stop … не имеет последующего эффекта»).
- **Предложение:** зафиксировать текущее поведение строкой в спеке agent-turn (Requirement «Отмена Turn'а») + ADR в `docs/design/decisions.md` (решение → альтернатива «не потреблять при CANCELLED» → почему). Решение — за владельцем; менять поведение без решения нельзя.

### F2. MEDIUM — Broadcaster после рестарта процесса застревает: живая доставка подписчикам ломается навсегда

- **Где:** `InMemorySessionEventBroadcaster.java:59-75` (bufferAndDrain ждёт `lastDeliveredSeq + 1`), `InMemorySessionEventBroadcaster.java:97` (`lastDeliveredSeq` стартует с 0), `InMemorySessionEventBroadcaster.java:93-98` (in-memory SessionState).
- **Дефект:** состояние доставки in-memory и инициализируется нулём. После рестарта процесса сессия с существующим журналом (например `last_seq = 56`) получает новое событие seq=57 → оно буферизуется, дренаж ждёт seq=1, которого в этом процессе уже не будет → все последующие события копятся в `pending` (неограниченный рост) и не доставляются. Комментарий «вечных дыр не бывает» верен только внутри жизни процесса. В пачке D подписчиков ещё нет (SSE — 8.5), поэтому дефект латентный, но контракт закладывается именно сейчас — пачка E построит SSE поверх сломанного базиса.
- **Предложение (любое из):** (а) `subscribe(sessionId, sinceSeq, consumer)` — базлайн от точки бэкфилла эндпоинта; (б) на первом событии для новой SessionState инициализировать `lastDeliveredSeq = seq - 1`. Плюс тест «события после рестарта при pre-existing журнале доставляются».

### F3. MEDIUM — HeldLock после неудачного extend() может снять чужой лок при close() (усиление принятого риска D-40)

- **Где:** `SessionLockManager.java:88-100` (heartbeat: `extend` вернул empty → только WARN, ссылка `lock` сохраняется), `SessionLockManager.java:102-111` (close(): безусловный `lock.unlock()`).
- **Дефект:** если TTL истёк и лок перехвачен другим инстансом, extend вернёт empty, но HeldLock продолжит считать себя владельцем. ShedLock-unlock матчит строку по имени и `lock_until > now` без проверки владельца → «зомби» при close() снимет лок уже легитимного нового владельца, открыв окно третьему Turn'у. Формально это класс принятого риска D-40 (два конкурентных Turn'а после истечения TTL), но удешевлённая защита доступна и сужает окно.
- **Предложение:** при `extend == empty` помечать HeldLock потерянным (`lost = true`), в `close()` не вызывать unlock (только отменять heartbeat); тест: захват → ручной `UPDATE shedlock SET lock_until = now()` (имитация перехвата) → extend пуст → close не снимает чужой строки.

### F4. LOW — Исключения в runTurn до/вне try теряются молча (Future без get())

- **Где:** `TurnManagerImpl.java:54` (`submit` без обработки), `TurnManagerImpl.java:66-71` (`tryAcquire`/`register`/`publishStatus` вне try).
- **Дефект:** `newVirtualThreadPerTaskExecutor().submit(Runnable)` заворачивает задачу в Future; исключение из tryAcquire (например, недоступность БД) никто не читает — wake-попытка исчезает без лога.
- **Предложение:** обернуть всё тело `runTurn` в try/catch с error-логом (или `whenComplete`).

### F5. LOW — Нет guard'а «нечего потреблять» на верху цикла: spurious Turn тратит LLM-вызов (TOCTOU tryStart)

- **Где:** `TurnManagerImpl.java:49-55` (eligible-проверка в tryStart до захвата лока), `AgentTurnEngine.java:67-80` (цикл начинается LLM-раунд без проверки `lastSeq <= lastConsumedSeq`).
- **Дефект:** между eligible-проверкой tryStart и захватом лока в runTurn чужой Turn может завершиться и потребить батч; наш Turn всё равно возьмёт свободный лок и выполнит полный LLM-раунд «в никуда» — лишний ASSISTANT в журнале и расход токенов. Псевдокод execution-model §2 («…и новых событий нет → выход») этого выхода в impl на верху цикла не имеет (проверка новых событий есть только после ответа модели — `AgentTurnEngine.java:130-143`).
- **Предложение:** в начале раунда `if (current.lastSeq() <= current.lastConsumedSeq()) return;` — без смены `last_turn_outcome`.

### F6. LOW (тест) — EVENT-латентность проверяется тавтологично

- **Где:** `TurnEngineWireMockTest.java:171-188` (`isLessThan(pollIntervalMillis)` при `poll-interval: 1h` в тестовом профиле — `application-test.yml`).
- **Дефект:** ассерт «быстрее интервала опроса» с интервалом 1 час почти всегда истинен; осмысленную латентность даст только связка «эндпоинт → tryStart» (пачка E).
- **Предложение:** фиксированная верхняя граница (например, несколько секунд) сейчас + повторная проверка в E по реальному пути.

### F7. LOW (тест) — Чистка shedlock: чужая просроченная строка не ассертится

- **Где:** `PollWakeJobTest.java:74-95` (`jobLockRow "another-job"` вставляется, но `remaining` проверяется только на expired-`sess-*` и live-`sess-*`).
- **Дефект:** деградация DELETE до `WHERE lock_until < now()` (без `LIKE 'sess-%'`) тест не поймает — job-строки начали бы удаляться молча.
- **Предложение:** добавить `assertThat(remaining).contains(jobLockRow)`.

### F8. LOW (покрытие) — Спец-сценарий «зависший процесс не блокирует навсегда» не покрыт тестом

- **Где:** спека agent-turn, Requirement «Взаимоисключение Turn'ов по сессии», сценарий «зависший процесс не блокирует навсегда»; тесты `SessionLockManagerTest` покрывают конкуренцию/heartbeat/unlock, но не expiry-takeover.
- **Предложение:** дешёвый тест: захват лока → `UPDATE shedlock SET lock_until = now() - interval '1 second'` → `tryAcquire` успешен (попутно фиксирует, что `sessionTtl` реально уходит в `lockAtMostFor`).

### F9. INFO (док) — Javadoc bash упоминает setsid, код setsid не использует

- **Где:** `ContainerWorkspaceTools.java:188-189` («в своей сессии процесса (setsid)») против фактической команды (PID-файл + ставка на собственную PGID-группу docker-exec, `ContainerWorkspaceTools.java:205-217`).
- **Предложение:** согласовать Javadoc с кодом (или добавить `setsid` для детерминизма группы). Механика подтверждена эмпирически: `TurnCancellationTest.stopDuringBashKillsProcessAndCancelsTurn` реально проверяет смерть sleep через pgrep в контейнере.

### F10. INFO — requestStop синхронно исполняет прерыватели: патологическая латентность stop

- **Где:** `TurnCancellation.java:38-47` (cancel() гоняет interruptor'ы на потоке вызывающего), `ContainerWorkspaceTools.java:261-276` (killUntilDead поллит до execDeadline = bash-timeout + grace).
- **Дефект:** если контейнер wedged и PID-файл не появился, POST /stop (E) заблокируется до полного bash-таймаута. Для M1 приемлемо (нормальный путь — миллисекунды, тест укладывается в 60с).
- **Предложение:** задокументировать верхнюю границу латентности stop; опционально — отдельный executor для прерывателей.

### F11. INFO — `* 1000` в secondsArg (сек→мс)

- **Где:** `NativeAgentTools.java:123-128`.
- **Дефект:** формально число в коде (D-39), фактически — unit-конверсия, не настраиваемый параметр. Не нарушает дух D-39; для единообразия можно заменить на `Duration.ofMillis(Math.round(number.doubleValue() * 1000))` с комментарием. (Grace `plusSeconds(5)` в bash — унаследован из пачки C, вне зоны пачки D.)

### F12. INFO — Имя таблицы `shedlock` дублируется литералом

- **Где:** `PollWakeJob.java:45-46` (SQL чистки) против дефолта `JdbcTemplateLockProvider` (`ShedLockConfig.java:24-30`).
- **Предложение:** общая константа/свойство — при переименовании таблицы рассинхрон не случится.

## Проверено и валидно (без замечаний)

**Отклонения, заявленные разработчиком (все оценены, все законны):**
1. **`SimpleLock.extend()` вместо `LockExtender`** — законно: `LockExtender` требует ThreadLocal-регистрацию `LockingTaskExecutor` (путь `@SchedulerLock`), при программном `LockProvider.lock()` неприменим; `extend` у `StorageBasedLockProvider` (ShedLock 7.10.1) — штатный механизм той же библиотеки. Heartbeat-интервал и TTL — конфиг (`LockProperties`, `application.yml`: session-ttl 10m / heartbeat 30s); heartbeat >> интервала продления, запас 20×; в `close()` — `cancel(false)` + mutex: гонок с in-flight extend нет (частичный остаток — см. F3).
2. **EVENT-wake только контрактом `tryStart`** — соответствует формулировке спеки («источник … SHALL инициировать»): в M1 источник = эндпоинт сообщений (пачка E), вызов `tryStart` — его обязанность; автовейка в `appendEvent` нет, POLL страхует (5с). Тест 7.3 есть (с оговоркой F6). **Пометка для ревью пачки E:** эндпоинт обязан звать `tryStart` после коммита дописи.
3. **7.6: тест на пост-краш-состоянии в БД/Docker вместо kill -9** — достаточно: рестарт-скан stateless (потребляет только durable-журнал + факт свободного лока + список контейнеров), in-memory мёртвого процесса ему не нужен; оба спец-сценария (свободный лок → LOST+wake; живой лок → нетронуто) покрыты, orphan-контейнеры — покрыты. Реальный kill -9 — задача 10.3, перенос согласован с планом.
4. **CANCELLED потребляет батч** — сознательное отклонение, тестами зафиксировано, но см. F1 (требует документирования/решения владельца).
5. **Default-метод `bash(..., TurnCancellation)` в `WorkspaceTools`** — законно: оба типа в пакете `execution`, границы модулей не задеты; старый 4-арг метод сохранён (обратная совместимость); релей M4 унаследует «отмена не поддержана» — задокументировано в Javadoc.
6. **`finishTurn`/pending-сканы** — атомарны: одиночный `UPDATE … SET last_consumed_seq = last_seq` читает `last_seq` в одном стейтменте; конкурентная допись держит row-lock строки сессии (`reserveSeqByRowLock`) → сериализация без потерь/дублей; «первый финальный по callId выигрывает» — `NOT EXISTS` по `payload_jsonb->>callId` (включая синтетические CANCELLED/LOST — корректно).

**Покрытие спеки agent-turn — все 11 сценариев закрыты тестами:** полный цикл с инструментом и сообщение во время хода (`TurnEngineWireMockTest`, детерминированный сон bash'а + допись USER до TOOL_RESULT); FAILED при исчерпании ретраев (SYSTEM-причина «503», потребление батча, ровно 2 LLM-вызова); рестарт между записью и исполнением (LOST + wake); stop во время bash (убийство процесса подтверждено pgrep, TOOL_RESULT CANCELLED, Turn CANCELLED, флаг сброшен); stop на IDLE + сообщение после отмены; конкурентный запуск (5 конкурентов → `verify(2)` LLM-вызовов + точный журнал; 4 конкурента на уровне лока → 1 победитель); пропуск EVENT → подбор POLL (обход wake прямой вставкой); чистка sess-строк по `lock_until < now()`; зависший TOOL_CALL/живой лок/orphan-контейнеры. Исключения — F8 (TTL-takeover) и оговорка F6.

**Условия выхода цикла (execution-model §3):** sync-инструменты → доп. раунд; новые события во время хода → доп. раунд (сравнение `lastSeq` с базой раунда); `cancel_requested` → CANCELLED (+синтетические CANCELLED для незакрытых pending-вызовов — и до исполнения, и в цикле исполнения); ошибка LLM → FAILED (SYSTEM-событие, потребление, без автоповтора); async-парковка и компакция — корректно вне M1. Write-ahead соблюдён: ASSISTANT+TOOL_CALL дописываются до исполнения инструментов.

**Отмена:** флаг сбрасывается на старте Turn'а (`TurnManagerImpl.runTurn`) и в `finishTurn` (любой исход); регистрация в `ActiveTurnRegistry` — строго до сброса флага (окно «stop в полёте» закрыто повторной проверкой `cancellation.isCancelled()` в `isCancelled`); `registerInterrupt` исполняет прерыватель немедленно, если отмена уже произошла (гонка «stop до появления PID-файла» закрыта); `WorkspaceContainerManager.exec` выходит из ожидания срезами `state-poll-interval`, путь self-kill exit>=128 при отмене не тратит окно `containerStoppedWithin` — корректно. Stop на другом инстансе — через флаг в БД (реестр локален процессу — по дизайну).

**Числа — только конфиг (D-39):** session-ttl/heartbeat/job-ttl/poll-interval/state-poll-interval/bash-timeout(+cap)/tool-output/write-chunk-bytes — yml; `@SchedulerLock`/`@Scheduled` — из конфига; `SessionRuntimeStatus` — enum без чисел; хардкоды — только семантические константы (Duration.ZERO, unit-фактор F11, тестовые константы потоков).

**Scope M3/M4 не протёк:** нет async-окна, late-результатов, spawn/transition, CLIENT_EXEC, SSE-эндпоинта, PARKED-статусов (только упоминания в Javadoc как точек роста); `ToolStatus.ASYNC_ACCEPTED` — резерв из пачки C, в M1 не пишется.

**#6915 не регрессировал:** prompt-options в `LlmInvoker` несут только `toolCallbacks`; `.timeout()`/`.maxRetries(0)` заданы в дефолтных options модели (`ChatModelFactory.options`) — при merge Spring AI null-поля prompt-options падают в дефолты модели.

**`SessionStoreImpl.notifyListenersAfterCommit`** — self-invocation ловушки нет: `appendEvent` вызывается только извне через прокси (класс `@Transactional`), fallback прямой публикации — для вызовов вне Spring-контекста; `afterCommit` при откате не срабатывает.

**`agentRevisionRepository`** — используется по назначению (`resolveRevision` ← `createFreeSession`); в `finishTurn` не требуется.

**Регрессии пачки C не задеты:** пачка D не трогает unicode/codepoint-логику, BoundedOutputStream (только hook-подключение через переопределённый `close()` с countDown — overflow реально прерывает callback и даёт результат с `truncated`), bash-таймаут (`timeout -s TERM`, exit 124).

## Summary

- **Всего находок: 12** — Critical: 0, High: 0, Medium: 3 (F1–F3), Low: 5 (F4–F8), Info: 4 (F9–F12).
- **Спека agent-turn:** 11/11 сценариев имеют тесты; задачи 7.1–7.6 выполнены, все 6 заявленных отклонений оценены как законные (№4 — с обязательным документированием, см. F1).
- **Топ-3:** F1 (CANCELLED поглощает батч — USER-сообщение из.mid-turn не дойдёт до модели; нужно решение владельца + ADR/спека), F2 (broadcaster после рестарта застревает на baseline seq — чинить до пачки E/8.5), F3 (HeldLock при пустом extend не должен unlock'ать в close — дешёвая защита против снятия чужого лока).
- **Вердикт: условный approve.** Блокеров нет; пачка D может идти дальше при условиях: (1) F1 — решение владельца с фиксацией в спеке/decisions.md (документировать поведение или сменить его); (2) F2 — код-фикс до начала работ по 8.5 (иначе сломается живая доставка SSE после рестарта); (3) F3/F4/F5 — дешёвые тarroустойчивые фиксы, желательно в пачке D-исправлениях; Low-тесты (F6–F8) — в ближайший тестовый коммит.
