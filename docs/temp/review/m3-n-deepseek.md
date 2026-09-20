# Ревью M3 batch N: async-инфраструктура (окно, ASYNC_ACCEPTED, парковка, watcher, рестарт-скан)

> Ревьюер: DeepSeek-V4.1-Flash (субагент).
> Дата: 2026-09-20.
> Объект: `agent/AsyncTimeoutWatcher`, `execution/impl/AsyncToolExecutor`, `execution/AgentTurnEngine` (sync/async split), `execution/{ToolResult,TurnPayloads,SessionPromptBuilder,WorkspaceTools,NativeAgentTools}`, `session/{MessageKind,SessionEntity,SessionRuntimeStatus,SessionStore(+Impl)}`, `api/{ApiMappers,SessionMessagesController,SessionEventsController}`, `config/{AsyncProperties,LateResultProperties}`, миграции `2027/017`, `2027/018`, тесты N, `apply-notes.md` §N.
> Контекст: `execution-model.md` §1/§3/§4/§5, `agent-tools.md` §5, `openapi.yaml` (заморожен), M1/M2-контракты.
> Сборки не запускались; сверка по исходникам.
> Severity: **HIGH** — блокер; **MEDIUM** — расхождение; **MINOR/NIT** — косметика.

## Сводка

| Severity | Кол-во |
|---|---|
| HIGH | 1 |
| MEDIUM | 1 |
| MINOR/NIT | 5 |
| **Итого** | **7** |

---

## HIGH

### N-1. `MessageKind.ASYNC_ACCEPTED` есть в домене, но отсутствует в замороженном `openapi.yaml`; маппинг DTO — `valueOf`-мина; фильтрация только в 2 точках
- **Пункт:** `session/MessageKind.java` (+`ASYNC_ACCEPTED`); `src/main/resources/api/openapi.yaml:1437` (`enum: [USER, ASSISTANT, SYSTEM, TOOL_CALL, TOOL_RESULT, COMPACT]`); `api/ApiMappers.java:73-74,109,132`; `api/{SessionMessagesController,SessionEventsController}`; `apply-notes.md` §«Blocker».
- **Цитата:** apply-notes: «**`MessageKind.ASYNC_ACCEPTED` отсутствует в замороженном `api/openapi.yaml`**… при маппинге в сгенерированный DTO `valueOf` упал бы. Временное решение пачки… `ApiMappers.apiVisible(...)` фильтрует плейсхолдер из `GET /sessions/{id}/messages` и SSE `message.created`».
- **Проблема:** доменный `MessageKind` = 7 значений, контрактный `api.gen.model.MessageKind` = 6; преобразование — `se.rocketscien.harness.api.gen.model.MessageKind.valueOf(kind.name())` (`ApiMappers:109,132`). Корректность держится только на двух фильтрах `apiVisible`; любой будущий путь публикации `MessageDto` (или новая точка) без фильтра → `IllegalArgumentException` → 500. Плейсхолдер парковки — факт журнала, но в публичном контракте не отражён; M3-дельта `session-api` добавляет только `late` и не оговаривает internal-вид. Деv сам зафиксировал это как открытый блокер, требующий решения владельца.
- **Предложение:** до заморозки M3 выбрать и зафиксировать: **(а)** добавить `ASYNC_ACCEPTED` в `MessageKind` `openapi.yaml` + дельту `session-api` + регенерация (рекомендовано; клиент видит парковку/pending); либо **(б)** формально закрепить фильтрацию в `api-contracts.md §2`/спеке `session-api` («`ASYNC_ACCEPTED` — internal kind, не отдаётся») **и** сделать маппинг kind безопасным (явный `switch`/`default → skip`, а не `valueOf`), закрыв мину.

---

## MEDIUM

### N-2. Смешанный раунд sync+async паркуется без дополнительного раунда — расхождение с execution-model §3 п.1
- **Пункт:** `execution/AgentTurnEngine` (sync/async split, `if (parked) finishTurn(COMPLETED…)`); specs/agent-turn «смешанный раунд»; `execution-model.md:67` (§3 п.1).
- **Цитата:** execution-model §3 п.1: «Модель вызвала синхронные инструменты (или **смесь** sync+async) — sync выполняются немедленно, async-часть уходит в плейсхолдеры `ASYNC_ACCEPTED` → **результаты дописаны → новый раунд**»; реализация: при `parked` ход завершается `COMPLETED` + `PARKED_ASYNC` без дополнительного раунда (модель не получает немедленного раунда с sync-результатами и плейсхолдером).
- **Проблема:** M3-спека (`agent-turn` сценарий «смешанный раунд») и код паркуют сразу; `execution-model.md` (источник дизайна) не обновлён → противоречие дизайн-документ ↔ спека/код. Поведение может задерживать обработку sync-результатов до прибытия позднего async (до 15 мин).
- **Предложение:** обновить `execution-model.md §3` (парковка при любом pending async, sync-результаты — в контексте следующего раунда) либо реализовать §3.1 (доп. раунд при смеси), привести спеку/код к единому решению.

---

## MINOR / NIT

### N-3. Задача N.5 в `tasks.md` противоречит реализации (write-ahead deviation не отражён в задаче)
- **Пункт:** `tasks.md` N.5; `apply-notes.md` §N п.3.
- **Цитата:** N.5: «`TOOL_CALL` + `ASYNC_ACCEPTED` пишутся **транзакционно (одна транзакция)**»; apply-notes п.3: «`ASYNC_ACCEPTED` дописывается одиночно при парковке. **Отклонение от буквы задачи N.5**».
- **Обоснование отклонения принято** (сохранён write-ahead M1: журнал — маркер «в полёте»; kill в окне → рестарт-скан LOST ровно один раз; чек под sess-локом; тесты M1 на journaled TOOL_CALL). Но текст N.5/design не приведён к принятой схеме.
- **Предложение:** переформулировать N.5 (и design D-60 о write-ahead) под фактическое решение; зафиксировать подтверждение владельца (dev его запросил).

### N-4. Миграции `017/018` в папке `2027/`, `changeSet id="1"` в двух файлах
- **Пункт:** `migrations/2027/017_…xml` (`changeSet id="1"`), `2027/018_…xml` (`id="1"`,`id="2"`); `changeset-master.xml`.
- **Проблема:** используется локальная нумерация changeSet (`id=1` в разных файлах — Liquibase различает по `file`, так что функционально ок), но конвенция проекта — глобально уникальные id (`001..016`). Папка `2027` при M2-миграциях в `2026` — скачок (задокументирован в apply-notes #1).
- **Предложение:** выровнять id/расположение с конвенцией (напр. `017`/`018` в `2026`, единые id) или явно зафиксировать новую конвенцию в AGENTS/design.

### N-5. `AsyncToolExecutor` — фоновый `ExecutorService` без `@PreDestroy`
- **Пункт:** `execution/impl/AsyncToolExecutor` (`Executors.newVirtualThreadPerTaskExecutor()`).
- **Проблема:** пул не закрывается на shutdown бина (виртуальные потоки-демоны, но ресурсная гигиена).
- **Предложение:** `@PreDestroy void shutdown() { background.shutdown(); }`.

### N-6. Worst-case задержка закрытия зависшего async = `watch-schedule` (+ занятый лок)
- **Пункт:** `agent/AsyncTimeoutWatcher` (`fixedDelay = harness.late-result.watch-schedule` 60s; при живом лока — пропуск).
- **Проблема:** LOST пишется на следующем скане; при часто занятом локе — задержка. Для MVP допустимо, но стоит отметить в спеке/ops (SLO закрытия).
- **Предложение:** зафиксировать ожидаемую задержку в документации; при желании — обработка «всех сессий» с ретраем.

### N-7. Несколько async-вызовов в раунде исполняются последовательно в окне
- **Пункт:** `AgentTurnEngine` (`for (asyncCall : asyncCalls) asyncExecutor.execute(...)`).
- **Проблема:** каждый вызов ждёт до `harness.async.window.default-ms` последовательно → до N×окно задержки раунда (в отличие от sync-параллельности). Для единичного bash — не критично.
- **Предложение:** при необходимости — параллельный запуск async-вызовов с общим окном.

---

## Проверка фокусных пунктов

1. **openapi/фильтрация** — ❌ Н-1 (блокер, dev подтверждает).
2. **write-ahead deviation** — ⚠️ обоснование принимаю (N-3 — привести задачу/design к решению).
3. **Рестарт-скан (M1+N.3)** — ✅ плоский скан видит `TOOL_CALL` без парного финального `TOOL_RESULT` (в т.ч. при наличии `ASYNC_ACCEPTED`); тест `restartScanClosesParkedAsyncWithSingleLostAndDoesNotDuplicate`; watcher и скан под sess-локом, «первый финальный выигрывает» (`hasToolResultForCall`).
4. **Парковка sleep 15 + wake + гонка late-result** — ✅ окно тест-профиля 10 с < sleep 15; парковка `PARKED_ASYNC`; поздний результат публикуется под `sess-{id}` (retry `late-publish-retry`), wake строго после release; тесты `busyLockIsRetriedUntilPublishSucceeds`, `existingFinalResultMakesLatePublicationNoOp`, `bashBeyondWindowParksThenLateResultWakesNewTurn`.
5. **ShedLock `async-timeout-watcher`** — ✅ отдельный ключ (не `task-scheduler`/`task-timeout-scanner`), TTL — `harness.late-result.timeout.ttl` (есть в yml).
6. **execution-model §3/§4/§5** — ⚠️ §4 (late result, обход дублей id — D-65), §5 (компакция не тронута) — ок; §3 п.1 — N-2.

## Вердикт

**REJECT — 7 находок (1 HIGH: N-1 `ASYNC_ACCEPTED` вне `openapi.yaml` при `valueOf`-маппинге; 1 MEDIUM: N-2 смешанный раунд vs execution-model §3; 5 MINOR/NIT: N-3…N-7).** Блокер заморозки — N-1 (контракт-first: журнальный вид вне публичного контракта + хрупкая фильтрация).

---

# Re-approval M3 batch N (2026-09-20)

> Проверены: `openapi.yaml`, регенерированные модели, `ApiMappers`/контроллеры, `execution/impl/AsyncToolExecutor`, `AgentTurnEngine`, спека `async-instruments`, `tasks.md`, `apply-notes.md`, тесты N. Сборки не запускались.

## Статусы находок

| # | Sev | Статус | Проверка |
|---|---|---|---|
| N-1 | HIGH | **закрыто** | `openapi.yaml:1437` — `MessageKind: [USER, ASSISTANT, SYSTEM, TOOL_CALL, TOOL_RESULT, COMPACT, ASYNC_ACCEPTED]` + описания (`TRANSITION`/`MessageDto`); модели регенерированы (`target/.../api/gen/model/MessageKind.java` содержит `ASYNC_ACCEPTED`); временная фильтрация удалена — `ApiMappers.apiVisible`/фильтры в `SessionMessagesController`/`SessionEventsController` отсутствуют, `valueOf`-мина устранена. Мини-амендмент спеки выполнен |
| N-2 | MEDIUM | **закрыто** | `specs/async-instruments` переформулирована: «Sync-результаты двигают раунд; async-вызовы в раунде — уложившиеся в окно…; превысившие окно → `PARKED_ASYNC`, ход завершён; фоновое завершение — поздний `TOOL_RESULT`, результат поднимает новый Turn». Согласовано с реализацией (`AgentTurnEngine` parks, `TurnManagerImpl` публикует `PARKED_ASYNC`, late → новый Turn `instructionSource=TOOL_RESULT`) |
| N-3 | MINOR | **закрыто** | `tasks.md` N.5 переписан: TOOL_CALL — всегда в write-ahead; `ASYNC_ACCEPTED` — отдельной строкой после round-finish (D-60); sess-лок гарантирует порядок; kill → restart-scan ровно один LOST; задача помечена `[x]` |
| N-4 | MINOR | **не закрыто (остаток)** | Миграции по-прежнему `migrations/2027/017_…`, `2027/018_…`, `changeSet id="1"` в обоих файлах (запрошенный выравнивающий правкой не затронут) |
| N-5 | MINOR | **закрыто** | `AsyncToolExecutor` — `@PreDestroy void shutdown() { background.shutdown(); }` |
| N-6 | MINOR | **закрыто** | `apply-notes.md` §«Worst-case задержка async-результата (N-6)» — `timeout-ms + watch-schedule` задокументировано |
| N-7 | MINOR | **закрыто** | Тест `AsyncToolTurnTest.twoAsyncCallsWithinWindowResolveInSameRound` — два async-вызова в окне разрешаются в одном раунде |
| GLM M-1 | — | **закрыто** | Спека `async-instruments` переформулирована под фактическую семантику (sync в раунде / async парковка → новый Turn) |

## Проверка

- Блокеры заморозки устранены: публичный контракт (`openapi.yaml`) и домен снова согласованы (7 значений `MessageKind`, регенерация, без фильтров/`valueOf`-мины).
- execution-model §3 ↔ спека/код: расхождение снято правкой спеки (M3-семантика парковки); `execution-model.md` как источник — см. остаток ниже (док-синк).
- Рестарт-скан + watcher (sess-лок, «первый финальный выигрывает»), гонка парковка/поздний результат (ретраи лока), `D-65`, отдельный ShedLock-ключ — не регрессировали; `mvn verify` 453.

## Остаток (не блокирует)

- **N-4** (нумерация/расположение миграций `017/018`, `changeSet id=1` в двух файлах) — не тронут; функционально корректно (Liquibase различает по файлу), но противоречит конвенции `001..016` — выровнять или зафиксировать новую конвенцию.
- **Док-синк**: `execution-model.md §3` остаётся в прежней формулировке («смесь sync+async → новый раунд») — желательно привести к принятой M3-семантике при следующей правке дизайн-доков.

**APPROVE — 0 блокеров (2 остаточных не-блокера: N-4 нумерация миграций; синк `execution-model.md §3`).** Пачка N принята.