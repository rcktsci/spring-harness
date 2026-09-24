# Ревью propose-артефактов change `deployment-readme`

**Ревьюер:** Kimi-K2.6 (субагент)  
**Дата:** 2026-09-24  
**Commit:** b89747f  
**Вердикт:** REJECT — 3 MAJOR находки блокируют первый прогон на VM; 1 CRITICAL противоречие с `application.yml`.

---

## Резюме

Артефакты логически целостны: три раздела README (backend → Keycloak → client), multi-stage Dockerfile, compose с env-плейсхолдерами, KC-чеклист воспроизводим. Соблюдены правила владельца: без `.env`, числа — конфиг, секреты — плейсхолдеры.

Однако **отсутствие `MANAGEMENT_SERVER_PORT`** в `application.yml` делает невозможным expose порта 8081 для actuator (Spring Boot слушает management на 8080 по умолчанию). Это прямое противоречие между `operations.md` §2 и кодом, которое change не решает. Также не описаны `POSTGRES_USER` (БД не создастся), `mvn` vs `./mvnw`, и node/pnpm версии. Без этих фиксов владелец получит нерабочий compose на первом же шаге.

---

## Находки

### CRITICAL — Противоречие с application.yml

| # | Severity | Файл | Строка | Находка | Действие |
|---|----------|------|--------|---------|----------|
| 1 | CRITICAL | `design.md` D-R2, `proposal.md` line 9, `tasks.md` line 6 | — | `operations.md` §2 декларирует `management.server.port=8081`, но в `application.yml` и `application-test.yml` отсутствует `management.server.port`. Spring Boot размещает actuator на порту приложения (8080). Compose будет expose 8081, health-check на 8081 упадёт. | Добавить `MANAGEMENT_SERVER_PORT=8081` в env-блок orchestrator в `docker-compose.yml` (change не меняет Java-код, конфигурирует через env). Либо убрать 8081 из expose и README, заменив health-check на `:8080/actuator/health`. |

### MAJOR — Первый прогон сломается

| # | Severity | Файл | Строка | Находка | Действие |
|---|----------|------|--------|---------|----------|
| 2 | MAJOR | `tasks.md` 1.2 | line 6 | В compose для postgres не указаны `POSTGRES_USER` / `POSTGRES_PASSWORD`. PostgreSQL Docker-образ создаёт БД с именем `POSTGRES_DB` (или `POSTGRES_USER` по умолчанию). `application.yml` ожидает `DB_NAME=harness`, `DB_USERNAME=harness`, `DB_PASSWORD=harness`. Если в compose не задать `POSTGRES_USER=harness` + `POSTGRES_PASSWORD=harness`, БД `harness` не создаётся автоматически, и orchestrator упадёт на старте (Liquibase не найдёт БД). | В `docker-compose.yml` сервис postgres добавить `environment: POSTGRES_USER: harness, POSTGRES_PASSWORD: harness` (или `POSTGRES_DB: harness`). |
| 3 | MAJOR | `design.md` D-R2 | line 33 | `mvn -q -DskipTests package` против `tasks.md` 1.1 `./mvnw -DskipTests package`. В репо есть `mvnw` (wrapper). На чистой Linux VM `mvn` может отсутствовать; `./mvnw` — воспроизводим. | Унифицировать на `./mvnw` в `design.md` D-R2 и `README.md`. Добавить `chmod +x mvnw` в инструкцию для Linux VM. |
| 4 | MAJOR | `tasks.md` 2.3 | line 12 | Не указаны версии Node.js / pnpm для клиента. `web-desktop/package.json` требует `node >=20.18.0`, `pnpm >=10.0.0`, `packageManager: pnpm@12.5.1`. Владелец с чистой VM не поймёт, какой runtime ставить. | В README раздел «Клиент» добавить Prerequisites: Node.js ≥20.18.0, pnpm ≥10.0.0 (или corepack enable). |
| 5 | MAJOR | `design.md` D-R3, `tasks.md` 1.2 | — | `HARNESS_WEBHOOK_BASE_URL` не упомянут в env compose. Дефолт в `application.yml`: `http://localhost:8080`. Внутри Docker `localhost` — сам orchestrator; внешние вебхуки (даже пустые по умолчанию) не смогут достучаться. При первом реальном использовании webhook callback URL будет некорректен. | Добавить `HARNESS_WEBHOOK_BASE_URL` в env-блок compose с плейсхолдером `http://<vm-host>:8080` и комментарием. |

### MINOR — Неточности и упущения

| # | Severity | Файл | Строка | Находка | Действие |
|---|----------|------|--------|---------|----------|
| 6 | MINOR | `tasks.md` 1.2 | line 6 | Формат healthcheck postgres не уточнён. `pg_isready` без параметров проверяет готовность сервера, но не конкретную БД/пользователя. | Уточнить в tasks: `healthcheck: test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB}"]`. |
| 7 | MINOR | `proposal.md` line 11, `design.md` D-R4 | — | KC-чеклист упоминает `Web Origins`, но не уточняет значение. Для Electron PKCE loopback-redirect (127.0.0.1:random_port) `Web Origins` обычно `http://127.0.0.1`. | В README добавить: Web Origins = `http://127.0.0.1` (или `+`, если KC позволяет). |
| 8 | MINOR | `tasks.md` 2.3 | line 12 | Упакованный билд (`pnpm package:win`) на Linux VM не соберёт NSIS-инсталлятор без wine/cross-compilation. README должен различать dev (любая платформа) и упаковку (целевая ОС). | Уточнить: `package:win` — на Windows (или через dev-машину разработчика); `package:linux` — на VM. |
| 9 | MINOR | `design.md` D-R3 | line 41 | `HARNESS_WORKSPACE_ROOT` — абсолютный путь на хосте. README не инструктирует создать директорию заранее. Docker daemon не создаст её автоматически при bind-mount. | В README шаг «подготовка VM» добавить `mkdir -p /srv/harness/workspaces` (или путь владельца). |
| 10 | MINOR | `design.md` D-R3 | line 45 | «LLM-ключ пустой → сессия честно падает на первом Turn». В README это не предупреждено; владелец может подумать, что сервер неработоспособен. | В README добавить callout: без `HARNESS_LLM_KEY_V1` сервер стартует, но turns будут падать — ключ обязателен для работы. |
| 11 | MINOR | `tasks.md` 2.2 | line 11 | KC-чеклист не упоминает `KEYCLOAK_JWKS_URI` как env-переменную для orchestrator, хотя `application.yml` ожидает её. | В таблицу env README добавить `KEYCLOAK_JWKS_URI` рядом с `KEYCLOAK_ISSUER_URI`. |
| 12 | MINOR | `proposal.md` line 28 | — | Risk: «Compose-файл не прогнан на реальной VM». Это честно, но в tasks 3.1 «прогон владельца» — конец цикла. До этого нет промежуточной верификации compose на dev-машине (Windows). | Добавить в tasks 1.2 шаг `docker compose config` (без `up`) для валидации YAML до передачи на VM. |

### Полнота и KC-чеклист

- **Полнота (backend)**: OK — clone → helper-образ → (опционально pre-build jar) → env → compose up → health-check. Пробел: `chmod +x mvnw` для Linux.
- **Полнота (KC)**: OK — public + Standard flow + PKCE S256 + redirect `http://127.0.0.1/*` + Group Membership mapper + группа `harness-users`. Пробел: `Web Origins` значение.
- **Полнота (client)**: OK — dev (`pnpm dev`) и упакованный (`pnpm package:*`) пути описаны. Пробел: node/pnpm версии.
- **KC-чеклист воспроизводим с нуля**: Теоретически да, если указать `Web Origins`.

### Соблюдение правил владельца

| Правило | Статус | Комментарий |
|---------|--------|-------------|
| Без `.env` | ✅ | Используется env-блок в compose; `.env`-файлы отвергнуты (D-R3). |
| Числа — конфиг | ✅ | Нет новых хардкодов в приложении. Порты 8080/8081 — инфраструктурные константы compose. |
| Секреты не в git | ⚠️ | Плейсхолдеры `CHANGE_ME` + предупреждение в README. Риск случайного коммита реальных секретов остаётся (human factor). Допустимо по уровню проекта, но можно усилить `git update-index --skip-worktree docker-compose.yml` в README. |

## Re-approval

**Дата:** 2026-09-24 (post-commit 7e8b83c)

Перепроверка обновлённых артефактов (`design.md`, `tasks.md`, `proposal.md`):

| # | Находка (оригинал) | design.md | tasks.md | proposal.md | Статус |
|---|-------------------|-----------|----------|-------------|--------|
| CRITICAL-1 | `MANAGEMENT_SERVER_PORT=8081` | ✅ D-R3, line 41 | ✅ 1.2, line 6 | N/A (proposal не детализирует env) | Закрыто |
| MAJOR-2 | `POSTGRES_USER/DB/PASSWORD` + `pg_isready -U -d` | ✅ D-R3, line 41 | ✅ 1.2, line 6 | N/A | Закрыто |
| MAJOR-3 | mvn (корп. образ) vs `./mvnw`+chmod | ✅ D-R2, line 33 | ✅ 1.1/2.1 | N/A | Закрыто |
| MAJOR-4 | Node ≥20.18 / pnpm ≥10 | N/A | ✅ 2.3, line 12 | N/A | Закрыто |
| MAJOR-5 | `HARNESS_WEBHOOK_BASE_URL` | ✅ D-R3, line 41 | ✅ 1.2, line 6 | N/A | Закрыто |
| MINOR-6 | `pg_isready` формат | N/A | ✅ 1.2, line 6 | N/A | Закрыто |
| MINOR-7 | Web Origins `http://127.0.0.1` | ✅ D-R4, line 49 | ✅ 2.2, line 11 | N/A | Закрыто |
| MINOR-8 | `package:win` на Windows / `package:linux` на Linux | N/A | ✅ 2.3, line 12 | N/A | Закрыто |
| MINOR-9 | `mkdir -p` workspace | ✅ D-R3, line 41 | ✅ 2.1, line 10 | N/A | Закрыто |
| MINOR-10 | LLM-key warning | N/A | ✅ 2.3, line 12 | N/A | Закрыто |
| MINOR-11 | `KEYCLOAK_JWKS_URI` в env | ✅ D-R3, line 41 | ✅ 1.2/2.1 | N/A | Закрыто |
| MINOR-12 | `docker compose config` | N/A | ✅ 1.2, line 6 | N/A | Закрыто |

Все 12 находок подтверждённо закрыты. Противоречий между design↔tasks↔proposal не выявлено. `MANAGEMENT_SERVER_PORT=8081` добавлено в env compose (решение CRITICAL без изменения Java-кода). PostgreSQL credentials — описаны. Node/pnpm версии — в 2.3. `mvn` vs `./mvnw` разнесён по сценариям (корп. образ внутри Docker, `./mvnw` на dev-машине). `HARNESS_WEBHOOK_BASE_URL` — в env.

**Вердикт Re-approval: APPROVE**


1. **CRITICAL**: В `docker-compose.yml` orchestrator env добавить `MANAGEMENT_SERVER_PORT=8081`. Альтернатива (если change не хочет env): убрать expose 8081 из compose и README, проверять health на `:8080`.
2. **MAJOR 2**: В `docker-compose.yml` postgres env добавить `POSTGRES_USER: harness`, `POSTGRES_PASSWORD: harness`.
3. **MAJOR 3**: Унифицировать на `./mvnw` во всех артефактах; добавить `chmod +x mvnw` в README для Linux VM.
4. **MAJOR 4**: Добавить Node.js ≥20.18.0 и pnpm ≥10.0.0 (или corepack) в prerequisites README.
5. **MAJOR 5**: Добавить `HARNESS_WEBHOOK_BASE_URL` в env compose с плейсхолдером.
6. **MINOR**: Уточнить healthcheck postgres, Web Origins, workspace `mkdir`, LLM-ключ warning, `KEYCLOAK_JWKS_URI`, `docker compose config`, NSIS/Windows vs Linux packaging.

После устранения MAJOR 1–5 — **APPROVE**.
