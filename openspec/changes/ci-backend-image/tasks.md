# Tasks: CI: Backend Image

## 1. Контекст сборки

- [ ] 1.1 Создать `.dockerignore` в корне: исключить `.git/`, `target/`, `web-desktop/{node_modules,dist,out,test-results,playwright-report}/`, `.idea/`, `.vscode/`, `.opencode/`, `docs/`, `openspec/`, `*.log`; `api/` и `src/` в списке исключений НЕ должны присутствовать — проверка: `docker build -f docker/Dockerfile.orchestrator -t spring-harness:ci-check .` собирает boot-jar (openapi-generator читает `api/openapi.yaml`, git-commit-id не падает без `.git`)
- [ ] 1.2 Проверить чистую сборку с нуля: `docker build --no-cache -f docker/Dockerfile.orchestrator -t spring-harness:ci-check .` завершается успешно, в образе только `app.jar` + JRE — проверка: вывод шага сборки без ошибок, `docker run --rm --entrypoint sh spring-harness:ci-check -c "ls /app"` показывает только `app.jar`

## 2. Workflow

- [ ] 2.1 Создать `.github/workflows/backend-image.yml`: триггеры `push` (branches: `main`) и `workflow_dispatch`; `permissions: contents: read, packages: write`; `concurrency: backend-image-${{ github.ref }}` с `cancel-in-progress: true` — проверка: файл валиден YAML, триггеры и права читаются из файла
- [ ] 2.2 Шаги workflow: `actions/checkout@v7` → `docker/setup-buildx-action@v4` → `docker/login-action@v4` (GHCR, `${{ secrets.GITHUB_TOKEN }}`) → `docker/metadata-action@v6` (images: `ghcr.io/${{ github.repository_owner }}/spring-harness-orchestrator`; tags: `type=ref,event=branch`, `type=sha,format=short`, `type=raw,value=latest,enable={{is_default_branch}}`) → `docker/build-push-action@v7` (`file: docker/Dockerfile.orchestrator`, `context: .`, `platforms: linux/amd64`, `push: true`, `cache-from: type=gha`, `cache-to: type=gha,mode=max`) — проверка: каждый action зафиксирован на существующей мажорной версии, в шаге сборки явно заданы `push: true`, `platforms: linux/amd64` и оба ключа GHA-кэша
- [ ] 2.3 Прогнать workflow на первом пуше: образ публикуется в GHCR с тегами `latest`, `main`, `sha-<short>` и OCI label'ами — проверка: в GHCR виден пакет `spring-harness-orchestrator` с тремя тегами, `docker buildx imagetools inspect ghcr.io/rcktsci/spring-harness-orchestrator:latest` показывает label'и `org.opencontainers.image.revision`/`source`

## 3. Документация и приёмка

- [ ] 3.1 Обновить `README.md`: раздел про доставку образа на VM — `docker login ghcr.io`, `docker pull ghcr.io/rcktsci/spring-harness-orchestrator:latest`, `docker tag ... spring-harness:local`, запуск через существующий `docker compose up -d`; явно описаны оба варианта доступа к пакету (сделать пакет публичным либо PAT `read:packages`); отметить, что локальный `build:` в compose остаётся рабочим путём — проверка: инструкции воспроизводимы по README без обращения к внутренним докам, и в README явно присутствуют оба варианта логина
- [ ] 3.2 Проверить архитектуру VM (`uname -m`): ожидается `x86_64`; при `aarch64` — эскалация владельцу до правки `platforms` (D-CI4) — проверка: зафиксированный вывод команды
- [ ] 3.3 Приёмочный прогон на VM: `docker pull` опубликованного образа, `docker compose up -d`, `curl http://<vm>:8081/actuator/health` отвечает `UP` — проверка: healthcheck зелёный, в `docker images` тег `spring-harness:local` указывает на скачанный образ
- [ ] 3.4 Проверить откат: `docker pull ghcr.io/rcktsci/spring-harness-orchestrator:sha-<предыдущий>` поднимается и проходит healthcheck — проверка: healthcheck зелёный на предыдущем коммите
- [ ] 3.5 Зафиксировать в `docs/design/decisions.md` новый ADR (D-95 — D-94 уже занят решением про Jackson 2) с решениями D-CI1…D-CI11 и отвергнутыми альтернативами; обновить `AGENTS.md` (M5/deploy status + новый CI) — проверка: вручную — ADR D-95 содержит отвергнутые альтернативы, `AGENTS.md` содержит новый пункт; плюс `openspec validate ci-backend-image --strict` зелёный (внешний openspec CLI, команду выполняет оркестратор, не артефакт репозитория)
