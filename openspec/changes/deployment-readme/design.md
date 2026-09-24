# Design: Deployment README

## Context

Сервер (M1–M4, 560 тестов) и клиент (M5, 166+7 тестов) готовы, но разворачивание описано разрозненно: `operations.md` §3 (декларативно: «postgres, orchestrator, helper-образ собирается при деплое»), §6 (сборка клиента), `application.yml` (env-переменные). В репо нет `docker-compose.yml` и нет Dockerfile самого оркестратора — есть только helper-образ `docker/Dockerfile` (`harness-helper:local`). KC — внешний корпоративный (`https://sso.rocketscien.se/realms/rcktsci`, клиент десктопа заведён владельцем: public + Standard flow + PKCE S256).

## Goals / Non-Goals

**Goals**:
- Воспроизводимый подъём бэкенда одной командой `docker compose up -d` на чистой VM с docker.
- Пошаговая настройка KC (раздел README): что кликнуть, какие мапперы, какая группа.
- Запуск клиента в двух режимах: dev (`pnpm dev`) и упакованный (NSIS/AppImage).
- Без `.env`-файлов: все параметры — env-блок в compose + дефолты `application.yml`.

**Non-Goals**:
- Production-hardening: реплики, реверс-прокси, TLS на API, secret-management (D-41: «сломалось — пофиксили — передеплоили»).
- Local Keycloak в compose (realm корпоративный; тестовый realm — только в автотестах).
- Автообновление клиента, CI/CD-пайплайн деплоя.
- MCP-серверы, вебхуки наружу (пустые по умолчанию; ссылки в README на доки).

## Decisions

### D-R1: Compose = postgres + orchestrator, KC внешний

**Решение**: два сервиса. Keycloak — корпоративный SaaS, в compose не входит.

**Альтернативы**: local Keycloak в compose — дублирует корпоративный, усложняет (realm-импорт, users), а целевой сценарий владельца — корпоративный realm. Local KC остаётся возможным вручную (README упоминает одной строкой).

**Почему**: меньше движущихся частей; issuer один и тот же на сервере и в клиенте.

### D-R2: Orchestrator — новый multi-stage Dockerfile (в репо), сборка внутри compose

**Решение**: `docker/Dockerfile.orchestrator`: stage 1 `eclipse-temurin:25-jdk` + `mvn -q -DskipTests package` (типовый билд-кэш docker); stage 2 `eclipse-temurin:25-jre`, копирование boot-jar, `ENTRYPOINT java -jar`. В compose секция `build:` → image `spring-harness:local`. `/var/run/docker.sock` монтируется в orchestrator (D-30: docker-java создаёт per-session контейнеры). Helper-образ собирается заранее отдельной командой `docker build -f docker/Dockerfile -t harness-helper:local .` (прописана в README шагом 2 — compose его не строит: docker-java только запускает локально существующий образ, pull-логика рассчитана на его присутствие).

**Альтернативы**: запуск jar на хосте VM без контейнера — ломает D-30 (docker-java из контейнера с примонтированным сокетом); сборка образа вне репо — не воспроизводимо.

**Почему**: соответствует design (архитектура: «оркестратор — контейнер на выделенной VM»); воспроизводимо с нуля.

### D-R3: Все параметры — env в compose, значения владельца — inline

**Решение**: в compose секция `environment:` с обязательными переменными (`DB_*`, `KEYCLOAK_ISSUER_URI`, `KEYCLOAK_JWKS_URI`, `HARNESS_WEBHOOK_SECRET`, `HARNESS_LLM_KEY_V1`) — владелец правит значения прямо в файле перед первым запуском (приватная VM, git не хранит секреты: в compose плейсхолдеры вида `CHANGE_ME`). `HARNESS_WORKSPACE_ROOT` — абсолютный путь на хосте VM (`/srv/harness/workspaces`), тот же путь монтируется томом в orchestrator (bind-mount резолвится демоном от ФС хоста — комментарий в application.yml).

**Альтернативы**: `.env` + `env_file:` — владелец явно просил «без .env»; docker secrets — энтерпрайз-раздутие.

**Почему**: один файл, ничего не забывается; плейсхолдер `CHANGE_ME` виден как ошибка при первом прогоне (LLM-ключ пустой → `harness.llm.encryption-keys.1` пуст → сессия честно падает на первом Turn).

### D-R4: groups-claim — через клиентский scope/mapper в KC

**Решение**: в KC на клиенте десктопа (или в realm client-scope, назначенном ему) добавить mapper **Group Membership** → token claim `groups` (full path off); пользователь — член группы `harness-users` (дефолт `harness.security.allowed-groups`). README фиксирует: без маппера сервер отвечает 403 на всё (SSO-гейт api-contracts §1).

**Альтернативы**: roles-маппер (вместо groups) — потребует менять `allowed-groups` на имена ролей; realm-level default scope — затрагивает всех клиентов realm'а.

**Почему**: application.yml уже ждёт claim `groups`; минимальное вторжение в чужой realm (один клиент + его scope).

### D-R5: README в корне, доки остаются истиной

**Решение**: корневой `README.md` — единственная точка входа: quick-start (3 раздела владельца), таблица обязательных env, ссылки на `docs/design/operations.md` (наблюдаемость), `web-desktop-client.md` (клиент), `web-desktop/docs/smoke.md` (проверка). Для KC — полный пошаговый листинг (realm чужой, кнопки важны).

**Альтернативы**: instructions только в docs/ — корень без README выглядит заброшенным; README = зеркало operations.md — рассинхрон.

**Почему**: минимум для MVP, без дублей.

## Risks / Trade-offs

- [Compose-файл не прогнан на реальной VM] → первый прогон владелец делает по README; правки в рамках этого change (до архивации). Обязательный шаг в tasks: «прогнать на VM, зафиксировать расхождения».
- [Сборка mvn внутри контейнера требует интернет/зеркало maven на VM] → README: альтернатива — собрать jar на машине разработки (`mvnw -DskipTests package`) и разкомментировать блок `image:` без `build:`; docker build кэш слои зависимостей.
- [helper-образ забыли собрать] → orchestrator при первом bash-инструменте упадёт с «образ не найден»; README шаг 2 + проверка `docker images harness-helper`.
- [Плейсхолдер CHANGE_ME уедет в git с реальным секретом] → README прямо запрещает коммитить правки compose с секретами; файл в .gitignore не прячем (нужен в репо), но владелец предупреждён.
- [KC — общий корпоративный realm] → изменения минимальны и локальны (клиент десктопа + его scope); README предупреждает не менять realm-дефолты.

## Migration Plan

Нет миграций кода. Liquibase накатывает схему на старте orchestrator (пустая БД postgres из compose). Откат = `docker compose down` (том postgres сохранить/удалить по необходимости).

## Open Questions

- Нет. Неясности первого прогона фиксируются в tasks как шаг «прогнать и поправить».
