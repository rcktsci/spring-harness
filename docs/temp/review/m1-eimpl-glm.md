# Ревью пачки E-impl (шаг 2 contract-first + 8.1–8.5) — GLM-5.3-Flash

Ревьюер: субагент GLM-5.3-Flash. Материал: незакоммиченный diff (git status/diff), эталоны —
замороженная спека `src/main/resources/api/openapi.yaml`, `openspec/changes/m1-session-core/specs/session-api/spec.md`,
`design.md` (D-M1-4/5/8), `apply-notes.md`, AGENTS.md. Сборки не запускались (прогон разработчика: 198/0/0).
Дополнительно к чтению кода: проверены байткод/дефолты фактических джар из локального репозитория
(spring-ai-model 2.0.1, tools.jackson jackson-databind 3.1.5, jackson-bom 3.1.5) — для п.7/п.8 и Jackson-вопросов.

## Findings

### E1 — MAJOR (регрессия пачки): порча кодировки в трёх docker-тестах, включая тестовые данные

Механическая правка конструкторов `LimitsProperties` (добавление `100` — `page`) пере-сохранила файлы
в двойной кодировке (UTF-8 → прочитан как CP1252 → сохранён как UTF-8). В рабочем дереве:

- `tests/execution/ContainerWorkspaceToolsDockerTest.java` — Javadoc в mojibake (стр. 32–33) и
  **порчена тестовая строка** `String content = "привет-😀-100%..."` → `"РїСЂРёРІРµС‚-рџ{..."` (стр. 198,
  тест unicode-roundtrip; проходит, но потерян исходный замысел данных — кириллица+эмодзи);
- `tests/execution/WorkspaceContainerManagerDockerTest.java` — Javadoc mojibake (стр. 29–30);
- `tests/execution/WorkspaceContainerFailureDockerTest.java` — Javadoc и комментарий mojibake (стр. 34, 95).

Фикс: `git restore` этих трёх файлов и заново добавить только аргумент `100` в конструкторы
`LimitsProperties` (в кодировке UTF-8). Обязательно до коммита.
Попутно: `TurnEngineWireMockTest.java` — контентный diff пуст (только CRLF→LF, шум автонормализации).

### E2 — MAJOR (подтверждён заявленный баг, п.8): финальный ASSISTANT с пустым text — реален, корень найден

Код: `AgentTurnEngine.callModel` (src/main/java/.../execution/AgentTurnEngine.java:185–193).

Механизм (проверен по байткоду `MessageAggregator` из spring-ai-model 2.0.1):
`MessageAggregator.aggregate(Flux<ChatResponse>, Consumer<ChatResponse>)` возвращает **Flux-пасстрою**:
исходные чанки проходят дальше (doOnNext лишь накапливает внутренние аккумуляторы), а **агрегированный**
ответ (склеенный текст, tool-calls, usage) выдаётся **во второй аргумент — Consumer — ровно один раз в
doOnComplete** (после чего аккумуляторы сбрасываются). В текущем коде:

- этот Consumer — `chunk -> { }` (агрегат выбрасывается);
- `.subscribe(responseRef::set, ...)` получает **каждый чанк**, и `responseRef` остаётся равным
  **последнему чанку** — у OpenAI-стрима это финальный chunk с пустым `delta` (finish_reason/usage) →
  `text = ""` → `TurnPayloads.assistant("")`.

Usage при этом «работает» (tokens не пустые), т.к. usage приезжает именно в последнем чанке — поэтому
баг и остался незамеченным в тестах 7.2 (текст там не ассертится).

Фикс (минимальный, в `callModel`): передавать агрегат в ссылку —
`.aggregate(llmInvoker.stream(...), responseRef::set)` и подписаться пустым value-консьюмером
(`.subscribe(r -> {}, error -> {...}, done::countDown)`); семантика cancel/dispose не меняется.
После фикса: снять комментарий-заглушку в `MessagesApiTest.sendMessageWakesTurnFasterThanPollInterval`
(стр. 130–132) и заассертить непустой text финального ASSISTANT (аналогично в TurnEngineWireMockTest).
Вопрос пачки: баг прекзистентный (движок, пачка D), но чинится одним изменением в `callModel` + два
ассерта — рекомендую чинить в пачке E либо немедленно завести отдельный таск (решение судьи).

### E3 — MEDIUM: keyset-курсор списка сессий — потеря точности + 500 на битом курсоре

`SessionStoreImpl.searchSessions` (строки ~240–310):

1. Курсор кодирует `lastActivityAt.toEpochMilli()` — обрезка суб-миллисекунд. Сравнение в JPQL
   `(lastActivityAt < :cursorActivity OR (= AND id < :cursorId))`: строка с тем же микро-инстаном, что
   у граничного элемента страницы, не удовлетворяет ни `<`, ни `=` → **пропускается навсегда**. Реалистичный
   сценарий: `now()` в одном транзакте/стейтменте даёт идентичные таймстампы (движок задач M2 будет
   массово создавать сессии). Тесты не ловят (sleep 10–20 мс между созданиями).
   Фикс: кодировать полную точность — `lastActivityAt.toString()` (ISO, до наносекунд) вместо epoch-milli.
2. `decodeCursor` бросает `IllegalArgumentException` → нет хендлера → падает в `unexpected` → **500**.
   Битый/чужой курсор — вход клиента, должен быть `422 validation-failed`. Фикс: в контроллере
   `listSessions` ловить/пробрасывать `ApiValidationException`, + тест на `?cursor=garbage`.

### E4 — MEDIUM: в проводе отсутствующие опциональные поля уходят как явные `null` (расхождение со спекой)

Проверено эмпирически на фактических джарах (jackson-databind 3.1.5 + jackson-annotations 2.21, BOM Boot 4.1.1):
дефолтная inclusion Jackson 3 — ALWAYS; Boot не переопределяет (в application.yml `spring.jackson.*` нет),
сгенерированные DTO без `@JsonInclude`. Итог на проводе: `"late":null` (спека: «в M1 не возвращается»),
`"author":null` для не-USER, `"callId":null`, `"tokens":null`, `"nextCursor":null` при последней странице,
`"lastTurnOutcome":null` (SessionDto), `"description":null` в каталоге. Строгий клиент, валидирующий ответ
по схеме спеки, упадёт на `late`/`author` (`type: string/boolean` без `'null'`). Тесты различие
absent-vs-null не пинят (типизированный клиент даёт null в обоих случаях).

Фикс (минимальный, без правки генерации и спеки): кастомайзер Boot-овского ObjectMapper'а
(`WebMvcConfig`) — config-override `Include.NON_NULL` для классов `se.rocketscien.harness.api.gen.model.*`,
**кроме `SessionDto`** (там `title` required-nullable: «поле присутствует всегда» — global NON_NULL сломал бы
`"title":null`). Альтернатива — принять и зафиксировать как допущение в apply-notes + примечание в спеке.
Требует решения судьи (та же категория вопросов, что замороженная спека).

### E5 — MEDIUM: SSE-гонка «снапшот → подписка» (потеря StatusChanged)

`SessionEventsController.start()`: снапшот читается и отправляется (стр. 115–117) **до** `broadcaster.subscribe`
(стр. 119). `MessageCreated` в этом окне не теряются (бэкфилл из store по lastSentSeq), а вот
`StatusChanged` в журнал не пишется: статус-переход, попавший в окно между чтением снапшота и подпиской,
клиент не узнает до следующего перехода (возможен вечный `IDLE` при коннекте ровно на старте Turn'а).
Фикс тривиален и порядок кадров не меняет: сначала `subscribe` (буфер pending уже всё складывает),
затем снапшот, затем бэкфилл, затем `drain()` (он уже есть).

### E6 — LOW (конвенции): FQDN вместо импортов

- `SessionMessagesController:79` и `SessionEventsController:124` — `java.util.Objects::nonNull`;
- `ApiExceptionHandler:104–105` — `jakarta.validation.ConstraintViolationException` (дважды), `:155` —
  `org.springframework.context.MessageSourceResolvable`;
- тесты: `MessagesApiTest` — `org.junit.jupiter.api.Assertions.assertThrows`, `...testclient.ApiException` (3×),
  `SessionsApiTest:109,280` — `new com.fasterxml.jackson.databind.ObjectMapper()`.
Не-нарушения (задокументированный clash имён домен/генерация): FQDN в `ApiMappers` (Javadoc-примечание) и
`SessionsController:70` — ок.

### E7 — LOW: 500-ответ пишет `"code":null`

`ApiExceptionHandler.unexpected` → `problemWriter.write(..., null, ...)`: `ProblemDetail.setProperty("code", null)`
сериализуется как `"code":null` — вне каталога и выглядит как дефект. Фикс: не ставить свойство при
`code == null` (одна проверка в `ApiProblemWriter`).

### E8 — LOW: непокрытый путь chunked-413

`PayloadSizeFilter`: тест `oversizedBodyReturns413PayloadTooLarge` идёт по Content-Length-ветке;
лимитирующий поток `BoundedRequest` (chunked, без Content-Length) тестом не покрыт — стоит добавить кейс
(-java.net.http: BodyPublishers с неизвестной длиной) либо принять риск до приёмки.

### E9 — INFO: `compactSession` принимает 202, не фиксируя намерение

Никакой записи «compact запрошен» нет (до задачи 9.2 команда ни во что не материализуется). По tasks.md 8.4
это согласованный срез; проследить, чтобы 9.2 не потерял точку ингресса (сегодня 202 не оставляет следа).

### E10 — INFO: SSE-доставка выполняется на потоке-публикаторе

`onEvent → drain → emitter.send` — синхронно в потоке, публикующем событие (broadcaster доставляет
синхронно; publisher — поток дописи/Turn'а). Медленный TCP-клиент потенциально задержит appendEvent/Turn
(medium-риск при масштабе, в M1 — приемлемо). Точка роста зафиксировать в design/execution-model
(асинхронный писатель/очередь на клиента) — не в пачке E.

### E11 — INFO: `renameSession` не обновляет `lastActivityAt`

Переименование не поднимает сессию в сортировке — скорее всего корректно (активность = события журнала),
но семантику `lastActivityAt` стоит один раз зафиксировать в glossary (вопрос судье/владельцу, не фикс).

### E12 — INFO: `retry: 5000` — хардкод при строгом чтении AGENTS.md

`SessionEventsController.RETRY_MILLIS = 5000` — контрактная константа api-contracts §3.1 (комментарий на
месте). Формально «числа — конфиг»; допустимо оставить (спека заморожена), решение зафиксировать словом
в apply-notes.

## Проверка по пунктам ТЗ

1. **Контроллеры/мапперы/DTO** — все 5 контроллеров реализуют сгенерированные интерфейсы (`AgentsApi`,
   `SessionsApi`, `SessionMessagesApi`, `SessionCommandsApi`; SSE — вручную, по плану). Мапперы тонкие,
   required-поля заполняются все; потери — только E4 (null-vs-absent) и E2 (пустой text — движок).
   `ProblemCodes` берёт значения из сгенерированного enum — расхождение каталога исключено.
2. **Jackson 2/3** — `jackson-annotations` строго provided; J2 databind/jackson-databind-nullable в
   main-зависимостях и main-коде отсутствуют (в src/main нет ни одного `com.fasterxml`-импорта;
   тест-клиент J2 — test-scope). **Важная поправка к формулировке apply-notes**: «аннотации J2 Jackson 3
   игнорирует» — неверно. tools.jackson-jackson-bom 3.1.5 использует тот же артефакт
   `com.fasterxml.jackson.core:jackson-annotations` (строка 2.x, 2.21) — аннотации **поддерживаются**,
   потому имена полей/enum'ы корректны. Поправить формулировку в apply-notes, чтобы не зафиксировать
   ложную модель. Дефолты сериализации — см. E4.
3. **Покрытие сценариев spec.md** — все 10 сценариев покрыты: каталог (SessionsApiTest), создание
   201+Location, 404, отправка 202+атрибуция (MessagesApiTest), 413 (ApiErrorHandlingTest), чтение
   (since, порядок, видимость COMPACT), compact FREE/STATE/404 (SessionCommandsApiTest), SSE since и
   Last-Event-ID (SessionEventsSseTest, реальный клиент), 422 errors[] c pointer. Замечание: сценарий
   «событие появляется в потоке с атрибуцией пользователя» покрыт по частям (атрибуция — REST, доставка —
   SSE-тест с дописью без автора); некритично. COMPACT-кадр — 9.2, зафиксировано.
4. **SSE** — retry 5000 первым байтом, снапшот `session.status` первым кадром, порядок seq, приоритет
   Last-Event-ID над since, реконнект без дублей/пропусков, ping по конфигу (тест-профиль 200 мс),
   timeout эмиттера — конфиг. Гонка статусных кадров — E5.
5. **Wake EVENT 8.3** — `tryStart` после `appendEvent`, неблокирующий `turnExecutor.submit`
   (виртуальный поток), сбой старта не роняет 202 (fallback POLL, log.warn), тест требует poll=1h и
   замеряет обгон. Соответствует D-M1-4/7.3.
6. **Merge-patch** — converter первым (`addFirst`) обгоняет дефолтный `application/*+json` Spring 7;
   absent/null/""/" "/unknown покрыты тестами; 415 на чужой Content-Type; ThreadLocal очищается
   фильтром в finally (Boot-авторегистрация; асинхронных диспетчей на этом пути нет) — утечек нет.
   Мелочь: roundtrip через `writeValueAsBytes` вместо `treeToValue` — не требует действия.
7. **Отклонение «202 без тела → produces problem+json → 406»** — подтверждено
   (SessionCommandsApi: `produces={"application/problem+json"}`). Механизм — ProducesRequestCondition
   на этапе маппинга, поэтому **отклонение шире заявленного**: клиент с `Accept: application/json`
   получает 406 не только на happy-path 202, но и **вместо контрактных 404/409**. Позиция: правка
   замороженной спеки оправдана; минимальный вариант — в 202 compact/stop добавить
   `content: { application/json: { schema: {} } }` (2 строки YAML, семантика «тела нет» не меняется),
   перегенерировать и убедиться, что `produces` станет `{application/json, application/problem+json}`,
   а тип возврата останется `Void`. Если генератор начнёт типизировать тело — фолбэк: оставить как есть
   и дописать в §0 спеки правило «клиентам команд шлять Accept */* или problem+json». Вынести судье
   вместе с E4.
8. **Баг «финальный ASSISTANT с пустым text»** — реален; корень и фикс — E2 (см. выше).
9. **406/413 ручные писатели** — `ApiProblemWriter` пишет problem+json напрямую в поток, минуя
   content negotiation (иначе 406 с Accept: text/plain не вернул бы тело); покрыто тестами
   (406 problem+json вопреки Accept, 413). Замечание E7 (code:null на 500).
10. **Границы M1/конвенции** — tree/билеты/скачивание отсутствуют; числовые параметры в конфиге
   (`limits.page`, `sse.timeout` добавлены с тестом привязки); `@RequiredArgsConstructor`+final везде;
   `*Impl` в `.impl`; тесты от единого `BaseApplicationTest` (Postgres+Keycloak+WireMock, без
   заглушек SSO), пакет `tests/api`. Нарушения конвенций — только E6 (FQDN) и E1 (кодировка).

## Summary

- Находок: **12** — MAJOR 2 (E1 кодировка/порча данных, E2 пустой text), MEDIUM 3 (E3 курсор,
  E4 null-vs-absent, E5 SSE-гонка), LOW 2 (E6 FQDN, E7 code:null, E8 chunked-413 — вместе с INFO 4:
  E9–E12). Вердикт: **approve не даю** до фиксов E1 (обязателен до коммита) и E2 (фикс в пачке или
  немедленный отдельный таск — решение судьи); E3/E5 дешёвые и должны войти в эту пачку; E4 и п.7 —
  судейские вопросы категории «замороженная спека».
- Пачка в остальном добротная: генерация изолирована (provided/test-scope выдержаны), merge-patch и
  413/406-инфраструктура корректны и покрыты, SSE-контракт оттестирован реальным клиентом, wake EVENT
  соответствует D-M1-4, сценарии спеки покрыты полностью.

## Fixes approval

Re-approval по своим находкам (гейты E-J-1…E-J-6), проверено по файлам 2026-09-18:

- **E1 → E-J-1: ЗАКРЫТ.** Mojibake в трёх docker-тестах отсутствует (grep по маркерам порчи — 0
  попаданий); кириллица Javadoc восстановлена; юникод-литерал roundtrip-теста цел
  (ContainerWorkspaceToolsDockerTest:198 — "привет-😀-100%..."); аргумент 100 в
  конструкторах LimitsProperties на месте (стр. 244/155/130).
- **E2 → E-J-2: ЗАКРЫТ.** AgentTurnEngine.callModel:188–196 — агрегат в esponseRef::set,
  onNext в subscribe — no-op (комментарий фиксирует причину). Ассерты текста добавлены:
  TurnEngineWireMockTest:96 (journalField(ASSISTANT,"text") = "готово"),
  MessagesApiTest:131 (payload text финального ASSISTANT = "готово"); заглушка-комментарий снят.
- **E3 → E-J-3: ЗАКРЫТ.** Курсор — Instant.toString()/Instant.parse (SessionStoreImpl:341,351 —
  полная точность); InvalidCursorException → SessionsController:78–82 → 422 validation-failed
  pointer /cursor. Тесты: paginationSurvivesSameMillisecondTies (идентичный last_activity_at
  через SQL, страница 1, без дублей/потерь) и garbageCursorReturns422ValidationFailed.
- **E4 → E-J-6: ЗАКРЫТ (принято как поведение).** Apply-notes:137–139 фиксируют ALWAYS-включение
  Jackson 3 и явные null'ы ("late":null и пр.) как осознанное поведение M1.
- **П.7 → E-J-4: ЗАКРЫТ.** openapi.yaml 202 compact/stop — content: {application/json: {schema: {}}}
  (стр. 251–254, 270–273); регенерированный SessionCommandsApi —
  produces = {application/json, application/problem+json}; костыль Accept: */* снят
  (SessionCommandsApiTest:65–69 шлёт дефолтный Accept: application/json → 202, тело пустое).
  Нюанс вне гейта: генератор дал ResponseEntity<Object> (судейская скобка предсказывала Void) —
  функционально эквивалентно (тела нет, тест ассертит blank), фиксировать не требую.
- **Поправка об аннотациях → E-J-5: ЗАКРЫТА.** Apply-notes:108 — «jackson-annotations 2.x и читает
  эти аннотации — функционального дефекта нет».

Не гейтилось судьёй и остаётся открытым как наблюдения: E5 (SSE-гонка снапшот→подписка),
E6 (FQDN), E7 ("code":null на 500), E8–E12 — в прежних формулировках.

**Вердикт: approve** (по своим находкам E1–E4 и п.7; сборки не запускал — прогон разработчика 200/0/0).
