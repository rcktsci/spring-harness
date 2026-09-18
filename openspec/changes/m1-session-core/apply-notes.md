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