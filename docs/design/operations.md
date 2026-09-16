# Наблюдаемость и эксплуатация spring-harness

> Итог темы 4 (ревью + кросс + судья). Спорные пункты разрешены в тексте; ADR — D-37.

## 1. Логи

- Структурированный JSON **только на stdout** (контейнер-паттерн; файлы/logrotate не заводим).
- Корреляция через MDC, поля: `instanceId, sessionId?, taskId?, turnId?, wakeSource?` — Turn пересекает HTTP/POLL/vthread/Docker/late-result, без них разбор инцидента невозможен.
- **Маскирование (обязательное)**: `api_key` (llm_credentials), заголовки `Authorization`, билеты (`?ticket=`), capability-токены вебхуков (`/api/webhooks/**/{token}` — путь редактируется), `task.params`, промпты/контент сообщений в лог-строках. Перенос MDC на executor'ы/виртуальные потоки — через декоратор задач (контекст копируется в дочерний поток).
- Компенсации docker-тумблера: крит-алерт `harness_docker_up == 0`; deploy-time проверка наличия helper-образа в деплой-скрипте.
- Экспозиция: `management.server.port=8081`, `/actuator/prometheus` (micrometer-registry-prometheus).
- Аудит исполнения: контейнерный stdout агентского bash не durable — полный вывод живёт в `TOOL_RESULT` сессии (квантован лимитом вывода) и в `reason` переходов `BASH_SCRIPT`.

## 2. Метрики (Micrometer + Actuator; кардинальность bounded — без label'ов sessionId/taskId)

| Домен | Метрики |
|---|---|
| Очередь/шедулер | eligible-размер (gauge), POLL-длительность (histogram), wake по источникам (counter POLL/EVENT/WEBHOOK/TASK_EVENT) |
| Turn'ы | активные (gauge), длительность (histogram), исходы COMPLETED/FAILED/CANCELLED (counters), парковки async/PARKED_CLIENT (counters) |
| LLM | токены in/out (counters), латентность wake→первый токен и ход целиком (histograms), 429/5xx + ретраи (counters), bulkhead-занятость (gauge) |
| Контейнеры | живые/потолок (gauges), вытеснения idle, reconcile-удаления сирот (counters), отказы pull/mount (counters) |
| API | SSE-подписки (gauge), rps/латентности эндпоинтов (стандартные http-метрики), 429-rate |
| Инфра | idempotency-чистка (строк/цикл), БД-пул занятость, instance-реестр размер |

## 3. Health

- **Readiness**: БД-ping + «Liquibase завершён» + Keycloak JWKS **с гистерезисом** (DOWN после 3 неудач подряд — иначе микросбой SSO выкидывает из балансировки) + docker-сокет **за тумблером** `harness.health.docker.critical` (default `false`: одномашинный MVP переживает рестарт докера без выкидывания трафика).
- **Liveness**: только живость процесса (heap-пороги — анти-паттерн, рестарт-петли).

## 4. Трейсинг

MVP — correlation-логи (§1). Мост Micrometer Tracing/OTel — **opt-in с выключенным экспортёром** (включается конфигом без код-изменений; D-37).

## 5. Деплой (docker-compose на выделенной VM)

- `postgres` (пин минорной версии; **WAL-архивирование включено, RPO ≤ 5 мин**; ежедневный `pg_dump` — выгрузка **off-VM**; restore-drill раз в квартал).
- `keycloak` (внешняя зависимость или контейнер в compose — по инфраструктуре компании).
- `orchestrator`: `stop_grace_period: 45s` — **больше окна дренажа 30с** (иначе SIGKILL посреди graceful shutdown).
- helper-образ собирается при деплое (наличие на VM локально — обязательно).
- Миграции: Liquibase при старте; конкурентный старт исключён (flock/lock Liquibase; rolling — по одному инстансу).
- Бэкапы аудита: сессии/переходы — часть БД (WAL+dump).

## 6. Конфигурация

- 12-factor: всё окружение — env (`HARNESS_*`); `application.yml` — только структурные дефолты; `.env` в dev (spring-dotenv уже в pom).
- JVM: heap 2–4 ГБ (MB-payload рендер), виртуальные потоки включены штатно.

## 7. Алерты (минимум)

1. eligible-очередь > 50 и растёт 10 мин (шедулер не справляется / LLM лежит).
2. LLM error-rate > 10% за 5 мин (включая исчерпание ретраев).
3. Живые контейнеры ≥ 90% потолка.
4. Диск workspace-тома > 80%.
5. POLL-длительность p95 > 1 с (скан деградировал).

## 8. Не контракт

Локальный audit-файл relay-клиента (`~/.harness/logs/relay-audit.jsonl`) — **рекендация в client-cli**, не серверный контракт (окружение клиента вне нашего контроля).
