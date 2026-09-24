# Design: CI: Backend Image

## Context

Образ оркестратора уже существует и проверен: `docker/Dockerfile.orchestrator` (D-R2, `deployment-readme`) — multi-stage, stage 1 `rcktsci/java-tooling:25` (Liberica JDK 25 + Maven), stage 2 `bellsoft/liberica-openjre-alpine-musl:25`, `ENTRYPOINT ["java","-jar","/app/app.jar"]`, тесты в образе пропускаются (`-DskipTests`). В репозитории нет ни `.github/` (CI отсутствует), ни `.dockerignore` (контекст сборки сейчас включает `.git`, `target`, `web-desktop/node_modules`).

Свойства сборки, важные для CI (сверено с `pom.xml`):
- `git-commit-id-maven-plugin:10.0.0` — фаза `initialize`, `failOnNoGitDirectory=false` (pom.xml:290) → `.git` в контексте не требуется, сборка без него уже рассчитана.
- `openapi-generator-maven-plugin:7.25.0` — два исполнения: серверные DTO (`generate-sources`) и тест-клиент (`generate-test-sources`), оба читают `${project.basedir}/api/openapi.yaml` (pom.xml:347, 376) → **`api/openapi.yaml` обязан быть в контексте сборки**.
- `api/openapi.yaml` — замороженный контракт проекта в корне репозитория (owner-решение 2026-09-24).

Платформа целевой VM — x86_64 Linux (D-R1/D-R2: docker-compose, рантайм один). Базовые образы публичные на Docker Hub → registry-креденшелы не нужны. Решения владельца: триггер — push в `main`, публикация — GHCR, имя образа `ghcr.io/rcktsci/spring-harness-orchestrator`. Актуальные на 2026-09-24 версии actions: `checkout@v7`, `setup-buildx-action@v4`, `login-action@v4`, `build-push-action@v7`, `metadata-action@v6`.

## Goals / Non-Goals

**Goals:**
- Один workflow-файл, который по push в `main` публикует в GHCR ровно тот образ backend-а, который собирается из закоммиченных исходников тем же Dockerfile'ом, что и локальная сборка.
- Воспроизводимость: содержимое образа не зависит от состояния dev-машины (никаких локальных артефактов в контексте).
- Минимальные права и отсутствие новых секретов: публикация — только встроенным `GITHUB_TOKEN`.
- Владелец на VM получает образ через `docker pull`, без JDK/Maven и без `docker compose build`.

**Non-Goals:**
- Прогон тестов в CI (`mvn verify` с живым Keycloak/Postgres) — отдельный change; в этом pipeline образ собирается с `-DskipTests` (см. спеку, требование «Границы scope»).
- Автодеплой на VM (по SSH/pull с VM-раннера) — отдельная задача; сейчас доставка = `docker pull` руками.
- Multi-arch образы, SBOM/provenance-аттестации, подпись образа — не в scope (см. D-CI4, D-CI7).
- Сборка helper-образа (`docker/Dockerfile`, `harness-helper:local`) — по-прежнему ручной шаг на VM (D-R2).

## Decisions

### D-CI1: Сборка целиком в контейнере, раннеру нужен только docker

**Решение**: GitHub-hosted runner (`ubuntu-latest`) выполняет только `checkout` → `buildx setup` → `login` → `build-push` с `file: docker/Dockerfile.orchestrator`, `context: .`. Никакого `setup-java`, никакого `mvn` в CI: jar собирается внутри stage 1 Dockerfile на JDK 25.

**Альтернативы**: (1) `actions/setup-java` на раннере + сборка jar → `docker build` простым копированием jar — дублирует логику сборки в двух местах (CI и Dockerfile), нужен JDK 25 на раннере, и «собирает CI» ≠ «собирает compose», т.е. расходятся потенциально; (2) self-hosted раннер на самой VM — убирает зависимость от GitHub-hosted, но добавляет на VM persistent-agent, его обслуживание и доверие к раннеру с доступом к docker-sockету; (3) локальный прогон `act`/CI на dev-машине — не CI.

**Почему**: Dockerfile уже инкапсулирует сборку и проверен реальным `docker build` (change `deployment-readme`); CI не создаёт второго места истины. Плата — полная пересборка jar на раннере, что лечится кэшем (D-CI3).

### D-CI2: Один job, без матриц и без артефактов между шагами

**Решение**: один job `build` на `ubuntu-latest`, последовательные шаги, публикация сразу после сборки; отдельные джобы на «сборку» и «пуш» не заводим (нет промежуточного артефакта, который надо где-то хранить).

**Альтернативы**: два job с выгрузкой образа в промежуточный registry (`load: true` + `docker save`/`upload-artifact`) — позволяет переиспользовать образ в других job (например, прогон e2e), но сейчас единственный потребитель — публикация, поэтому посредник только усложняет.

**Почему**: правило владельца — без энтерпрайз-раздутия; когда появится e2e в CI, разделение добавится отдельным change.

### D-CI3: Кэш слоёв через GHA cache (`type=gha`), не registry cache

**Решение**: в `build-push-action` — `cache-from: type=gha` и `cache-to: type=gha,mode=max`. Это кэш всех слоёв, включая Maven-зависимости из `dependency:go-offline` (Dockerfile.orchestrator:17).

**Альтернативы**: (1) без кэша — каждое время качает весь Maven-репозиторий заново; (2) `cache-to: type=registry` в GHCR — кэш-артефакты светятся рядом с релизным образом в GHCR (мусор), едят квоту пакетов, и cache-to не даёт `cache-from` между прогонами в том же репо без явного манифеста; (3) самодельный кэш в артефактах Actions — ручная работа с томами, хрупко.

**Почему**: GHA cache встроен, не требует дополнительных токенов и не засоряет registry; его вытеснение (LRU, ограничение объёма на org) — не блокер, полная пересборка просто дороже по времени. `mode=max` кэширует и промежуточные слои (иначе после смены Dockerfile кэш сбрасывается целиком).

### D-CI4: Платформа — только `linux/amd64`

**Решение**: `platforms: linux/amd64`.

**Альтернативы**: (1) `linux/amd64,linux/arm64` — вдвое дольше сборка из-за QEMU-эмуляции, при этом ни один потребитель (одна x86_64 VM) arm64 не запускает; (2) без явного `platforms` — buildx возьмёт платформу раннера, что делает результат зависимым от того, где прогоняется job.

**Почему**: детерминированный результат и никакого эмулятора. Проверка архитектуры VM (`uname -m`) — обязательный шаг tasks; при неожиданном `aarch64` решение пересматривается (см. Open Questions).

### D-CI5: Теги и label'ы — явной конфигурацией `metadata-action`

**Решение**: `docker/metadata-action@v6` формирует теги `type=ref,event=branch` (→ `main`), `type=sha,format=short` (→ `sha-<short>`) и `type=raw,value=latest,enable={{is_default_branch}}`; OCI label'ы (`org.opencontainers.image.source`, `.revision`, `.version`, `.created`) — из коробки metadata-action. Имя образа: `ghcr.io/${{ github.repository_owner }}/spring-harness-orchestrator`.

**Альтернативы**: полагаться на дефолты metadata-action (дадут `main` + `sha-<short>` + `latest` + ещё `pr-`/`v` теги, если появятся) — «случайная» схема тегов, которую придётся читать по исходнику action; теги через shell-скрипт — ручное форматирование SHA, лишний код в CI.

**Почему**: схема тегов — контракт для владельца на VM (`latest`, откат по `sha-<short>`), её лучше видеть в репозитории явно.

### D-CI6: `.dockerignore` — контекст без VCS-метаданных и локальных артефактов

**Решение**: новый `.dockerignore` в корне исключает: `.git/`, `target/`, `web-desktop/node_modules/`, `web-desktop/dist/`, `web-desktop/out/`, `web-desktop/test-results/`, `web-desktop/playwright-report/`, `.idea/`, `.vscode/`, `.opencode/`, `docs/`, `openspec/`, `*.log`. Обязательные исключения: `api/` и `src/` в списке **не** могут появиться (openapi-generator читает `api/openapi.yaml`).

**Альтернативы**: (1) не добавлять `.dockerignore` — каждый прогон отправляет buildx десятки мегабайт мусора (`.git`, `node_modules`), cache-hit по слоям работает хуже, риск случайно запечь лишнее в образ; (2) копировать в образ только `pom.xml`/`src`/`api` через `additional_contexts` — сложнее и не избавляет от передачи контекста; (3) включать `.git` ради `git.properties` — см. D-CI7.

**Почему**: воспроизводимость (в контексте только то, что нужно для jar) + скорость + правило владельца «никакого энтерпрайз-раздутия» (dockerignore — это 15 строк, а не инфраструктура).

### D-CI7: В образ не попадает `.git`: ревизия живёт в теге и label'ах

**Решение**: `.git` исключён из контекста (D-CI6). В `pom.xml:290` уже стоит `failOnNoGitDirectory=false`, поэтому `git-commit-id-maven-plugin` в такой сборке не падает; `git.properties` в jar не попадает.

**Альтернативы**: (1) оставить `.git` в контексте, чтобы `git.properties` содержал SHA — вторая (дублирующая) точка правды о ревизии плюс рост контекста; (2) передавать SHA через build-arg в git-commit-id — ради данных, которые уже есть в теге образа и OCI label'ах.

**Почему**: если приложение или эксплуатация хотят знать ревизию — она в `docker inspect` (label `.revision`) и в теге `sha-<short>`; содержимое jar от способа сборки не зависит.

### D-CI8: Отмена устаревших прогонов (`concurrency`)

**Решение**: `concurrency: {group: backend-image-${{ github.ref }}, cancel-in-progress: true}`.

**Альтернативы**: разрешить параллельные прогоны — при быстрых пушах два job сражаются за тег `latest`, и возможен «откат» `latest` на более старый коммит.

**Почему**: дешёвый способ не дать устаревшей сборке перебить свежую.

### D-CI9: Права — минимальные, секретов в репозитории нет

**Решение**: `permissions: {contents: read, packages: write}` на уровне workflow; `login-action` использует `GITHUB_TOKEN`; никаких `secrets.*`, PAT, registry-ключей в репозитории.

**Альтернативы**: PAT/Deploy-ключ в secrets — расширение поверхки секретов ради операции, которую умеет `GITHUB_TOKEN`; `id-token: write` для OIDC-логина — не нужен, GHCR принимает `GITHUB_TOKEN`.

**Почему**: правило владельца — минимум сущностей и слоёв; `GITHUB_TOKEN` выдаётся на время job'а и не переживает его.

### D-CI10: Потребление образа на VM — `docker pull` + тег, compose не трогаем

**Решение**: compose продолжает собирать образ локально (`build:` + `image: spring-harness:local`, deployment-readme D-R2) — это быстрый путь для владельца и чистый checkout. Для CI-образа README получает короткий блок: `docker pull ghcr.io/rcktsci/spring-harness-orchestrator:latest` и `docker tag ... spring-harness:local` (либо замена строки `image:` в compose на GHCR-ссылку). Пакет GHCR — **публичный** (решение владельца 2026-09-24): первый push создаёт пакет приватным, владелец один раз переключает видимость в настройках пакета, после чего `docker pull` на VM идёт без логина и без PAT.

**Альтернативы**: (1) переключить compose на `image: ghcr.io/...` — ломает путь «clone VM → `docker compose up -d` без кредов» и смешивает два способа доставки в одном файле; (2) `docker compose pull` с override-файлом (`-f compose.yml -f compose.ghcr.yml`) — лишний файл ради внутреннего проекта.

**Почему**: разделение «локальная сборка для разработки / pull для доставки» соответствует модели «один инстанс, сломалось — пофиксили — передеплоили».

### D-CI11: Без SBOM/provenance и без подписи — сознательно

**Решение**: `provenance`/`sbom` не включаем, подпись не делаем.

**Альтернативы**: `provenance: mode=max` и `sbom: true` в build-push-action бесплатны и дают аттестации в GHCR; cosign-подпись требует OIDC-ключа и политики доверия.

**Почему**: внутренний MVP на одну VM; ревизии достаточно через тег и label (D-CI5). Включается одним флагом позже, без изменения схемы.

## Risks / Trade-offs

- [Архитектура VM — x86_64, подтверждено владельцем 2026-09-24] → задача верифицирует это на месте (`uname -m`); при неожиданном `aarch64` вернуться к D-CI4 (arm64-вариант базовых образов может отсутствовать — проверить до правки).
- [Полный промах кэша GHA (вытеснение, первый прогон, смена Dockerfile)] → прогон дольше, но корректен; повторный прогон кэширует. Признак — время job'а кратно выросло.
- [Docker Hub rate limit на публичные базовые образы] → анонимные pull'ы с раннеров GitHub обычно в лимитах; при всплеске — предсобрать базовый образ в GHCR отдельным job'ом (отдельная задача, не здесь).
- [`.git` нет в контексте → `git.properties` отсутствует в jar (D-CI7)] → плагин терпит это (`failOnNoGitDirectory=false`, pom.xml:290); ревизия доступна через label/тег. Проверяется прогоном: `docker run ... /app/app.jar` стартует (actuator отвечает).
- [Первый push в GHCR создаёт приватный пакет, а владелец рассчитывает на публичный] → решение владельца: пакет публичный; после первого прогона владелец один раз переключает видимость пакета в GHCR (разовая ручная настройка, до неё pull с VM не идёт) — README фиксирует этот шаг.
- [Публикация succeeds, но compose на VM продолжает собирать локально (D-CI10)] → README даёт точную команду перехода на pull-образ; расхождение заметно по тегу `docker images`.
- [Кто-то добавит в `.dockerignore` `api/` или `src/` и сломает сборку неочевидно (openapi-generator)] → в `.dockerignore` эти каталоги не упоминаются, а задача верификации включает `docker build` с нуля (чистый контекст).

## Migration Plan

1. Добавить `.github/workflows/backend-image.yml` и `.dockerignore` в `main` — pipeline срабатывает на самом этом пуше, публикует первый образ.
2. Владелец один раз: после первого пуша переключает видимость пакета GHCR на публичную (решение владельца 2026-09-24) — до этого `docker pull` с VM не идёт.
3. Проверить на VM: `docker pull ghcr.io/rcktsci/spring-harness-orchestrator:latest`, тег в `spring-harness:local`, `docker compose up -d`, healthcheck 8081.
4. Откат: тег `sha-<short>` любого предыдущего коммита; compose остаётся рабочим путём `build:` в любой момент — переключение образа не блокируется.

## Open Questions

- Не блокирующие (меняют только tasks, не подход): имя тега для `workflow_dispatch` (предлагается прогон с ref из UI — тег `main`/`sha-` по этому коммиту, отдельный `dispatch-` тег не нужен).
- Закрыты владельцем 2026-09-24: пакет GHCR публичный (PAT не нужен), архитектура VM x86_64 (`platforms: linux/amd64` подтверждён).
