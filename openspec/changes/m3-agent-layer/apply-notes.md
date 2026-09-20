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
