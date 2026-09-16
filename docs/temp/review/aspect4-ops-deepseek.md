# Aspect 4 — Observability и эксплуатация (DeepSeek-V4.1-Flash)

> Дата: 2026-09-17. Метод: аудит «что нужно, чтобы эксплуатировать систему по текущему дизайну» против **текущего** дизайн-базиса. Проверяются разрывы в 7 областях: логи, метрики, health/readiness, трейсинг, деплой-манифест, конфигурация, алертинг.
> База: `docs/design/architecture.md`, `execution-model.md`, `data-model.md`, `api-contracts.md`, `security-multitenancy.md`, `workflow-domain.md`, `agent-tools.md`, `roadmap.md`, `decisions.md`, `docs/glossary.md`.
> Формат: **область → что уже в доках (цитата) → GAP/RISK → предложение, готовое к вставке.**
> Вердикты: **GAP** — в доках нет решения (дыра); **RISK** — решение есть/подразумевается, но создаёт эксплуатационный риск без компенсации.
> Все предложения рассчитаны на вставку в новый `docs/design/observability-ops.md` + правки `architecture.md` §4 и `roadmap.md` (M1). Текст «не коммитилось».

## 0. Резюме

| Область | GAP | RISK | Всего |
|---|---|---|---|
| 1. Логи | 5 | 3 | 8 |
| 2. Метрики Micrometer | 9 | 2 | 11 |
| 3. Health/readiness | 3 | 2 | 5 |
| 4. Трейсинг | 2 | 1 | 3 |
| 5. Деплой-манифест | 6 | 4 | 10 |
| 6. Конфигурация | 5 | 1 | 6 |
| 7. Алертинг | 2 | 2 | 4 |
| **Итого** | **32** | **15** | **47** |

Системный корень: **observability-контура нет ни в одном проектном документе**. Слово «метрика/лог/health/trace» в `docs/design/*` встречается только как оговорка «секреты не логируются» (`security-multitenancy.md:42`) и «`reason` вебхуков пишется по дизайну аудита» (`api-contracts.md:128`). Всё остальное — рекомендации прошлых ревью (`docs/temp/review/aspect2-sre-deepseek.md` §9.5, `aspect3-perf-*.md`), которые **не перенесены в дизайн**. При этом архитектура уже накладывает наблюдаемость-нагрузку: контейнеры, POLL, локи, bulkhead, SSE, TTL-чистка, heartbeat-реестр — все эти механизмы существуют, но **безизмерительны**, то есть эксплуатировать их вслепую.

Три следствия-риска верхнего уровня:
1. **Нет Micrometer/Actuator в стеке** (M-1) → нет ни метрик, ни health-групп, ни алертов (A-1 зависит от M). Это блокирует весь остальной разбор.
2. **Нет бэкапов Postgres и не зафиксирована безопасность конкурентных миграций** (D-4, D-3) → единственный инстанс БД, append-only аудит и вся очередь (`session_message`) без резервной копии; rolling deploy может стартовать с недомигрированной схемой.
3. **Нет контракта корреляции** (L-2, T-1, T-2) → Turn размазан по HTTP → POLL → виртуальный поток → контейнер → поздний `TOOL_RESULT`; без единого `turnId`/`traceId` ни лог, ни трейс не соберёт Turn в цепочку.

---

## 1. Логи

### Что уже в доках

- `security-multitenancy.md:42` — «Правило: секреты не логируются; в `reason`/payload вебхуки пишут тела вызовов (по дизайну аудита), но не наши секреты.»
- `api-contracts.md:128` — «логирование всех вызовов в `reason`»; `security-multitenancy.md:63` — docker.sock описан в threat-model.
- `architecture.md:52` — «git-commit-id (git.properties), JaCoCo» (единственное упоминание о сборочной метаинформации).
- `agent-tools.md:18` — «вывод инструмента ограничен (защита контекста), при превышении — усечение с маркером»; `agent-tools.md:53-57` — контракт `TOOL_RESULT { callId, tool, status, output?, exitCode?, truncated?, late? }`.
- `workflow-domain.md:43` — «вывод+exit → reason» для `BASH_SCRIPT`.

### Находки

#### L-1. Формат логов не задан → **GAP**
Дизайн молчит о том, JSON это или текст, какие поля обязательны, куда пишется. При этом все эксплуатационные сценарии (сбор логов, алерты по логам, поиск Turn'а) требуют структурного формата.
**Предложение (вставка в `observability-ops.md` §1):**
> **Формат.** Логи — только структурированный JSON, только в stdout контейнера (12-factor, см. §5). Формат — Elastic Common Schema (native Spring Boot 4): `logging.structured.format.console=ecs`. ECS автоматически добавляет в JSON все пары MDC и поля SLF4J fluent `addKeyValue`. Обязательные поля: `@timestamp`, `log.level`, `logger`, `message`, `service.name=spring-harness`, `service.version=<git.commit.id.abbrev>`, `instance.id`, плюс MDC-набор §1.2. Человекочитаемый формат (`logging.structured.format.console=` пусто) — только на dev-профиле.

#### L-2. Нет контракта корреляции и идентификатора Turn → **GAP**
`data-model.md` и `decisions.md` D-04 сознательно **не вводят** таблицу Turn/Run («Нет таблицы Turn/Run: локи в колонках сессии»), но для логов и трейсов Turn нужен как минимум несохраняемый идентификатор корреляции. Сейчас его нет: ни MDC, ни `turnId`, ни `wakeSource` в логах (хотя глоссарий уже знает `wake_source: POLL | EVENT | API`, `glossary.md:67`).
**Предложение:**
> **MDC-контракт.** На каждый Turn генерируется эфемерный `turnId` (ULID, in-memory, **не персистится** — согласуется с D-04). Заполняется в MDC на весь Turn и во все дочерние виртуальные потоки:
> `instance.id`, `session.id`, `session.kind`, `task.id` (для STATE), `state.code` (для STATE), `agent.key`, `agent.rev`, `turn.id`, `turn.wakeSource` (`EVENT|POLL|API`), `llm.model` (на время стрима), `tool.callId` (на время исполнения инструмента), `container.id` (на время docker-операции), `api.requestId` (на HTTP-запросе).
> Правила: (1) MDC дочернего потока не наследуется автоматически — при передаче работы в executor/`Thread.ofVirtual()` **явно копировать** контекст (helper `MdcScope.wrap(Runnable)`); (2) `api.requestId` = W3C `traceparent`-или-генерируемый ULID, отдаётся клиенту заголовком `X-Request-Id`; (3) Turn-контекст восстанавливается при каждом входе в цикл (в т.ч. после POLL-wake), а не только на взятии лока.

#### L-3. Логирование контейнерного bash: нет аудита исполнения → **GAP**
`workflow-domain.md:43` фиксирует, что для `BASH_SCRIPT` вывод идёт в `task_transition_history.reason`. Но для **агентского** `bash` (`agent-tools.md:14`) единственный носитель вывода — `TOOL_RESULT`, а `agent-tools.md:18` прямо усекает вывод. То есть полный `command` + stdout/stderr + exit-код исполнения агентского bash **никуда durable не попадает.** При разборе инцидента («что агент реально сделал в контейнере») данных нет.
**Предложение:**
> **Аудит контейнерного исполнения.** Каждый вызов `WorkspaceTools` в `SERVER_DIR` (в т.ч. `bash`, `write_file`, `edit_file`) пишет структурное событие аудита в лог уровня INFO:
> `event=tool.executed`, `tool`, `callId`, `sessionId`, `taskId?`, `containerId`, `cwd`, `exitCode`, `durationMs`, `outputBytes`, `truncated`, `mode=sync|async`, `clientExec=false`.
> Для `bash` дополнительно `command` (с маскированием секретов, §1.4). Полный stdout/stderr **в лог не пишется** (защита контекста и объёма) — он доступен в `TOOL_RESULT`/`docker logs` контейнера (§5, ротация). Событие — часть контракта `ContainerWorkspaceTools`, не опция.

#### L-4. Полнота запрета секретов: capability-токен в пути → **GAP**
`security-multitenancy.md:42` запрещает логировать «наши секреты», но capability-токен вебхука передаётся **в пути** (`api-contracts.md:120`), а `security-multitenancy.md:56` признаёт риск утечки URL. При этом D-26 прямо допускает, что URL попадает в access-логи. Формально запрет есть — механизма маскирования нет.
**Предложение:**
> **Маскирование.** Обязательная маска для: (1) пути `/api/webhooks/**/<token>` — в access-логе заменять последний сегмент на `***` (или отключать access-лог для этого пути); (2) query `?ticket=`, `?token=`; (3) заголовков `Authorization`, `Cookie`; (4) полей `api_key*`, `password*`, `*secret*` в MDC/JSON. Реализация — кастомный Logback converter/`MessageConverter` + `logback-spring.xml`, применяется к appender'ам, а не к отдельным вызовам логгера. Тест: регрессионный тест «токен не встречается в выводе лога».

#### L-5. Политика логирования LLM-контента → **RISK**
Дизайн логирует техническую сторону LLM (счёт токенов `session.tokens_total`, `data-model.md:187`), но не решает, логируются ли промпты и ответы. Полный prompt содержит `task.params` (может быть чувствительным, `security-multitenancy.md:39`), роль агента, MCP-результаты.
**Предложение:**
> **По умолчанию — контент НЕ логируется.** В логах только метаданные LLM-вызова: `llm.requestId`, `model`, `promptTokens`, `completionTokens`, `finishReason`, `httpStatus`, `durationMs`, `retryAttempt`, `errorCode`. Полные prompt/response — только при `HARNESS_LOG_LLM_CONTENT=true` (dev/staging) и с предупреждением об уровне DEBUG; для прод-разбора — отдельный защищённый канал (отдельный appender с ограниченным retention), не общий лог.

#### L-6. Вывод и ротация логов → **GAP**
Не зафиксировано «stdout-only» (в контейнере легко появляется файловый appender) и параметры ротации драйвера.
**Предложение:**
> Внутри контейнера — `logging.structured.format.file` **не использовать**; единственный appender — stdout. Ротация/retention — на уровне docker-драйвера (см. §5, `max-size=50m`, `max-file=5`) и системного сборщика. Файловые appender'ы запрещены (инвариант; проверяется код-ревью + absence-тест в `logback-spring.xml`).

#### L-7. Security-audit события не логируются → **RISK**
Append-only журналы (`session_message`, `task_transition_history`) — доменный аудит, но они не покрывают: отказы 401 (`ticket-used`, `signature-invalid`), отсутствие гранта (отдаётся как 404, `api-contracts.md:154`), конфликты идемпотентности, изменения `share`/архивацию, bootstrap-CLI. Для эксплуатации это «слепые» события.
**Предложение:**
> Ввести выделенный security-логгер `harness.security` (WARN/INFO, структурно, всегда вкл.): `auth.failure` (`reason`, `path`, `subject?`, `ip`, `userAgent`), `access.denied` (намеренный 404 — логируется с реальной причиной `no-grant`), `idempotency.conflict`, `share.changed` (`grantedBy`), `admin.cli.bootstrap` (`llmCredentialsId`, `agentKey/rev`). Токены/URL — по §1.4.

#### L-8. CLIENT_EXEC: серверный аудит отсутствует → **RISK**
В CLIENT_EXEC (роуминг, `api-contracts.md:130-146`) bash/файлы исполняет клиент; сервер видит только `tool.result { callId, output, exitCode }`, который тоже усечён (`agent-tools.md:18`). Это осознанный design-разрыв, но операционный контракт «что клиент обязан логировать локально» не задан.
**Предложение:**
> Зафиксировать в контракте клиента: CLI/WebUI-релей пишет локальный структурированный audit-лог исполненных `tool.call` (тот же поля-набор `event=tool.executed`, `clientExec=true`) в файл `~/.harness/logs/relay-audit.jsonl` с ротацией; retention ≥ 30 дней. В `docs/design/client-cli.md` добавить пункт «локальный аудит исполнения». Серверный лог фиксирует `register`/`disconnect`/`tool.call-sent` с `callId`.

---

## 2. Метрики Micrometer

### Что уже в доках

- Прямых упоминаний метрик нет. Косвенно измеримые механизмы: `execution-model.md:34` (bulkhead «дефолт 8», backoff 429/5xx), `execution-model.md:79` (лимиты контейнеров, «потолок одновременно живых контейнеров — конфиг (дефолт 50)», idle-вытеснение 30 мин), `execution-model.md:13` (POLL раз в ~5 сек), `data-model.md:231` (heartbeat 10 с / мёртв 30 с), `data-model.md:242-244` (`idempotency_key` TTL 24 ч, чистка пачками ≤1000), `api-contracts.md:75/143` (SSE/WS ping 15 с).
- `docs/temp/review/aspect2-sre-deepseek.md:234` (рекомендация) — «метрики/алерты: возраст самого старого лока, число зависших WAIT_TASKS, число сирот-задач без сессии, число дублей late-результатов, очередь POLL». В дизайн **не внесено**.

### Находки

#### M-1. Micrometer/Actuator отсутствуют в стеке → **GAP**
`architecture.md:47-52` перечисляет стек и автоконфигурации, `spring-boot-starter-actuator` и Micrometer там нет; `roadmap.md` M1 его тоже не содержит. Без этого невозможны ни метрики, ни health-группы (§3), ни алерты (§7).
**Предложение:**
> Добавить в pom M1: `spring-boot-starter-actuator`, `micrometer-registry-prometheus` (pull-модель; эндпоинт `/actuator/prometheus` только на management-порту, недоступен публично). Management-порт отдельный: `management.server.port=8081`, `management.endpoints.web.exposure.include=health,info,metrics,prometheus`. Публичный `/actuator/**` запрещён (кроме проб, §3). Метрики — префикс `harness_*`; правила именования: `<area>_<entity>_<unit>`, единицы в имени (`_seconds`, `_bytes`, `_total` — counter).

#### M-2. Метрики очереди/eligibility → **GAP**
**Предложение:**
> | Метрика Prometheus | Тип | Метки | Смысл |
> |---|---|---|---|
> | `harness_eligible_sessions` | gauge (callback) | — | сессий, удовлетворяющих предикату eligibility (`data-model.md:196`) |
> | `harness_eligible_oldest_age_seconds` | gauge (callback) | — | `now - min(last_activity_at)` по eligible — «возраст самой старой работы» |
> | `harness_poll_duration_seconds` | timer | `result=ok\|error\|lock_busy` | длительность POLL-джобы |
> | `harness_poll_last_success_timestamp_seconds` | gauge | — | dead-man (см. §7) |
> | `harness_poll_claims_total` | counter | `source=POLL\|EVENT\|API` | сколько Turn'ов поднято каждым контуром (соотношение EVENT/POLL — индикатор потерь) |
> | `harness_poll_reconcile_total` | counter | `kind=wait_tasks\|wait_webhook_timeout\|agent_bootstrap\|orphan_container\|idempotency_cleanup\|cancel_recovery`, `result` | эффекты task-level скана и джоб |

#### M-3. Метрики Turn → **GAP**
**Предложение:**
> | Метрика | Тип | Метки |
> |---|---|---|
> | `harness_turns_total` | counter | `outcome=COMPLETED\|FAILED\|CANCELLED`, `terminal=PARKED_ASYNC\|PARKED_CLIENT\|done`, `trigger=EVENT\|POLL\|API` |
> | `harness_turn_active` | gauge | — |
> | `harness_turn_duration_seconds` | timer | `outcome` |
> | `harness_turn_rounds` | distribution summary | — |
> | `harness_turn_context_tokens` | distribution summary | — |
> | `harness_compactions_total` | counter | `reason=auto\|command` |
> | `harness_llm_bulkhead_in_use` / `harness_llm_bulkhead_queued` | gauge | — |
> | `harness_lock_oldest_age_seconds` | gauge (callback) | — |
> | `harness_locks_stolen_total` / `harness_fencing_aborts_total` / `harness_heartbeat_failures_total` | counter | — |
> Отдельно: `harness_turn_failures_total{error_class}` — для отличия транзиентной ошибки провайдера от дефекта (закрывает пробел `aspect3-perf-glm.md` про неотличимость).

#### M-4. Метрики LLM (токены, TTFT, retry/429, bulkhead) → **GAP**
**Предложение:**
> | Метрика | Тип | Метки |
> |---|---|---|
> | `harness_llm_first_token_seconds` | timer | `model` — **латентность wake→первый токен** (TTFT; ключевой UX-показатель) |
> | `harness_llm_stream_duration_seconds` | timer | `model`, `outcome` |
> | `harness_llm_tokens_total` | counter | `model`, `type=prompt\|completion` |
> | `harness_llm_requests_total` | counter | `model`, `outcome=ok\|error`, `http_status` (класс) |
> | `harness_llm_retries_total` | counter | `reason=429\|5xx\|timeout` |
> | `harness_llm_parked_total` | counter | `reason=retry_exhausted` — парковка после backoff (`execution-model.md:34`) |
> Разбивка по `credentials` **не** метрика (высокая кардинальность/безопасность), а лог-поле.

#### M-5. Метрики контейнеров → **GAP**
**Предложение:**
> | Метрика | Тип | Метки |
> |---|---|---|
> | `harness_containers_live` | gauge (callback) | `kind=session\|task` |
> | `harness_containers_limit` | gauge | — (из конфига, дефолт 50) |
> | `harness_container_operations_total` | counter | `op=create\|start\|stop\|remove`, `result` |
> | `harness_container_create_failures_total` | counter | `reason=pull\|mount\|ceiling\|docker` |
> | `harness_container_evictions_total` | counter | `reason=idle` |
> | `harness_container_orphans_removed_total` | counter | — |
> | `harness_docker_up` | gauge | — (1/0; дублируется health, §3) |
> | `harness_image_pull_backoff_total` | counter | — |

#### M-6. Метрики POLL/reconcile/чисток → **GAP**
Уже частично в M-2 (`harness_poll_reconcile_total`). Дополнительно:
> | Метрика | Тип | Метки |
> |---|---|---|
> | `harness_idempotency_rows` | gauge (callback) | — |
> | `harness_idempotency_deleted_total` | counter | — |
> | `harness_idempotency_replays_total` / `harness_idempotency_conflicts_total` | counter | — |
> | `harness_instance_heartbeat_failures_total` | counter | — |
> | `harness_stale_locks_recovered_total` | counter | — |

#### M-7. Метрики SSE/relay → **GAP**
> | Метрика | Тип | Метки |
> |---|---|---|
> | `harness_sse_subscriptions` | gauge | `stream=session\|task` |
> | `harness_sse_connects_total` | counter | `stream` |
> | `harness_sse_disconnects_total` | counter | `stream`, `reason=client\|timeout\|server` |
> | `harness_relay_connections` | gauge | — |
> | `harness_relay_registrations_total` | counter | `result=ok\|occupied\|forbidden` |
> Порог внимания: `sse_subscriptions` к числу коннектов сервера (прошлое ревью оценивало 100–150 коннектов, `aspect3-perf-mercury.md:34`).

#### M-8. Метрики диска/workspace → **GAP**
> | Метрика | Тип | Метки |
> |---|---|---|
> | `harness_workspace_bytes` | gauge | `scope=free\|task` |
> | `harness_volume_free_bytes` / `harness_volume_capacity_bytes` | gauge | `volume=postgres\|workspace\|docker` |
> | `harness_workspace_quota_exceeded_total` | counter | — |
> Собирается внешним node-exporter'ом по томам **или** периодической джобой (`du`), т.к. JVM не должен рекурсивно считать каталоги.

#### M-9. Сквозной набор из коробки → **GAP**
> Не изобретать: `http_server_requests_seconds` (Boot), `hikaricp_connections_active/pending`, `jvm_memory_*`, `jvm_gc_*`, `process_cpu_usage`, и Boot 4-специфичные `jvm_threads_virtual_*` (`JdkVirtualThreadSchedulerMetrics`) + `jdk_virtual_thread_pinned` (`JdkVirtualThreadPinnedMetrics`, упоминалось в `aspect1-stack-minimax.md:264`). Pinning — обязательная метрика, т.к. JDBC/`synchronized` могут пинить виртуальные потоки.

#### M-10. Не зафиксирована cardinality-дисциплина → **RISK**
Метки `sessionId`/`taskId`/`userId` напрашиваются (turn-метрики), но при 50+ пользователях и тысячах сессий это взорвёт TSDB.
**Предложение:**
> Инвариант: **запрещены метки с неограниченной кардинальностью** (`session.id`, `task.id`, `user.id`, `callId`, `model_id` из БД). Разрешённые метки — перечисления и ограниченные множества (`model` — из каталога `llm_model`, десятки). Высококардинальные измерения — только в логах/трейсах. Ревью метрик — часть code review; добавить ArchUnit/тест «запрещённые Tag keys».

#### M-11. Мультиинстансные gauge'и вводят в заблуждение → **RISK**
Допускается несколько инстансов (`execution-model.md:108`), но `harness_containers_live` — процессный, а `harness_eligible_sessions` — глобальный (БД). На дашборде они смешаются без правила агрегации.
**Предложение:**
> Каждая метрика получает `instance` label (Micrometer `management.metrics.tags.instance=${HARNESS_INSTANCE_ID}`). Правило дашборда: `eligible_sessions/idempotency_rows/volume_*` — агрегировать `max`/`sum` по назначению явно (это БД-состояние, не сумма процессов); `containers_live/turn_active` — `sum`; по умолчанию **не** суммировать gauge'и вслепую. Записать в `observability-ops.md` таблицу «как агрегировать».

---

## 3. Health / readiness

### Что уже в доках

- Ничего о health/probes. Косвенно: `architecture.md:47` — порядок инициализации «**пул → preliquibase → liquibase → JPA**»; `architecture.md:48` — внешний Keycloak (OIDC); `architecture.md:50` — docker.sock + helper-образ на VM; `roadmap.md:8` (M1) — bootstrap-сид.
- `security-multitenancy.md:64` — «Compromise SSO — вне контура системы».

### Находки

#### H-1. Health-группы не определены → **GAP**
**Предложение:**
> Actuator health-groups (Boot 4):
> - `/actuator/health/liveness` (группа `liveness`): только `livenessState` + JVM/thread-deadlock. Никогда не включает внешние зависимости — иначе падение Keycloak/докера перезапустит здоровый процесс.
> - `/actuator/health/readiness` (группа `readiness`): `readinessState`, `db` (доступность БД), `liquibase` (миграции применены), `dockerExec` (docker.sock + helper-образ), `keycloak` (условно, см. H-4).
> - `/actuator/health/startup` — см. H-3.
> Конфигурация: `management.endpoint.health.group.readiness.include=readinessState,db,liquibase,dockerExec,keycloak`; `management.endpoint.health.probes.add-additional-paths=true` (`/livez`, `/readyz`). Management-порт отдельный (§2, M-1) и не публикуется наружу. Admission-контроль: пока `readiness != UP` — новые TRY_START не принимаются (инстанс не берёт работу), POLL-джоба не стартует.

#### H-2. Явные условия готовности (Liquibase/Keycloak/docker) → **GAP**
**Предложение:**
> | Индикатор | UP когда | DOWN когда | Влияние |
> |---|---|---|---|
> | `db` | `SELECT 1` в пределах 1 с | нет коннекта | readiness DOWN; POLL не работает |
> | `liquibase` | changelog применён (`DATABASECHANGELOG` без pending) и **preliquibase завершён** | pending/ошибка | readiness DOWN; API не принимает трафик |
> | `dockerExec` | `docker.ping()` успешен **и** helper-образ присутствует локально (`inspectImage`) | daemon недоступен / образа нет | readiness DOWN (нативные инструменты неработоспособны) |
> | `keycloak` | JWKS-кэш свежий или OIDC-discovery отвечает | см. H-4 | readiness DOWN только при холодном кэше |

#### H-3. Нет startup-probe под медленные миграции → **GAP**
Liquibase+Preliquibase на больших changelog'ах могут идти минуты; при этом liveness, настроенный как у всех, убьёт стартующий контейнер.
**Предложение:**
> `management.endpoint.health.group.startup.include=readinessState`; chart/пробинг: startup-проба с `failureThreshold=30, periodSeconds=5` (≤150 с), liveness — `initialDelaySeconds` после старта, `periodSeconds=10`. В docker-compose VM (single instance) эти пробы задаются healthcheck'ом контейнера оркестратора.

#### H-4. Keycloak в readiness — риск каскада → **RISK**
Если Keycloak недостижим, все Bearer-токены нельзя валидировать → API отдаёт 401 на всё. При этом `security-multitenancy.md:64` выносит SSO за контур. Включение Keycloak в readiness «жёстко» означает: падение Keycloak → readiness DOWN → оркестратор выпадает из строя целиком, хотя уже выпущенные токены можно валидировать по **закэшированному** JWKS.
**Предложение:**
> Keycloak-индикатор — **деградируемый**: UP, пока валидация возможна по закэшированному JWKS (окно кэша TTL, например 6 ч) или discovery отвечает; DOWN, только если кэш пуст/просрочен **и** Keycloak недоступен. Индикатор переводит readiness в `OUT_OF_SERVICE` (а не Liveness DOWN). Новые логины в этот период недоступны — это задокументированное поведение, не «сервис лёг». Метрика `harness_keycloak_jwks_age_seconds`.

#### H-5. Поведение при недоступном docker.sock не определено → **RISK**
`security-multitenancy.md:63` описывает docker.sock как root-эквивалент и компенсации, но не говорит, что делать, если **сам daemon** недоступен: существующие контейнеры продолжают жить/работать, а новые Turn'ы стартовать не могут. Если readiness не учитывает docker, система принимает Turn'ы и падает на первом инструменте; если учитывает жёстко — POLL и синхронные операции встают, хотя БД-часть жива.
**Предложение:**
> Явно: docker недоступен → `dockerExec=DOWN` → readiness DOWN (новые Turn'ы не берём), POLL-джоба продолжает **только** БД-сканы (синтетические `LOST` для незакрытых `TOOL_CALL`), Turn'ы в части LLM-рассуждения, не требующие контейнера, не начинаются. Переход в DOWN и обратно — событие `WARN` в security/ops-лог + метрика `harness_docker_up`. Тумблер `harness.health.docker.critical` (default `true`; `false` — degraded-режим для отладки на хосте без докера).

---

## 4. Трейсинг

### Что уже в доках

- Ничего. `glossary.md:67` вводит `wake_source`, `data-model.md:231` — heartbeat-реестр; это точки, которые трейс обязан связать.

### Находки

#### T-1. Решения о трейсинге нет; рекомендация для MVP → **GAP**
**Предложение (ADR D-37, вставка в `decisions.md`):**
> **D-37. Наблюдаемость MVP: корреляционные структурные логи обязательны; OpenTelemetry — опциональный слой, включаемый конфигом.**
> Альтернативы: (а) сразу полный OTel-коллектор как обязательная зависимость; (б) только текстовые логи.
> Почему так: система асинхронна и пересекает границы (HTTP → БД-очередь → POLL → виртуальный поток → Docker → поздний `TOOL_RESULT`), поэтому **корреляция нужна с первого дня** — но как контракт логов (`turn.id`, `trace.id` в MDC), не как обязательная инфраструктура. Корпоративный стенд может не иметь OTel-коллектора; навязанная зависимость заблокирует M1. OTel добавляется одним флагом (`HARNESS_TRACING_ENABLED=true` + `OTEL_EXPORTER_OTLP_ENDPOINT`) без изменения кода, т.к. trace/span API уже проставлен (см. T-3).
> Следствие для M1: `micrometer-tracing` + `micrometer-tracing-bridge-otel` + `spring-boot-starter-opentelemetry` в pom **опционально** (профиль/флаг), `management.tracing.probability=0.1` в проде, `1.0` в staging; `management.tracing.mdc.enabled=true` (traceId/spanId в MDC → попадают в ECS-лог, §1.1).

#### T-2. Разрывы трейс-контекста на асинхронных границах → **RISK**
Наивный `@Observed` на `TurnManager` не соберёт цепочку: Turn может быть поднят POLL-джобой, дождаться позднего результата из фонового потока, а инструмент исполнить в контейнере. Без ручного линка span'ы окажутся сиротами, и трейс будет хуже логов.
**Предложение:**
> Обязательные ручные линки (даже при выключенном экспортёре — API вызывается всегда): (1) `tryStart` открывает корневой span Turn'а и кладёт `turn.id`; (2) фоновые async-инструменты и релей — `Span.makeCurrent`-контекст **сериализуется** вместе с задачей и восстанавливается при докатке `TOOL_RESULT` (тот же `turn.id` из БД-связки `callId→session`); (3) POLL-wake создаёт **root-span** (нет parent'а — это признаётся явно); (4) docker-операции — child-span внутри Turn'а; (5) JDBC-инструментирование Boot включается автоматически. Правило: **`turn.id` — единственная нить, span-иерархия вторична** (она может рваться, `turn.id` — нет).

#### T-3. Trace↔log контракт → **GAP**
**Предложение:**
> `management.tracing.mdc.enabled=true` → в каждую лог-строку ECS попадают `trace.id`, `span.id`. Инвариант «у любого WARN/ERROR внутри Turn'а есть `turn.id` и `session.id`». Для разбора: поиск по `turn.id` даёт полный лог Turn'а независимо от наличия трейс-бэкенда.

---

## 5. Деплой-манифест VM

### Что уже в доках

- `architecture.md:50` — «Деплой: оркестратор — контейнер на выделенной VM, `/var/run/docker.sock` смонтирован (docker-java создаёт helper-контейнеры — D-30); helper-образ собирается из Dockerfile в репозитории и присутствует на VM локально (pull — только backoff-обновление).»
- `architecture.md:51` — bootstrap первые `llm_credentials`/`agent` — админ-команда CLI из env.
- `execution-model.md:106-110` — graceful shutdown 30 с дренаж.
- `data-model.md:242-244` — `idempotency_key` чистка джобой; `data-model.md:210` — retention сессий «отдельное решение».

### Находки

#### D-1. Нет деплой-манифеста → **GAP**
**Предложение (`deploy/docker-compose.vm.yml`, вставка):**
> ```yaml
> name: spring-harness
> services:
>   postgres:
>     image: postgres:17.6-alpine
>     restart: unless-stopped
>     command:
>       - "-c"; - "archive_mode=on"
>       - "-c"; - "archive_command=test ! -f /wal-archive/%f && cp %p /wal-archive/%f"
>     environment:
>       POSTGRES_DB: ${HARNESS_DB_NAME:-harness}
>       POSTGRES_USER: ${HARNESS_DB_USER:-harness}
>       POSTGRES_PASSWORD: ${HARNESS_DB_PASSWORD:?required}
>     volumes:
>       - pg-data:/var/lib/postgresql/data
>       - pg-wal:/wal-archive
>     healthcheck:
>       test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB}"]
>       interval: 5s
>       timeout: 3s
>       retries: 24
>     logging: &log-opts
>       driver: json-file
>       options: { max-size: "50m", max-file: "5" }
>   keycloak:
>     image: quay.io/keycloak/keycloak:26.3
>     command: ["start", "--optimized", "--http-enabled=true"]
>     environment:
>       KC_DB: postgres
>       KC_DB_URL: jdbc:postgresql://postgres:5432/${HARNESS_KEYCLOAK_DB:-keycloak}
>       KC_DB_USERNAME: ${HARNESS_DB_USER:-harness}
>       KC_DB_PASSWORD: ${HARNESS_DB_PASSWORD:?required}
>       KC_HOSTNAME: ${HARNESS_KC_HOSTNAME:?required}
>       KC_BOOTSTRAP_ADMIN_USERNAME: ${HARNESS_KC_ADMIN:?required}
>       KC_BOOTSTRAP_ADMIN_PASSWORD: ${HARNESS_KC_ADMIN_PASSWORD:?required}
>     depends_on:
>       postgres: { condition: service_healthy }
>     logging: *log-opts
>   helper-image:
>     image: ${HARNESS_HELPER_IMAGE:-harness-helper:dev}
>     build: { context: ../helper }
>     profiles: ["build"]           # только сборка: docker compose --profile build build
>   orchestrator:
>     image: ${HARNESS_ORCHESTRATOR_IMAGE:-harness-orchestrator:dev}
>     build: { context: .. }
>     restart: unless-stopped
>     env_file: [.env]
>     depends_on:
>       postgres: { condition: service_healthy }
>       keycloak: { condition: service_started }
>     ports: ["${HARNESS_HTTP_PORT:-8080}:8080"]
>     volumes:
>       - /var/run/docker.sock:/var/run/docker.sock
>       - workspace:/var/lib/harness/workspaces
>       - dumps:/var/lib/harness/dumps
>     healthcheck:
>       test: ["CMD-SHELL", "wget -qO- http://localhost:8081/actuator/health/readiness | grep -q '\"status\":\"UP\"'"]
>       interval: 10s
>       timeout: 5s
>       retries: 12
>       start_period: 120s
>     logging: *log-opts
> volumes: { pg-data: {}, pg-wal: {}, workspace: {}, dumps: {} }
> ```
> Порядок: `docker compose --profile build build` (helper-образ) → `docker compose up -d postgres` → `up -d keycloak` (одноразовый bootstrap realm) → `up -d orchestrator` → bootstrap-CLI (`architecture.md:51`) → smoke. Dockerfile оркестратора обязан содержать `wget`/`curl` для healthcheck (иначе заменить пробу на spawn-Java-проверку).

#### D-2. Порядок старта и healthchecks → **GAP**
Выше — `depends_on … service_healthy` для Postgres и readiness-gate приложения (H-1). Отдельно: Keycloak не гейтит старт (H-4, деградируемая зависимость) — только `service_started`.
**Предложение:** зафиксировать в `observability-ops.md` таблицу «зависимость → гейт → поведение при недоступности» (postgres — hard gate; keycloak — soft; docker — readiness soft/hard по тумблеру H-5).

#### D-3. Конкурентные миграции при rolling deploy → **RISK**
Дизайн допускает несколько инстансов (`execution-model.md:108`), значит возможен старт второго оркестратора при живом первом. Liquibase имеет собственную `DATABASECHANGELOGLOCK`, но: (а) Preliquibase может выполняться до неё; (б) поведение при ожидании лока не задано (по умолчанию Liquibase падает при занятом локе); (в) `architecture.md:47` описывает порядок, но не конкурентность.
**Предложение:**
> Инвариант: **миграции выполняет только инстанс, взявший распределённый лок**; остальные ждут (или стартуют read-only с readiness DOWN до подтверждения версии схемы). Механика: Preliquibase-скрипты идемпотентны (`CREATE TABLE IF NOT EXISTS`), Liquibase — `lockWaitTime`/`changeLogLock` с разумным ожиданием и **fail-fast** при таймауте (readiness так и не станет UP). Перед приёмом трафика readiness проверяет «нет pending changeset'ов» (`liquibase` индикатор, H-2). Для одноразового VM-деплоя — тот же путь, без спец-режима.

#### D-4. Бэкапы Postgres не заведены → **GAP**
Postgres локальный, `session_message` — бессрочный append-only аудит и одновременно очередь (`execution-model.md:17`). Резервных копий в дизайне нет вообще.
**Предложение:**
> - **Base backup:** ежедневно `pg_dump -Fc` → внешнее хранилище (NFS/S3), retention 14 дней ежедневных + 8 недельных.
> - **WAL/PITR:** `archive_mode=on` (см. compose), retention WAL ≥ 7 дней; RPO ≤ 5 мин, RTO ≤ 1 ч (цель).
> - **Restore drill:** ежемесячно восстановление из backup на тестовый стенд + smoke (миграции + один Turn) — без drill бэкап не считается существующим.
> - `pg_dump` **не** запускать через `docker exec` из оркестратора; отдельный cron/systemd на VM, логируемый.
> Артефакт: `deploy/backup/` + runbook.

#### D-5. Workspace-том: retention/бэкап/watermark → **GAP**
`data-model.md:210` откладывает retention сессий, `execution-model.md:79` — квота 5 GB на workspace, но политики очистки томов и контроля заполнения нет (это же отмечалось в `aspect3-perf-mercury.md:54`).
**Предложение:**
> Retention: FREE auto-workspace — удалять через N дней после архивации сессии; task-workspace — через N дней после терминала задачи (симметрично, оба N — конфиг, дефолт 30). GC-ветка POLL (reconcile) удаляет каталоги без живой сессии/терминальной задачи старше N. Бэкапить **не** весь том, а только артефакты, помеченные как результат (иначе объём неограничен). Disk watermark: 85% → алерт (§7), 95% → блокировать создание новых workspace и переводить readiness в `OUT_OF_SERVICE`.

#### D-6. Ротация логов → **GAP**
См. §1.6. В compose задаётся `logging.driver=json-file` c `max-size/max-file` (вставка в D-1).

#### D-7. Секреты в compose → **GAP**
`.env` с `HARNESS_DB_PASSWORD`, `HARNESS_KC_ADMIN_PASSWORD`, `HARNESS_LLM_ENC_KEY`, `HARNESS_HMAC_SECRET` — где хранится, кто читает, какие права.
**Предложение:**
> `.env` (chmod 600, владелец деплой-пользователя, вне git) для dev/малой VM; для прод-контура — docker secrets / внешний secret-store (эволюция). Обязательные секреты: `HARNESS_DB_PASSWORD`, `HARNESS_LLM_ENC_KEY` (ключ шифрования `api_key_encrypted`), `HARNESS_HMAC_SECRET` (`server_secret` вебхуков), `HARNESS_KC_ADMIN_PASSWORD`. `docker compose config` не должен печатать значения (`--quiet`); запрещено логировать `env_file` при старте. `.dockerignore`/`.gitignore` на `.env`.

#### D-8. Версия/присутствие helper-образа → **RISK**
`architecture.md:50` полагается на локальное присутствие образа; pull — backoff. Если тег в compose и `harness.docker.helper-image` в конфиге разошлись, новые сессии падают на `inspectImage`.
**Предложение:**
> Единый источник: `HARNESS_HELPER_IMAGE` передаётся и в compose build, и в env оркестратора. Readiness `dockerExec` проверяет наличие именно этого тега (H-2). Тег — иммутабельный (git-sha/дата), не `latest`. При отсутствии образа: readiness DOWN + `harness_container_create_failures_total{reason="pull"}`; существующие сессии не трогаются.

#### D-9. docker.sock = root → **RISK**
`security-multitenancy.md:63` фиксирует угрозу, «опционально docker-socket-proxy». Для MVP решение «опционально» означает, что по умолчанию — root-эквивалент.
**Предложение:**
> Для MVP принять явно с компенсациями и **записать в runbook**: выделенная VM без других нагрузок, сеть VM изолирована, ssh ограничен, обновления ОС; docker-socket-proxy вынести в `roadmap.md` как обязательный пункт эволюции (не «опционально»), с критерием «до выхода за пределы одной доверенной VM».

#### D-10. Единственный Postgres без HA → **RISK**
`architecture.md:48` — «PostgreSQL + Liquibase + Preliquibase» локально; `D-18` — мультиарендность с первого дня. SPOF не зафиксирован как принятый риск.
**Предложение:**
> Явно записать принятие: MVP — single Postgres, восстановление через D-4 (RPO ≤ 5 мин). В `roadmap.md` «Эволюция» — реплика/управляемый PG, критерий — рост нагрузки/требование RPO.

---

## 6. Конфигурация (12-factor)

### Что уже в доках

- `architecture.md:46` — «Spring Boot 4.1.1, Java 25 (виртуальные потоки для Turn'ов)».
- `architecture.md:47` — «Автоконфигурации **отключены** (`application.yml`)… фактический порядок инициализации: **пул → preliquibase → liquibase → JPA**».
- `architecture.md:49` — «регрессия Spring AI 2.0.1 (#6915) — **в каждом `OpenAiChatOptions` задавать `.timeout()`/`.maxRetries()` явно**».
- `architecture.md:51` — bootstrap CLI «читает env: base_url/key/model».

### Находки

#### C-1. Нет каталога env-переменных и правила yml-vs-env → **GAP**
**Предложение:**
> **Правило.** `application.yml` — структура, дефолты, тюнинги, которые одинаковы на всех стендах (таймауты, лимиты, имена эндпоинтов актуаторов, structured-logging). Env — всё, что различается по стендам и/или секретно. В yml **только** `${VAR:default}` или `${VAR}` для обязательных; никаких прод-значений в репозитории.
> Каталог (фрагмент):
> | Env | Default | Назначение |
> |---|---|---|
> | `HARNESS_INSTANCE_ID` | hostname | `instance.id`, `locked_by`, метка метрик |
> | `HARNESS_DB_URL/USER/PASSWORD` | — | datasource (обязательны в проде) |
> | `HARNESS_OIDC_ISSUER_URI` | — | Keycloak realm issuer |
> | `HARNESS_LLM_ENC_KEY` | — | ключ шифрования `codex`/`api_key` |
> | `HARNESS_HMAC_SECRET` | — | `server_secret` вебхуков |
> | `HARNESS_DOCKER_HELPER_IMAGE` | — | тег helper-образа |
> | `HARNESS_DOCKER_CONTAINER_CEILING` | 50 | потолок живых контейнеров |
> | `HARNESS_LLM_BULKHEAD` | 8 | параллельные стримы (D-35) |
> | `HARNESS_LOCK_TTL_SECONDS` | 60 | TTL лока Turn'а |
> | `HARNESS_SHUTDOWN_DRAIN_SECONDS` | 30 | graceful drain |
> | `HARNESS_LOG_LLM_CONTENT` | false | L-5 |
> | `HARNESS_TRACING_ENABLED` | false | T-1 |
> | `HARNESS_HTTP_PORT` / `HARNESS_MGMT_PORT` | 8080 / 8081 | порты |

#### C-2. `.env` для дева → **GAP**
**Предложение:** `deploy/.env.example` (без секретов, с комментариями) в git; `.env` в `.gitignore`; профиль `dev` — только для локального запуска (человекочитаемые логи, tracing 1.0, helper-образ local, Postgres localhost). `SPRING_PROFILES_ACTIVE=dev` — не более.

#### C-3. JVM-флаги не заданы → **GAP**
**Предложение (`JAVA_TOOL_OPTIONS` в Dockerfile/compose):**
> ```
> -XX:MaxRAMPercentage=70
> -XX:+ExitOnOutOfMemoryError
> -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/lib/harness/dumps
> -Xlog:gc*,safepoint:stdout:time,uptime,level,tags
> -XX:+UseZGC
> ```
> Виртуальные потоки: `spring.threads.virtual.enabled=true` (Boot 4). `-Djdk.tracePinnedThreads` — **не** в прод (накладные расходы), использовать JFR-событие `jdk.VirtualThreadPinned` + метрику (§3, M-9). `ActiveProcessorCount` не задавать — ZGC/пул считаются от cgroup-лимитов контейнера.

#### C-4. Heap под MB-payload рендер контекста → **RISK**
`execution-model.md:85` — рендер «полные payload только видимых» на каждый раунд; payload'ы инструментов могут быть мегабайтными, а параллельных Turn'ов десятки. Память на Turn не оценена.
**Предложение:**
> Рекомендуемый старт: контейнер 4 GB, `MaxRAMPercentage=70` (≈2.8 GB heap) на 8–16 одновременных Turn'ов; рендер — потоковый до уровня JSON-массива (не держать несколько копий всего контекста), лимит на размер отдельного payload с усечением и метрикой `harness_render_bytes`. Нагрузочный тест M1: N параллельных Turn'ов с 2 MB tool-output — без OOM и с ростом heap < линейного ×2. Точное значение — по результатам теста, зафиксировать в `observability-ops.md`.

#### C-5. Нет fail-fast валидации конфигурации → **GAP**
**Предложение:**
> Все конфиги — `@ConfigurationProperties` + `@Validated` (`@NotBlank`, `@Positive`, `@Min`); отсутствие обязательного env (DB, OIDC, HMAC, LLM-key) → отказ старта с понятным сообщением (не NULL-падение в рантайме). Проверки диапазонов: `bulkhead ≥ 1`, `containerCeiling ≥ bulkhead`, `lockTtl > shutdownDrain`. Тест `ApplicationContextRunner` на каждое обязательное свойство.

#### C-6. Ротация ключа шифрования `api_key` → **GAP**
`security-multitenancy.md:37` — `api_key` шифруется ключом из env; ротация не описана. Смена `HARNESS_LLM_ENC_KEY` без перешифровки сделает существующие `llm_credentials` нечитаемыми.
**Предложение:**
> Формат хранения: `v1:<keyId>:<ciphertext>` (keyId = идентификатор ключа). Поддержать два активных ключа (`HARNESS_LLM_ENC_KEY`, `HARNESS_LLM_ENC_KEY_PREVIOUS`) — чтение старым, запись новым; админ-команда `reencrypt-credentials` перешифровывает все строки и репортит остаток. Ротация `HARNESS_HMAC_SECRET` — отдельная эволюция (D-26), но должна быть заложена в формат capability-токена (версия в токене).

---

## 7. Алертинг-минимум

### Что уже в доках

- Нет. `docs/temp/review/aspect2-sre-deepseek.md:234` рекомендовал набор, в дизайн не перенесён. Пороговых значений и владельцев алертов нет.

### Находки

#### A-1. Каталог правил отсутствует → **GAP**
**Предложение (вставка в `observability-ops.md` §7, PromQL; метрики — из §2):**
> | # | Правило | Условие | Severity | Действие |
> |---|---|---|---|---|
> | AL-1 | Eligible-очередь растёт | `harness_eligible_oldest_age_seconds > 120` for 5m | warning | смотреть POLL/локи |
> | | | `harness_eligible_oldest_age_seconds > 600` for 5m | critical | стр. дежурному |
> | AL-2 | Ошибки/лимиты LLM | `sum(rate(harness_llm_requests_total{outcome="error"}[5m])) / sum(rate(harness_llm_requests_total[5m])) > 0.1` for 10m **или** `increase(harness_llm_retries_total{reason="429"}[10m]) > 50` | warning→critical | проверить провайдера/credentials; Turn'ы паркуются (D-35) |
> | AL-3 | Потолок контейнеров | `harness_containers_live / harness_containers_limit > 0.9` for 10m **или** `increase(harness_container_create_failures_total[10m]) > 0` **или** `harness_docker_up == 0` for 1m | critical | не создаются workspace-инструменты |
> | AL-4 | Диск workspace/PG | `1 - harness_volume_free_bytes{volume=~"workspace\|postgres"} / harness_volume_capacity_bytes{volume=~"workspace\|postgres"} > 0.85` for 15m | warning (0.95 — critical) | GC workspace, ротация WAL/dump |
> | AL-5 | Dead-man POLL/инстанс | `time() - harness_poll_last_success_timestamp_seconds > 30` for 2m **или** отсутствует heartbeat инстанса > 45 с | critical | POLL не работает — работа стоит |

#### A-2. Нет владельцев/маршрутизации алертов → **GAP**
**Предложение:** один канал (например, `#harness-alerts` + дежурный по расписанию) для MVP; severity-critical → пейдж; каждый алерт обязан иметь ссылку на runbook-секцию (`observability-ops.md` §8). Алерты без действия запрещены (принцип «no actionable → no alert»).

#### A-3. Нет dead-man'а на молчание системы → **RISK**
Если POLL молча умер (исключение, залипший лок джобы), метрики «очереди» будут показывать ноль, и мониторинг будет зелёным при полностью вставшей системе. AL-5 обязателен именно как dead-man (timestamp, а не value).
**Предложение:** `harness_poll_last_success_timestamp_seconds` и `harness_instance_heartbeat_timestamp_seconds` обновляются в конце каждой успешной итерации; missing = critical. External watchdog (Healthchecks.io-подобный) на push — если есть корпоративный.

#### A-4. Нет SLO/error budget → **RISK**
Пороги выше — эвристика. Без SLO невозможно отличить «нормально» от «деградация» и настроить бюджет.
**Предложение:** зафиксировать MVP-SLO: (1) доступность API 99.5%/мес; (2) доля Turn'ов с исходом не-FAILED ≥ 95% (без учета пользовательских ошибок); (3) p95 TTFT ≤ 10 с; (4) p95 очередь-латентность (eligible→начало Turn) ≤ 15 с. Error budget — по ним; пересмотр после первых недель эксплуатации.

---

## 8. Топ-3

1. **M-1: Micrometer/Actuator отсутствуют в стеке.** Это корень остальных пробелов: §2 (метрики), §3 (health-группы) и §7 (алерты) нереализуемы без стартера и management-порта, а эксплуатировать контейнеры/POLL/локи/bulkhead «вслепую» нельзя. Фикс дешёвый (одна зависимость + конфиг) и разблокирует 20 находок из §2/§3/§7.
2. **D-4 (+D-3): нет резервных копий Postgres и не зафиксирована безопасность конкурентных миграций.** Единственный инстанс хранит всю очередь, append-only аудит и ревизии; без pg_dump/WAL и без проверенного restore drill потеря данных необратима. Отдельно — rolling deploy может стартовать второй инстанс с недомигрированной схемой.
3. **L-2 (+T-1/T-2): нет контракта корреляции (`turn.id`/`trace.id` в MDC).** Turn пересекает HTTP → БД-очередь → POLL → виртуальный поток → Docker → поздний `TOOL_RESULT`; без единого идентификатора ни логи, ни (потенциальные) трейсы не соберут Turn, и разбор инцидента превращается в гадание. Ввести `turnId` (in-memory, не персистится — совместимо с D-04) в M1, до появления async/spawn-сценариев.

---

## 9. Что дизайн делает хорошо (чтобы не «лечить» зря)

- `security-multitenancy.md:42` — правило «секреты не логируются» уже есть; нужна лишь полнота и механизм маскирования (L-4), а не новая политика.
- Append-only журналы (`session_message`, `task_transition_history`) — готовая база доменного аудита; observability не нужно дублировать «журнал действий» (L-3, L-7 дополняют, не заменяют).
- `instance`-реестр с heartbeat (`data-model.md:231`) и `wake_source` (`glossary.md:67`) — уже идеальные якоря для метрик и dead-man'а (M-2, M-6); ничего нового вводить не надо.
- Единый management-порт и Boot 4 native ECS/health-groups/Micrometer-tracing дают observability почти «декларативно» — основная работа не в коде, а в фиксации контрактов и конфигов.
- Graceful shutdown 30 с (`execution-model.md:106-110`), TTL-чистка idempotency (`data-model.md:244`), idle-вытеснение контейнеров (`execution-model.md:79`) — уже измеримые операции с готовыми точками для счётчиков.

## 10. Сквозные рекомендации

1. Завести `docs/design/observability-ops.md` (кодифицировать §1–§7) и ADR **D-37** (logs-first / OTel opt-in) и **D-38** (Micrometer + Actuator в M1).
2. Добавить в `roadmap.md` M1 явные criterion-пункты: `/actuator/health/readiness`, `/actuator/prometheus`, структурированные логи с `turn.id`, `turnId` в MDC.
3. Ввести инвариант «любой WARN/ERROR внутри Turn'а содержит `session.id` и `turn.id`» + тест на маскирование секретов (L-4).
4. Держать observability-контракт под тем же ревью, что OpenAPI/Java-контракты (D-22): набор метрик и MDC-полей — версионируемый контракт, не «как получится».
5. Проверить на реальном стенде: structured-logging ECS + MDC в Boot 4.1, health-group с custom indicator, `spring-boot-starter-opentelemetry` — версии и поведение (в рамках M1 openspec-change).

## 11. Кросс-ссылки на прошлые ревью (что подтверждаю/расширяю)

| Прошлая находка | Статус здесь |
|---|---|
| `aspect2-sre-deepseek.md:234` §9.5 «метрики/алерты: возраст лока, зависшие WAIT_TASKS, сироты-задачи, дубли late, очередь POLL» | **подтверждаю и расширяю** до M-2…M-9, A-1…A-4 |
| `aspect3-perf-mercury.md:34` «мониторить лимиты коннектов» | → M-7 (SSE/relay) |
| `aspect3-perf-mercury.md:54`/`aspect3-perf-glm.md:209` «диск workspace, watermark 80/90%» | → M-8, D-5, AL-4 (порог 85/95) |
| `aspect3-perf-glm.md:173` «метрика занятости bulkhead» | → M-3/M-4 |
| `aspect1-stack-minimax.md:264` `VirtualThreadSchedulerMXBean`/pinned | → M-9, C-3 |
| `aspect2-sre-deepseek.md` F-32 «reconcile контейнеров непериодичен» | → `harness_container_orphans_removed_total`, M-5/M-6 |
| `design-review-mercury.md:65` «при failed mount — лог volume path и error» | → L-3 + `harness_container_create_failures_total{reason=mount}` |

> Не коммитилось. Файл — промежуточный артефакт в `docs/temp/`.

---

# Cross-check (ретро-кросс темы 4, 2026-09-17)

Сверка находок коллег (`aspect4-ops-glm.md`, `aspect4-ops-mercury.md`) с моим разбором. Вердикты: **agree** (находка верна и у меня отсутствует — принимаю как расширение) / **duplicate** (находка верна и совпадает с моей — указан мой №) / **disagree** (не согласен с находкой или её рекомендацией — привожу почему).

## 0. Мои ID для маппинга

| Область | Мои ID |
|---|---|
| Логи | L-1 формат, L-2 MDC/turnId, L-3 bash-аудит, L-4 маскирование, L-5 LLM-контент, L-6 stdout/ротация, L-7 security-события, L-8 CLIENT_EXEC-аудит |
| Метрики | M-1 стек, M-2 очередь, M-3 Turn, M-4 LLM, M-5 контейнеры, M-6 POLL/чистки, M-7 SSE/relay, M-8 диск, M-9 из коробки, M-10 cardinality, M-11 агрегация gauge'ей |
| Health | H-1 группы, H-2 условия, H-3 startup-probe, H-4 Keycloak-гистерезис, H-5 docker-down |
| Трейсинг | T-1 решение, T-2 разрывы контекста, T-3 trace↔log |
| Деплой | D-1 compose, D-2 порядок, D-3 миграции, D-4 бэкапы, D-5 workspace-retention, D-6 ротация, D-7 секреты, D-8 версия helper-образа, D-9 docker.sock, D-10 single PG |
| Конфигурация | C-1 env-каталог, C-2 .env, C-3 JVM-флаги, C-4 heap, C-5 fail-fast, C-6 ротация ключей |
| Алертинг | A-1 правила, A-2 владельцы, A-3 dead-man, A-4 SLO |

## 1. GLM (`aspect4-ops-glm.md`, 20 находок: 11 GAP / 9 RISK)

| Находка GLM | Их вердикт | Кросс-вердикт | Фикс / обоснование |
|---|---|---|---|
| L1 нет политики логирования (формат/MDC/уровни/turn-контекст) | GAP | **duplicate (L-1, L-2)** | Формат → L-1; MDC/turn-контекст → L-2. Сверх моей версии — **политика уровней** (ERROR/WARN/INFO/DEBUG по событиям): принимаю как дополнение к L-1 |
| L2 неполный перечень секретов (capability-токен, JWT, `task.params`, `api_key` из env) | RISK | **duplicate (L-4, L-5)** | Совпадает; `task.params`→промпты — моя L-5 |
| L3 нет application-лога контейнерных исполнений | GAP | **duplicate (L-3)** | Явно оговаривает «без дубля полного stdout» — согласовано с L-3 |
| L4 нет ротации/retention логов | GAP | **duplicate (L-6, D-6)** | Совпадает |
| M1 нет ни одной осевой метрики | GAP | **duplicate (M-1…M-9)** | Совпадает. Сверх моей версии: `webhooks_total{status=401/409}` и `container_exec` timer — принимаю как дополнение (мой §2 их не содержал) |
| M2 кардинальность тегов | RISK | **duplicate (M-10)** | Формулировки совпадают дословно по смыслу |
| H1 readiness/liveness/startup не определены | GAP | **duplicate (H-1, H-2, H-3)** | Совпадает |
| H2 Keycloak без гистерезиса роняет readiness | RISK | **duplicate (H-4)** | Совпадает: у меня — деградируемый индикатор/JWKS-кэш; у GLM — 2 мин гистерезиса |
| H3 helper-образ не проверяется при старте | RISK | **duplicate (H-2)** | Моя `dockerExec` включает `inspectImage` (H-2) |
| **H4 `stop_grace_period` (10с) < дренажа (30с) → SIGKILL посреди graceful shutdown** | RISK | **agree (новое, ценное)** | У меня отсутствовало; тезис верен: compose/docker default `stop_grace_period=10s` противоречит `execution-model.md:106-110`. Принимаю как **H-6** (добавить в §3) и в D-1 (`stop_grace_period: 45s`) |
| T1 корреляция «HTTP→Turn→LLM→контейнер» не определена | GAP | **duplicate (T-1, T-3)** | Совпадает |
| T2 MDC теряется на хендоффах | RISK | **duplicate (T-2)** | Совпадает; GLM добавляет конкретику декораторов `MdcCallable/MdcRunnable` — согласовано с моим L-2 |
| D1 нет манифеста и порядка старта | GAP | **duplicate (D-1, D-2)** | Совпадает, включая порядок «helper build → postgres → orchestrator → readiness → bootstrap» |
| D2 бэкапы не определены; «бэкап на той же VM — не бэкап» | RISK | **duplicate (D-4)** | Совпадает; GLM корректно требует offsite/restore-drill — согласовано с моим D-4 |
| D3 конкурентный старт миграций не документирован | GAP | **duplicate (D-3)** | Совпадает, но с **расхождением по рекомендации**: GLM пишет «действий не требуется»; я требую зафиксировать ожидание лока (`lockWaitTime`)/fail-fast и readiness-гейт на отсутствие pending changesets |
| D4 docker.sock = root, нужна эксплуатационная компенсация | RISK | **duplicate (D-9)** | Совпадает |
| C1 нет таблицы env и правила yml-vs-env | GAP | **duplicate (C-1)** | Совпадает |
| C2 нет политики хранения/ротации серверных секретов | RISK | **duplicate (C-6, D-7)** | Совпадает; GLM формулирует как «замена вручную» — согласовано с моим C-6 |
| C3 JVM-флаги не определены | GAP | **duplicate (C-3)** | Совпадает; GLM даёт `-Xmx4g`/G1 vs мой `MaxRAMPercentage`/ZGC — предмет нагрузочного теста (мой C-4), не расхождение |
| A1 нет ни одного правила алертинга | GAP | **duplicate (A-1)** | Совпадает; набор правил совпадает 1:1 по смыслу (очередь/LLM/потолок/диск/POLL-мёртв) |

**GLM-итог:** agree **1** (H4) · duplicate **19** · disagree **0** (+1 расхождение по рекомендации, D3). Набор и приоритеты практически совпали; GLM H4 — единственная реально новая находка и она же его топ-1.

## 2. Mercury (`aspect4-ops-mercury.md`, 7 блоков; мой разбор по пунктам)

| Находка Mercury | Кросс-вердикт | Фикс / обоснование |
|---|---|---|
| 1.1 нет политики структурированного логирования | **duplicate (L-1)** | — |
| 1.2 нет MDC (`sessionId`, `taskId`, `turnId`) | **duplicate (L-2)** | У Mercury `turnId` как данность; у меня зафиксировано, что Turn-сущности нет (D-04) и `turnId` эфемерный |
| 1.3 нет логирования bash в контейнерах | **duplicate (L-3)** | — |
| 1.4 нет ротации/сборщиков логов | **duplicate (L-6, D-6)** | — |
| 1.5 предложение: логи в файлы `/var/log/...` + `logrotate` | **disagree** | Противоречит stdout-only/12-factor (мой L-6, L-1): файловые appender'ы запрещены, ротация — docker json-file. Иначе логи теряются при рестарте контейнера и не попадают в сборщик |
| 1.6 утверждение «Вывод команды → `task_transition_history.reason` (не в application-лог)» | **disagree** | Неверно для **агентского** `bash`: `reason` заполняется только `BASH_SCRIPT`-состояниями (`workflow-domain.md:43`); вывод агентского bash живёт в усечённом `TOOL_RESULT` (`agent-tools.md:18`). Именно поэтому нужен L-3 |
| 2.1 нет наблюдаемости SLO-метрик | **duplicate (M-1…M-9)** | — |
| 2.2 нет инвентаризации live containers / capacity | **duplicate (M-5)** | — |
| 2.3 инфра-JVM/HTTP-метрики | **duplicate (M-9)** | — |
| 3.1 оркестратор может принять трафик до готовности; нет liveness/readiness | **duplicate (H-1, H-2, H-3)** | — |
| 3.2 readiness без гистерезиса: Keycloak «reachable (curl)» | **disagree** | Жёсткая проверка Keycloak без гистерезиса роняет готовность сервиса на флапе SSO (расходится с GLM H2 и моей H-4) |
| 3.3 liveness = «heap usage < 90%» | **disagree** | Анти-паттерн: liveness должен быть поверхностным; heap-порог → рестарт-шторм и потеря in-flight Turn'ов, при том что OOM и так гасится `ExitOnOutOfMemoryError` (мой C-3). Heap — метрика/алерт, не liveness |
| 3.4 startup: «Liquibase completed (`changeLogHistory` не пустой)» | **disagree** | Слабая проверка: «таблица не пуста» ≠ «нет pending changeset'ов». Нужна проверка pending + успешного завершения (мой H-2) |
| 3.5 startup: порядок preliquibase→liquibase→pool, POLL через 5с | **duplicate (H-1, H-3)** | Совпадает с моими H-1/H-3 (startup-probe + admission) |
| 4.1 MDC-корреляция как MVP-трейсинг | **duplicate (T-1, T-3)** | — |
| 4.2 OTel отложить до M3 | **duplicate (T-1)** | Направление совпадает с моим ADR D-37 (logs-first, OTel opt-in) |
| 5.1 нет docker-compose | **duplicate (D-1)** | — |
| 5.2 нет порядка старта компонентов | **duplicate (D-2)** | — |
| 5.3 нет конкурентной безопасности миграций (preliquibase) | **duplicate (D-3)** | — |
| 5.4 нет политики бэкапов | **duplicate (D-4)** | — |
| 5.5 предложение: WAL-архивацию **отключить** для MVP (M3+ при >10 GB) | **disagree** | `session_message` — очередь + append-only аудит; RPO ≤ 24 ч для неё — неоправданный риск, а `archive_mode` почти бесплатен. GLM прав: либо WAL сразу (RPO ≤ 5 мин), либо осознанное решение до M2; «отключить» как дефолт — нет |
| 5.6 предложение: `version: "3.8"` в compose | **disagree** | Спецификация `version` устарела в Compose v2 и вызывает warning; поле надо опустить (мой D-1) |
| 5.7 предложение: `image: postgres:15` | **disagree** | Устаревший мажор и нет пиннинга patch-версии; стек мажор не фиксирует, а воспроизводимость деплоя требует пиннинга (мой D-1: `postgres:17.6-alpine`) |
| 6.1 нет `.env` для дева | **duplicate (C-2)** | — |
| 6.2 не описаны JVM-флаги | **duplicate (C-3)** | — |
| 6.3 нет разделения application.yml vs env | **duplicate (C-1)** | — |
| 6.4 предложение: «Виртуальные потоки: `--enable-native-access=ALL-UNNAMED`» | **disagree** | Ложный маппинг: `--enable-native-access` — про FFM/JNI-доступ (JEP 472 warnings), а не про виртуальные потоки; VTs включаются `spring.threads.virtual.enabled=true` (у Mercury упомянут отдельным пунктом — то есть противоречит сам себе). Как флаг для docker-java/jnr сокетов — возможно, но по другой причине |
| 7.1 нет алертинг-правил | **duplicate (A-1)** | — |
| 7.2 правило `rate(session.eligible.queue.size[5m]) > 0` | **disagree** | `rate()` по gauge — ошибка (нужен `deriv`/сравнение с порогом) и метрики в таком имени не будет: dot-нотацию Micrometer преобразует в `_`. Корректно — `harness_eligible_oldest_age_seconds`/`harness_eligible_sessions` (мой A-1) |
| 7.3 правило `up{job="postgres"} == 0` | **disagree** | Предполагает postgres_exporter, которого нет в MVP-манифесте (GLM прямо пишет: node_exporter не нужен). Правило сработает «вечно» или не сработает вовсе (мой A-1: dead-man на POLL + health БД) |
| 7.4 Alertmanager → Slack `#spring-harness-alerts` | **duplicate (A-2)** | Маршрутизация/владельцы — моя A-2 |

**Mercury-итог:** agree **0** · duplicate **20** · disagree **11**. Метрика «Итого: 7 GAP/RISK» в шапке Mercury не соответствует его же перечислению (7 блоков × несколько находок) — та же недооценка, что и в `aspect2-sre-mercury.md`; к цифрам Mercury следует относиться как к «7 областям», а не 7 находкам.

## 3. Сводные цифры кросса

| | agree | duplicate | disagree | Итого пунктов |
|---|---|---|---|---|
| GLM | 1 | 19 | 0 | 20 |
| Mercury | 0 | 20 | 11 | 31 |
| **Всего** | **1** | **39** | **11** | **51** |

Новых валидных находок, которых не было у меня: **1** — GLM H4 (`stop_grace_period` < дренаж). Принятые дополнения внутри дублей: метрики `webhooks_total`/`container_exec` (GLM M1), политика лог-уровней (GLM L1). Расхождение по рекомендации внутри дубля: GLM D3 («действий не требуется»).

## 4. Список disagree (11)

| # | Источник | Что отклоняю | Почему |
|---|---|---|---|
| 1 | Mercury 1.5 | Логи в файлы + `logrotate` | Противоречит stdout-only (L-6); файлы теряются при рестарте, не попадают в сборщик |
| 2 | Mercury 1.6 | «Вывод bash → `task_transition_history.reason`» | Верно только для `BASH_SCRIPT`; агентский bash — усечённый `TOOL_RESULT`, durable-аудита нет (L-3) |
| 3 | Mercury 3.2 | Keycloak в readiness без гистерезиса | Флап SSO роняет готовность всего сервиса при валидном JWKS-кэше (H-4) |
| 4 | Mercury 3.3 | Liveness по «heap < 90%» | Анти-паттерн: рестарт-шторм, потеря in-flight Turn'ов; OOM гасится `ExitOnOutOfMemoryError` (C-3) |
| 5 | Mercury 3.4 | Startup «`changeLogHistory` не пустой» | Не проверяет pending changesets; схема может быть недомигрирована (H-2) |
| 6 | Mercury 5.5 | WAL-архивацию отключить для MVP | RPO ≤ 24 ч для очереди+аудита; WAL почти бесплатен (D-4) |
| 7 | Mercury 5.6 | `version: "3.8"` в compose | Устаревшее поле спецификации Compose v2 (D-1) |
| 8 | Mercury 5.7 | `postgres:15` без пиннинга patch | Воспроизводимость деплоя; устаревший мажор (D-1) |
| 9 | Mercury 6.4 | «Виртуальные потоки: `--enable-native-access`» | Ложный маппинг: флаг про FFM/JNI, VT включаются `spring.threads.virtual.enabled` (C-3) |
| 10 | Mercury 7.2 | `rate(gauge) > 0` и dot-имя метрики | PromQL-ошибка + имени метрики в таком виде не будет (A-1) |
| 11 | Mercury 7.3 | `up{job="postgres"}` | Нет postgres_exporter в MVP-манифесте; правило не будет работать (A-1) |

Расхождение по рекомендации внутри дубля (не входит в 11): **GLM D3** — «действий при конкурентных миграциях не требуется»; требуется зафиксировать ожидание Liquibase-лока/fail-fast и readiness-гейт на pending changesets (мой D-3).

## 5. Что подтверждает мой разбор

- **GLM** повторяет мой набор почти 1:1 (20/20 попаданий в мои ID, 0 disagree), включая стек метрик, health-группы, MDC-дисциплину, offsite-бэкапы, кардинальность. Его единственная новая находка (H4) — верная и повышает мой приоритет «деплой/graceful shutdown»; принимаю как H-6.
- **Mercury** правильно называет все 7 областей, но даёт конкретику с ошибками (11 disagree) — та же закономерность, что и в `aspect2-sre-mercury.md` (там — 3 ложных вердикта): область угадана, деталь/вердикт нет.
- Ни одна из сторон не нашла находок, которые **опровергали** бы мои: пересечение 39 дублей без противоречий по существу.

> Не коммитилось.

---

# Fixes approval (2026-09-17)

Проверка судейского фикса темы 4 (`docs/design/operations.md` + `D-37` + `key_version` + `AGENTS.md`) против моих находок. Вердикт: **approve 12 / reject 0** (4 требуемые + 8 остальных судейских решений, задевающих мои находки).

| Моя находка | Судейский фикс | Вердикт | Одна строка |
|---|---|---|---|
| **M-1** Micrometer/Actuator отсутствуют в стеке | `operations.md` §2 «Метрики (Micrometer + Actuator)» + D-37 (решение #8) | **approve** | База наблюдаемости зафиксирована в дизайне; остаток — pom-зависимости (`micrometer-registry-prometheus`), management-порт `8081` и экспозиция `/actuator/prometheus` не специфицированы. |
| **D-4** нет бэкапов Postgres; **D-3** миграции | `operations.md` §5: WAL «RPO ≤ 5 мин», ежедневный `pg_dump` off-VM, restore-drill; «конкурентный старт исключён (flock/lock Liquibase; rolling — по одному инстансу)» (решение #4) | **approve** | Очередь+аудит защищены, RPO задан, миграции сериализованы; остаток — retention-политика и fail-fast при занятом локе не зафиксированы. |
| **L-2** нет контракта корреляции `turnId`/`wakeSource` | `operations.md` §1: MDC-поля `instanceId, sessionId?, taskId?, turnId?, wakeSource?` (решение #1) | **approve** | Требование «Turn собирается по логам» выполнено; остаток — `wakeSource` рассинхронизирован с глоссарием (`API` vs `WEBHOOK`/`TASK_EVENT`) и механика переноса MDC на executor'ы/vthread не описана. |
| **C-6** ротация ключа шифрования `api_key` | `data-model.md` §2: `llm_credentials.key_version int DEFAULT 1` («ротация без порчи данных») | **approve** | Конверт версии ключа добавлен — ротация перестала быть необратимой; остаток — формат `vN:`/два активных ключа и команда re-encrypt не описаны. |
| H-4 Keycloak в readiness | readiness с гистерезисом: DOWN после 3 неудач подряд (решение #2) | **approve** | Совпадает с моим предложением (деградируемый индикатор/JWKS-кэш). |
| H-5 docker.sock недоступен | тумблер `harness.health.docker.critical` default `false` (решение #3) | **approve** | Поведение определено; default иной, чем в моём H-5 (`true`), но обоснован: ERROR-путь отказа монтирования (`execution-model.md` §4) не роняет готовность одномашинного MVP. |
| M-10 кардинальность | `operations.md` §2 «кардинальность bounded — без label'ов sessionId/taskId» + ~30 метрик (решение #5) | **approve** | Инвариант зафиксирован; набор покрывает M-2…M-9. |
| L-8 CLIENT_EXEC-аудит | `operations.md` §8: локальный audit-файл relay — **рекомендация** в client-cli, не серверный контракт (решение #6) | **approve** | Согласен: окружение клиента вне нашего контроля, жёсткий контракт был бы необязательным. |
| H-6 (принят от GLM) `stop_grace_period` < дренажа | `operations.md` §5: `stop_grace_period: 45s` > 30 с (решение #7) | **approve** | Закрывает SIGKILL посреди graceful shutdown. |
| T-1 трейсинг | MVP — MDC-логи, OTel-мост opt-in с выключенным экспортёром (решение #1, D-37) | **approve** | Совпадает с моим ADR-предложением (logs-first, OTel по переменной окружения). |
| A-1 алертинг | `operations.md` §7: 5 правил (очередь/LLM/потолок/диск/POLL-p95) | **approve** | Минимум покрыт; POLL-правило — по длительности, dead-man (last_success timestamp, моя A-3) не введён. |
| C-1/C-2 конфигурация | `operations.md` §6: 12-factor `HARNESS_*`, yml = структурные дефолты, `.env` в dev (spring-dotenv подтверждён в pom) | **approve** | Правило yml-vs-env зафиксировано; полный каталог env-переменных и JVM-флаги детально не расписаны. |

**Остаточные замечания (не блокируют approve):**

1. **M-1:** добавить в `operations.md`/roadmap M1 явные артефакты — `spring-boot-starter-actuator` + `micrometer-registry-prometheus` в pom, `management.server.port=8081`, `/actuator/**` не публикуется (кроме проб).
2. **L-2:** привести `wakeSource` к глоссарию (`POLL | EVENT | API`) либо обновить глоссарий; зафиксировать `MdcCallable/MdcRunnable` для executor'ов и виртуальных потоков.
3. **L-3 (не в судейском списке, остаётся открытым):** `operations.md` §1 корректно отмечает, что stdout агентского bash не durable, но опирается на `TOOL_RESULT`, который усечён (`agent-tools.md:18`) — структурное `event=tool.executed` (command/exit/duration) в лог так и не введено.
4. **C-6:** описать формат `v<key_version>:<ciphertext>`, два активных ключа и админ-команду re-encrypt.
5. **D-4:** уточнить retention (суточные/недельные dump'ы) и поведение при занятом `DATABASECHANGELOGLOCK` (ожидание/таймаут) для rolling.

> Не коммитилось.
