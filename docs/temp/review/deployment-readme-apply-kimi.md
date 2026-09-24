# Ревью apply change `deployment-readme`

**Ревьюер:** Kimi-K2.6 (субагент)
**Дата:** 2026-09-24
**Base:** openspec change `deployment-readme`, tasks 1.1–2.4 [x], незакоммичено
**Вердикт:** REJECT — 2 MAJOR находки: Dockerfile сломается при сборке; `/actuator/health` endpoint отсутствует, healthcheck ложноположителен.

---

## Резюме

Артефакты `docker-compose.yml` и `README.md` логически целостны по структуре: три раздела README, полный env-перечень с плейсхолдерами, KC-чеклист воспроизводим, ссылки на дизайн-доки корректны (кроме одного dead link). Все находки ревью propose (CRITICAL/MAJOR 1–5 + MINOR 6–12) закрыты в текущей версии файлов.

Однако **две новые MAJOR находки** делают первый прогон невозможным без фикса:
1. `Dockerfile.orchestrator` — команда `cp target/*.jar` сломается, т.к. `spring-boot-maven-plugin` создаёт два `.jar` (fat + `*.original`), а `cp` с несколькими source и single destination file завершается ошибкой.
2. `/actuator/health` endpoint отсутствует в приложении (`spring-boot-starter-actuator` не в `pom.xml`). Compose healthcheck через `wget` получит HTTP 401 от `denyAllFilterChain`, но `wget` вернёт exit code 0 — Docker посчитает контейнер healthy. README инструктирует проверять `{"status":"UP"}` — вместо этого curl получит 401 ProblemDetails.

---

## Находки

### MAJOR

| # | Severity | Файл | Строка | Находка | Действие |
|---|----------|------|--------|---------|----------|
| 1 | MAJOR | `docker/Dockerfile.orchestrator` | 27 | `RUN cp target/*.jar /tmp/app.jar` — `spring-boot-maven-plugin` (pom.xml:295, без `<configuration>` отключающего original) создаёт два файла: `spring-harness-1.0.0-SNAPSHOT.jar` (fat) и `spring-harness-1.0.0-SNAPSHOT.jar.original`. Glob `*.jar` раскрывается в два аргумента; `cp` с несколькими source и destination-файлом (а не директорией) падает с ошибкой `cp: target '/tmp/app.jar' is not a directory`. Сборка образа оборвётся на Stage 1. | Заменить на однозначный выбор fat-jar, например: `RUN JAR=$(ls target/*.jar \| grep -v original \| head -1) && cp "$JAR" /tmp/app.jar` |
| 2 | MAJOR | `docker-compose.yml` line 100; `README.md` line 80 | 100 / 80 | `spring-boot-starter-actuator` отсутствует в `pom.xml` (проверено `Select-String pom.xml — нет matches`). Endpoint `/actuator/health` не существует; запросы к `/actuator/**` попадают в `denyAllFilterChain` (`SecurityConfig.java:84–93`) и возвращают HTTP 401. Compose healthcheck `wget -qO- http://127.0.0.1:8081/actuator/health` — wget возвращает exit code 0 при любом HTTP-ответе (включая 401), Docker считает orchestrator healthy при мёртвом actuator. README указывает `curl …/actuator/health` и ожидает `{"status":"UP"}` — получит 401 ProblemDetails с `code:unauthenticated`. | Либо добавить `spring-boot-starter-actuator` в pom.xml (вне scope этого change? — тогда README/compose не должны опираться на actuator), либо заменить healthcheck на проверку живого API (например, `wget -qO- http://127.0.0.1:8080/api/v1/agents` — но требует Bearer, или TCP-порт), либо убрать healthcheck orchestrator до появления actuator. |

### MINOR

| # | Severity | Файл | Строка | Находка | Действие |
|---|----------|------|--------|---------|----------|
| 3 | MINOR | `README.md` | 191 | Dead link: `[docs/design/client-cli.md](docs/design/client-cli.md)` — файл физически отсутствует (superseded в M5, `web-desktop-client.md:3`). | Удалить dead link; `web-desktop-client.md` уже присутствует в той же строке. |
| 4 | MINOR | `docker-compose.yml` | 37 | Опубликован порт `5432:5432` для postgres, но `tasks.md` 1.2 декларирует: «publish только 8080 (API) и 8081 (actuator)». Комментарий в compose («Published only for local debug») честен, но tasks.md исключает этот порт. | Либо убрать `5432:5432` из compose (рекомендуется: production-debug порт в MVP = ненужная поверхность), либо добавить оговорку в tasks.md 1.2. |
| 5 | MINOR | `README.md` | 181 | `pnpm e2e:electron` запускает Playwright против **stub-сервера** (per `package.json:24`, `tests/e2e/stub-server.ts`), а не против поднятого docker-compose. Для smoke против compose используются `scripts/smoke-docker.{ps1,sh}` (см. `web-desktop/docs/smoke.md:5`). README смешивает эти два сценария. | Разделить: `e2e:electron` — быстрый stub-тест; `scripts/smoke-docker.*` — smoke против живого compose. Дать правильные команды для каждого. |
| 6 | MINOR | `README.md` | 79 | Опечатка: `# Актиuator` — смешение кириллицы и латиницы. | Исправить на `# Actuator`. |
| 7 | MINOR | `docker/Dockerfile.orchestrator` | 38–39; `README.md` | 20 | Orchestrator работает от non-root `harness` (Alpine system user, UID непредсказуем). Workspace bind-mount `/srv/harness/workspaces` на хосте создан через `mkdir -p` (обычно root). Если host UID ≠ container UID, orchestrator не имеет write-доступа. | Добавить в README примечание: убедиться, что каталог workspace доступен на запись для пользователя контейнера (или использовать `chmod 777` для MVP). Либо зафиксировать numeric UID в Dockerfile (`adduser -u 1000 …`) и дать `chown 1000:1000` в инструкции. |

---

## Проверка закрытия находок ревью propose (b89747f → 7e8b83c)

| # | Оригинальная находка | Закрыто в apply? | Примечание |
|---|----------------------|------------------|------------|
| CRITICAL-1 | `MANAGEMENT_SERVER_PORT=8081` | ✅ compose env, line 98 | Закрыто, но endpoint `/actuator/health` всё равно отсутствует (MAJOR-2 выше). |
| MAJOR-2 | `POSTGRES_USER/PASSWORD/DB` | ✅ compose postgres env, line 29–31 | — |
| MAJOR-3 | `mvn` vs `./mvnw`+chmod | ✅ design D-R2 + README | Разнесено: корп. образ с mvn внутри; `./mvnw` на dev-машине. |
| MAJOR-4 | Node ≥20.18 / pnpm ≥10 | ✅ README 2.3, line 131–132 | — |
| MAJOR-5 | `HARNESS_WEBHOOK_BASE_URL` | ✅ compose env, line 85 | — |
| MINOR-6 | `pg_isready` формат | ✅ compose line 39 | — |
| MINOR-7 | Web Origins `http://127.0.0.1` | ✅ README line 109 | — |
| MINOR-8 | `package:win` vs `package:linux` | ✅ README line 159–162 | — |
| MINOR-9 | `mkdir -p` workspace | ✅ README line 20 | — |
| MINOR-10 | LLM-key warning | ✅ README line 177 | — |
| MINOR-11 | `KEYCLOAK_JWKS_URI` в env | ✅ compose env, line 79 | — |
| MINOR-12 | `docker compose config` | ✅ tasks 1.2 line 6 | — |

---

## application.yml-консистентность

| Параметр | application.yml | compose / README | Статус |
|----------|---------------|------------------|--------|
| `harness.docker.helper-image` | `harness-helper:local` | README: `docker build -f docker/Dockerfile -t harness-helper:local .` | ✅ |
| `harness.docker.workspace-root` | default `workspaces/sessions` | compose env: `/srv/harness/workspaces`; volume mount: `/srv/harness/workspaces` | ✅ env перекрывает default |
| `harness.security.allowed-groups` | `[harness-users]` | README KC: группа `harness-users` | ✅ |
| `DB_*` env-имена | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | compose: все присутствуют | ✅ |
| `KEYCLOAK_ISSUER_URI` / `KEYCLOAK_JWKS_URI` | `${KEYCLOAK_ISSUER_URI:…}` / `${KEYCLOAK_JWKS_URI:…}` | compose: оба присутствуют | ✅ |
| `HARNESS_WEBHOOK_SECRET` / `HARNESS_WEBHOOK_BASE_URL` | `${HARNESS_WEBHOOK_SECRET:…}` / `${HARNESS_WEBHOOK_BASE_URL:…}` | compose: оба присутствуют | ✅ |
| `HARNESS_LLM_KEY_V1` | `${HARNESS_LLM_KEY_V1:}` | compose: `CHANGE_ME_llm_provider_key` | ✅ |
| `MANAGEMENT_SERVER_PORT` | отсутствует в application.yml | compose env: `8081` | ⚠️ env бездейственна без actuator в pom (MAJOR-2) |

---

## Рекомендации по фиксу

1. **MAJOR-1 (Dockerfile cp)**: Исправить `cp target/*.jar` на надёжный выбор fat-jar (grep -v original).
2. **MAJOR-2 (actuator)**: Либо добавить `spring-boot-starter-actuator` в pom.xml (scope — другой change? Но README/compose зависят от него), либо удалить healthcheck orchestrator из compose и убрать `/actuator/health` из README проверок до реализации actuator. В текущем виде healthcheck — ложноположительный.
3. **MINOR-1**: Удалить dead link `client-cli.md` из README.
4. **MINOR-2**: Убрать `5432:5432` из postgres service или добавить оговорку в tasks.md.
5. **MINOR-3**: Разделить `e2e:electron` (stub) и `smoke-docker` (compose) в README.
6. **MINOR-4**: Исправить опечатку `Актиuator`.
7. **MINOR-5**: Добавить примечание о правах на workspace каталог.

После устранения MAJOR-1 и MAJOR-2 — **APPROVE**.

---

## Re-approval apply

**Дата:** 2026-09-24 (post-фикс-раунд)

Перепроверка фактических файлов:

| # | Находка | Фикс в файле | Статус |
|---|---------|-------------|--------|
| MAJOR-1 | `cp target/*.jar` падает на двух `.jar` | `docker/Dockerfile.orchestrator:28`: `JAR=$(ls -1 target/*.jar \| grep -v '\.original$' \| head -n1) && cp "$JAR" /tmp/app.jar` | ✅ Закрыто — детерминированный выбор fat-jar |
| MAJOR-2 | actuator отсутствует, healthcheck ложноположителен | `pom.xml:96`: `spring-boot-starter-actuator` добавлен; git-commit-id plugin: `failOnNoGitDirectory:false`, `failOnUnableToExtractRepoInfo:false` | ✅ Закрыто — endpoint `/actuator/health` теперь существует, wget получит 200 UP |
| MINOR-1 | dead link `client-cli.md` | `README.md`: ссылка удалена, присутствуют только валидные доки | ✅ Закрыто |
| MINOR-2 | postgres 5432 published наружу | `docker-compose.yml:34-37`: ports-секция удалена, оставлен комментарий + `docker compose exec` для ad-hoc psql | ✅ Закрыто |
| MINOR-3 | e2e/smoke смешаны | `README.md:179-206`: явные два подраздела — stub e2e (`pnpm e2e:electron`) vs smoke-docker (`scripts/smoke-docker.{ps1,sh}`) | ✅ Закрыто |
| MINOR-4 | typo «Актиuator» | `README.md:79`: `# Actuator` | ✅ Закрыто |
| MINOR-5 | workspace bind-mount права | `README.md:20`: примечание о root-owned для MVP + объяснение почему helper-контейнеры работают через `docker exec` | ✅ Закрыто |

Дополнительные проверки:
- `docker compose config` — валиден (подтверждено девом).
- `docker build -f docker/Dockerfile.orchestrator …` — прошёл полностью: rcktsci/java-tooling:25 → jar 151MB → liberica JRE (подтверждено девом).
- `pom.xml` dependency:list подтверждает `spring-boot-starter-actuator` на classpath.
- `pom.xml` git-commit-id plugin: `failOnNoGitDirectory:false` — сборка в контейнере без `.git` не упадёт.
- Все env-имена в `docker-compose.yml` совпадают с `${…}` в `application.yml`.
- KC-чеклист воспроизводим: public + Standard flow + PKCE S256 + redirect `127.0.0.1/*` + Web Origins `http://127.0.0.1` + Group Membership mapper (full path OFF) + группа `harness-users`.
- Ссылки в README корректны, `client-cli.md` (superseded) удалён.

**Вердикт Re-approval apply: APPROVE**
