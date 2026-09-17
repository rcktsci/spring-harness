# Наблюдаемость и эксплуатация spring-harness

> Минимум для MVP. Всё, что не здесь, — не делаем.

## 1. Логи

- Структурированный JSON **только на stdout**.
- Корреляция через MDC: `sessionId?, taskId?, turnId?`.
- Маскирование: `api_key`, `Authorization`, билеты (`?ticket=`), capability-токены вебхуков, `task.params`.

## 2. Метрики и health

- **Actuator + Prometheus** (`management.server.port=8081`, `/actuator/prometheus`) — стандартные метрики http/jvm/db + прикладные Micrometer по мере надобности.
- Health: базовый readiness (БД + конфиг), liveness — процесс. Никаких гистерезисов и тумблеров.

## 3. Деплой (docker-compose на VM)

- `postgres`, `orchestrator`, helper-образ собирается при деплое.
- Миграции — Liquibase при старте (один инстанс — без конкурентного старта).
- Трейсинг: correlation-логи (§1), ничего больше. Graceful shutdown не проектируем (D-41): передеплой = рестарт, локи истекут, POLL поднимет.

## 4. Конфигурация

12-factor: env (`HARNESS_*`); `application.yml` — структурные дефолты; `.env` в dev. **Все числа — конфиг** (`@ConfigurationProperties`), хардкод запрещён (architecture §4).

## 5. Вне MVP

Бэкапы/WAL-политики, restore-drills, алерты, OTel-мост, SLA-дашборды — не делаем; вернёмся при реальной эксплуатации.
