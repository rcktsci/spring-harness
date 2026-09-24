# backend-image-ci Specification

## Purpose
CI-поведение репозитория: по push в `main` backend-образ (docker/Dockerfile.orchestrator) собирается и публикуется в GitHub Container Registry, откуда его забирает VM через `docker pull` вместо ручной пересборки на dev-машине.

## ADDED Requirements

### Requirement: Сборка и публикация образа по push в main

Pipeline SHALL собирать образ backend-а из `docker/Dockerfile.orchestrator` и публиковать его в GHCR при каждом push в ветку `main` репозитория `github.com/rcktsci/spring-harness`. Сборка SHALL использовать Dockerfile и исходники из закоммиченного коммита запуска. Push в другие ветки SHALL NOT запускать публикацию образа.

#### Scenario: push в main

- **WHEN** коммит смержен в `main`
- **THEN** запускается pipeline, образ backend-а собирается и публикуется в GHCR

#### Scenario: push в feature-ветку

- **WHEN** коммит запушен в ветку, отличную от `main`
- **THEN** публикация образа backend-а не запускается

#### Scenario: ручной перезапуск

- **WHEN** владелец запускает pipeline вручную (`workflow_dispatch`)
- **THEN** образ backend-а пересобирается и публикуется с теми же тегами, что и при push в `main`

### Requirement: Идентичность и теги публикуемого образа

Образ SHALL публиковаться в GHCR под именем `ghcr.io/rcktsci/spring-harness-orchestrator` и получать теги: `latest`, имя ветки (`main`) и короткий git-SHA коммита (`sha-<short>`), плюс OCI label'ы с источником и ревизией. Один и тот же тег SHALL всегда указывать на образ, собранный из соответствующего коммита.

#### Scenario: владелец тянет образ на VM

- **WHEN** владелец выполняет `docker pull ghcr.io/rcktsci/spring-harness-orchestrator:latest` с авторизованными креды
- **THEN** скачивается образ, собранный последним push в `main`, с тегами `latest`, `main`, `sha-<short>` для того же коммита

#### Scenario: откат на конкретный коммит

- **WHEN** владелец тянет тег `sha-<short>` известного коммита
- **THEN** получает ровно тот образ backend-а, который был собран при пуше этого коммита в `main`

### Requirement: Образ, пригодный для развёртывания на VM

Опубликованный образ SHALL запускаться с тем же env-контрактом, что и локальная сборка: конфигурация только через переменные окружения (в т.ч. `POSTGRES_*`, `MANAGEMENT_SERVER_PORT`), порты REST/WS/SSE 8080 и management 8081, точка входа — boot-jar, пользователь непривилегированный. CI SHALL NOT вносить в образ изменений относительно `docker/Dockerfile.orchestrator`.

#### Scenario: запуск опубликованного образа

- **WHEN** владелец запускает скачанный образ с env-контрактом из README и подключённой БД/Keycloak
- **THEN** backend поднимается и отвечает на management-порту, поведение не отличается от локальной сборки

### Requirement: Аутентификация и права pipeline

Публикация в GHCR SHALL выполняться только встроенным `GITHUB_TOKEN` с правом `packages: write`; workflow SHALL явно объявлять минимальные права (`contents: read`, `packages: write`). Репозиторий SHALL NOT содержать registry-паролей, PAT или иных секретов для публикации образа. Учётные данные Docker Hub для базовых образов SHALL NOT требоваться (базовые образы публичные).

#### Scenario: права workflow

- **WHEN** смотрят объявление `permissions` в workflow
- **THEN** видны только `contents: read` и `packages: write`, других прав нет

#### Scenario: попытка собрать без права публикации

- **WHEN** публикация невозможна (нет права `packages: write` у токена)
- **THEN** pipeline завершается ошибкой на шаге публикации, а не публикует образ частично

### Requirement: Прозрачность сбоев и отмена устаревших прогонов

Pipeline SHALL отмечать прогон как failed, если сборка или публикация не удались, и SHALL NOT публиковать частичный образ. Новый push в `main` SHALL отменять ещё не завершившийся прогон предыдущего коммита.

#### Scenario: сборка упала

- **WHEN** docker-сборка завершается с ошибкой
- **THEN** прогон помечается failed, теги в GHCR не обновляются

#### Scenario: пришёл более новый коммит

- **WHEN** во время прогона коммита A в `main` пушится коммит B
- **THEN** прогон коммита A отменяется, публикуется образ коммита B

### Requirement: Границы scope

Pipeline SHALL NOT прогонять тесты проекта в этом change: Dockerfile собирает boot-jar с `-DskipTests`, а прогон `mvn verify` с живым Keycloak/Postgres — отдельная задача. Наличие непроходящих тестов SHALL NOT блокировать публикацию образа.

#### Scenario: тесты падают, Dockerfile собирается

- **WHEN** тесты проекта падают, но docker-сборка успешна
- **THEN** образ публикуется (ответственность за тесты — вне этого pipeline, отдельный change)
