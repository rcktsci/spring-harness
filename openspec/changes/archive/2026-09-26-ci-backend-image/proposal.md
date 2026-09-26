# CI: Backend Image

## Why

Сейчас образ backend-а (на базе `docker/Dockerfile.orchestrator`, из change `deployment-readme`) собирается вручную на dev-машине владельца через `docker compose build`, а на VM нужно воспроизводимое обновление после каждой правки в `main`. На VM нет ни JDK, ни Maven — только docker — поэтому пересборка вручную невоспроизводима. Нужен pipeline, который по push в `main` сам собирает образ и публикует его в GitHub Container Registry, а VM делает `docker pull`.

## What Changes

- **Новый GitHub Actions workflow** `.github/workflows/backend-image.yml`: триггеры — `push` в ветку `main`, `push` тегов `v*` и `workflow_dispatch` (ручной перезапуск). Шаги: `actions/checkout@v7` → `docker/setup-buildx-action@v4` → `docker/login-action@v4` в GHCR под `GITHUB_TOKEN` (право `packages: write` объявляется явно в `permissions:` workflow) → проверка согласованности версии (только на прогоне тега: версия в `pom.xml` и `web-desktop/package.json` должна совпасть с тегом) → `docker/metadata-action@v6` (теги и OCI label'ы) → `docker/build-push-action@v7` с контекстом `.`, `dockerfile: docker/Dockerfile.orchestrator`, кэш слоёв через GHA cache (`type=gha`). Публикация в `ghcr.io/rcktsci/spring-harness-orchestrator`: на пуше в `main` — теги `main`, `sha-<short>`, `latest`; на теге `vX.Y.Z` — теги `vX.Y.Z`, `X.Y.Z`, `sha-<short>`.
- **Релизная политика SemVer** (расширение этого же change, решения владельца 2026-09-24): одна версия на весь продукт (backend + web-desktop), источник истины — git-тег `vX.Y.Z`, старт с `v0.1.0`. Релиз = коммит, в котором версия в `pom.xml` (версия самого проекта, не `<parent>`) и `web-desktop/package.json` совпадает с тегом, плюс запись в `CHANGELOG.md`; CI на теге падает при расхождении версий и при попытке переиспользовать уже опубликованный тег (релизные теги неизменяемы). SemVer-правила проекта: MAJOR — breaking в `api/openapi.yaml` либо в WS/SSE-контракте; в `0.x` breaking допустим в MINOR с явной пометкой `**BREAKING**`; PATCH — баги. Breaking обязан быть помечен в change/PR и проверяется на ревью — автоматика только подсказывает bump, но не блокирует.
- **Новый `CHANGELOG.md`** в корне (формат Keep a Changelog): запись на каждый релиз, breaking-изменения — всегда с миграцией; ссылка на тег в заголовке версии.
- **Новый `.dockerignore`** в корне: исключает `.git/`, `target/`, `web-desktop/node_modules/` (отдельный npm-проект, десятки МБ), IDE/инструментальные артефакты (`.idea/`, `.vscode/`, `.opencode/`), `openspec/changes/` (черновики и архивы — в контексте не нужны). Сейчас `.dockerignore` отсутствует (зафиксировано 2026-09-24), и `docker build` отправляет в buildx лишнее; это отдельная гигиеническая правка, которую ловим заодно с введением CI.
- **Non-goal**: запуск `mvn verify` (или иной прогон тестов) в CI — **не входит** в scope этого change. `docker/Dockerfile.orchestrator` уже собирает boot-jar с `-DskipTests` (D-R2 / deployment-readme): образ предназначен для деплоя, а не для прогона тестов. Тесты в CI — отдельный будущий change со своим JDK/Maven-шагом и test-контекстом (Keycloak/Postgres). В этом change раннеру достаточно docker — JDK/Maven на нём не ставятся. Сюда же — автоматическая публикация desktop-инсталлеров (сборка клиента остаётся локальной) и автоматический деплой на VM: доставка = `docker pull` по semver-тегу.
- Актуальные версии actions (проверено 2026-09-24): `actions/checkout@v7`, `docker/login-action@v4`, `docker/setup-buildx-action@v4`, `docker/build-push-action@v7`, `docker/metadata-action@v6`.

## Capabilities

### New Capabilities

- `backend-image-ci`: поведение CI — push в `main` репозитория `github.com/rcktsci/spring-harness`, push тега `v*` (и ручной `workflow_dispatch`) запускает пересборку backend-образа из `docker/Dockerfile.orchestrator` и публикацию в GitHub Container Registry под именем `ghcr.io/rcktsci/spring-harness-orchestrator`: на ветке — теги `main`/`sha-<short>`/`latest`, на теге — semver-теги `vX.Y.Z`/`X.Y.Z`/`sha-<short>`; единственный секрет — встроенный `GITHUB_TOKEN` с `packages:write`.
- `release-policy`: релизная политика продукта — единая SemVer-версия на backend + web-desktop, источник истины — git-тег `vX.Y.Z` (старт `v0.1.0`), релиз-коммит с согласованными версиями в `pom.xml`/`package.json` и записью в `CHANGELOG.md`, CI-гейт на теге, правила bump (MAJOR = breaking контракта; в `0.x` breaking = MINOR с пометкой `**BREAKING**`) и трассируемость версии на VM.

### Modified Capabilities

<!-- нет: ни одна существующая спека не меняется -->

## Impact

- **Новые файлы**: `.github/workflows/backend-image.yml`, `.dockerignore`, `CHANGELOG.md`.
- **Изменяются**: `pom.xml` и `web-desktop/package.json` — версия в них меняется только в релиз-коммите (сейчас расходятся: `1.0.0-SNAPSHOT` и `0.1.0`; первый релиз приводит обе к `0.1.0`).
- **Не меняется**: `docker/Dockerfile.orchestrator`, `docker-compose.yml`, `application.yml`, исходники и тесты — это первое CI-применение уже существующего Dockerfile; из существующих спек не затрагивается ни одна (`session-api`, `agent-turn`, `workspace-tools`, `client-relay` и др.).
- **Внешние системы**: GitHub Container Registry (GHCR): первый push создаёт пакет приватным, владелец один раз переключает его на **публичный** (решение владельца 2026-09-24) — после этого `docker pull` на VM идёт без логина и без PAT; способ потребления образа — в README.
- **Секреты**: только встроенный `GITHUB_TOKEN` с правом `packages:write` (выдаётся раннеру автоматически). Никаких внешних registry-секретов, никаких SSH-ключей до VM, никаких `DOCKERHUB_*` — образ публикуется только в GHCR.
- **Поведение приложения**: не меняется (рантайм-контракт API/WS/SSE остаётся прежним; меняется способ доставки образа на VM).
- **Стоимость GHCR**: бесплатно для private-пакетов в пределах лимитов GitHub Free/Team; для внутреннего MVP на одной VM нагрузка пренебрежима.
- **Следующий шаг вне scope**: добавить отдельный CI-workflow на прогон тестов (`mvn verify` + compose-test-контекст с Keycloak) — отдельный change.