# Deployment README

## Why

M1–M5 завершены, но проект разворачивается только автором сессии: нет воспроизводимой инструкции «поднял сервер → настроил Keycloak → запустил клиент». Первый реальный прогон владелец делает сейчас (VM + локальный клиент) — без README каждый шаг восстанавливается по чату и design-докам.

## What Changes

- **Новый `docker-compose.yml`** в корне: `postgres` + `orchestrator` (сборка из исходников), монтирование `/var/run/docker.sock` и workspace-тома; все параметры — env внутри compose (без `.env`-файла), дефолты совместимы с `application.yml`.
- **Новый `docker/Dockerfile.orchestrator`**: multi-stage (maven-build → JRE-рантайм), JDK 25, helper-образ `harness-helper:local` собирается отдельной командой из существующего `docker/Dockerfile`.
- **Новый корневой `README.md`**: три раздела — (1) подъём бэкенда через docker-compose, (2) настройка Keycloak (realm rcktsci: public+PKCE клиент десктопа, groups-mapper, группа `harness-users`), (3) запуск клиента (`pnpm dev` / упакованный билд, Settings).
- README — единственная точка входа; design-доки (`operations.md`, `web-desktop-client.md`) остаются источниками истины по деталям, README ссылается на них.

## Capabilities

### New Capabilities

<!-- нет: docs/инфра-change, поведение рантайма не меняется (skip_specs: true) -->

### Modified Capabilities

<!-- нет -->

## Impact

- Новые файлы: `docker-compose.yml`, `docker/Dockerfile.orchestrator`, `README.md`.
- Существующий код не меняется. Поведение приложения не меняется.
- Риски: compose-конфигурация не была прогнана на реальной VM — первый прогон владелец делает по этому README; расхождения чинятся в рамках этого же change до архивации.
