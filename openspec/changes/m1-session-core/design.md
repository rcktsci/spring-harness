# Design: m1-session-core

> HOW для фазы M1. WHAT — в `specs/`; мотивация — в `proposal.md`. Исходные дизайн-доки: `docs/design/architecture.md`, `execution-model.md`, `data-model.md`, `api-contracts.md`, `agent-tools.md`; ADR — `docs/design/decisions.md` (D-01…D-42). Здесь — только решения, специфичные для реализации M1, и связки с доками.

## Context

Скелет проекта: Boot 4.1.1 / Java 25 / Spring AI 2.0.1 BOM / ShedLock 7.10.1 (jdbc-template), preliquibase, Testcontainers (postgres), WireMock — в pom. Автоконфигурации отключены (`DataSourceAutoConfiguration`, автоконфиги Spring AI) — значит ручная сборка DataSource (пул → preliquibase → liquibase → JPA) и LLM-клиентов — сама первая задача M1 (задача 1.5), не данность скелета. Кода домена нет — greenfield.

## Goals / Non-Goals

**Goals:**
- Реализовать M1 из `roadmap.md`: identity-гейт, SessionStore, LlmGateway, агентный цикл (sync), ContainerWorkspaceTools, TurnManager (EVENT+POLL, ShedLock `sess-{id}`), REST/SSE-подмножество, страховочная компакция, минимальный attach.
- Интеграционная база: Testcontainers (Postgres + Keycloak), WireMock для LLM, docker-java против тестового Docker.
- Конфиг-инвентарь: все числа — `application.yml` + `@ConfigurationProperties`.

**Non-Goals (границы фаз):**
- M2: workflow/задачи/transition/BASH_SCRIPT/WAIT_*; M3: spawn_subagent, async-инструменты (окно 30с/ASYNC_ACCEPTED/late), read_compacted, MCP-клиент (зависимость в pom — с появлением MCP-инструментов, M3; осознанный перенос из architecture.md §4), триггеры/вебхуки; M4: CLIENT_EXEC-релей, полный CLI; WebUI-фаза: билеты, скачивание файлов.
- `bash` в M1 — строго синхронный (таймаут, отмена убийством процесса); async-окно — M3.
- `GET /sessions/{id}/tree` — M3 (поддерево субагентов); в M1 не реализуем.
- Права/папки/шары/fork/rewind/export — вырезано навсегда (D-41).

## Decisions

### D-M1-1. Раскладка кода по модулям архитектуры

Пакеты по `architecture.md` §1: `identity`, `session`, `execution`, `intelligence`, `api` (+общий `config`). Границы контрактов — Java-интерфейсы (`SessionStore`, `TurnManager`, `LlmGateway`, `WorkspaceTools`) с Javadoc-контрактами; ArchUnit-тест на запрещённые кросс-импорты — с M1 (дешёвый, ловит деградацию сразу).
*Альтернатива*: один плоский пакет — отвергнуто: границы контрактов — основа эволюции (раннеры, Jira-адаптер).

### D-M1-2. Jackson 3 и jsonb (ловушка Boot 4)

Boot 4 = Jackson 3. Для jsonb-полей — кастомный `FormatMapper` (`hibernate.type.json_format_mapper`) на Jackson 3-маппере, единая точка. Проверяется интеграционным тестом на запись/чтение `payload_jsonb`.
*Альтернатива*: откат на Jackson 2 — отвергнуто (архивный путь).

### D-M1-3. LLM-клиенты вручную + защита от #6915

Клиенты собираются из `llm_model`/`llm_credentials` (кэш по `llm_model.id`; `llm_model` правится вручную в БД — актуализация кэша рестартом процесса, MVP). В каждом `OpenAiChatOptions` — явные `.timeout()` (конфиг `harness.llm.timeout`) / `.maxRetries(0)` (регрессия Spring AI 2.0.1 #6915: дефолты невидимы); наши ретраи 429/5xx — свои, экспоненциальный backoff (число попыток и база задержки — конфиг), чтобы управлять политикой из одного места. api_key — AES-GCM с `key_version`, ключ из конфига окружения.
*Альтернатива*: положиться на встроенные ретраи Spring AI — отвергнуто: неуправляемые дефолты + #6915.

### D-M1-4. ShedLock: две роли, один `LockProvider`

Джоба POLL — `@Scheduled` + `@SchedulerLock` (имя `poll-wake`, `lockAtMostFor` — конфиг `harness.lock.job-ttl`, ~10с — параметр джобы). Сессии — программный `LockProvider.lock("sess-"+id, lockAtMostFor=session-ttl)`; TTL конфиг `harness.lock.session-ttl` (заведомо > макс. Turn), heartbeat — `LockExtender` с конфигурируемым интервалом. Блокировка `sess-{id}` — **исключительно mutex Turn'ов** в tryStart; допись событий в журнал её не использует: монотонность `seq` — транзакционный row-lock строки сессии (`UPDATE session SET last_seq = last_seq+1 … RETURNING`), поэтому USER-допись работает и при активном Turn'е. Fencing-токенов нет — осознанно (D-40): компенсация щедрым TTL + extend. Чистка старых `sess-*`-строк — та же POLL-джоба, критерий `lock_until < now()` (не возраст записи — иначе удалили бы живой продлённый лок).
*Альтернатива*: CAS-колонки в `session` — отвергнуто (D-40); Zookeeper/Redis-локи — отвергнуто (лишняя инфраструктура, БД уже есть); допись под `sess-{id}`-локом — отвергнуто: несовместимо с удержанием лока на весь Turn (сообщение во время хода стало бы невозможным).

### D-M1-5. Turn на виртуальных потоках

Turn исполняется на виртуальном потоке (Java 25, JEP 491 — пиннинга нет); стрим LLM блокирующий, доставку deltas в SSE — через in-memory broadcaster на сессию (subscribers list, доставка строго по возрастанию seq). Никаких реактивных цепочек — простота дебага важнее.
*Альтернатива*: WebFlux end-to-end — отвергнуто: сложность не окупается одним инстансом.

### D-M1-6. Рендер видимости и «шапка» кэша

Видимые события = журнал минус `COMPACT.covers` (один проход: лёгкая проекция без payload → SET покрытий → полные payload только видимых). В M1 кэш «шапки» + дельта НЕ делаем (преждевременная оптимизация при пустом продукте); точка роста зафиксирована в `execution-model.md` §5 — рендер за одним интерфейсом, кэш добавится без смены контракта.
*Альтернатива*: кэш сразу — отвергнуто: YAGNI, измерять нечего.

### D-M1-7. Helper-образ и docker-java

Образ `harness-helper` из `docker/Dockerfile` в репо (alpine + find/grep/coreutils/git; добавлять — только по требованию инструмента). docker-java + httpclient5-транспорт, `/var/run/docker.sock`. Контейнер `harness-<sessionId>`: workspace — bind-mount хост-каталога `workspaces/sessions/{sessionId}` (glossary §6; не named volume — важно для будущего скачивания файлов, api-contracts §8), лимиты cpu/mem — конфиг с дефолтами, сеть — отключена (git-клон состояниям нужен с M2 — включим per-state). Pull-политика: локальный образ приоритетен; pull из registry — только backoff-обновление; недоступность registry не фейлит вызов. Смерть контейнера отслеживается при вызове (sync-мир M1); docker events — с M3 (async). Осиротевшие контейнеры удаляет рестарт-скан (список `harness-*` минус живые сессии); иных триггеров удаления в M1 нет (API удаления сессий отсутствует).
*Альтернатива*: исполнение на хосте — отвергнуто (безопасность, D-30); gVisor/Kata — отвергнуто (не тот уровень, D-39); named volume вместо bind-mount — отвергнуто (расходится с glossary и будущим API файлов).

### D-M1-8. SSE и минимальный attach

SSE — Spring MVC `SseEmitter` (Boot 4 MVC-стек; WebFlux не подключаем). `id:` = seq; снапшот `session.status` при коннекте; `Last-Event-ID` приоритетен; ping-комментарий по таймеру конфига. Минимальный attach = «GET events + POST messages» — этого достаточно для приёмочного сценария (curl/sse-клиентом); отдельного клиентского приложения в M1 нет.
*Альтернатива*: WebSocket — отвергнуто для событий (SSE проще и уже в контракте §3).

### D-M1-9. Идентификаторы

UUID v7 — генератор владельца через единую точку `IdGenerator` (D-31, проверен на Boot 3.4 — перепроверить на Boot 4 при реализации). `session_message.id` — ULID (26 символов, Crockford base32, монотонный).
*Альтернатива*: UUID v4 (не сортируемый) и снежинки/Sequence из БД — отвергнуто (D-31: v7 даёт временную локальность без координации).

### D-M1-10. Тестовая стратегия

Слои: (1) unit — рендер видимости, eligibility, ретраи-политика, edit_file-матчинг; (2) интеграционные — Testcontainers Postgres (миграции+репозитории), Keycloak (гейт: JWT в/вне группы), WireMock LLM (стрим, 429→ретраи, tool-calling), Docker-контейнер helper-образа (реальный docker в CI); (3) приёмочный — сценарий `roadmap.md` M1 end-to-end + рестарт-тест (kill -9 между фазами, проверка LOST/подбора POLL).
*Альтернатива*: мокировать всё — отвергнуто: приёмочный критерий фазы — именно end-to-end.

## Risks / Trade-offs

- [Spring AI 2.0.1 #6915 (дефолты таймаутов/ретраев)] → явные `.timeout()/.maxRetries(0)` в каждой сборке options + интеграционный тест на поведение при 429.
- [ShedLock без fencing: замороженный процесс поверх истёкшего TTL] → щедрый session-ttl + heartbeat; риск принят осознанно (D-40).
- [docker.sock из контейнера оркестратора — root-эквивалент на хосте] → закрытая внутренняя VM, один инстанс (уровень проекта, D-39/D-41); сеть контейнера отключена в M1.
- [Джексон 3 / jsonb-несовместимости] → единый FormatMapper + интеграционный тест на roundtrip payload.
- [Keycloak в CI — медленный/капризный] → Testcontainers-keycloak + fallback: юнит-гейт на самоподписанных JWT.
- [Монотонный seq при конкурентных дописях (API-поток + Turn)] → транзакционный row-lock строки сессии (`UPDATE … last_seq+1 RETURNING`); `sess-{id}`-лок только для взаимоисключения Turn'ов.
- [Retry-шторм POLL после LLM-FAILED] → FAILED поглощает батч: SYSTEM-событие причины + `last_consumed_seq := last_seq`; автоматических повторов Turn'а нет.
- [Флаг cancel_requested переживает CANCELLED] → сброс при завершении Turn'а (любой исход) и на старте нового Turn'а.

## Migration Plan

Greenfield: Liquibase-чейнджсет `m1-core` (`app_user`, `llm_credentials`, `llm_model`, `agent`, `session`, `session_message`, ShedLock-таблица) + preliquibase. Откат — дроп схемы (до продукта данных нет). Начальные `llm_credentials`/`agent` — вручную в БД (MVP, D-39).

## Open Questions

- Базовый образ helper: alpine vs debian-slim (проверить find/grep/coreutils/git в alpine-версиях) — решается при написании Dockerfile, контракт инструментов не меняет.
- Конфиг-префиксы для docker (имя образа, лимиты) — финализируются в tasks при написании `@ConfigurationProperties`-классов.
