# Aspect 4: Observability и эксплуатация — GAP-анализ (GLM)

> Ревьюер: GLM. Основание: `architecture.md`, `execution-model.md`, `data-model.md`, `api-contracts.md`, `security-multitenancy.md`, `roadmap.md`, `decisions.md` (D-04, D-18, D-26, D-30, D-33–D-36).
> Формат: область → что в доках (цитата) → GAP/RISK → предложение (готовое к вставке).
> Посадка: новый `docs/design/operations.md` (§1–§7) + точечные правки в существующие доки (обозначены в каждом пункте).
> Не коммитится — только черновик для сверки.

---

## 1. Логи

**Что уже в доках:**

- `security-multitenancy.md` §3: «Правило: секреты не логируются; в `reason`/payload вебхуки пишут тела вызовов (по дизайну аудита), но не наши секреты.»
- `security-multitenancy.md` §5: «Append-only журналы: `session_message` (всё, что говорили/делали), `task_transition_history` (каждый ход задачи с обоснованием…), `reason` вебхуков (что приходило снаружи).»
- `api-contracts.md` §4.4: «логирование всех вызовов в `reason`» (компенсация D-26).
- `execution-model.md` §7: «вывод и exit-код — в `reason` записи `task_transition_history`»; `data-model.md`: `reason_jsonb` — «bash: stdout/stderr + exit-код».
- `decisions.md` D-04: «аудит попыток — не сценарий заказчика» (про Turn/Run-таблицу) — следствие: наблюдаемость Turn'ов целиком ложится на логи/метрики, durable-аудит есть только у сообщений и переходов.

**GAP/RISK:**

- **GAP L1** — нет политики структурированного логирования: формат (JSON/текст), набор MDC-ключей, уровни, привязка turn-контекста. Ни один док не упоминает MDC.
- **RISK L2 (безопасность, проверка полноты запрета секретов)** — правило «секреты не логируются» не перечисляет, *что именно* секрет. Реальные утечки через логи:
  1. **capability-токен вебхука в URL** (`/api/webhooks/**/{token}`, D-26) — любой access-лог, лог исключения с URI, лог Tomcat/прокси утекает валидный токен полномочия;
  2. `Authorization: Bearer <JWT>` при логировании заголовков/исключений фильтров;
  3. `task.params` — «могут содержать чувствительное» (security §3), при этом попадают в seed-контекст → в промпты;
  4. `llm_credentials.api_key` — зашифрован в БД, но приходит в env bootstrap-CLI (architecture §4).
- **GAP L3** — контейнерный bash-аудит для эксплуатации: BASH_SCRIPT-состояния имеют durable `reason`, агентские bash — durable `TOOL_CALL/TOOL_RESULT` в сессии; но для troubleshooting инцидента нужен application-лог executions (кто/что/когда/exit/длительность) без дубля полного stdout (он уже в БД).
- **GAP L4** — нет ротации/retention логов на VM (stdout контейнера без лимитов = переполнение диска за недели).

**Предложение — `operations.md` §1 (новый):**

```markdown
## 1. Логи

- Формат: JSON (logback-spring.xml + logstash-encoder), приложение пишет в stdout контейнера.
  docker json-file driver: max-size=50m, max-file=5 (ротация на уровне docker).
- MDC-контекст Turn'а (ставится при взятии лока, чистится в finally): `sessionId`, `taskId`,
  `stateCode`, `agentKey@rev`, `instanceId`; на HTTP-слое — `traceId`/`spanId` (см. §4).
  Правило хендоффов: фоновые потоки (late-TOOL_RESULT, ShedLock-джоба, WS-релей, spawn-ожидатели)
  получают MDC через декоратор `MdcCallable`/`MdcRunnable` — сырые `CompletableFuture`/`Executor`
  без декоратора запрещены код-ревью.
- Уровни: ERROR — потеря durable-данных/сбой recovery; WARN — парковки Turn'ов, LOST-контур,
  429-backoff, вытеснение контейнеров; INFO — жизненный цикл Turn/контейнера, bootstrap;
  DEBUG — рендер, дельты, пулы (не в prod по умолчанию).
- Маскирование (дополняет security-multitenancy §3): в логи никогда не попадают —
  capability-токен в URL вебхуков (логируем `/api/webhooks/**` с заменой пути на
  `/api/webhooks/<kind>/<id>/***`), `Authorization`/JWT, `api_key`, `server_secret`,
  `task.params` целиком (только набор ключей), тела промптов LLM (только метаданные:
  model, tokens, latency).
- Аудит контейнерного исполнения — logger `harness.container.exec`: sessionId, инструмент,
  команда (обрезка 512 символов), exit-код, длительность, контейнер. stdout/stderr в лог —
  хвост ≤ 4 КБ на DEBUG; полный вывод — уже в БД (`TOOL_RESULT`/`reason`), в лог не дублируем.
```

Правка: `security-multitenancy.md` §3 — после слов «но не наши секреты» добавить: «Перечень маскируемого в логах — `operations.md` §1 (включая capability-токены в URL вебхуков).»

---

## 2. Метрики Micrometer

**Что уже в доках:**

- Ничего. `architecture.md` §4 перечисляет добавки в pom на M1 (docker-java, MCP-клиент, oauth2-resource-server, git-commit-id, JaCoCo) — Micrometer/Prometheus не упомянуты.

**GAP/RISK:**

- **GAP M1** — нет наблюдаемости ни одной осевой метрики. Критично именно для этой системы: ключевые сценарии отказов уже спроектированы (утренний пик wake × rate-limit — D-35; смерть POLL — D-33; смерть контейнера — D-30), но ни один из них не виден снаружи: `harness_poll` просто перестанет обрабатывать, и никто не узнает.
- **RISK M2** — кардинальность: без явного правила (нет session/task/user id в тегах) Prometheus-ряды взорвутся при 50+ пользователях и сотнях сессий.

**Предложение — `operations.md` §2 (новый):**

```markdown
## 2. Метрики (Micrometer + Prometheus, префикс `harness_`)

| Метрика | Тип | Теги | Комментарий |
|---|---|---|---|
| sessions_eligible | Gauge | — | count по partial-индексу `last_seq > last_consumed_seq AND locked_by IS NULL AND archived_at IS NULL`; пересчитывается в POLL-джобе |
| turn_active | Gauge | agentKey | Turn'ов сейчас (виртуальные потоки) |
| turn_duration | Timer | agentKey | wake → завершение Turn'а |
| turn_rounds | DistributionSummary | agentKey | раундов на Turn |
| turn_outcomes | Counter | outcome{COMPLETED,FAILED,CANCELLED} | дублирует `last_turn_outcome` на момент записи |
| llm_first_token | Timer | modelId | wake→первый токен (KPI латентности) |
| llm_stream_duration | Timer | modelId | полный стрим |
| llm_tokens | Counter | modelId, direction{prompt,completion} | при начислении (те же числа, что в `session_message.tokens`) |
| llm_requests | Counter | modelId, outcome{ok,429,5xx,timeout,cancelled} | |
| llm_retries | Counter | modelId, attempt | backoff-попытки |
| llm_parked | Counter | modelId | парковка Turn'а после исчерпания backoff (D-35) |
| llm_bulkhead_available | Gauge | — | свободные разрешения bulkhead (дефолт 8) |
| compactions | Counter | kind{auto,/compact} | страховочная ~80% окна и явная |
| containers_live | Gauge | — | живые helper/task-контейнеры (истина — docker-java, кэш в POLL) |
| containers_capacity | Gauge | — | потолок (конфиг, дефолт 50) |
| container_evictions | Counter | reason{idle,orphan} | idle-вытеснение 30 мин / reconcile-сироты |
| container_exec | Timer | tool | sync-инструмент в контейнере |
| poll_duration | Timer | — | обязана быть < интервала 5s; `lockAtMostFor` 10s |
| poll_last_success_age | Gauge | — | секунд с последнего успешного POLL |
| recovery_events | Counter | kind{lock_lost,tool_result_lost,cancel_done} | рестарт-скан и LOST-контур |
| sse_active | Gauge | stream{session,task} | живые SSE-подписки |
| ws_relay_active | Gauge | — | подключения CLIENT_EXEC-релея |
| webhooks_total | Counter | kind, status{2xx,401,409} | 401-всплеск = перебор токенов |
| idempotency_cleanup | Counter, Timer | — | удалено строк (пачки ≤1000) + длительность |
| instances_stale | Gauge | — | инстансов в реестре с `last_seen` старше 30s |
| disk_free_bytes | Gauge | mount{workspace,pgdata} | `File.getUsableSpace` — node_exporter в MVP не нужен |

Правило кардинальности: теги — только из ограниченных множеств (modelId, outcome, kind, tool,
agentKey). SessionId/taskId/userId в тегах запрещены.
Плюс стандартные: jvm.*, http.server.requests, hikaricp.*, process.*.
pom на M1: `micrometer-registry-prometheus`; actuator — экспонировать только health,info,prometheus
(за JWT или на внутреннем интерфейсе).
```

---

## 3. Health/readiness

**Что уже в доках:**

- Про health — ничего. Косвенно: `architecture.md` §4: «фактический порядок инициализации: **пул → preliquibase → liquibase → JPA**»; `execution-model.md` §1: «pull helper-образа: образ обязан присутствовать локально на VM (собирается при деплое)»; `execution-model.md` §8: дренаж 30 сек при SIGTERM.

**GAP/RISK:**

- **GAP H1** — readiness/liveness/startup не определены: трафик может приниматься до Liquibase, до доступности docker.sock и до появления helper-образа.
- **RISK H2** — Keycloak — внешний SSO: жёсткая проверка в readiness означает, что флап Keycloak роняет готовность всего сервиса, хотя уже выданные JWT валидируются по кэшированным JWKS. Нужен гистерезис.
- **RISK H3** — отсутствие helper-образа на VM (D-30: «образ обязан присутствовать локально») не проверяется при старте: все контейнерные инструменты начнут падать в рантайме на каждой сессии.
- **RISK H4** — согласование graceful shutdown с docker: окно дренажа 30 сек, но дефолтный `stop_grace_period` в docker/compose — 10 сек → SIGKILL посреди дренажа, обрыв LLM-стримов, волна синтетических LOST на каждом деплое. Это противоречит замыслу `execution-model.md` §8.

**Предложение — `operations.md` §3 (новый) + одна строка в `execution-model.md` §8:**

```markdown
## 3. Health/readiness (Spring Boot Actuator)

- Readiness (`/actuator/health/readiness`) = AND:
  1. `db` + `liquibase` (встроенные индикаторы; порядок инициализации: пул → preliquibase →
     liquibase → JPA — readiness true только после JPA);
  2. `dockerPing` — docker-java `info()` с таймаутом 2с (docker.sock доступен);
  3. `helperImage` — `docker image inspect HARNESS_HELPER_IMAGE` локально (D-30);
  4. `keycloak` — GET `<issuer>/.well-known/openid-configuration` с кэшем 60с;
     выводит not ready только после 2 мин непрерывной недоступности (гистерезис: флап SSO
     не роняет трафик — JWT валидируются по кэшированным JWKS).
- Liveness (`/actuator/health/liveness`) = только процесс (без внешних зависимостей —
  иначе рестарт-шторм при падении БД/Keycloak).
- Startup probe — на первый старт (Liquibase на пустой БД нетороплив).
- SIGTERM-последовательность: readiness → false немедленно, затем дренаж 30 сек.
```

Правка: `execution-model.md` §8, в конец абзаца про graceful shutdown: «Оркестратор помечает себя not-ready до начала дренажа; внешний стоп-таймаут контейнера (docker `stop_grace_period`) ≥ 45 сек — иначе SIGKILL убьёт процесс посреди 30-секундного дренажа.»

---

## 4. Трейсинг

**Что уже в доках:**

- Ничего (ни OpenTelemetry, ни correlation-логов).

**GAP/RISK:**

- **GAP T1** — корреляция «HTTP-запрос → Turn → LLM-стрим → контейнер» не определена вообще.
- **RISK T2** — MDC основан на ThreadLocal и теряется на хендоффах: late-`TOOL_RESULT` приходит из фоновых потоков, POLL — поток ShedLock, релей — WS-потоки, spawn — родительский виртуальный поток ждёт дочерний. Виртуальные потоки сами по себе проблему не решают (контекст не «протекает» между потоками автоматически).

**Предложение — `operations.md` §4 (новый):**

```markdown
## 4. Трейсинг — рекомендация для MVP

MVP = correlation-логи, а не OTel-стек:
- Micrometer Tracing (W3C tracecontext; в Boot-стеке из коробки) — спаны на: HTTP-запрос,
  Turn-раунд, LLM-стрим (с тегами modelId), container exec, POLL-скан, вебхук, relay tool.call.
- Экспортёр ВЫКЛЮЧЕН (OTLP-endpoint не задан): traceId/spanId попадают в MDC и в JSON-логи.
  Эффект: сквозной grep по логам по `traceId` уже сейчас; включение бэкенда (Tempo/Jaeger)
  после M3 = одна переменная окружения, инструментация уже стоит.
- Полный OTel (коллектор + бэкенд + сэмплинг) — после M3, когда появятся async-инструменты
  и релей: там спаны между компонентами окупятся. Для одного инстанса сейчас — оверинжиниринг.
- Дисциплина: перенос MDC/контекста на все хендоффы — через `MdcCallable`-декораторы
  исполнителей (см. §1); спан LLM-стрима закрывается по первому терминальному событию,
  не по отмене.
```

---

## 5. Деплой-манифест

**Что уже в доках:**

- `architecture.md` §4: «Деплой: оркестратор — контейнер на выделенной VM, `/var/run/docker.sock` смонтирован (docker-java создаёт helper-контейнеры — D-30); helper-образ собирается из Dockerfile в репозитории и присутствует на VM локально (pull — только backoff-обновление).»
- `architecture.md` §4: «Bootstrap: первые `llm_credentials`/`agent` заводятся админ-командой CLI (читает env: base_url/key/model).»
- `execution-model.md` §8: graceful shutdown 30 сек; §1: heartbeat 10с/порог 30с, TTL-чистка idempotency, reconcile в POLL.
- Keycloak — внешний SSO (security §1), Postgres локальный.

**GAP/RISK:**

- **GAP D1** — нет манифеста и порядка старта (кто, когда, с какими healthcheck; где собирается helper-образ; когда выполняется bootstrap).
- **RISK D2** — бэкапы Postgres не определены: append-only аудит (`session_message`, `task_transition_history`) — данные, которые терять нельзя, верхняя оценка роста ~1 ТБ/год (data-model §5); нет ни процедуры, ни RPO, ни проверки восстанавливаемости. Отдельно: «бэкап на ту же VM» — не бэкап.
- **GAP D3** — конкурентный старт миграций не документирован: Liquibase сериализуется собственным локом `DATABASECHANGELOGLOCK`, но это надо зафиксировать (для MVP один инстанс — некритично, для будущего rolling — важно).
- **RISK D4** — `/var/run/docker.sock` = root-доступ к хосту; компенсация «выделенная VM» (D-30) должна быть эксплуатационной, а не бумажной: никаких других workload'ов и логинов, пин версий образов.

**Предложение — `operations.md` §5 (новый):**

```markdown
## 5. Деплой-манифест VM

Состав prod-VM: postgres + orchestrator (+ helper-образ локально). Keycloak — внешний, вне манифеста;
dev-compose может поднимать keycloak с realm-import.

### Порядок старта (deploy-скрипт)
1. `docker build -t harness-helper:<git-sha> deploy/helper/` — helper-образ (D-30: обязан быть локально).
2. postgres up (healthcheck `pg_isready -U harness`, `stop_grace_period: 2m`), том pgdata.
3. orchestrator up: image `harness/orchestrator:<git-sha>`, docker.sock смонтирован,
   `stop_grace_period: 45s` (> 30с дренажа!), restart=unless-stopped,
   logging json-file max-size=50m max-file=5, depends_on postgres healthy.
   Миграции выполняются ВНУТРИ старта приложения (пул → preliquibase → liquibase → JPA).
4. Ждём `/actuator/health/readiness` = UP.
5. Bootstrap one-shot: `docker compose run --rm orchestrator bootstrap` (идемпотентен:
   повтор на заполненной БД — no-op).
6. Трафик.

### Конкурентный старт миграций
Liquibase сериализуется локом `DATABASECHANGELOGLOCK` (второй инстанс ждёт) — безопасно;
для MVP один инстанс, для rolling deploy — задокументировано, действий не требуется.

### Бэкапы Postgres
- Ночной `pg_dump -Fc` (03:00) в том /backups + WAL-архив: `archive_mode=on`,
  `archive_command='test ! -f /backups/wal/%f && cp %p /backups/wal/%f'`, `archive_timeout=60s`
  → RPO ≤ 5 мин. Если WAL не осиливаем на старте — осознанный RPO ≤ 24 ч (только dump), решить до M2.
- Retention: 7 суточных + 4 недельных. Синхронная копия на второй носитель/хост (rsync) —
  бэкап на той же VM не бэкап.
- Ежемесячный restore-drill: подняться из бэкапа в одноразовом контейнере, проверить схему+счётчики.
- Еженедельно `docker system prune -f --filter until=168h` (живые контейнеры не трогает);
  контроль диска — метрика disk_free_bytes (§2).
```

---

## 6. Конфигурация

**Что уже в доках:**

- `architecture.md` §4: «Автоконфигурации **отключены** (`application.yml`)… datasource собирается вручную… автоконфиги Spring AI… (клиенты собираются из `LlmModel` вручную)»; bootstrap-CLI «читает env: base_url/key/model».
- Конфигурируемые значения, разбросанные по докам: потолок контейнеров 50, cpus=2, memory=2g, pids=512, квота workspace 5GB, idle 30 мин (execution §4); bulkhead 8, backoff×3 (§1, D-35); POLL 5с/10с; лок TTL 60с/heartbeat 30с; heartbeat инстанса 10с/30с; дренаж 30с; idempotency TTL 24ч.

**GAP/RISK:**

- **GAP C1** — нет единой таблицы env-переменных и правила «application.yml vs env»; нет `.env.example` для дева.
- **RISK C2** — серверные секреты (`server_secret` вебхуков, ключ шифрования `api_key_encrypted` — «ключ — из env», security §3, bootstrap `API_KEY`) не имеют политики хранения на VM (права файла, вне git) и ротации (D-26 называет ротацию «эволюцией» — ок, но базовая замена вручную должна быть описана).
- **GAP C3** — JVM-флаги не определены: виртуальные потоки, heap под MB-payload рендер (`renderVisibleEvents` собирает полный видимый контекст в памяти — пики в единицы МБ на Turn, плюс компакция на ~80% окна делает свой LLM-вызов).

**Предложение — `operations.md` §6 (новый):**

```markdown
## 6. Конфигурация (12-factor)

Правило: application.yml — структура и дефолты, не секреты; env — секреты, адреса инфраструктуры,
лимиты среды. `.env.example` — в репозитории; `.env` на VM — вне git, chmod 600.

| Переменная | Назначение | Дефолт |
|---|---|---|
| HARNESS_DB_URL / _USER / _PASSWORD | Postgres | — |
| HARNESS_KEYCLOAK_ISSUER / _AUDIENCE | OIDC resource-server | — |
| HARNESS_SERVER_SECRET | HMAC capability-вебхуков (D-26) | — |
| HARNESS_SECRET_KEY | шифрование api_key на уровне приложения | — |
| HARNESS_BOOTSTRAP_BASE_URL / _API_KEY / _MODEL | bootstrap-сид CLI | — |
| HARNESS_HELPER_IMAGE | тег helper-образа | harness-helper:<git-sha> |
| HARNESS_CONTAINERS_CAPACITY / _CPUS / _MEMORY / _PIDS / _WORKSPACE_QUOTA / _IDLE_TIMEOUT | контейнеры (execution §4) | 50 / 2 / 2g / 512 / 5g / 30m |
| HARNESS_LLM_BULKHEAD / _RETRIES | D-35 | 8 / 3 |
| HARNESS_POLL_INTERVAL / _LOCK_AT_MOST | POLL | 5s / 10s |
| HARNESS_LOCK_TTL / _LOCK_RENEW | Turn-лок | 60s / 30s |
| HARNESS_HEARTBEAT / _INSTANCE_DEAD | реестр instance | 10s / 30s |
| HARNESS_SHUTDOWN_DRAIN | graceful shutdown | 30s |
| HARNESS_IDEMPOTENCY_TTL | чистка | 24h |
| OTEL_EXPORTER_OTLP_ENDPOINT | пусто = трейсинг только в логах (§4) | — |
| JAVA_TOOL_OPTIONS | JVM-флаги (ниже) | — |

JVM (prod): `spring.threads.virtual.enabled=true` (Turn'ы на виртуальных потоках);
`-Xms256m -Xmx4g` (запас под рендер MB-payload и JSON-буферы; VM 8–16 GB, рядом только Postgres);
GC — G1 `-XX:MaxGCPauseMillis=200` (базово; вариант для латентности — generational ZGC,
`-XX:+UseZGC -XX:+ZGenerational`, проверить на M1 и выбрать один); `-Duser.timezone=UTC`;
UTF-8 — дефолт JDK 18+; native-access для unix-socket транспорта docker-java проверить на M1,
добавить флаг только если упадёт.
```

---

## 7. Алертинг-минимум

**Что уже в доках:**

- Ничего. Косвенно: спроектированные сценарии отказов (D-30 контейнер умер, D-33 потерянный wake, D-35 утренний 429-пик) задают, *что* надо алертить.

**GAP/RISK:**

- **GAP A1** — нет ни одного правила: система может молча стоять (POLL мёртв, очередь eligible растёт) без единого сигнала.

**Предложение — `operations.md` §7 (новый):**

```markdown
## 7. Алерты (минимум, Alertmanager → канал #harness-ops)

| # | Правило | Условие | Severity | Действие |
|---|---|---|---|---|
| 1 | Очередь eligible растёт | `harness_sessions_eligible > 10 for 5m` | warning | проверить poll_last_success_age, turn_active; см. §2 |
| 2 | LLM-ошибки | `sum(rate(harness_llm_requests_total{outcome=~"429|5xx"}[5m])) / sum(rate(harness_llm_requests_total[5m])) > 0.2 for 10m` или `increase(harness_llm_parked_total[15m]) > 0` | warning | утренний пик/деградация провайдера (сценарий D-35) |
| 3 | Контейнерный потолок | `harness_containers_live / harness_containers_capacity > 0.85` (== 1.0 → critical) | warning/critical | новые сессии не получают контейнеры; проверить сироты/evictions |
| 4 | Диск | `min(harness_disk_free_bytes) / <раздел> < 0.15 for 10m` | critical | workspace (5GB×сессии) + pgdata + образы; чистка/расширение |
| 5 | POLL встал | `harness_poll_last_success_age_seconds > 30` | critical | система молча не обрабатывает ничего; высший приоритет |

Каждый алерт — ссылка на runbook-секцию (завести в operations.md по мере инцидентов).
```

---

## Сводка

**Итого: 20 находок — 11 GAP + 9 RISK.**

| Область | GAP | RISK |
|---|---|---|
| Логи | L1, L3, L4 | L2 |
| Метрики | M1 | M2 |
| Health | H1 | H2, H3, H4 |
| Трейсинг | T1 | T2 |
| Деплой | D1, D3 | D2, D4 |
| Конфигурация | C1, C3 | C2 |
| Алертинг | A1 | — |

**Топ-3:**

1. **H4 — деплой убивает graceful shutdown**: дефолтный `stop_grace_period` (10с) < окна дренажа (30с) → SIGKILL посреди дренажа на каждом деплое; обрыв LLM-стримов, волна синтетических LOST, потерянный spend. Чинится одной строкой в манифесте и одной в `execution-model.md` §8, но найдётся только в бою.
2. **M1+A1 — нулевая наблюдаемость**: ни метрик, ни алертов; все спроектированные отказы (смерть POLL, утренний 429-пик D-35, потолок контейнеров) невидимы снаружи — узнаем от пользователей.
3. **D2 — бэкапы не определены**: append-only аудит (до ~1 ТБ/год) без процедуры восстановления, RPO и restore-drill; риск «бэкап на той же VM».

**Рекомендация по срокам**: H4, D1 (манифест) и каркас M1/A1 (метрики+алерты №5 и №1) — до конца M1; бэкапы D2 — до M2 (появляются реальные данные задач); L2 (маскирование) — до первого публичного стенда.

---

## Cross-check (сверка с ревью DeepSeek и Mercury)

> Вердикты: `agree` — новое ценное, беру; `duplicate (№)` — моя находка уже покрывает; `disagree` — с обоснованием.
> Мои №: L1–L4, M1–M2, H1–H4, T1–T2, D1–D4, C1–C3, A1.

### aspect4-ops-deepseek.md — 47 находок: **31 duplicate / 15 agree / 1 disagree**

| Их № | Вердикт | Комментарий |
|---|---|---|
| L-1 | duplicate (L1, L4) | JSON/stdout/ротация у меня есть; их выбор ECS (нативный в Boot 4) — согласен, беру как конкретику |
| L-2 | agree | дублирует мой L1/T2 по механике, но добавляет эфемерный `turnId` (D-04-совместимо — не персистится), `wakeSource` из MDC-набора, `X-Request-Id` — беру |
| L-3 | agree | решение дублирует мой L3, но с фактической поправкой ко мне: мой тезис «полный вывод уже в БД» **неверен** — `agent-tools.md:18` усекает вывод `TOOL_RESULT`; полный stdout агентского bash нигде не durable. Поправка принята, их событие аудита беру |
| L-4 | duplicate (L2) | маскирование у меня; их добавки — `?ticket=` в query и регресс-тест «токена нет в логе» — беру |
| L-5 | duplicate (L2) | у меня «промпты не логируем»; их opt-in флаг `HARNESS_LOG_LLM_CONTENT` — разумное оформление, беру |
| L-6 | duplicate (L4) | stdout-only + json-file caps — то же |
| L-7 | agree | новое: security-логгер (`auth.failure`, `access.denied` с реальной причиной при 404-вместо-403, `idempotency.conflict`, bootstrap). Ценно, у меня только метрика webhooks{401} |
| L-8 | **disagree** (частично) | серверная фиксация `register`/`disconnect`/`tool.call-sent` — ок (у меня ws_relay-метрики + логи). Но **обязательный клиентский audit-файл** (`~/.harness/logs/relay-audit.jsonl`, retention ≥30 дней) в контракте CLI — over-spec: клиентское окружение вне контроля сервера (машина пользователя — его логи, вопрос приватности), обязательство невыполнимо-непроверяемо. Максимум — рекомендация в `client-cli.md` |
| M-1 | duplicate (M1) | actuator+prometheus у меня; отдельный management-порт 8081 — беру |
| M-2 | agree | расширение моего списка: `eligible_oldest_age_seconds` (лучше абсолютного count), `poll_claims{source}` (соотношение EVENT/POLL), `reconcile_total{kind}` |
| M-3 | agree | расширение: `locks_stolen`, `fencing_aborts`, `lock_oldest_age`, `turn_failures{error_class}` — прямые измерения механики D-36, у меня нет |
| M-4 | duplicate (M1, §2) | LLM-набор совпадает почти 1:1 (TTFT, tokens, retries, parked, bulkhead) |
| M-5 | agree | расширение: `create_failures{reason=pull\|mount\|ceiling\|docker}`, `image_pull_backoff` — детализируют мой H3/D-контур |
| M-6 | agree | расширение: `idempotency_replays/conflicts` — у меня только cleanup |
| M-7 | duplicate (M1, §2) | sse/relay gauge у меня; connects/disconnects — тривиальная добавка, беру |
| M-8 | duplicate (M1, disk_free_bytes) | разбиение по томам (postgres/workspace/docker) — беру; их повод («JVM не должен du») к моему решению не относился: `File.getUsableSpace` рекурсии не делает |
| M-9 | agree | pinned-виртуальные-потоки метрика — дешёвая страховка: JEP 491 убирает pinning от `synchronized`, но не JNI/native-кейсы |
| M-10 | duplicate (M2) | кардинальность — то же правило |
| M-11 | agree | новое: `instance`-label + таблица «как агрегировать» — дизайн допускает несколько инстансов, мои gauge без этого правила смешаются |
| H-1 | duplicate (H1) | health-группы совпадают; их admission («не ready → не берём tryStart») беру с поправкой из H-5 |
| H-2 | duplicate (H1, H3) | условия совпадают (у меня helper-образ отдельным RISK) |
| H-3 | duplicate (H1) | startup probe — у меня |
| H-4 | duplicate (H2) | их формулировка **точнее моей**: DOWN только при «кэш JWKS пуст/просрочен И Keycloak недоступен» вместо моего тайм-гистерезиса — заменяю |
| H-5 | agree | новое: при недоступном docker readiness DOWN, но POLL продолжает БД-сканы (LOST-синтетика, WAIT_*-переоценки) — у меня не расписано, иначе dead-man ложно сработает; тумблер degraded-режима — беру |
| T-1 | duplicate (T1) | та же рекомендация (correlation-first, OTel opt-in); ADR-оформление D-37 — беру |
| T-2 | duplicate (T2) | их глубже (span-линки, сериализация контекста), принцип общий: `turn.id` — главная нить |
| T-3 | duplicate (L1, §4) | traceId в MDC-логах — у меня |
| D-1 | duplicate (D1) | нюанс: их prod-compose поднимает Keycloak на VM — **расходится с базисом** «Keycloak — внешний SSO»; у меня Keycloak только в dev-compose |
| D-2 | duplicate (D1) | порядок старта и гейты — у меня |
| D-3 | duplicate (D3) | их тонкость верна и ценна: Preliquibase выполняется **до** `DATABASECHANGELOGLOCK` — конкуренция возможна раньше лока; беру |
| D-4 | duplicate (D2) | pg_dump+WAL+drill совпадают; «pg_dump не через docker exec, отдельный cron» — беру |
| D-5 | agree | новое: retention workspace-каталогов (после архивации сессии/терминала задачи) — у меня только алерт на диск, политики очистки нет; watermark 85/95 — беру |
| D-6 | duplicate (L4) | ротация — у меня |
| D-7 | duplicate (C2) | секреты в .env — у меня |
| D-8 | duplicate (H3, D1) | единый иммутабельный тег helper-образа — у меня (git-sha) |
| D-9 | duplicate (D4) | их усиление («socket-proxy — обязательный пункт эволюции, не опция») — беру |
| D-10 | agree | новое: явно записать принятие SPOF single-Postgres — дёшево, честно |
| C-1 | duplicate (C1) | каталог env + правило yml-vs-env — у меня |
| C-2 | duplicate (C1) | .env.example/dev-профиль — у меня |
| C-3 | duplicate (C3) | их `MaxRAMPercentage=70`, `ExitOnOutOfMemoryError`, `HeapDumpPath` — беру (лучше фиксированного -Xmx в контейнере); по GC остаюсь при «G1 базово / ZGC по замеру M1» — безусловный ZGC преждевременен |
| C-4 | duplicate (C3) | heap-расчёт согласуется; их `render_bytes` метрика — беру |
| C-5 | agree | новое: fail-fast валидация конфига (@Validated) + инварианты диапазонов (`containerCeiling ≥ bulkhead`, `lockTtl > shutdownDrain`) — у меня нет |
| C-6 | agree | новое и важное: версионируемый формат шифротекста (`v1:<keyId>:<ct>`) + reencrypt-команда — иначе ротация `HARNESS_SECRET_KEY` (мой C2) необратимо ломает все `llm_credentials`. Закрывает дыру моего C2 |
| A-1 | duplicate (A1) | их условие №1 через `eligible_oldest_age_seconds` **лучше моего count-порога** (независим от нагрузки) — заменяю |
| A-2 | duplicate (A1) | канал/runbook/no-actionable-no-alert — у меня |
| A-3 | duplicate (A1, №5) | dead-man через timestamp — то же |
| A-4 | agree | новое: черновые SLO (не-FAILED ≥95%, p95 TTFT, p95 queue-latency) — у меня нет; фиксацию значений согласен отложить до эксплуатации |

### aspect4-ops-mercury.md — 7 областей: **7 duplicate / 1 agree / 8 disagree-нюансов**

| Их область | Вердикт | Комментарий |
|---|---|---|
| 1 Логи | duplicate (L1, L3, L4) + **disagree** по решению | находки — подмножество моих. Их решение «файловые логи + logrotate» (`application.log`, `bash-audit.log` в контейнере) — хуже: файловые appender'ы в контейнере против 12-factor; stdout + json-file caps (моё, совпадает с D L-6) |
| 2 Метрики | duplicate (M1 + §2) | их список — подмножество моего (12 метрик против ~25) |
| 3 Health | duplicate (H1) + **disagree** ×2 | (а) «heap usage < 90% в liveness» — опасно: высокий heap — норма, получим рестарт-петли; liveness — только живость процесса (OOM-детект — `ExitOnOutOfMemoryError`, C-3). (б) Keycloak жёстко в readiness без гистерезиса — мой RISK H2 (флап SSO роняет весь сервис) |
| 4 Трейсинг | duplicate (T1) | та же рекомендация (MDC-корреляция как MVP, OTel — после M3) |
| 5 Деплой | duplicate (D1, D3, D2) + **disagree** ×3 + partial | (а) `deploy.restart_policy` — swarm-синтаксис, в docker compose игнорируется (нужно `restart: unless-stopped`); (б) бэкап cron'ом на **той же VM** без выноса — прямо мой RISK D2 («бэкап на той же VM не бэкап»); (в) пин postgres:15 без нужды отстаёт. WAL «отключить для MVP» — осознанное ослабление RPO до 24ч: допустимо, но должно быть зафиксировано как выбор (моё D2 это и требует) |
| 6 Конфигурация | duplicate (C1, C3) + **disagree** ×1 | «`--enable-native-access=ALL-UNNAMED` (для виртуальных потоков)» — фактическая ошибка: флаг про FFM/JNI restricted methods, к виртуальным потокам отношения не имеет; реальный кандидат — unix-socket транспорт docker-java (проверить на M1, как у меня в C3) |
| 7 Алертинг | duplicate (A1) + **disagree** ×1 + agree ×1 | `rate(session.eligible.queue.size[5m]) > 0` — `rate()` на gauge семантически некорректен (надёжнее прямой порог/deriv; правильнее — oldest-age, см. A-1 DeepSeek). Их прямой алерт `postgres down` — согласен, добавляю (у меня покрывается только косвенно через dead-man №5) |

### Встречные наблюдения

- **Мой H4 (stop_grace_period 10с < дренаж 30с) отсутствует у обоих** — и в их манифестах тоже: оба compose воспроизводили бы SIGKILL посреди дренажа на каждом деплое. Это усиливает мой топ-1.
- Ни один из ревьюеров не оспорил мои H2/H3/L2 — пересечение консистентно.
- Беру себе (апгрейды этого документа): `turnId`/`wakeSource` в MDC; поправку L3 (полный stdout не хранится); JWKS-формулировку H2; H5 (docker-down → POLL продолжает БД-сканы); алерт №1 через oldest-age; security-логгер L-7; C-5/C-6 (fail-fast конфиг + версионируемый шифротекст); D-5 (retention workspace); D-10 (запись SPOF); M-3/M-5/M-6/M-9/M-11 (locks_stolen, create_failures, replays/conflicts, pinned, instance-агрегация); management-порт 8081; `MaxRAMPercentage`/`ExitOnOutOfMemoryError`.
- Их топ-3 согласуются с моим по M1 и D2; «корреляция» (топ-3 DeepSeek) у меня в L1/T2 ниже рангом — после апгрейда L-2 готов поднять до топ-3; «stop_grace_period» в их списках нет ни у кого.
- Итог по цифрам: DeepSeek 47 = 31 duplicate + 15 agree + 1 disagree; Mercury 7 = 7 duplicate + 1 agree + 8 disagree-нюансов в решениях (находки — все дубликаты моих).

---

## Fixes approval (судейский фикс темы 4)

> Проверка судейских решений против моих находок (L1–L4, M1–M2, H1–H4, T1–T2, D1–D4, C1–C3, A1).

| # | Фикс | Вердикт | Одна строка |
|---|---|---|---|
| 1 | Новый `docs/design/operations.md` (§1–§7) | **approve** | моя посадка; при вставке проследить, что §1 включает **список маскирования** (мой L2: capability-токены URL, `?ticket=`, Authorization, `task.params`, промпты) + правку security §3 |
| 2 | `key_version` у `llm_credentials` (data-model) | **approve** | реализует C-6 (ротация ключа шифрования без потери credentials); колонка проще embed-формата `v1:<keyId>:<ct>` — достаточно |
| 3 | D-37 (трейсинг-ADR) | **approve** | дословно мой T1 / DeepSeek T-1 |
| 4 | Ссылка на operations.md в AGENTS.md | **approve** | housekeeping, без возражений |
| 5 | (1) Трейсинг: MDC MVP, OTel-мост opt-in, экспортёр выключен | **approve** | совпадает с моим T1; при включении экспортёра — 0 изменений кода |
| 6 | (2) Keycloak readiness: гистерезис 3 неудач | **approve** | эквивалент моего H2 (счётчик вместо тайм-окна/состояния JWKS); цель та же — флап SSO не роняет трафик; интервал проверок зафиксировать (при 60с кэше ≈ 3 мин терпимости) |
| 7 | (3) docker-readiness: тумблер, default false | **approve** с условием | принимаю (LLM-only/MCP-работа жива, ERROR/LOST-пути инструментов уже спроектированы — execution-model §1), но обязательные компенсации: critical-алерт `harness_docker_up == 0` + проверка helper-образа в деплой-скрипте до старта (мой D1/H3 уходит из readiness в deploy-time) |
| 8 | (4) WAL включён, RPO ≤ 5 мин + off-VM dump | **approve** | дословно мой D2 (archive_timeout 60с + вынос копии); корректно отвергает mercury-вариант «WAL выключить» и его same-VM cron |
| 9 | (5) Метрики ~30, bounded-кардинальность | **approve** | совпадает с моей таблицей §2 (~25–30) и правилом M2 (теги только из ограниченных множеств) |
| 10 | (6) relay audit-файл — рекомендация, не контракт | **approve** | моя позиция в споре L-8 принята; рекомендацию — в `client-cli.md`, серверную фиксацию register/disconnect/tool.call — оставить |
| 11 | (7) `stop_grace_period: 45s` | **approve** | мой H4 (топ-1) закрыт; не забыть зеркальную строку в execution-model §8 |

**Итог**: 11/11 approve, 0 reject (1 — с обязательными компенсациями). Все три позиции моего топ-3 закрыты: H4 → фикс 11, M1+A1 → операции.md (фиксы 1, 9), D2 → фикс 8. Открытым остаётся только контроль, что маскирование L2 реально вошло в §1 при переносе текста.
