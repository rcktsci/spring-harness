# M1 — Ядро сессий

> Фаза M1 дорожной карты (`docs/design/roadmap.md`). Первый реализуемый срез платформы: FREE-сессия работает end-to-end. Дизайн-базис: `docs/design/*` (D-01…D-42), глоссарий `docs/glossary.md`.

## Why

Платформа спроектирована (11 доков, ревью-циклы закрыты), кода нет. Нужен фундамент, на котором строятся workflow (M2), агенты (M3) и клиенты (M4/M5): сессия как append-only журнал, агентный цикл с tool-calling, исполнение инструментов в изолированном per-session контейнере. Без M1 всё остальное не стартует.

## What Changes

- **Identity**: SSO-гейт по `groups`-claim Keycloak (OIDC resource server) — «аутентифицированный и в разрешённой группе видит всё и пишет куда угодно» (D-41); синхронизация пользователей в БД.
- **SessionStore**: таблицы `session`/`session_message` (append-only), рендер видимости (COMPACT-производная), UUID v7, генерация seq.
- **LlmGateway**: сборка ChatModel из `LlmModel` вручную (автоконфиги Spring AI отключены), стриминг, отмена, ретраи с backoff; явные `.timeout()`/`.maxRetries()` (#6915).
- **Агентный цикл (sync-инструменты)**: Turn от сообщения, tool-calling, дописывание результатов в журнал.
- **Исполнение в контейнере (D-30)**: helper-образ (минимальная ОС + find/grep/coreutils/git), docker-java, `ContainerWorkspaceTools`, контейнер `harness-<sessionId>` lifecycle = lifecycle сессии; workspace — bind-mount хост-каталога `workspaces/sessions/{sessionId}`.
- **TurnManager + локи (D-40)**: wake EVENT+POLL (ShedLock-джоба), сессионные локи ShedLock `sess-{id}` (программный `LockProvider`, TTL/heartbeat — конфиг), рестарт-скан (синтетический LOST для «зависших» TOOL_CALL у сессий без живого лока, удаление осиротевших контейнеров).
- **API**: REST/SSE сессий и сообщений (api-contracts §2/§3 подмножество для FREE-сессий), минимальный attach (стриминг + отправка).
- **Страховочная компакция**: авто-COMPACT при пороге контекста (порог — конфиг).
- **Инфра**: Liquibase-миграции (preliquibase: начальные данные вручную не трогаем — bootstrap БД только схема), `application.yml` с `@ConfigurationProperties` (хардкод чисел запрещён), Testcontainers-интеграция.

Не входит (осознанно, по де-скоупу D-41/D-42): матрица прав, билеты, fork/rewind/export, workflow/задачи (M2), субагенты/async-инструменты (M3), CLIENT_EXEC-релей и полный CLI (M4), WebUI (отдельная фаза).

## Capabilities

### New Capabilities

- `sso-gate`: OIDC-аутентификация, groups-claim гейт, синхронизация `app_user`.
- `session-store`: создание FREE-сессий, append-only журнал, seq/видимость, рестарт-устойчивость данных.
- `llm-gateway`: ChatModel из БД-конфигурации, стриминг, отмена, ретраи.
- `agent-turn`: агентный цикл sync-инструментов, wake EVENT+POLL, сессионные локи ShedLock, рестарт-скан.
- `workspace-tools`: helper-образ, per-session контейнеры, нативные инструменты (`read_file`/`write_file`/`edit_file`/`bash`/`glob`/`grep`), lifecycle контейнера.
- `session-api`: REST/SSE сессий-сообщений, минимальный attach (стриминг + отправка).

### Modified Capabilities

(нет — первые спеки в проекте)

## Impact

- **Код**: новые модули `identity`, `session`, `execution`, `intelligence`, `api` поверх существующего скелета (Boot 4.1.1, ShedLock 7.10.1 уже в pom).
- **pom**: уже есть preliquibase 2.0.0, testcontainers (junit-jupiter, postgresql), wiremock, awaitility; добавить: `spring-boot-starter-oauth2-resource-server`, `docker-java` (+транспорт httpclient5), `archunit`, testcontainers-keycloak.
- **БД**: Liquibase-миграции `app_user`, `agent`, `session`, `session_message`, `llm_model`, `llm_credentials` (схема `data-model.md`).
- **Конфиг**: `application.yml` — security (allowed-groups, issuer), harness.lock.* (TTL/heartbeat), harness.turn.*, harness.compact.threshold, docker/helper-image.
- **Инфра**: Keycloak-инстанс (dev: Testcontainers), Docker на CI/VM, helper-образ в репо.
- **Критерий приёмки** (`roadmap.md`): FREE-сессия через минимальный attach end-to-end: сообщение → модель → инструменты в helper-контейнере → стриминг; рестарт сервера не теряет сессии; контейнер поднимается/уничтожается корректно.
