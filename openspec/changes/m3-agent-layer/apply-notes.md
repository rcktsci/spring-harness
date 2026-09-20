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
