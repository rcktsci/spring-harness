# Observability и эксплуатация: GAP-анализ

## 1. Логи

**Что в доках:**
- `security-multitenancy.md §5`: "Append-only журналы: `session_message`, `task_transition_history`, `reason` вебхуков... Секреты не логируются."
- `execution-model.md §4`: контейнерное исполнение bash через `WorkspaceTools`

**GAP/RISK:**
- Нет политики структурированного логирования (JSON vs text)
- Нет MDC-контекста (sessionId, taskId, turnId) для трассировки через все модули
- Нет логирования исполнения bash-скриптов в контейнерах (аудит безопасности)
- Нет ротации/сборщиков логов

**Предложение (вставить в docs/design/security-multitenancy.md §5):**
```markdown
### 5.1 Структура логов
- Все логов приложения — JSON (`logback-spring.xml` с JSON-encoder).
- MDC на каждый Turn: `sessionId`, `taskId`, `turnId`, `instanceId`.
- Bash-команды в контейнерах логируются в отдельный поток (`container.bash`): команда, exit-code, длительность. Вывод команды → в `task_transition_history.reason` (не в application-лог).
- Пайплайн: `stdout` → `/var/log/spring-harness/application.log`; `container.bash` → `/var/log/spring-harness/bash-audit.log`; ротация через `logrotate` (10 МБ, 7 дней).
```

---

## 2. Метрики Micrometer

**Что в доках:**
- Нет упоминания Micrometer/Prometheus в доках.

**GAP/RISK:**
- Нет наблюдаемости ключевых SLO-метрик
- Невозможно детектировать деградацию (LLM latency растёт, очередь eligible растёт)
- Нет инвентаризации инстансов (live containers, capacity)

**Предложение (новое docs/design/operations.md §2):**
```markdown
## 2. Метрики (Micrometer + Prometheus)

### Бизнес-SLO
| Метрика | Тип | Описание |
|---------|-----|----------|
| `session.eligible.queue.size` | Gauge | count сессий где `last_seq > last_consumed_seq` |
| `session.turn.duration.seconds` | Histogram | длительность Turn (wake → finish) |
| `session.turn.outcome` | Counter | outcomes: COMPLETED, FAILED, CANCELLED, LOST |
| `llm.token.count` | Counter | total tokens (prompt+completion) по модели |
| `llm.latency.to-first-token.ms` | Histogram | время от запроса до первого токена |
| `llm.retry.count` | Counter | retry/429 по типу ошибки |
| `container.live.count` | Gauge | живые helper-контейнеры |
| `container.capacity.limit` | Gauge | дефолт 50 (конфиг) |
| `container.eviction.count` | Counter | вытеснения idle-контейнеров |
| `poll.duration.seconds` | Histogram | длительность POLL-скана |
| `sse.subscription.count` | Gauge | активные SSE-подписки |
| `idempotency.cleanup.deleted` | Counter | удалённые idempotency-записи |

### Инфраструктура
- JVM: `jvm.memory.used`, `jvm.threads.live`, `jvm.gc.pause`
- HTTP: `http.server.requests` (status, method, uri, outcome)
```

---

## 3. Health/readiness

**Что в доках:**
- Нет секции health checks

**GAP/RISK:**
- Оркестратор может принять трафик до готовности БД/Liquibase
- Неизвестно, доступен ли docker-sock для helper-контейнеров
- Нет distinction между liveness и readiness

**Предложение (новое docs/design/operations.md §3):**
```markdown
## 3. Health Checks

### Readiness
| Probe | Условия |
|-------|---------|
| `/ready` | Liquibase completed (`liquibase.changeLogHistory` не пустой) AND Postgres pool initialized AND docker.sock exists AND Keycloak OIDC issuer reachable (curl) |

### Liveness
| Probe | Условия |
|-------|---------|
| `/alive` | JVM не deadlock (thread dump check), heap usage < 90% |

### Startup
1. Liquibase run (preliquibase → liquibase)
2. Postgres pool warm
3. Readiness endpoint start (проверка docker.sock)
4. POLL-джоба старт (через 5s после readiness)
```

---

## 4. Трейсинг

**Что в доках:**
- Нет упоминания распределённого трейсинга

**GAP/RISK:**
- Нет end-to-end visibility (user request → LLM call → container execution)
- Корреляция логов через MDC помогает, но нет trace-context для отладки

**Предложение (новое docs/design/operations.md §4):**
```markdown
## 4. Трейсинг

### MVP (рекомендация)
- Использовать MDC-корреляцию как MVP-трассировку (`sessionId`, `taskId`, `turnId`).
- OpenTelemetry auto-instrumentation отложить до M3 (когда появятся async-инструменты).

### M3+ (если потребуется)
- OpenTelemetry Jaeger/Zipkin: traceId распространяется через MDC.
- Key spans: `session.eligible.check`, `llm.stream`, `container.tool.execute`.
```

---

## 5. Деплой-манифест

**Что в доках:**
- `architecture.md §4`: "оркестратор — контейнер на выделенной VM, `/var/run/docker.sock` смонтирован; helper-образ собирается из Dockerfile в репозитории и присутствует на VM локально"
- `execution-model.md §8`: graceful shutdown 30с

**GAP/RISK:**
- Нет docker-compose manifest для MVP
- Нет порядка старта компонентов (postgres → liquibase → app)
- Нет миграций конкурентной безопасности (preliquibase не описан)
- Нет политики бэкапов Postgres

**Предложение (новое docs/design/operations.md §5):**
```markdown
## 5. Деплой-манифест (MVP)

### docker-compose.yml
```yaml
version: "3.8"
services:
  postgres:
    image: postgres:15
    volumes: [postgres_data:/var/lib/postgresql/data]
    environment: [POSTGRES_USER=harness, POSTGRES_DB=harness]
    healthcheck: [test: pg_isready, interval: 5s, retries: 5]

  orchestrator:
    build: .
    volumes: [/var/run/docker.sock:/var/run/docker.sock]
    environment:
      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/harness
      - SPRING_LIQUIBASE_CHANGELOG=classpath:/db/changelog/db.changelog-master.yaml
    depends_on:
      postgres: [condition: service_healthy]
    deploy:
      restart_policy: [condition: on-failure, delay: 5s]

volumes: [postgres_data]
```

### Порядок старта
1. postgres (healthcheck)
2. preliquibase (пре-DDL, если нужно)
3. liquibase (DDL + миграции)
4. app start (ready = liquibase completed)

### Бэкапы Postgres
- pg_dump daily через cron: `pg_dump -h localhost harness | gzip > /backup/harness-$(date).sql.gz`
- WAL archiving: отключить для MVP (M3+ при росте >10GB)
- ротация: 7 дней локально + 1 месяц S3/Offsite

### Конкуренция миграций
- Liquibase changelog с checksum: concurrent start → одна инстанс выигрывает таблицу LOCK
- preliquibase: отдельный changelog, запускается до main liquibase
```

---

## 6. Конфигурация

**Что в доках:**
- `architecture.md §4`: "autoconfigurations disabled (`application.yml`): `DataSourceAutoConfiguration`... `LlmModel` вручную"
- `architecture.md §5`: "CLI bootstrap (читает env: base_url/key/model)"

**GAP/RISK:**
- Нет единого .env для локального дева
- Не описаны JVM-флаги (виртуальные потоки, heap под MB-payload)
- Нет distinction что в application.yml vs env

**Предложение (новое docs/design/operations.md §6):**
```markdown
## 6. Конфигурация

### 12-factor (env vs application.yml)
| Категория | application.yml | env |
|-----------|-----------------|-----|
| JDBC/Liquibase | template | `SPRING_DATASOURCE_URL`, `SPRING_LIQUIBASE_CHANGELOG` |
| Keycloak OIDC | issuer | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER` |
| LLM (bootstrap) | - | `BASE_URL`, `API_KEY`, `MODEL_ID` (CLI bootstrap) |
| JVM (флаги) | - | `JAVA_OPTS` |

### .env для дева (пример)
```
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/harness
BASE_URL=https://api.openai.com
API_KEY=sk-xxx
MODEL_ID=gpt-4o
CONTAINER_CAPACITY_LIMIT=10
```

### JVM-флаги (production)
- Виртуальные потоки: `--enable-native-access=ALL-UNNAMED` (JDK 25)
- Heap под payload: `-Xmx2g` (MB-payload rendering в memory)
- G1GC: `-XX:+UseG1GC -XX:MaxGCPauseMillis=200`
- Virtual threads: `spring.threads.virtual.enabled=true`
```

---

## 7. Алертинг (минимум)

**Что в доках:**
- Нет упоминания alerting-правил

**GAP/RISK:**
- Нет проактивного уведомления о деградации
- Риск silent failures (очередь растёт, LLM errors растут)

**Предложение (новое docs/design/operations.md §7):**
```markdown
## 7. Алертинг (минимум)

### 3–5 правил (Prometheus Alertmanager)

| Правило | Условие | Severity |
|---------|---------|----------|
| `session.eligible.queue.growing` | `rate(session.eligible.queue.size[5m]) > 0` | warning |
| `llm.error.rate` | `sum(rate(llm.retry.count{status=~"5xx|429"}[5m])) / sum(rate(llm.retry.count[5m])) > 0.1` | warning |
| `container.capacity.reached` | `container.live.count / container.capacity.limit > 0.8` | warning |
| `disk.workspace.low` | `100 - (node_filesystem_avail_bytes / node_filesystem_size_bytes) * 100 > 80` | critical |
| `postgres.down` | `up{job="postgres"} == 0` | critical |

### Интеграция
- Alertmanager → Slack channel `#spring-harness-alerts`
- Уведомления в PagerDuty (если включено)
```

---

## Сводка GAP/RISK

**Итого:** 7 областей, 7 GAP/RISK, 7 предложений

**Топ-3 риска:**

1. **Отсутствие метрик (GAP#2)** — система работает без наблюдаемости SLO, деградацию LLM latency/eligible queue детектировать невозможно
2. **Нет health checks (GAP#3)** — оркестратор может принимать трафик до готовности infra, causing request failures
3. **Бэкапы Postgres не определены (GAP#5)** — при потере данных восстановление не задокументировано, критично для append-only журналов

**Рекомендация:** реализовать GAP#2 и GAP#3 до M1-release (метрики и health checks — минимальный observability stack).

---

## Cross-check с GLM и DeepSeek

**Итого:** GLM: 20 находок (11 GAP + 9 RISK), DeepSeek: 47 находок (32 GAP + 15 RISK), Mercury: 7 GAP/RISK.

**Сводка по областям (Mercury: GLM: DeepSeek):**

| Область | Mercury | GLM | DeepSeek | Notes |
|---------|---------|-----|----------|-------|
| Логи | 4 GAP | 4 GAP + 1 RISK | 8 GAP + 3 RISK | GLM/DeepSeek более детально (ECS, turnId, security-audit, маскирование capability-токенов) |
| Метрики | 1 GAP | 1 GAP + 1 RISK | 11 GAP + 2 RISK | GLM/DeepSeek дают полный набор с кардинальностью-дисциплиной |
| Health | 1 GAP | 1 GAP + 4 RISK | 5 GAP + 2 RISK | GLM/DeepSeek добавляют startup probe, Keycloak hysteresis, degraded mode |
| Трейсинг | 1 GAP | 2 GAP + 1 RISK | 3 GAP + 1 RISK | GLM предлагает Micrometer Tracing + OTel bridge; DeepSeek — ADR D-37, ручные линки |
| Деплой | 4 GAP | 2 GAP + 2 RISK | 10 GAP + 4 RISK | GLM/DeepSeek: WAL archiving, workspace retention, docker.sock root, restore drill |
| Конфигурация | 3 GAP | 2 GAP + 1 RISK | 5 GAP + 1 RISK | GLM/DeepSeek: таблица env, .env.example, JVM (ZGC, heapdump) |
| Алертинг | 1 GAP | 1 GAP | 1 GAP | Все согласны (GLM: +poll_last_success_age, webhook failures) |

**Disagree (отличия в рекомендациях):**

1. **Трейсинг (Mercury: MDC-корреляция vs GLM: Micrometer Tracing):** Mercury предлагает отложить OTel до M3. GLM/DeepSeek рекомендуют Micrometer Tracing + OTel bridge с выключенным экспортёром по умолчанию — traceId/spanId уже в логах, включение бэкенда = одна переменная env.

2. **Health: Keycloak (Mercury: проверка issuer vs GLM: degraded mode):** Mercury предлагает проверку OIDC issuer в readiness. GLM/DeepSeek рекомендуют деградируемый индикатор — UP при закэшированном JWKS, DOWN только если кэш пуст и Keycloak недоступен.

3. **Health: docker (Mercury: readiness DOWN vs DeepSeek: тумблер):** Mercury предлагает readiness DOWN при недоступном docker. DeepSeek предлагает тумблер `harness.health.docker.critical` — degraded-режим для отладки на хосте без докера.

4. **Бэкапы (Mercury: WAL отключить до M3 vs GLM/DeepSeek: WAL включить):** Mercury предлагает WAL archiving отключить до M3. GLM/DeepSeek рекомендуют WAL включен с RPO ≤ 5 мин.

5. **Метрики (Mercury: упрощённый набор vs GLM/DeepSeek: полный набор):** Mercury предлагает ~12 метрик. GLM/DeepSeek предлагают 30+ метрик с кардинальностью-дисциплиной и правилами агрегации.

**Топ-3 расхождения, требующие решения:**

1. **Micrometer Tracing (T-4)** — GLM/DeepSeek: включить Micrometer Tracing + OTel bridge с выключенным экспортёром (traceId/spanId в логах сразу).

2. **Keycloak hysteresis (H-4)** — GLM/DeepSeek: деградируемый readiness-индикатор, а не жёсткая проверка issuer.

3. **WAL archiving (D-2)** — GLM/DeepSeek: включить сразу с RPO ≤ 5 мин, а не откладывать до M3.

---

## Fixes approval

| Находка Mercury | Статус | Примечание |
|-----------------|--------|------------|
| Метрики-наблюдаемость (GAP#2) | Approve | ~30 метрик (не ~12), кардинальность-дисциплина |
| Readiness Postgres/Liquibase/docker (GAP#3) | Approve | readiness = AND(db, liquibase, dockerExec); docker — тумблер, default false |
| Бэкапы-политика (GAP#5) | Approve | WAL archiving включён с RPO ≤ 5 мин (не отложено до M3) |
| Keycloak readiness | Reject | Гистерезис (деградируемый индикатор), а не жёсткая проверка issuer |
| Трейсинг MVP | Approve | MDC-корреляция принята (Micrometer Tracing + OTel bridge отложен) |
| Алертинг | Approve | 3–5 правил приняты (+poll_last_success_age добавлено) |

**Итого:** 5 approve, 1 reject (Keycloak hysteresis — судья уже решил, D-37).