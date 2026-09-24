# Tasks: Deployment README

## 1. Инфраструктура деплоя

- [ ] 1.1 `docker/Dockerfile.orchestrator` — multi-stage (temurin-25-jdk + `./mvnw -DskipTests package` → temurin-25-jre + boot-jar). Верификация: `docker build -f docker/Dockerfile.orchestrator -t spring-harness:local .` проходит локально, `docker run --rm spring-harness:local java -version` печатает 25.x.
- [ ] 1.2 `docker-compose.yml` — сервисы `postgres:17` (том `pgdata`, healthcheck pg_isready) и `orchestrator` (build из 1.1, `depends_on: postgres: condition: service_healthy`, монтирование `/var/run/docker.sock` и `HARNESS_WORKSPACE_ROOT` → одинаковый путь в контейнере, env-блок: DB_*, KEYCLOAK_ISSUER_URI/JWKS_URI, HARNESS_WEBHOOK_SECRET, HARNESS_LLM_KEY_V1, HARNESS_WORKSPACE_ROOT — секреты-плейсхолдеры `CHANGE_ME`); сеть по умолчанию, без publish лишних портов кроме 8080 (API) и 8081 (actuator). Верификация: `docker compose config` валиден без warnings о missing env.

## 2. README

- [ ] 2.1 Корневой `README.md`, раздел «Бэкенд»: пререквизиты VM (docker + плагин compose, доступ к sso.rocketscien.se), шаги — clone → собрать helper-образ (`docker build -f docker/Dockerfile -t harness-helper:local .`) → при необходимости pre-build jar вместо in-container build (альтернатива из design Risks) → заполнить env в compose (таблица: имя, назначение, пример; запрет коммитить секреты) → `docker compose up -d` → проверка (`/actuator/health` на 8081, логи контейнера, Liquibase накатился). Верификация: текст присутствует, команды копипастой выполняются на VM (первый прогон владельца).
- [ ] 2.2 README, раздел «Keycloak» (realm `rcktsci`): клиент десктопа — public + Standard flow + PKCE S256 + redirect `http://127.0.0.1/*` + Web Origins (уже заведён владельцем — зафиксировать как чеклист для воспроизведения); mapper Group Membership → claim `groups` (full path off) на client-scope клиента; пользователь — член группы `harness-users`; предупреждение: без маппера — 403 на всё; «не менять realm-дефолты». Верификация: чеклист воспроизводим с нуля на тестовом realm.
- [ ] 2.3 README, раздел «Клиент (Web Desktop)»: два пути — dev (`cd web-desktop && pnpm install && pnpm dev`, Settings: baseUrl = `http://<vm>:8080`, issuer = `https://sso.rocketscien.se/realms/rcktsci`, clientId; Login) и упакованный (`pnpm build && pnpm package:win`, дистрибуция по operations.md §6); проверка первого сценария (логин → создание сессии → отправка → ответ); ссылки: operations.md, web-desktop-client.md, web-desktop/docs/smoke.md. Верификация: сценарий выполняется владельцем локально против VM.
- [ ] 2.4 README, шапка: 3 строки о проекте (агентный harness: сессии, workflow, субагенты, MCP, десктоп-клиент), стек, ссылки на docs/design/* и AGENTS.md. Верификация: читается как точка входа, без дублей содержимого доков.

## 3. Верификация и закрытие

- [ ] 3.1 Прогон владельца на VM по README: compose поднялся, health зелёный, KC-логин проходит, сессия отвечает, bash-инструмент исполняется в helper-контейнере. Расхождения текста с реальностью — правки в README в рамках этого change. Верификация: владелец подтвердил прогон.
- [ ] 3.2 `openspec validate deployment-readme --strict`; коммит оркестратора после ревью-цикла. Верификация: validate — valid.
