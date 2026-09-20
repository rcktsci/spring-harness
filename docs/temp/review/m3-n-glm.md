# Ревью пачки N (async-инфраструктура M3) — GLM-5.3-Flash

Дата: 2026-09-19. Объект: миграции 017/018 (migrations/2027), AsyncToolExecutor, AsyncTimeoutWatcher (agent/), AgentTurnEngine (sync/async split, write-ahead), TurnManagerImpl (PARKED_ASYNC), SessionMessageKind +ASYNC_ACCEPTED, ApiMappers.late, RestartScanRunner (расширен), тесты (AsyncToolExecutorTest, AsyncToolTurnTest, AsyncLateResultApiTest, ConfigPropertiesBindingTest). Сборки не запускались.

**Вердикт: REJECT** — 2 находки (1 minor / 1 nit-напоминание). Инфраструктура качественная (окно/парковка/лок/first-final-wins/watcher — всё на месте и согласовано с D-60/D-64/D-65); блокирует расхождение спеки с реализацией по «дополнительному раунду».

---

## (1) Миграции 017/018 — ✅
017: `session.depth INT NOT NULL DEFAULT 0` (D-61-старое решение заменено на колонку — согласовано с re-approval плана), preConditions MARK_RAN. 018: двухшаговый DROP+ADD CHECK `ck_session_message__kind` (+ASYNC_ACCEPTED; Postgres не умеет IN-расширение на месте — прокомментировано), оба шага с идемпотентность-гвардами; апгрейд с M1/M2 и чистая база корректны (006 создаёт исходный CHECK раньше). Папка `migrations/2027` зарегистрирована в changeset-master по порядку.

## (2) AsyncToolExecutor (N.1) — ✅ c одним расхождением (M-1)
Окно `harness.async.window.default-ms` (конфиг): уложился → `Resolved` (sync-журналирование движком); превысил → `Parked(callId)` (журнальную пару TOOL_CALL+ASYNC_ACCEPTED пишет движок — write-ahead), фоновое продолжение на виртуальном потоке. `publishLate`: под программным локом `sess-{id}` (D-64) с проверкой `hasToolResultForCall` (**первый финальный выигрывает** — LOST от watcher'а/рестарта пришёл раньше → no-op), допись → `tryStart` строго после release; retry-цикл с конфиг-интервалом; guard «сессия исчезла»; прерывание Turn'а → sync `CANCELLED`. `late=true` — `ToolResult.asLate()` + ApiMappers по payload.

### M-1 (minor). Спека обещает «дополнительный раунд» при активном Turn'е — реализация даёт всегда новый Turn
- **Цитата**: specs/async-instruments: «late-result, Turn активен — THEN Turn **подхватывает его в дополнительном раунде**»; реализация: `publishLate` ждёт освобождения `sess-{id}` (живой Turn удерживает лок; комментарий в коде: «поздний результат уходит строго после release») — допись журнала происходит **после** завершения Turn'а → M1-механика «дополнительного раунда» (сообщение во время хода → доп. раунд) недостижима в принципе: журнал пополняется только вне Turn'а.
- **Проблема**: расхождение спека↔реализация (замороженная дельта). Функционально оба пути доставляют результат, но: (а) тесты пачки N/O будут писать под один из вариантов; (б) латентность разная (новый Turn против доп. раунда).
- **Предложение**: выбрать одно и выровнять: (а) спеку поправить — «поздний результат доставляется после освобождения лока Turn'а; активный Turn завершается, результат поднимает новый Turn» (реализация уже такая, D-64-сериализация важнее доп. раунда — рекомендую); (б) либо append без sess-лока с опорой на целостность по callId (уникальный частичный индекс — миграция; дороже).

## (3) PARKED_ASYNC (N.2) — ✅
Парковка — **состояние сессии**: `TurnManagerImpl` публикует `session.status` c `SessionRuntimeStatus.PARKED_ASYNC` (строка 99) вместо IDLE при выходе Turn'а с pending async; `TurnOutcome`/`last_turn_outcome` НЕ расширялись (DS F5-контракт кадра сохранён: lastTurnOutcome в кадре есть) — ровно как зафиксировано в re-approval плана (M-2).

## (4) Рестарт-скан (N.3) — ✅
`RestartScanRunner` — `findSessionIdsWithPendingToolCalls()` по **всем** сессиям (субагентские включатся автоматически — журнал per-session), закрытие под `sess-{id}`-локом, `tryStart` после release; финальные статусы — OK|ERROR|CANCELLED|LOST (ASYNC_ACCEPTED — не финальный, m-4-фикс спеки отражён). Осторожная оговорка: SQL выборки pending не читал построчно — семантика «TOOL_CALL без последующего финального TOOL_RESULT по callId» заявлена контрактом `SessionStore` (findSessionIdsWithPendingToolCalls/hasToolResultForCall) и покрыта тестами пачки.

## (5) N.4 BLOCKER (ASYNC_ACCEPTED vs openapi.yaml) — ✅ разрешено фильтрацией
Решение: **внутренняя журнальная запись без выхода в API** — `ApiMappers.isVisibleInApi`: ASYNC_ACCEPTED не маппится в MessageDto; `SessionEventsController` SSE-кадр не отправляет (оба места прокомментированы «в замороженной спеке отсутствует»); openapi.yaml НЕ правился (заморозка цела, регенерация не нужна). Обоснование корректно: плейсхолдер — для модели (SessionPromptBuilder рендерит «принято, в полёте» с id=callId), клиенту достаточно TOOL_CALL → TOOL_RESULT(late=true); seq-дыры легальны — прецедент COMPACT задокументирован в ApiMappers. Принимаю; в apply-notes L/N зафиксировать как решение пачки (проверю на M-ревью).

## (6) Write-ahead (N.5 DEVIATION) — ✅ принято с обоснованием
TOOL_CALL пишется движком в write-ahead раунда; ASYNC_ACCEPTED — **отдельной записью** после превышения окна (AgentTurnEngine:244). Отступление от дельты («TOOL_CALL + ASYNC_ACCEPTED атомарно») обосновано M1-инвариантом рестарт-скана: атомарная пара при kill между записями вела бы к «ASYNC_ACCEPTED без TOOL_CALL»/дублю; раздельные записи дают рестарт-скану корректную картину (TOOL_CALL без финала → LOST). Согласовано с инвариантом; риск двойного письма закрыт sess-локом (комментарий в AsyncToolExecutor:77–78).

## (7) AsyncTimeoutWatcher (N.6) — ✅
ShedLock `async-timeout-watcher` (`harness.late-result.watch-schedule` + `timeout.ttl`), скан `findExpiredAsyncAccepteds(now - timeout-ms)`; залоченные (живой Turn) пропускаются — следующий скан; first-final-wins под локом; LOST «превышен верхний лимит»; wake после release. Двойной таймаут с рестарт-сканом идемпотентен (оба под локом + hasToolResultForCall). Джоба живёт в новом пакете `agent/` — по proposal Impact.

## (8) ArchUnit — ⚠️ запланировано, не забыть (nit)
Пакет `agent/` появился (AsyncTimeoutWatcher, agent → execution), но в слой-граф/foreignImpl/циклы ArchUnit пока не включён — по плану это задача 6.1 (пачка S). Напоминание: в 6.1 включить `agent` в allowed-layers (`execution → agent`? текущее направление agent → execution — зафиксировать фактически сложившееся), foreignImpl по 7 модулям, violation-фикстуры. Не блокер пачки N.

## (9) Тесты — ✅
AsyncToolExecutorTest (окно/парковка/first-final-wins), AsyncToolTurnTest (PARKED_ASYNC + wake), AsyncLateResultApiTest (API-поверхность: late=true, фильтрация ASYNC_ACCEPTED), ConfigPropertiesBindingTest (async/late-result свойства). Заявленные 13 — счёт по методам, принимаю; критичные пути (окно, гонка LOST/late, API) покрыты.

---

## Позитив

- D-64 реализован ровно как пере-согласовано в re-approval плана: sess-лок + проверка по payload + who-first.
- Развязка «плейсхолдер для модели / невидим для API» — аккуратное решение N.4-блокера без вскрытия замороженной спеки.
- Все новые механизмы (watcher, publishLate) соблюдают дисциплину локов и after-release wake — стиль M1/M2 выдержан.

## Вердикт

**REJECT** — 2 находки (1 minor: M-1 выровнять спеку async-instruments с реализацией доставки late-результата — рекомендую вариант «всегда новый Turn»; 1 nit: ArchUnit agent-пакет — напоминание для 6.1). После правки спеки — approve.

---

# Re-approval пачки N (2026-09-19)

## Статус моих находок

- **M-1 (спека vs реализация доставки late-результата)** — **закрыто**: async-instruments/agent-turn переформулированы — штатный случай: «Turn с async завершён (PARKED_ASYNC, last_turn_outcome=COMPLETED, pending>0) → поздний результат поднимает **новый Turn** с instructionSource=TOOL_RESULT» (в рендер входят и плейсхолдер, и поздний результат); остаточная формулировка «если Turn по какой-то причине ещё активен — дополнительный раунд (M1-семантика)» — условная защита общего M1-правила, основному пути не противоречит. Реализация (lock-deferred append) теперь соответствует спеке.
- **N-1 (ASYNC_ACCEPTED vs openapi.yaml, BLOCKER)** — **закрыто mini-amendment'ом, синхронно с кодом**: openapi.yaml — MessageKind enum +ASYNC_ACCEPTED с описанием («финальным не считается, закрывается поздним TOOL_RESULT(late=true) либо LOST»), payload-форма {callId, tool}, callId распространён на TOOL_CALL/TOOL_RESULT/ASYNC_ACCEPTED, late-описание; фильтрация в ApiMappers снята (ASYNC_ACCEPTED наружу), SSE-пропуск кадра убран. Замороженная спека расширена аддитивно (обратно совместимо) — контракт-first не нарушен, клиент получает честный журнал.

## Прочее

- ArchUnit agent-пакет — напоминание перенесено в пачку S (tasks 6.1) — согласовано, из находок пачки N снято.
- mvn verify 453 зелёных (прогон dev, принят без повторного запуска).

## Вердикт re-approval

**APPROVE** — незакрытых: 0. Пачка N (async-инфраструктура) соответствует спеке и D-60/D-64/D-65; готова к пачке O (spawn/read_compacted/subtree-cancel).
