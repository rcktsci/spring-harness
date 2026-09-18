# Apply-заметки: m1-session-core

## Пачка A (задачи 1.1–2.2)

Судейские фиксы: `docs/temp/review/m1-apply-A-judge.md` (A-J-1…A-J-6 применены; A-J-1 закрыт
директивой владельца — ручная сборка пула убрана, DataSource собирает автоконфигурация Boot,
exclude `DataSourceAutoConfiguration` снят).

### Отложенные пункты схемы (сверка с data-model.md §5)

- **FK `session.task_id → task.id`** — не создан в M1: таблицы `task` нет (фаза M2). Follow-up
  M2: миграция добавляет FK и инварианты `state_code` (частично уже покрыто partial UNIQUE
  `(task_id, state_code) WHERE kind = 'STATE'`). Держать в задачах M2, чтобы не потерялось.
- **`session_message.id` = TEXT** — выровнено с data-model §5 (`text UNIQUE`); длина 26 символов
  и алфавит Crockford base32 — контракт приложения (`IdGenerator` + тесты), не схемы.

### Политика каскадов (data-model преамбула: «удаления каскадные от владельца»)

- `session.owner_user_id → app_user` — `ON DELETE CASCADE` (владелец).
- `session.parent_session_id → session` — `ON DELETE CASCADE` (поддерево субагентов, stop).
- `session_message.session_id → session` — `ON DELETE CASCADE` (журнал умирает с сессией).
- `session.agent_revision_id → agent`, `agent.llm_model_id → llm_model`,
  `llm_model.credentials_id → llm_credentials`, `session_message.author_user_id → app_user` —
  restrictive: ревизии иммутабельны и бессрочны (retention), авторство — не владение.

### Прочее

- Jackson 2 в classpath — транзитивно из Spring AI (openai-java-core); прямая зависимость
  `jackson-datatype-jsr310` удалена. Пин `hibernate.type.json_format_mapper` =
  `Jackson3JsonFormatMapper` обязателен (в hibernate-core есть оба маппера, дефолт выбрал бы J2).
- OSIV выключен (`spring.jpa.open-in-view: false`).
- preliquibase: хук подключён стартером 2.0.0 (Boot 4 совместим); pre-DDL скриптов в M1 нет —
  файл-заглушка не нужна (comment-only файл ломает старт стартера).

## Пачка D (задачи 7.1–7.6) и её ревью-фиксы

Судейские вердикты: `docs/temp/review/m1-apply-D-judge.md`. Гейты D-J-1…D-J-6 закрыты.

### Зафиксированные решения (D-J-5 и отклонения пачки D)

- **Broadcaster — in-memory, lastDeliveredSeq — не источник истины (D-J-5).**
  InMemorySessionEventBroadcaster живёт в границах процесса: буфер переупорядочения и
  lastDeliveredSeq после рестарта не восстанавливаются, события, опубликованные до старта
  текущего процесса, подписчикам не доставляются. Восстановление потока при
  коннекте/реконнекте SSE — бэкфилл из SessionStore по ?since=/Last-Event-ID
  (эндпоинт 8.5 строится поверх этого базлайна); broadcaster — только живая доставка.
- **Heartbeat = SimpleLock.extend()** (не LockExtender): LockExtender привязан к
  ThreadLocal-регистрации LockingTaskExecutor, при программном LockProvider.lock()
  неприменим; SimpleLock.extend — тот же вызов, что внутри LockExtender. Интервал/TTL — конфиг.
- **EVENT-хук = контракт TurnManager.tryStart**; вайринг «допись USER → немедленный
  tryStart» — точка ингресса сообщений, задача 8.3 (пачка E); контроль — 10.2.
- **kill -9 в 7.6 смоделирован пост-фактум** (состояние в БД/Docker, in-memory у мёртвого
  процесса нет); полный рестарт-тест — приёмочная задача 10.3.
- **Потребление батча — watermark виденного (D-45, D-J-2)**: COMPLETED — финальный ASSISTANT;
  FAILED — SYSTEM-причина (retry-шторма POLL нет); CANCELLED — последний рендер (собственные
  результаты и свежий USER остаются непотреблёнными и поднимают новый Turn).
### D-46: чистка ShedLock-строк отменена

PollWakeJob больше не чистит просроченные sess-*-строки — джоба делает ровно одно:
нашёл eligible-сессии → 	ryStart. Просроченные строки в shedlock безвредны: взятие лока
(lock_until <= now) и продление (lock_until > now AND locked_by = …) их игнорируют,
чужой poll-wake-рядок не трогается. Владелец: сущность «чистка» не имеет сценария
необходимости (правило проекта); тесты чистки удалены. Решение — D-46 в
docs/design/decisions.md; тексты D-M1-4 (design.md) и tasks.md (7.4) обновлены.
### Пачка D-2: Path-guard удалён по директиве владельца

\WorkspacePathGuard\/\WorkspacePathException\ и их тесты удалены; изоляция = контейнер.
Инструменты принимают любой путь и передают его в контейнер как есть (относительные —
от \/workspace\, абсолютные — в ФС контейнера). Canonical-гвард вернётся в эндпоинте
скачивания §8. Спека \specs/workspace-tools/spec.md\ согласована (сценарий
path traversal и запрет \..\ убраны).

## Шаг 2 contract-first: генерация из замороженной спеки (S-J-9)

Спека: `src/main/resources/api/openapi.yaml` (OpenAPI 3.1). Обязательные пункты склейки
(судейский вердикт `docs/temp/review/m1-spec-judge.md`, DS F1/F2/F9/F10):

- **`openApiNullable=false`** в конфиге openapi-generator (версия — самая свежая): иначе
  nullable-поля (`type: [string, 'null']` — `SessionDto.title`, `UpdateSessionRequest.title`)
  разворачиваются в `JsonNullable<T>` и тянут `jackson-databind-nullable` (Jackson 2) в
  main-runtime. Jackson 2 в проект не тащить: компиляционные нужды генератора —
  provided/test-scope, HTTP-слой — Jackson 3 (AGENTS.md).
- **SSE-эндпоинт `GET /sessions/{id}/events` исключить из генерации** (`.openapi-generator-ignore`
  или операционный фильтр): контроллер — SseEmitter вручную (бэкфилл из SessionStore по
  `?since=`/`Last-Event-ID` + живой broadcaster, базлайн D-J-5); сгенерированный метод
  (`ResponseEntity<Void>`-стиль) с SseEmitter несовместим. Схемы кадров — якоря
  `MessageDto`/`SessionStatusEvent` в спеке.
- **RFC 9457-хендлеры с кастомным `code`** для фреймворковых исключений, которые Spring сам
  не форматирует: 405 (method-not-allowed), 406 (not-acceptable), 415 (unsupported-media-type),
  413 (payload-too-large, лимит `harness.limits.body`); 401 (unauthenticated) — без
  WWW-Authenticate-челленджа. Все ошибки — `application/problem+json` с `code` из каталога
  (ProblemDetail+расширения спеки).
- **Пробельные строки**: `minLength: 1` не ловит `" "` — blank-проверка title/text/agentKey —
  сервисное правило, если требуется (решить при 8.x).
- **Разрыв контракт↔код (DS F5)**: `SessionEvent.StatusChanged`/broadcaster сейчас не несут
  `lastTurnOutcome`, а контракт §3.1 (снапшот + `session.status`) — несёт; дополнить
  broadcaster/снапшот при реализации 8.5.

### Реализация шага 2 (пачка E, 8.1–8.5) — факты и отклонения

- Генерация: openapi-generator-maven-plugin **7.25.0** (latest release), генератор `spring`,
  `interfaceOnly`+`skipDefaultInterface`+`requestMappingMode=api_interface`+`useSpringBoot3`,
  `openApiNullable=false`; SSE-операция исключена селективной генерацией
  (`globalProperties.apis` без тега SessionEvents). Тест-клиент — генератор `java` (library
  `native`), test-scope, тот же фильтр.
- **Отклонение 1 (Jackson 2-аннотации)**: шаблоны spring-генератора жёстко кладут в DTO
  `com.fasterxml.jackson.annotation.*` (JsonProperty/JsonValue/…). Jackson 3 использует тот же
  `jackson-annotations` 2.x и **читает эти аннотации** — функционального дефекта нет (E-J-5);
  зависимость объявлена **provided** — фиксация компиляционной нужды, в рантайме аннотации
  присутствуют транзитивно. Сверено тестами: nullable title, payload, enum'ы, OffsetDateTime.
- **Отклонение 2 (produces на командах) — СНЯТО правкой спеки (E-J-4, судейское решение)**:
  в 202 compact/stop добавлен `content: {application/json: {schema: {}}}` — единственная
  аддитивная правка замороженной спеки. Генератор теперь объявляет
  `produces={application/json, application/problem+json}` — `Accept: application/json`
  проходит content negotiation, костыль `Accept: */*` из тестов убран. Факт генератора
  7.25.0: пустая schema разворачивается в `ResponseEntity<Object>` (не Void, как ожидалось
  судьёй) — контроллер возвращает `ResponseEntity.accepted().<Object>build()` (тело пустое).
- **merge-patch absent/null**: типизированный DTO различить не может → converter
  `application/merge-patch+json` читает JSON-дерево и отдаёт контроллеру через
  `MergePatchBodyContext` (ThreadLocal, очистка фильтром). Дефолтный Jackson-конвертер
  Spring 7 принимает `application/*+json`, поэтому кастомный конвертер регистрируется первым.
- **413**: фильтр `PayloadSizeFilter` — Content-Length до диспетчеризации + лимитирующий
  поток для chunked; `harness.limits.body`.
- 406-ответ пишется вручную (`ApiProblemWriter`) — content negotiation при `Accept: text/plain`
  не вернул бы problem+json тело.
- **Курсор страницы (E-J-3)**: opaque-курсор кодирует `Instant.toString()` (полная точность —
  микросекунды Postgres не теряются) + id; битый/подделанный курсор → 422 validation-failed
  (обёртка IllegalArgumentException в контроллере). Тай-брейк сортировки — пара
  (lastActivityAt, id); тест «сиды в одну миллисекунду».
- Broadcaster дополнен (DS F5): `StatusChanged`/`statusSnapshot` несут `lastTurnOutcome`;
  TurnManagerImpl публикует исход при завершении Turn'а.
- **Пустой text ASSISTANT — починен (E-J-2)**: причина — `subscribe(responseRef::set)` получал
  сырые чанки `aggregate()` (выходной поток — pass-through), последний чанк (finish/usage,
  пустой delta) затирал агрегат пустым текстом. Фикс: агрегат — в consumer
  `aggregate(stream, responseRef::set)`, в subscribe onNext — no-op. Ассерты текста добавлены
  в тесты 7.2 (fullCycle: финальный ASSISTANT = «готово») и 8.3 (wake-тест).
- **Явные null'ы J3 (E-J-6, принято как поведение)**: Jackson 3 в Boot-конфигурации
  сериализует отсутствующие объектные поля явно (`"late":null`, `"lastTurnOutcome":null`) —
  включение ALWAYS. Поле `late` в M1 всегда null (M3), клиенты толерантны (внутренний MVP);
  спека не правится.

## Пачка F (задачи 10.1, 10.2, 10.4; 10.3 отменена владельцем)

`mvn clean verify` — BUILD SUCCESS, **209 тестов** (до пачки — 200; +8 ArchUnit, +1 приёмочный
e2e), 0 провалов. Линии 429/503 в логах — ожидаемый шум retry-тестов WireMock.

### 10.1 — ArchUnit (`tests/architecture/ArchitectureRulesTest.java`, чистый unit)

- Импорт только main-классов (`target/classes`, включая сгенерированный `api.gen`);
  тест-классы и test-клиент в границы не входят. `common`/`config` — технические пакеты,
  правил не имеют (config легитимно собирает бины из impl-пакетов, напр. `LlmConfig`
  → `AesGcmCredentialDecryptor`).
- Слои (layeredArchitecture, optional layers — пустые `task`/`workflow` M2 не фейлят):
  `api → {execution, intelligence, session, identity}`;
  `execution → {session, identity, task, workflow, intelligence}`;
  `task`/`workflow → identity`; `session → identity`; `identity` изолирована;
  `api` никем не доступается; `intelligence ↛ execution/api`; циклы между модулями запрещены
  (slices по доменным пакетам, common/config из графа исключены).
- **Отклонение от текста задачи (осознанное)**: в правиле для `execution` разрешён
  `intelligence` — «контракты intelligence знают только execution/api» (architecture.md §2),
  фактическая зависимость `AgentTurnEngine → LlmInvoker`; текст задачи перечислил
  `{session, identity}` не исчерпывающе.
- Impl-правило: чужой `<модуль>.impl.*` вне модуля не импортируется (параметризовано по
  session/execution/intelligence/identity).

### 10.2 — приёмочный e2e (`tests/api/AcceptanceEndToEndTest.java`, критерий M1 — выполнен)

Полный срез от живого Keycloak до реального helper-контейнера: alice (password grant) →
FREE-сессия по реальному агенту (сгенерированный клиент) → POST сообщения → WireMock-LLM
4 раунда → инструменты в реальном контейнере `harness-<sessionId>` (write_file →
`wc -l` → read_file) → финальный ответ. Проверено: переходы `IDLE → TURN_RUNNING → IDLE`
по живому SSE; seq 1..11 без дыр; текст финального ASSISTANT непустой; TOOL_RESULT видимы
клиенту; `callId` парный; файл физически в host-workspace сессии (bind-mount — доказательство
реального контейнера); ровно 4 вызова LLM.

- Нюанс тест-стаба: arguments tool-call'а — вложенный JSON, `\n` в wire-стриме должен
  экранироваться дважды (иначе внешний парсинг чанка даёт сырой newline внутри
  JSON-строки → аргументы не парсятся → content=null). Хелпер `toolCallChunk` в тесте
  экранирует бэкслеши и кавычки.

### Итог по критерию M1 (roadmap.md)

Выполнен: FREE-сессия через минимальный attach (SSE+POST) работает end-to-end — сообщение →
модель → инструменты в helper-контейнере → стриминг; контейнер поднимается лениво и
переживает Turn (рестарт-скан чистит сирот — пачка D); сессии персистентны (append-only
журнал, Postgres). «Рестарт сервера не теряет сессии» — покрыто пост-краш-тестами 7.6
(10.3 отменена владельцем).