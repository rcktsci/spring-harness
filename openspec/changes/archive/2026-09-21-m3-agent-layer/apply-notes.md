# Apply notes — m3-agent-layer

## Пачка N. Async-инфра (2026-09-20, dev-субагент GLM-5.3-Flash)

### Сущностные решения пачки

1. **Нумерация миграций 074/075 → 017/018.** В репо физически существуют миграции 001–016
   (M1: 001–007, M2: 010–016), папки — по годам (`migrations/2026`, `migrations/2027`).
   «074/075» в proposal/design — артефакт нумерации плана. Фактические файлы:
   `migrations/2027/017_create_column_session_depth.xml`,
   `migrations/2027/018_alter_session_message_kind_add_async_accepted.xml`.
   Альтернатива — оставить дыры 017–073. Отклонено: ломает сквозную нумерацию без пользы.

2. **Миграция 018 — два changeSet (DROP CONSTRAINT + ADD CONSTRAINT)**: Postgres не умеет
   менять IN-список CHECK на месте. Идемпотентность через preConditions (`onFail=MARK_RAN`)
   по `pg_constraint` — чистая база и апгрейд с M1/M2 дают одинаковый результат.

3. **N.5 — write-ahead TOOL_CALL сохранён для ВСЕХ вызовов; ASYNC_ACCEPTED дописывается
   одиночно при парковке. Отклонение от буквы задачи N.5 («пара в одной транзакции»).**
   Буквальное прочтение (TOOL_CALL отложен до разрешения и коммитится атомарно с
   ASYNC_ACCEPTED) реализовано первым, но сломано три вещи: (а) инвариант write-ahead M1
   (execution-model §1: журнал всегда отражает «в полёте» — крах во время 30-секундного окна
   терял вызов безследово: ни LOST от рестарт-скана, ни видимого следа; модель видела
   повисший tool_call без ответа); (б) журнал-как-маркер-старта: тесты M1
   (TurnCancellationTest — stop по факту старта bash; TurnEngineWireMockTest — тайминг
   межходового USER) используют journaled TOOL_CALL как сигнал «исполнение началось»;
   (в) цель N.5 «kill посреди записи → рестарт-скан не дублирует» достигается без атомарной
   пары: kill между TOOL_CALL (write-ahead) и ASYNC_ACCEPTED оставляет pending TOOL_CALL —
   рестарт-скан пишет LOST ровно один раз; поздний результат no-op по D-64 (чек под локом).
   Финальная схема: TOOL_CALL всех вызовов — в write-ahead батче хода; на превышении окна —
   одиночная допись ASYNC_ACCEPTED; порядок «плейсхолдер прежде позднего результата»
   гарантирует sess-лок. Требуется подтверждение владельца/ревью, что трактовка принята;
   если буквальная пара нужна — вернуться к отложенному журналированию ценой (а)-(б).
   Альтернатива «ASYNC_ACCEPTED в write-ahead до исполнения» отвергнута: окно ещё не
   истекло — плейсхолдер был бы ложью.

3a. В связи с (3) `SessionStore.appendEvents`/`EventDraft` не введены (не понадобились —
   YAGNI, D-41); добавлены только `hasToolResultForCall` и `findExpiredAsyncAccepteds`.

4. **Порядок «пара прежде позднего результата» гарантирован sess-локом** (D-64): фоновое
   продолжение `AsyncToolExecutor` публикует `TOOL_RESULT(late=true)` только под
   `sess-{id}`, а живой Turn удерживает этот лок, пока коммитит пару. Дополнительная
   синхронизация не понадобилась. Занятый лок → повторные попытки с интервалом
   `harness.async.late-publish-retry` (конфиг, 200ms).

5. **«Первый финальный выигрывает» (D-64)**: проверка `SessionStore.hasToolResultForCall`
   под локом перед дописью позднего результата / LOST (watcher). Все TOOL_RESULT в системе
   терминальны (`ASYNC_ACCEPTED` — отдельный вид, не статус), поэтому чек по наличию
   TOOL_RESULT с данным callId эквивалентен чеку по терминальным статусам.

6. **Wake позднего результата** — явный `TurnManager.tryStart` после release (как POST
   messages в M1): поднимает новый Turn у запаркованной сессии или отдаёт событие
   активному ходу через дополнительный раунд. POLL (5s) остаётся страховкой.
   Cycle бинов `turnManager → engine → asyncToolExecutor → turnManager` разорван
   `ObjectProvider<TurnManager>` в executor (ленивый резолв в момент публикации).

7. **`SessionRuntimeStatus.PARKED_ASYNC`** — значение enum добавлено (зарезервировано
   api-contracts §2). Публикация статуса — в finally `TurnManagerImpl.runTurn` по флагу
   `AgentTurnEngine.TurnResult.parkedAsync()` (run теперь возвращает TurnResult вместо
   голого InstructionSource; исход раунда COMPLETED не расширялся).

8. **`ToolResult.late` (Boolean, 8-й компонент)** — аддитивно; фабрики M1 не меняли
   семантики; `asLate()` — копия с late=true. Payload позднего результата несёт
   `"late": true` (TurnPayloads.LATE); плейсхолдер — `{callId, tool}`.

9. **Prompt-side (D-65)**: `ASYNC_ACCEPTED` рендерится в промпт tool-ответом «принято,
   в полёте» с id = callId; поздний TOOL_RESULT — с уникальным id `callId + "-late"`
   (OpenAI не допускает дубль tool_call_id).

10. **Новые конфиги** (`application.yml`, только конфиг, без хардкода):
    `harness.async.window.default-ms` (30000), `harness.async.late-publish-retry` (200ms),
    `harness.late-result.timeout-ms` (900000), `harness.late-result.watch-schedule` (60s),
    `harness.late-result.timeout.ttl` (10s — lockAtMostFor джобы `async-timeout-watcher`).

### Отклонения от буквы задач

- **N.3 «restart-timeout»**: текст синтетического LOST рестарт-скана оставлен M1
  («операция потеряна при перезапуске») — тесты M1 (RestartScanTest) завязаны на подстроку
  «перезапуск»; причина рестарта — смерть процесса, а не таймаут. «превышен верхний лимит»
  использует watcher (N.6), как в спеке.
- **N.3 конфиг timeout-ms**: отнесён к watcher'у; рестарт-скан закрывает ВСЕ зависшие
  TOOL_CALL независимо от возраста (процесс умер — вызов гарантированно потерян),
  поведение M1 сохранено. Субагентские сессии покрыты автоматически (скан плоский по всем
  сессиям; рекурсия по parent_session_id не потребовалась).
- **N.1 «kill посреди исполнения → LOST»**: LOST для kill-сценария даёт рестарт-скан
  (он и проверен); в рамках окна поверяется cancel → CANCELLED.
- **N.5**: см. решение 3 выше — отступление от буквы «одна транзакция» при сохранении
  проверяемого инварианта; формулировка задачи N.5 переписана фикс-раундом (N-3).
- **N.1/N-1**: см. «Blocker … РЕШЁН» выше — mini-amendment openapi.yaml, фильтры удалены.
- **N-2/GLM M-1 (спека)**: формулировки async-instruments/agent-turn приведены к
  реализации: «sync-результаты раунда — в этом же раунде; async, не уложившийся в окно, —
  Turn завершает раунд и паркуется (PARKED_ASYNC); поздний результат поднимает новый Turn».
  Убраны «модель получает ASYNC_ACCEPTED в этом же раунде» (видит в рендере нового Turn'а)
  и «встраивается в активный» как штатный путь (остался не-штатным: активный Turn
  подхватит событие дополнительным раундом по M1-семантике). Исправлен номер миграции
  в тексте спеки: 075 → 018. Синхронизация `docs/design/execution-model.md` §3 п.1 —
  при apply (S.3).
- **Тест-профиль**: окно `harness.async.window.default-ms: 10s` — больше всех «синхронных»
  bash старых тестов (sleep 2/3), меньше sleep 15 async-тестов: M1-поток не задет,
  парковка детерминирована.

### Worst-case задержки async-закрытия (N-6)

Верхний лимит обнаружения потери результата = `harness.late-result.timeout-ms`
(дефолт 900000 = 15 мин) **+** до `harness.late-result.watch-schedule` (дефолт 60 с) —
интервал скана `AsyncTimeoutWatcher`: худший случай ≈ 15 мин 60 с между ASYNC_ACCEPTED и
синтетическим LOST. Рестарт-скан закрывает зависшие вызовы немедленно при старте процесса
(вне расписания watcher'а). N-5: `AsyncToolExecutor` — `@PreDestroy` graceful drain
(shutdown без ожидания потоков, D-41); недописанные результаты закрывают
рестарт-скан/watcher нового процесса.

### Blocker (спека/openapi.yaml) — РЕШЁН фикс-раундом ревью (N-1)

**`MessageKind.ASYNC_ACCEPTED` отсутствовал в замороженном `api/openapi.yaml`** — решён
**mini-amendment'ом openapi.yaml**: `MessageKind` расширен 7-м значением `ASYNC_ACCEPTED`
(+ описание payload-формы в MessageDto и заполнение поля `callId` для плейсхолдера);
регенерация server/test-клиента — штатным generate в maven-прогоне. Фильтрация из API
**удалена** (не defense-in-depth: мёртвая логика, D-41) — клиенты REST/SSE видят
плейсхолдер как обычное событие сессии (kind=ASYNC_ACCEPTED, callId заполнен, late нет);
модель видит его через `SessionPromptBuilder`. Тест AsyncLateResultApiTest перевёрнут:
assert'ит видимость плейсхолдера.

### Файлы пачки

- Миграции: `migrations/2027/017_…`, `migrations/2027/018_…`, `changeset-master.xml`.
- Домен: `MessageKind` (+ASYNC_ACCEPTED), `SessionEntity` (+depth), `SessionRuntimeStatus`
  (+PARKED_ASYNC), `ToolResult` (+late), `TurnPayloads` (+late/asyncAccepted/LATE_ID_SUFFIX),
  `WorkspaceTools` (+asyncCapabilities), `SessionStore`/`SessionStoreImpl`
  (+appendEvents/hasToolResultForCall/findExpiredAsyncAccepteds/PendingAsyncCall/EventDraft).
- Конфиг: `AsyncProperties`, `LateResultProperties`, `application.yml`, `application-test.yml`.
- Исполнение: `execution/impl/AsyncToolExecutor` (новый), `AgentTurnEngine` (sync/async split,
  пары, парковка, TurnResult), `impl/TurnManagerImpl` (PARKED_ASYNC), `SessionPromptBuilder`
  (рендер плейсхолдера/late), `api/ApiMappers` (+late, apiVisible), `api/SessionMessagesController`
  и `api/SessionEventsController` (фильтр плейсхолдера), `agent/AsyncTimeoutWatcher` (новый).
- Тесты: `AsyncToolExecutorTest` (юнит, 4), `AsyncToolTurnTest` (интеграция, 4),
  `AsyncLateResultApiTest` (REST, 1), `SessionPromptBuilderTest` (+2), `ConfigPropertiesBindingTest`
  (+2); правка `BashStateExecutorClassifyTest` (конструктор ToolResult 7→8 аргументов).

### Verify

- `mvn clean verify` — см. финальный прогон ниже (было 439, стало: +13 тестов пачки N).
- Миграции: Liquibase на чистой базе тест-контекста (все e2e поднимают контекст заново) — ок.
- Апгрейд с M1/M2-базы: отдельного стенда нет; обе миграции аддитивны
  (ADD COLUMN … DEFAULT 0 NOT NULL; DROP+ADD CHECK), риск апгрейда — минимальный.

## Пачка O. Spawn + subagent-lifecycle (2026-09-20, dev-субагент GLM-5.3-Flash)

### Сущностные решения пачки

1. **Пакеты: `execution/impl/`, не `agent/`** (O.1/O.3/O.4). `SubagentSpawner`, `ReadCompactedTool`,
   `SubtreeCanceller` вызывает `AgentTurnEngine`/`TurnManagerImpl` (execution); в `agent/` они
   создали бы цикл слайсов ArchUnit (agent → execution уже есть через `AsyncTimeoutWatcher`,
   N.6). Формализация слоя agent и перенос — S.1. Отступление от буквы задач (`agent/…`) —
   с сохранением смысла «агентская зона» (N-прецедент: «agent/AsyncToolExecutor
   (`execution/impl/`)»).

2. **Спавн — блокирующее ожидание на потоке Turn'а родителя** (не CompletableFuture —
   виртуальный поток и так дешёв): поллинг `findSession(child)` до «исход зафиксирован И
   батч потреблён» (D-10) с интервалом `harness.spawn.poll-interval` и жёстким таймаутом
   `harness.spawn.timeout-ms` (дефолт 30 мин). Доставка результата — обычный путь движка
   (TOOL_RESULT на ходу, где spawn вызван); «родительский Turn завершился до возврата»
   внутри одного процесса невозможен (поток блокирован в spawn), кросс-рестарт покрывает
   рестарт-скан (LOST). Отступление от буквы O.2 («CompletableFuture.get()») — семантика
   та же, код проще.

3. **Условие завершения субагента**: `lastTurnOutcome != null && lastSeq <= lastConsumedSeq`.
   Узкое место: если субагенту прилетели события после финального хода (USER), спавнер
   вернёт результат по первому завершённому Turn'у, а субагентская сессия продолжит жить
   независимо (wake собственным контуром). Для M3 (spawn = один seed-USER) — детерминировано;
   сценарий «долгоживущий субагент с параллельными сообщениями» — точка эволюции.

4. **Cancel_requested не «липкий»** (M1-семантика, спека agent-turn: сброс флага на старте
   нового Turn'а): stop поддерева выставляет флаги, прерывает активные Turn'ы (их
   CANCELLED-результаты пишет движок) и закрывает parked-вызовы; **wake после каскада
   НЕ выполняется** — первая реализация будила сессии финальным tryStart, что (а) сбрасывало
   cancel_requested новых ходов (сломало M2-тесты флага stop'а задач) и (б) дало модели
   немедленно продолжить работу вопреки только что случившейся отмене. Запаркованных
   поднимут POLL (5s) или поздний результат — модель увидит CANCELLED-результаты в рендере.
   «Отменённое поддерево» — это событие отмены (CANCELLED-результаты + прерванные Turn'ы),
   не постоянное состояние. Идемпотентность stop'а — по отсутствию незакрытых вызовов
   и активных Turn'ов.

5. **Depth в проекции `Session`** — колонка `session.depth` (N.0) поднята в публичную
   запись (getDepth), SELECT'ы SessionStoreImpl/StateSessionServiceImpl расширены.

6. **`read_compacted` — усечение по содержимому** (D-67 «на одну запись»): лимит
   `harness.compact.read-max-bytes` применяется к `payload.text` записи; конверт
   (seq/id/kind/payload) сохраняется целиком, маркер `truncated: true` — в записи.
   Альтернатива — байтовое усечение всего JSON — отклонена: рвёт структуру, модель
   теряет seq/kind.

7. **ULID компакта в промпте** (`SessionPromptBuilder`): COMPACT-пересказ рендерится с
   `id=<ULID>` — иначе модели неоткуда взять `compact_message_id`. Скрытие оригиналов
   COMPACT'ом не меняется; `read_compacted` возвращает покрытые записи, последний
   покрывающий компакт; указывать можно и на COMPACT, и на любое покрытое сообщение.

8. **Гейты spawn_subagent**: инструмент регистрируется в манифесте только при
   `permissions_jsonb.metaTools == true` (временно, до P-пачки — тот же флаг, что
   P.1/P.2 формализуют); явный вызов без флага → `forbidden (no-metaTools)`. D-59-гейт
   (instructionSource=USER) на spawn НЕ распространяется — спекой разрешён в любом ходе
   оркестратора. Depth-гейт — до создания сессии; `workspace-strategy != inherit` →
   `forbidden (workspace-strategy)` (M3 — только inherit, D-66).

9. **Конфиги**: `harness.spawn.max-depth` (2), `workspace-strategy` (inherit),
   `timeout-ms` (1800000), `poll-interval` (200ms); `harness.compact.read-max-bytes` (16KB);
   тест-профиль: max-depth 1 (оба сценария depth на одном контексте), timeout 60s,
   read-max-bytes 64B.

10. **O-1 (фикс ревью): завершение субагента — полный D-10**. `SubagentSpawner.awaitCompletion`
    требует НЕ только «исход зафиксирован + батч потреблён», но и `pending_tool_calls == 0`
    и `runtimeStatus == IDLE`: Turn с превысившим окно async-инструментом завершается
    COMPLETED при незакрытом вызове (PARKED_ASYNC) — без проверки родитель получил бы
    частичный результат, а финальный ASSISTANT субагента потерялся. Тест
    `spawnerWaitsForParkedSubagentToFinish`: воркер в парковке — spawn не закрывается;
    поздний результат → финал воркера → только тогда TOOL_RESULT родителю.

11. **O-2 (фикс ревью): stop персистентен**. SubtreeCanceller пишет CANCELLED без wake;
    сессия становится eligible — но `TurnManager.tryStart` при `cancel_requested = true`
    — no-op: POLL, поздний результат и рестарт-скан не поднимают остановленное поддерево,
    флаг не сбрасывается (раньше новый Turn сбрасывал его на старте, и модель продолжала
    работу после stop'а). Явные resume-точки (сбрасывают флаг): **USER-сообщение — сброс
    в самом `SessionStoreImpl.appendEvent`** (та же транзакция, что и допись; работает и
    через REST, и через прямой append — M1-семантика «stop по IDLE не гасит новые ходы»
    сохранена, тест `stopOnIdleSessionIsHarmlessAndNextMessageStillWorks` зелёный) и
    **вход/resume задачи** (`AgentStateBootstrapper.bootstrap`; для остановленной
    `'$CANCELLED'`-задачи недостижим: диспетчер ведёт её в handleStop). Тест
    `stopKeepsSubtreeStoppedUntilExplicitResume`: POLL не будит; сообщение пользователя
    возобновляет только корень.

12. **Spawn-timeout orphan (GLM nit, задокументировано)**: при превышении
    `harness.spawn.timeout-ms` (дефолт 30 мин) без финала субагента родитель получает
    `TOOL_RESULT ERROR «spawn-timeout»`, а субагентская сессия продолжает жить
    самостоятельно (свой wake-контур, POLL); принудительная отмена child по таймауту —
    сознательно не делается (инвазивное действие; при необходимости — stop поддерева
    или future-механика отдельным решением). Незакрытые вызовы такого child'а страхуют
    рестарт-скан и AsyncTimeoutWatcher.

13. **R-2 (фикс ревью, round 2): мгновенный выход спавнера при stop поддерева.**
    `SubagentSpawner.awaitCompletion` возвращает `SpawnWait {COMPLETED, CANCELLED, TIMEOUT}`;
    ветка (b): `child.cancelRequested == true` → немедленный
    `ToolResult.cancelled «субагент отменён (subtree-cancelled)»` — без ветки родитель
    висел бы до `harness.spawn.timeout-ms` (CANCELLED-результаты от SubtreeCanceller дают
    вечное `lastSeq > lastConsumed`). Микро-отступление от буквы «ERROR „subtree-cancelled"»:
    статус CANCELLED, а не ERROR — консистентно с маппингом исхода субагента
    (CANCELLED → CANCELLED) и классификацией BashStateExecutor (ERROR = сбой инструмента);
    причина «subtree-cancelled» — в output. Тест `stopDuringSpawnCancelsParentPromptly`:
    TOOL_RESULT CANCELLED в журнале родителя в пределах 2 с после stop.

14. **R-1 (фикс ревью, round 2): mini-amendment спеки agent-turn** — MODIFIED-абзац
    «С флагом cancel_requested в M3»: персистентный stop supersede M1-требование
    «сбрасывается при завершении Turn'а (любой исход) и на старте нового Turn'а»; сброс
    только явным resume (USER-сообщение атомарно с дописью / вход-resume задачи);
    tryStart при флаге — no-op. Два сценария (возобновление сообщением; stop против
    фоновых wake). Внутри change — без внешнего ревью-цикла.

### Файлы пачки

- Конфиг: `SpawnProperties` (новый), `CompactProperties` (+readMaxBytes), `application.yml`,
  `application-test.yml`.
- Сессии: `Session` (+depth), `SessionEntity` (+сеттеры parent/depth), `SessionStore`/
  `SessionStoreImpl` (+createChildSession, findLastAssistantText, findMessageRef,
  findCompactedOriginals, MessageRef), `StateSessionServiceImpl` (depth в SELECT/маппере).
- Исполнение: `execution/impl/SubagentSpawner` (новый), `execution/impl/ReadCompactedTool`
  (новый), `execution/impl/SubtreeCanceller` (новый), `AgentTurnEngine` (манифест + диспетчер
  + гейт no-metaTools), `impl/TurnManagerImpl` (requestStop → каскад), `SessionPromptBuilder`
  (ULID компакта в рендере).
- Тесты: `SubagentSpawnTest` (3: happy path, depth-limit, no-metaTools), `ReadCompactedToolTest`
  (3: оригиналы, усечение, not-found/чужой id), `SubtreeCancelApiTest` (2: активная ветка
  каскада, parked-ветка canceller'а + идемпотентность), `ExecutionFixtures` (+агент с
  permissions_jsonb), `ConfigPropertiesBindingTest` (+spawn, +compact.readMaxBytes),
  `AsyncToolExecutorTest` (стаб SessionStore под новые методы контракта).

### Verify пачки O

- Точечные прогоны: SubagentSpawnTest 3/3, ReadCompactedToolTest 3/3, SubtreeCancelApiTest 2/2.
- Финальный `mvn clean verify` — см. отчёт пачки.

## Пачка P. Оркестратор-metaTools (2026-09-20, dev-субагент GLM-5.3-Flash)

### D-70: metaTools-гейт для оркестратора частично supersede D-41

**Решение**: `permissions_jsonb.metaTools = true` (per-agent-revision, правка = новая
ревизия, D-31) открывает оркестратору 6 metaTools (`create_workflow`, `edit_workflow`,
`create_task`, `create_subtask`, `set_dependency`, `configure_trigger`) и `spawn_subagent`
БЕЗ D-59-гейта `instructionSource = USER` — оркестратор вправе звать их в любом ходе.
**Supersede**: D-41 («не заводить metaTools-гейты») частично — для оркестраторских
инструментов гейт возвращается; **D-41 остаётся в силе** для обычных агентов (у них
metaTools = false, инструменты скрыты из манифеста; явный вызов → forbidden) и для
`transition` — там D-59 (USER-source + лимит на Turn) действует в прежней силе.
**Альтернативы**: глобальный мета-инструмент без градаций (D-41 в чистом виде) — отклонено:
оркестраторский сценарий «Сделай биллинг» требует программного создания задач в любом
ходе, включая реакцию на WAIT_TASKS; полноценный ACL-4-множитель (D-38) — отклонено как
overkill для M3 (одного boolean достаточно, точка эволюции).

### Сущностные решения пачки

1. **Пакет `execution/impl/OrchestratorTools`** (не `agent/`) — преемственность N/O:
   диспетчер живёт в `AgentTurnEngine` (execution), вынос в `agent/` дал бы цикл слайсов
   ArchUnit; слои agent — S.1.

2. **Гейт — в движке, не в реестрах**: `isOrchestrator(agent)` проверяет
   `permissions_jsonb.metaTools == TRUE`; orchestrator-список (`OrchestratorTools.NAMES`)
   без флага → `forbidden (no-metaTools)`; реестры (Workflow/Task/Trigger Registry) гейтов
   не знают — они остаются чистыми дверями (D-M1-1), REST-путь не затронут (контракт-first).

3. **Владелец созданных сущностей — владелец сессии** (owner-наследование O-пачки):
   `createWorkflow`/`createTask`/`configure_trigger` получают `session.ownerUserId()`;
   author задач — агент (`authorUserId = null`, data-model §4). Альтернатива — владелец
   ревизии агента: отклонено, agent не пользователь.

4. **Сигнатуры с дозволенными дефолтами** (отступление от буквы задач):
   `start_state` опционален — дефолт «первое состояние графа» (реестру H-1 нужен явно);
   `description` задачи опционален — дефолт title (TaskRegistry требует непустой);
   `name` workflow опционален — дефолт key. Всё остальное — строго по сигнатурам задач.

5. **`create_subtask` = `createTask` + `parentTaskId`** (реестр изначально поддерживает);
   `set_dependency` — один вызов `TaskRegistry.addDependencies` (атомарная пачка K-1,
   частичный коммит невозможен), не цикл `addDependency`.

6. **Ошибки реестров → машиночитаемые коды в TOOL_RESULT** (префиксы в output):
   `422 graph-invalid`, `422 params-schema`, `422 dependency-invalid`,
   `404 workflow-not-found`, `404 task-not-found`, `409 workflow-key-exists` (+ текст
   errors[] валидатора). Turn не падает — модель видит причину и может скорректировать
   вызов (агентный цикл).

7. **`configure_trigger` возвращает `{triggerId, url}`** — capability-URL с HMAC-токеном
   (M2 L.1); rev пинится в latestRev (CreateTriggerCommand.rev = null).

8. **Манифест (P.3)**: orchestrator-6 + `spawn_subagent` — только metaTools=true;
   `read_compacted` — всем; `transition` — STATE-сессиям (D-59 в прежней силе); native —
   всем (allowlist permissions_jsonb.allowedTools). D-69 не требует кода: флаг per-agent-
   revision, дочерняя сессия пинит ревизию по agentKey спавна — «наследование» невозможно
   по построению; поведение покрыто SubagentSpawnTest (explicitSpawnByPlainAgentIsForbidden).

### Файлы пачки

- `execution/impl/OrchestratorTools` (новый), `AgentTurnEngine` (манифест + диспетчер + гейт).
- Тесты: `OrchestratorMetaToolsTest` (9: 6 happy-path, гейт no-metaTools, graph-invalid +
  workflow-not-found коды, params-schema).

### Verify пачки P

- OrchestratorMetaToolsTest 9/9; финальный `mvn clean verify` — см. отчёт пачки.

### Фикс-round пачки P (2026-09-20)

- **P-1**: tasks.md S.3 расширен — в apply-синхронизацию добавлены
  `workflow-domain.md §6` и `api-contracts.md §4.1` (TaskDto.owner = username, D-41).
- **P-2**: `docs/design/api-contracts.md` §4.1 — `TaskDto.owner`/`author` документированы
  как username (по образцу SessionDto §2).
- **P-3**: `create_task`/`create_subtask` приняли `rev?` (agent-tools §2b «пин последней
  ревизии (или явной)»): отсутствует → latest, указан → пин той ревизии
  (`getRevision(key, rev)`; неизвестная → 404 workflow-not-found). Тест
  `createTaskWithExplicitRevPinsThatRevision`.
- **P-4**: `create_workflow`/`edit_workflow` валидируют key по `^[a-z][a-z0-9-]*$`
  (тот же pattern, что REST-bean-validation) → `422 validation-failed (rule=kebab-case)`.
  Тест `createWorkflowRejectsNonKebabKey`.
- **P-5**: `OrchestratorTools.ok` пишет в TOOL_RESULT фактическое имя инструмента
  (`create_task` и т.д.) вместо заглушки «orchestrator».
- **P-6 (D-69)**: тест `subagentOfOrchestratorCannotCallOrchestratorTools` — оркестратор
  спавнит кодера (без metaTools), кодер явно зовёт `create_workflow` →
  `forbidden (no-metaTools)`, workflow не создан; у кодера ровно 4 LLM-вызова на цепочку.

## Пачка Q. MCP-клиент (2026-09-21, dev-субагент GLM-5.3-Flash)

### Сущностные решения пачки

1. **SDK: `io.modelcontextprotocol.sdk:mcp` 2.0.0** (D-63 соблюдён буквально — MCP Java SDK
   из экосистемы Spring AI; свой JSON-RPC клиент НЕ писался). Агрегатор тянет `mcp-core`
   (клиент + streamable-HTTP транспорт) и `mcp-json-jackson3` (`JacksonMcpJsonMapper` поверх
   `tools.jackson` — Jackson 3, рантайм проекта; правило «без Jackson 2 в main» не задето —
   mcp-core несёт только jackson-annotations). Версия `2.0.0` — property `mcp-sdk.version`
   поверх spring-ai-bom. Локальный репозиторий уже содержал артефакты — новых загрузок нет.

2. **Транспорт — streamable HTTP** (`HttpClientStreamableHttpTransport`); `stdio`/`sse` —
   точки эволюции (конфиг сервера их допускает, реестр отклоняет с явной ошибкой).
   `resumableStreams(false)` + `openConnectionOnStartup(false)` — для простых
   request/response-серверов (фоновый GET SSE-поток против WireMock-имитации не нужен).

3. **Пакет `mcp` — технический, зависимость ОДНОНАПРАВЛЕННАЯ (execution → mcp)**: адаптер
   возвращает mcp-локальный `McpToolResult` (не `execution.ToolResult`) — иначе возник бы
   цикл слайсов ArchUnit (mcp ↔ execution). Преобразование в стандартный контракт — на
   стороне движка (`mcpToToolResult`); callId/late проставляются как у нативных
   (write-ahead / `asLate()`). Слои `mcp` в ArchUnit не именованы (как common/config) —
   формализация не требуется (S.1 при желании добавит).

4. **Ленивость (Q.1)**: ни одного соединения до первого `listTools`/`callTool`;
   `initializedClients()` для наблюдаемости. Дедупликация имён серверов — fail-fast
   в конструкторе реестра (`MCP-name-collision`) — контекст с дубликатами не поднимается.
   Манифест инструментов кэшируется в холдере (подписки tools/list_changed — не в M3).

5. **Auth (Q.4)**: bootstrap-токен из env `secretRef` (может отсутствовать); заголовок по
   типу: `oauth-bearer` → `Authorization: Bearer`, `api-key` → `X-Api-Key`. 401/403
   (детект — `McpHttpClientTransportAuthorizationException` в cause-цепочке либо «401/403»
   в тексте) → `McpAuthRefresher.refresh` (POST прокси `{server, secretRef}` → `{token}`) →
   пересборка клиента с новым заголовком → РОВНО один повтор; повторная авторазница →
   `McpToolResult.error(auth-refresh-failed)`. Проксирующий вызов без автоповторов.

6. **Асинхронность (Q.2)**: `_meta["async-capable"] = true` в манифесте сервера →
   инструмент классифицируется движком как async (`isAsyncTool`) и идёт через
   `AsyncToolExecutor.executeSupply` (обобщение окна: работа — supplier) — те же окна,
   парковка `ASYNC_ACCEPTED`, поздний `TOOL_RESULT(late=true)`, first-final-wins (D-64).
   MCP-вызов не прерывается TurnCancellation (транспорт не умеет) — прерванный Turn
   оставит фоновому вызову обычную публикацию/LOST-страховку.

7. **Манифест (Q.3)**: `tools_jsonb.mcp = {servers[], include[], exclude[]}`; namespace
   `{server}.{tool}`; exclude сильнее include; include пуст → все. Неизвестный сервер —
   runtime-error построения манифеста → Turn FAILED + SYSTEM-причина (по букве задачи).

8. **Конфиги**: `harness.mcp.servers: []` (пустой дефолт), `auth.proxy-url` (env),
   `call-timeout` (60s), `init-timeout` (30s); тест-профиль: сервер `demo` на WireMock
   (`${wiremock.llm.url}`), proxy `${wiremock.llm.url}/mcp-auth-proxy`, call-timeout 20s
   (строго БОЛЬШЕ окна 10s — иначе SDK-таймаут абортит вызов раньше парковки, выявлено
   при отладке), init-timeout 10s.

9. **WireMock-имитация MCP** (инструментальная, в тесты): streamable HTTP на `POST /mcp`,
   матчинг по `$.method` (initialize / notifications/initialized → 202 / tools/list /
   tools/call), response-template ЭХОИТ `$.id` (SDK матчит ответы по id — статический id
   дал timeout при отладке) и `$.params.protocolVersion` (согласование версий), заголовок
   `Mcp-Session-Id` возвращается, но необязателен для SDK. Приоритеты stub'ов (atPriority(1))
   перекрывают дефолтный tools/call в 401-сценариях.

### Файлы пачки

- pom.xml (+mcp 2.0.0), `config/McpProperties` (новый), `application.yml`, `application-test.yml`.
- `mcp/`: `McpClientRegistry`, `McpToolAdapter`, `McpAuthRefresher`, `McpToolCallback`,
  `McpToolDescriptor`, `McpToolResult` (все новые).
- `AgentTurnEngine` (+mcp-манифест, isAsyncTool, mcpToToolResult, диспетчер), 
  `AsyncToolExecutor` (+executeSupply — обобщение окна на supplier).
- Тесты: `McpToolsTest` (7: холодный старт+кэш, namespace+фильтры, sync-вызов,
  async-парковка+late, unknown-server fail-fast, 401→refresh→200, повторный 401 →
  auth-refresh-failed), `ConfigPropertiesBindingTest` (+mcp), `ExecutionFixtures`
  (+tools_jsonb).

### Verify пачки Q

- McpToolsTest 7/7; финальный `mvn clean verify` — см. отчёт пачки.

### Фикс-round пачки Q (2026-09-21)

- **Q-1/GLM M-1**: `tools_jsonb.mcp` приведён к спеке — массив per-server объектов
  `[{server, include?, exclude?}]` (было `{servers[], include[], exclude[]}`); движок
  итерирует записи. Валидация при загрузке ревизии: API регистрации агентов в коде нет
  (D-39 «управление ревизиями — вручную в БД»), поэтому реализовано (а) runtime-ошибка
  манифеста при неизвестном сервере (было с Q.3, тест unknownMcpServerInAgentConfigFailsTurn)
  и (б) стартовый аудит `McpAgentConfigAuditor` (ApplicationReadyEvent: ERROR-лог по агентам
  с неизвестными серверами; не фатально). При появлении API регистрации — проверка
  переносится туда как 422.
- **Q-2**: семантика фильтров выровнена со спекой — сначала include (белый список; пусто →
  все инструменты сервера), затем exclude вычитает поверх (в overlap включённого-и-
  исключённого инструмент НЕ входит). Тест `excludeWinsOverIncludeOnOverlap`.
- **Q-3**: tool-level `MCP-name-collision` — на уровне манифеста: namespace
  `{server}.{tool}` делает коллизию между разными серверами недостижимой по построению;
  дубль namespaced-имени (повтор сервера в конфиге агента) → fail-fast построения
  манифеста. Зафиксировано в javadoc/apply-notes.
- **Q-4**: `mcp-json-jackson3` транзитивно тянет json-schema-validator — НЕ нарушение D-58:
  наш D-58 — ограниченный профиль для params/payload реестров; MCP-сервер управляет своей
  JSON-Schema для input своих инструментов сам.
- **Q-5**: `McpAuthRefresher` — RestClient с таймаутами из конфига
  `harness.mcp.auth.connect-timeout-ms` (5000) / `read-timeout-ms` (10000) через
  `JdkClientHttpRequestFactory` (без безлимитных дефолтов).
- **GLM nit 1 (компромисс — задокументировано)**: MCP-вызов не прерывается отменой Turn'а —
  MCP-вызов синхронный по контракту MCP, cancel родительского Turn не дёргает MCP SDK;
  отменённый Turn оставляет фоновому вызову штатную публикацию результата (first-final-wins
  D-64), а рестарт-скан/watcher закрывают потерянные LOST'ом.
- **GLM nit 2**: ArchUnit-слой для `mcp` — отложен до S.1 (как `agent/`); сейчас пакет
  технический, зависимость однонаправленная execution → mcp.

## Пачка R. ACL/owner + рестарт-скан + FQDN-аудит (2026-09-21)

### R.1..R.4 — верификация существующей реализации

- **R.1 (D-69)**: `agent.metaTools=true` не наследуется субагентам — дочерняя сессия пинит
  ревизию по `agentKey` спавна (`SessionStoreImpl.createChildSession`), манифест/гейт строятся
  по её `permissions_jsonb`. Покрыто `OrchestratorMetaToolsTest#subagentOfOrchestratorCannotCallOrchestratorTools`
  (оркестратор → coder без metaTools → `create_workflow` → `forbidden (no-metaTools)`) и
  `SubagentSpawnTest#explicitSpawnByPlainAgentIsForbidden`.
- **R.2**: `createChildSession` — `owner_user_id` от родителя (не ключа), `parent_session_id`
  текущей, `depth = parent.depth + 1`; покрыто `SubagentSpawnTest#orchestratorSpawnsSubagentAndGetsFinalAnswerAsToolResult`.
- **R.3**: рестарт-скан плоский по всем сессиям, субагентские контейнеры — тот же
  `harness-<subSessionId>` → удаляются `removeOrphanContainers`. Добавлен тест
  `RestartScanTest#orphanSubagentContainerRemovedWhileParentAndSiblingsLive` (сирота-ребёнок
  удаляется; живые родитель/ребёнок остаются). **Отклонение от строки verify задания**
  («stop родителя → контейнеры поддерева удалены»): stop НЕ удаляет контейнеры — замороженная
  спека (`subagent-lifecycle` «Отмена поддерева», `restart-scan`) этого не требует; контейнеры
  снимает только рестарт-скан (API удаления сессий нет).
- **R.4**: cross-session `read_compacted` — `ref.sessionId != current` → `not-found`;
  покрыто `ReadCompactedToolTest#unknownOrForeignIdIsNotFound`.

### Фиксы ревью R (DS/GLM)

- **R-1 (DS HIGH)**: `McpAgentConfigAuditor` SQL `WHERE tools_jsonb ? 'mcp'` — `?` трактуется
  PgJDBC как плейсхолдер (`tools_jsonb $1 'mcp'`), запрос никогда не матчился. Заменено на
  `jsonb_exists(tools_jsonb, 'mcp')`.
- **R-2 (DS minor)**: не-`List` форма `tools_jsonb.mcp` → WARNING-лог (не silent `continue`).
- **R-3 (DS minor)**: тест `McpToolsTest#mcpNameCollisionFailsFastOnDuplicateServerNames` —
  два сервера с одним `name` → `IllegalStateException` `MCP-name-collision` (fail-fast
  в конструкторе реестра); runtime-покрытие неизвестного сервера —
  `McpToolsTest#unknownMcpServerInAgentConfigFailsTurn` (было).
- Тест-покрытие R-1/R-2: `McpToolsTest#auditorLogsUnknownServerAndWarnsOnNonCanonicalMcpForm`
  (неизвестный сервер → ERROR; известный — тишина; mcp-map → WARNING).

### FQDN-аудит (AGENTS.md «Импорты вместо FQDN»)

- Code-aware сканер (стрипает комментарии/строки/import) нашёл **53 inline-FQDN в 26 файлах**.
- Починены все вхождения в **25 файлах** (import + короткое имя); `ApiMappers` — частично
  (4→2, остаток — коллизия ниже): main — `ApiExceptionHandler`,
  `SessionEventsController`, `SessionMessagesController`, `LimitedJsonSchemaValidator`,
  `OrchestratorTools`, `WaitTasksStateExecutor`, `McpClientRegistry`, `ApiMappers`; test —
  `AcceptanceTwoPhaseReviewTest`, `AsyncLateResultApiTest`, `TaskCommandsApiTest`,
  `DataSourceBootstrapTest`, `AgentStateBootstrapperTest`, `AgentTransitionToolTest`,
  `BashStateExecutorTaskContainerTest`, `TaskEngineTestFixtures`, `TaskEngineTransitionTest`,
  `TaskWakeDispatcherStopTest`, `WaitTasksStateExecutorIntegrationTest`,
  `WaitWebhookStateExecutorIntegrationTest`, `TurnCancellationTest`, `AesGcmEncryptionTest`,
  `SessionAppendTest`, `StateSessionServiceTest`, `TaskRegistryLifecycleTest`, `TaskTestFixtures`
  (+ `lombok.SneakyThrows` в 2 файлах).
- **Осталось 2 inline-FQDN — неустранимые коллизии simple-name** (Java не импортирует два
  одноимённых типа): `ApiMappers` (дом. `task.TaskTreeNode` vs ген. `api.gen.model.TaskTreeNode`)
  и `SessionsController:78` (ген. `api.gen.model.SessionKind` параметр интерфейса vs дом.
  `session.SessionKind`). Enum-конверсии в `ApiMappers` убраны инлайном `GenEnums.*`.
- Попутно (блокировало сборку, не связано с R): снят UTF-8 BOM в 4 тестах
  (`TaskEventsSseTest`, `ConfigPropertiesBindingTest`, `OrchestratorMetaToolsTest`,
  `SessionPromptBuilderTest`); `SessionsApiTest` — битый импорт `java.time.ChronoUnit` →
  `java.time.temporal.ChronoUnit`; `SessionsController` — восстановлена доменная конвертация
  (предыдущая FQDN-правка через `GenEnums.sessionKind` возвращала gen-тип).

### Verify пачки R

- `mvn clean verify` — BUILD SUCCESS, **489 тестов**, 0 failures/errors/skipped
  (+1 к предыдущему прогону пачки R — `RestartScanTest`, +2 — аудитор/коллизия MCP).

## Пачка S. Приёмка M3 (2026-09-21)

### S.1 — ArchUnit-расширения

- `ArchitectureRulesTest`: в слои добавлены `agent` (агентский рантайм — как `execution.impl`:
  зависит от контрактов `execution`/`session`, от него никто не зависит) и `mcp` (технический,
  однонаправленный `execution → mcp`, `mayNotAccessAnyLayer`); `execution` допущен к `mcp`.
- `.impl`-правило параметризовано по `agent`/`mcp`; `noDomainModuleDependsOnApi` и граф циклов
  (`DOMAIN_CLASSES`) распространены на оба пакета. Циклов нет (agent → execution → mcp — DAG).
- Негативный тест `agentImplViolationIsCaught` + фикстуры `agent/impl/ArchUnitAgentImplFixture`
  (test-classpath) и `api/ArchUnitAgentImplViolator` доказывают, что `api → agent.impl` ловится
  (паттерн M.1). Позитивные правила на `target/classes` фикстуру не видят.
- Прогон: `ArchitectureRulesTest` — 16/16 (было 15: +2 параметра agent/mcp, +1 негативный).

### S.2 — `AcceptanceMakeBillingTest` (критерий M3)

Детерминированный e2e на живом Keycloak (alice) + WireMock-LLM + реальном helper-контейнере.
Оркестратор `make-billing` (`permissions_jsonb.metaTools=true`), FREE-сессия по REST.

- **Фаза 1** (USER «Сделай биллинг»): оркестратор metaTool'ами `create_workflow` (корневой граф
  `stage1`/`stage2` `WAIT_TASKS` TAGGED + AGENT-разборщик `review` → done/failed) и `create_task`
  создаёт корневую задачу.
- **Фаза 2**: 6× `create_subtask` (analytics, contract-first, impl-1, impl-2, tests, e2e) —
  parent = реальный id root, теги stage1/stage2; подзадачи входят в AGENT-состояния, для каждой
  бутстрапится STATE-сессия.
- **Фаза 3**: `set_dependency` (tests ← impl-1, батч-печка) + `spawn_subagent` (analyst, depth 1).
- **Стейдж-барьеры**: stage1 (ALL_SUCCESS/TAGGED(stage1)) закрывается NEXT после analytics+contract;
  impl-2 завершается FAILED → stage2 закрывается ERROR → AGENT-сессия разборщика (`review`) →
  разборщик переводит задачу `done`; финал root — SUCCESS.
- **Async** (D-60/D-65): подзадача `tests` вызывает `bash` в helper-контейнере (`sleep 12` >
  окно 10 c) → `ASYNC_ACCEPTED`/`PARKED_ASYNC` → поздний `TOOL_RESULT late=true` (output
  `tests-ok`) → wake нового Turn → USER-ход с `transition`.
- **Проверки**: 9 переходов (root 3 + подзадачи 6), kind-ы рёбер (`NEXT`/`ERROR`), failed-ids в
  reason ERROR-ребра, SSE-снапшот корневой задачи (`stage2 → review → done` + первый
  `task.status`), ребро `task_dependency`, поздний результат и артефакт `tests.txt` в workspace
  STATE-сессии, owner-наследование и depth=1 у сессии аналитика.

Отклонения/упрощения S.2 (сознательные, для детерминизма):

- **Скрипты агентов = WireMock-сценарии** (per-agent): живая LLM недоступна; stubs эмулируют
  ответы модели, включая чтение tool-результатов. Оркестратор вызывает metaTools реальными
  ходами 3-х USER-фаз; id root/подзадач тест подставляет в аргументы между фазами (без
  шаблонов WireMock) — детерминированно и без зависимости от формата tool-ответов.
- **Рабочие workflow-ревизии подзадач — фикстура среды** (как ревизии агентов/LLM-модель):
  оркестратор создаёт только корневой workflow; сделано ради компактности сценария
  (оркестраторский путь `create_workflow` проверен на корневом графе, `OrchestratorMetaToolsTest`
  покрывает остальные). `OrchestratorTools`-реализация не менялась.
- **USER-сообщения в STATE-сессии** дописываются тестом между ходами (как M2-приёмка) — иначе
  `transition` заблокирован гейтом D-59 (`instructionSource=USER`).
- **`set_dependency`** проверяет метаинструмент и атомарное ребро `task_dependency`; у барьеров
  scope = `TAGGED` (workflow-domain §3 — «барьеры на группах подзадач»), поэтому ребро не
  участвует в закрытии стейджей (BLOCKED_STATUS в модели отсутствует — зависимости влияют
  только на `WAIT_TASKS`-scope `BLOCKED_BY`).
- **Стабилизация чужого флаки-теста**: `AsyncToolExecutorTest` публикует поздний результат из
  фонового virtual-thread, а стабы-списки (`StubSessionStore.appended`, `StubTurnManager.started`)
  были `ArrayList` — гонка чтения в `containsExactly` изредка роняла `mvn verify` под нагрузкой.
  Заменены на `CopyOnWriteArrayList` (правка только тест-стаба, прод-путь не задет).

### S.3 — docs sync + ADR

- `docs/design/decisions.md`: добавлены **D-60…D-70** (формат решение → альтернативы → почему;
  D-47…D-59 не дублировались, нумерация продолжена). D-70 — частичный supersede D-41.
- `docs/design/workflow-domain.md` §6: доступ оркестраторских инструментов через
  `permissions_jsonb.metaTools` (D-62/D-70), гейт/`forbidden (no-metaTools)`, relation к D-59/D-69.
- `docs/design/agent-tools.md` §2/§2b/§3/§4: `spawn_subagent` — только `metaTools=true`;
  §2b-сигнатуры синхронизированы с реализацией (`start_state?`, `description?`, батч
  `set_dependency`, `{triggerId,url}`); `allowedTools`/`workspaceScope` помечены «вне M3» (§T.1),
  `metaTools` — M3-поле; `tools_jsonb.mcp` = `[{server, include?, exclude?}]`.
- `docs/design/api-contracts.md` §4.1: doc-clarification `TaskDto.owner`/`author` = username уже
  внесена фикс-раундом P (P-2) — сверено, правок не потребовалось.
- `AGENTS.md`: M3 завершён (D-60…D-70, слои ArchUnit `agent`/`mcp`, 493 теста), следующий шаг — M4.

### S.4 — Verify

- `mvn clean verify` — BUILD SUCCESS, **493 теста**, 0 failures/errors/skipped
  (489 пачки R + S.1 `+3` [2 параметра + негативный] + S.2 `+1`; флаки `AsyncToolExecutorTest`
  стабилизирован `CopyOnWriteArrayList`).
- Docker-контейнеры (Postgres/Keycloak/helper) — на месте; async-bash реально исполнялся.

### S.5 — Архив

- `openspec archive m3-agent-layer --yes` → спеки `async-instruments`, `subagent-lifecycle`,
  `orchestrator-meta-tools`, `mcp-client`, `agent-turn`, `session-api` в `openspec/specs/`.

### Критерий M3 — выполнен

Сценарий «Сделай биллинг» (оркестратор режет на подзадачи через metaTools, стейджи `WAIT_TASKS`,
провал подзадачи → ERROR в сессию-разборщик → SUCCESS) проходит на живом Keycloak + WireMock-LLM +
реальном helper-контейнере (async-bash с поздним результатом).



