## Context

M1 закрыт (`2026-09-18-m1-session-core`, 209 тестов зелёных): ядро сессий работает — append-only сообщения, FREE-сессии, turn-движок с EVENT+POLL wake, локи `sess-{id}`, helper-контейнеры per-session, SSE, REST+SSE API на сгенерированных интерфейсах. Архитектурный каркас: модули `identity`/`session`/`execution`/`intelligence`/`api`/`config`/`common`; ArchUnit запрещает кросс-импорты мимо контрактов; контракт-first работает (openapi-generator 7.25, генератор → сгенерированные интерфейсы → контроллеры → тест-клиент). Модули `workflow` и `task` в roadmap заявлены (architecture.md §1), но пакеты отсутствуют — в M2 они появляются и ArchUnit-правило `execution → {task, session, workflow}` начинает работать на полную.

Целевой рантайм — один инстанс на VM (docker-compose, Linux). Правила владельца зафиксированы в AGENTS.md: dev = GLM-5.3-Flash, ревью = full pipeline на сущностных пачках, числа — конфиг, никакого энтерпрайз-раздутия, изоляция = контейнер (path-guard удалён `70e5260`).

## Goals / Non-Goals

**Goals:**
- Домен задач: ревизии workflow, инстансы задач с историей переходов, BASH_SCRIPT/WAIT_WEBHOOK/WAIT_TASKS состояния, STATE-сессии с резюмом по `(task_id, state_code)`, suspend/stop, capability-URL вебхуки, триггеры.
- Контракт-first: спека M2-подмножества api-contracts §4 замораживается через полный ревью-цикл → генерация → контроллеры на сгенерированных интерфейсах → тест-клиент.
- POLL-страховка задачного слоя (D-33) + таймаут-скан по `deadline_at`.
- Мета-инструмент `transition` с обязательным reason и гейтом metaTools (D-38: только `instructionSource = USER`; metaTools-гейт — D-59, supersession в M2).
- Архитектурные границы держатся (ArchUnit расширяется под `workflow`/`task` пакеты).

**Non-Goals (явно за рамками M2):**
- `@Tool` metaTools-агенты (D-M1-8 отложен до M3).
- Async-инструменты (`ASYNC_ACCEPTED`, поздние TOOL_RESULT) — M3.
- Spawn_subagent, read_compacted, отмена поддерева — M3.
- Webhook авто-регистрация (агент заводит подзадачу через `POST /tasks/{id}/subtasks` и настраивает триггер) — вне MVP.
- Rate-limit, idempotency-хранилище, fork/rewind, экспорт, архивация — D-41 вырезано.
- Multi-instance состояния (длина поля сессий состояния >1) — D-07 оставлено за M3+ как точка эволюции.
- WebUI-фаза (билеты SSE/WS, скачивание workspace §8) — отдельный чендж.
- Раннеры (control/execution split) — точка эволюции, не фаза.
- MCP-сервер наружу — точка эволюции.

## Decisions

### D-47 Контракт-first M2: вариант A (закреплён из M1)
**Решение**: проектирование спеки (`src/main/resources/api/openapi.yaml` — M2-подмножество) → полный ревью-цикл 3×approve → заморозка → генерация (openapi-generator-maven-plugin 7.25+, два execution: spring interfaceOnly + java native test-scope) → контроллеры на сгенерированных интерфейсах → тест-клиент.
**Альтернативы**: код-first (отклонён в M1 — контракт-first закреплён владельцем 2026-09-18).
**Обоснование**: процесс владельца; Jackson 2 — provided/test-scope only (main-runtime = Jackson 3, Boot 4).

### D-48 Модули `workflow` и `task`: новые пакеты с impl
**Решение**: пакеты `se.rocketscien.harness.workflow` (+ `impl/`) и `se.rocketscien.harness.task` (+ `impl/`). Контракты — `WorkflowRegistry` (workflow) и `TaskRegistry` (task). Реализации — `impl/WorkflowRegistryImpl`, `impl/TaskRegistryImpl`. ArchUnit расширяется: правило `execution → {task, session, workflow}` действует на полную; запрет кросс-импорта чужих `.impl` — параметризован по 4 модулям (был по 3 в M1).
**Альтернативы**: держать workflow/task в одном пакете; вынести в отдельный репозиторий.
**Обоснование**: roadmap/architecture.md явно разделяют workflow и task как самостоятельные домены; единый пакет усложнит рост (M3 добавит ревизии агентов, async).

### D-49 Движок состояний: один исполнитель на задачу + ShedLock
**Решение**: системная джоба `task-scheduler` под ShedLock-ключом `task-scheduler` (TTL = конфиг `harness.task.scheduler.ttl`). Параллельно — EVENT-wake из переходов (insert/update `task` шлёт событие в InProcessTaskWakeBus; аналогично session wake из M1). Без выделенных «runner»'ов в M2 (точка эволюции). Границы модулей (J-9): `BashStateExecutor` и `TaskEngine` — в `execution.impl` поверх контрактов `WorkspaceTools` (execution→execution) и `TaskRegistry` (execution→task); отмена Turn'ов при stop — в `TaskWakeDispatcher.handleStop` (`execution.impl`; см. D-54, sync/async-разделение); `WebhookHandlers` — в `api/impl/` (HTTP-контроллеры; webhook-handler делегирует в `task` через `TaskRegistry`); `TriggerRegistry` — внутренняя деталь пакета `task` (CRUD триггеров); контракт `InboundTriggers` остаётся в architecture.md для будущих внешних интеграций (не код M2). Курсор истории переходов — пара `(created_at, id)`, индекс `(task_id, created_at, id)`. SSE задач: курсор `since=` — монотонный `task_event_seq` (сквозная нумерация всех событий задачи, включая не-transition); снапшот при коннекте = последний `task.status`.
**Альтернативы**: один поток на задачу через ShedLock per-task; микросервис; WebhookHandlers в `integration` (нет HTTP-поверхности — контроллеры живут в `api`).
**Обоснование**: соответствует D-33/D-36; в M1 wake уже работает через EVENT+POLL — переиспользуем инфраструктуру; границы пакетов повторяют слои architecture.md (HTTP — в `api`, оркестрация — в `execution`); ArchUnit allowed-layers для `api` дополняется `task`/`workflow` (architecture.md §2, задача M.1).

### D-50 BASH_SCRIPT в `harness-task-<taskId>`
**Решение**: bash-состояние запускает `script` через `WorkspaceTools.executeBash(...)` в **отдельном** контейнере `harness-task-<taskId>` (по аналогии с `harness-<sessionId>` для сессий). Workspace — `SERVER_DIR / mode=PATH / path = "${task.id}"` по умолчанию; переопределяется через `state.workspace`. `state_attempt` инкрементируется при каждом входе (включая рестарт-восстановление) — `bash-скрипты должны быть идемпотентны` (явный инвариант в data-model §4).
**Альтернативы**: переиспользовать контейнер сессии родительской сессии; выполнение на хосте.
**Обоснование**: D-30 (изоляция = контейнер); отдельный task-контейнер изолирует задачи, не загрязняет workspace сессии; идемпотентность скриптов — обязательное свойство для надёжности при рестартах (D-36).

### D-51 Capability-URL: HMAC-SHA256 в пути
**Решение**: `token = HMAC-SHA256(server_secret, kind + ':' + entityId)`, `kind ∈ {"task", "trigger"}`. Секрет — конфиг `harness.webhook.secret` (env). Для задач проверка чистая (без БД — состояние известно по `task.current_state`); для триггеров — точечный lookup `trigger.revoked_at`.
**Альтернативы**: подпись тела + timestamp (D-05/D-26 отклонено: отправитель не владеет секретом); общие секреты по типу источника (ротация).
**Обоснование**: D-26 (паттерн unsubscribe-ссылок); stateless для задач.

### D-52 Гейт metaTools: `instructionSource = USER` (D-38, в M2)
**Решение**: гейт metaTools (D-38; supersession в M2 — D-59): инструмент `transition` разрешён только при `instructionSource = USER`. В M2 единственная точка входа — USER-сообщение прямо в сессию состояния (`POST /api/v1/sessions/{id}/messages`). Агент-сообщения (TOOL_RESULT, внутренний апдейт) не порождают metaTools. Применение `transition` — транзакционно **в момент исполнения tool-call** (CAS `current_state` + INSERT `task_transition_history` атомарны с исполнением инструмента), не отложенно на turn-finish. Дополнительный лимит — `harness.task.transition.max-per-turn` (конфиг, дефолт 1; защита от спама). Реестр в decisions.md — D-59 (частично supersede D-41 в границах M2).
**Альтернативы**: разрешить всегда; разрешить по роли агента; разрешить по гранту.
**Обоснование**: D-38 (закрывает инъекционный вектор «мета-инструмент от имени USER через субагента»).

### D-53 STATE-сессии: PARTIAL UNIQUE + резюм
**Решение**: PARTIAL UNIQUE INDEX `(task_id, state_code) WHERE kind = 'STATE'` в `session`. Повторный вход в то же `(task_id, state_code)` — UPSERT-семантика: если существует сессия — подхватывается (resume); иначе — INSERT. Агент-ревизия фиксируется на момент входа (замена ревизии родительского workflow не задевает идущие задачи).
**Альтернативы**: отдельная таблица `state_session`; ленивое создание через первую запись.
**Обоснование**: соответствует D-02 (2 таблицы для сессий; STATE — вариант `session`); data-model §5; PARTIAL UNIQUE — единственная дверь к резюму без race.

### D-54 Suspend+Stop: разные операции (D-29)
**Решение**: `suspend` (флаг, Turn дорабатывает) и `stop` — **всегда каскадный** (параметр `cascade` в API stop отсутствует: suspend + отмена Turn'ов задачи и всех подзадач + терминал `'$CANCELLED'` — псевдо-код вне `codes` ревизии). Resume `'$CANCELLED'`-задачи → `409 task-already-terminal`. Идемпотентность POST — выживем без хранилища (D-41); повторный stop на терминальной → `409`.
**StopTaskFacade → sync + async (K-2, реализация пачки K)**: отдельный facade не потребовался — stop разделён на две фазы по существующей событийной инфраструктуре (D-49):
- **sync** (в HTTP-транзакции, `TaskRegistryImpl.stop`): атомарный CAS поддерева в `'$CANCELLED'` + записи `kind=CANCEL` в `task_transition_history` + `status_projection=CANCELLED` + кадр `task.status` (`task_event_seq++`, durable-инкремент транзакционно с переходом); 202 возвращается клиенту сразу;
- **async** (после коммита): EVENT-wake в `InProcessTaskWakeBus` → `TaskWakeDispatcher.handleStop` (`execution.impl`) находит STATE-сессии всего поддерева (`StateSessionService.findSessionIdsByTaskIds`) и отменяет их Turn'ы (`TurnManager.requestStop` — `cancel_requested` + прерывание in-flight Turn'а, идемпотентно).
**Альтернативы**: одна операция; idempotency-хранилище (отвергнуто D-41); синхронная отмена Turn'ов в HTTP-потоке (отклонено: клиент ждал бы обход поддерева и прерывания, а дисциплина «события — строго после коммита» сломалась бы).
**Обоснование**: разные сценарии (пауза против аварии); replay-safe контракт без хранилища; отмена Turn'ов наследует семантику EVENT-wake (после коммита, идемпотентно, POLL-страховка не нужна — `'$CANCELLED'` уже терминален и переживает рестарт как факт).

### D-55 Таймаут-скан: джоба + индекс
**Решение**: системная джоба `task-timeout-scanner` под своим ShedLock-ключом. Сканирует `deadline_at IS NOT NULL AND deadline_at <= now() AND status_projection IN ('RUNNING','WAITING')`. Переводит в TIMEOUT с записью истории. Индекс — partial `(deadline_at) WHERE deadline_at IS NOT NULL`.
**Альтернативы**: таймеры per-task (БД-уровневые, не переживают рестарт); проверка на POLL-страховке.
**Обоснование**: D-36 (recovery после рестарта); отдельный сканер — независимая подсистема с понятным SLO.

### D-56 JSON-Schema валидации графа: реестр валидаторов
**Решение**: `WorkflowGraphSchemaValidator` — единая точка валидации JSON-Schema + правил §2 workflow-domain.md. Используется при создании workflow, ревизии и при автоматической валидации на старте (sanity-check на загруженных ревизиях). Ошибки — массив `{ pointer, rule, message }`, без утечки внутренних имён.
**Альтернативы**: валидация в `WorkflowRegistryImpl` напрямую; jsonschema-generator.
**Обоснование**: тестируемость (отдельный unit без БД); переиспользуется в sanity-check.

### D-57 Триггеры: pin ревизии + revoke (D-25)
**Решение**: триггер хранит `rev` (пин к ревизии workflow при создании) + `params_jsonb` + `tags`. DELETE API = revoke (`UPDATE SET revoked_at = now()`); URL умирает мгновенно (проверка `revoked_at IS NULL`).
**Альтернативы**: хранение в params задач; отказ от триггеров.
**Обоснование**: D-25; сценарий «внешний вебхук → задача по workflow» с отзывом URL.

### D-58 Ограниченный профиль JSON-Schema без новых зависимостей
**Решение**: pom не меняется (ShedLock, Jackson 3, Spring Boot 4, Spring AI 2.0.1, Testcontainers, WireMock уже на месте). `paramsSchema`/`payloadSchema` в M2 — **ограниченный профиль JSON-Schema**: `required`, `type`, `enum`, `items`, `properties` первого уровня; валидация — собственный ручной обход (hibernate.validator произвольный JSON-Schema не покрывает). Полная JSON-Schema — точка эволюции (тогда и подключается полноценный валидатор).
**Альтернативы**: `networknt/json-schema-validator` (полная спецификация — лишняя зависимость для профиля M2); jsonschemagen (генерация из POJO); hibernate.validator как JSON-Schema-валидатор (им не является).
**Обоснование**: правила §2 нетривиальны (fan-out, достижимость, TIMEOUT-рёбра) — всё равно ручной обход; профиль закрывает реальные сценарии `params`/`payload` без новой зависимости.

## Risks / Trade-offs

- **R1 — большая фаза за один присест (~24 операции, 7 таблиц, новые движки)**. Митигация: пачки D/H/I/J/K/L/M/N с отдельными ревью-циклами и коммитами (см. tasks.md); каждая пачка ≤ 1 крупная подсистема.
- **R2 — гонки stop↔transition**: stop выигрывает через CAS без гварды `NOT suspended` (data-model §7.2). Если stop и transition финишируют одновременно, transition может записать SUCCESS-переход, после чего stop перезапишет на `'$CANCELLED'` — тогда в истории два перехода (NEXT, потом CANCEL). Митигация: задача в этом случае финально CANCELLED; UI показывает оба события.
- **R3 — идемпотентность bash-скриптов**: обязательное свойство (D-36); некорректный скрипт (например, `rm -rf /workspace && git clone`) после ретрая выполнит clone дважды. Митигация: явный инвариант в Javadoc `WorkspaceTools.executeBash`; тест-примеры идемпотентных скриптов в документации workflow.
- **R4 — гонка WAIT_TASKS переоценки**: событие терминала подзадачи может прийти раньше, чем POLL-страховка или EVENT. Митигация: переоценка идемпотентна (CAS по `current_state`); двойная переоценка — no-op.
- **R5 — webhook-путь в HTTP-потоке без дедупликации**: обработка триггера синхронна в handler'е — создание задачи по `POST /api/webhooks/triggers/...` выполняется в HTTP-потоке (долгая транзакция держит соединение); дедупликации повторных доставок триггера нет — каждый валидный POST создаёт новую задачу (внешний ретрай = дубли-задачи). Митигация: приемлемо для MVP (внутренний контур, отправители контролируемы); очередь + дедупликация — точка эволюции M3+.
- **R6 — capability-URL утечка секрета**: секрет хранится в env; ротация не реализована (точка эволюции). Митигация: TLS-only, rate-limit вне MVP.
- **R7 — metaTools-гейт по `instructionSource`**: требует, чтобы `instructionSource` корректно проставлялся во всех точках входа. Митигация: unit-тест + интеграционный тест (USER-сообщение через `POST /api/v1/sessions/{id}/messages` в сессию состояния разрешает `transition`; TOOL_RESULT — нет).
- **R8 — JSON-Schema валидации params**: схема из `state.paramsSchema` (JSON-Schema-формат) требует парсера. Митигация: ограниченный профиль (D-58: required/type/enum/items/properties первого уровня) — собственный ручной обход; полная JSON-Schema — точка эволюции.
- **R9 — рост нагрузки POLL**: новые индексы (частичные) компенсируют; конкретные числа — на этапе нагрузочного тестирования (вне MVP).

## Migration Plan

- Миграции `010..016` создают таблицы; существующие таблицы M1 (`001..007`) не трогаются. Колонки `session.task_id`/`state_code`/`agent_revision_id` и PARTIAL UNIQUE `(task_id, state_code) WHERE kind='STATE'` уже существуют с M1 (миграция 005) — переиспользуются, бэкфилл не нужен (`task_id` IS NULL у существующих FREE-сессий).
- Включение `workflow`/`task` пакетов не требует миграции данных; новые сущности — пустые до первого API-вызова.
- Существующие FREE-сессии продолжают работать без изменений (AgentTurnEngine расширяется, но не ломает старый путь).
- Откат — `DROP TABLE` в обратном порядке (бэкап БД перед миграцией — по D-37 вне MVP).
- Деплой: один инстанс, рестарт; preliquibase-стартер обеспечивает порядок (пул → preliquibase → liquibase → JPA).

## Open Questions

- `payloadSummary` в webhook-reason — формат (размер, ключи). Решить в пачке L при реализации: дефолт `{ source, topKeys, byteSize }`.
- Что делать с `task.owner` при создании подзадачи агентом — наследовать от родителя или от агент-сессии? Решить в пачке J: наследовать от **породившей сессии** (агент работает от имени принципала; владелец сессии = владелец создаваемой задачи).
- `transition` через `tool.call` в M2 — фиксируется ли он как `TOOL_CALL` в журнале сессии состояния? Решить в пачке J: да, как обычный tool-call (видимость в журнале полезна для UI и аудита).
