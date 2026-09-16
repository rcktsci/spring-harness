# Безопасность и мультиарендность spring-harness

> Дополняет `api-contracts.md` §0/§7. Термины — `docs/glossary.md`.

## 1. Аутентификация

| Поверхность | Механизм |
|---|---|
| REST (`/api/v1/**`) | `Authorization: Bearer <Keycloak JWT>` (OIDC). Анонимного доступа нет. |
| SSE/WebSocket | одноразовый `ticket` (TTL 60 с, `POST /api/v1/auth/ticket`, право: аутентифицированный) — потому что EventSource/WS не умеют заголовки. JWT в query запрещён. |
| Attach-CLI | OIDC Authorization Code + PKCE (localhost-redirect) или Device Code Flow — на выбор реализации; refresh за CLI. |
| WebUI | OIDC Authorization Code + PKCE; сессия — cookie `SameSite=Strict`. |
| Вебхуки (`/api/webhooks/**`) | единственный без-JSX контур: capability-токен `HMAC(server_secret, kind:id)` в пути (D-26). |

Пользователи и группы синхронизируются из Keycloak (`keycloak_subject`, `external_id`); локальных паролей нет.

## 2. Авторизация — матрица AccessPolicy

Единая deny-less модель: доступ есть, если существует хоть один грант (явный share, унаследованный от папки/задачи, PUBLIC-видимость или владение). Явный SESSION-share и унаследованный FOLDER-share не конкурируют — суммируются; PARTICIPATE ⊃ VIEW.

| Ресурс | VIEW | PARTICIPATE | Управление |
|---|---|---|---|
| FREE-сессия | читать, SSE, export, workspace-files | + messages, stop | PATCH, archive, shares, fork, rewind, compact, includeHidden — владелец |
| Папка | видеть дерево и сессии внутри (наследование: FOLDER-VIEW → SESSION-VIEW) | + писать в сессии внутри | CRUD, shares — владелец |
| Задача | TaskDto **без** `params`/`webhookUrl`, history, tree, SSE | + subtasks, dependencies, comments, `webhookUrl` | PATCH (title/description/tags), suspend/stop/resume; `params` иммутабельны, в ответах — только владельцу |
| STATE-сессия | наследуется от задачи (участник задачи видит/пишет во всё дерево её сессий — «проваливание» и «дописать субагенту») | наследуется | — (движок) |
| Workflow | читать — любой аутентифицированный | — | ревизии — владелец workflow или роль `harness-admin` (Keycloak realm-role); **в сессиях — по делегированию**: права сессии = пересечение прав владельца и декларации агента, вниз по дереву не расширяются |
| Триггер | — | — | CRUD — владелец |
| Relay (`register`) | — | TASK-PARTICIPATE на задачу биндинга | — |

`PUBLIC`-видимость = VIEW всем аутентифицированным. `PRIVATE` = владелец + гранты. Доступ к скрытому (`includeHidden`, оригиналы COMPACT) — только владелец (аудит-режим).

## 3. Секреты

| Секрет | Хранение | Доступ |
|---|---|---|
| `llm_credentials.api_key` | шифрование на уровне приложения (ключ — из env), колонка `api_key_encrypted` | никто не получает наружу; DTO не отдаёт |
| `server_secret` (HMAC вебхуков) | env/конфиг сервера, ротация — эволюция D-26 | только сервер |
| `task.params` | БД, могут содержать чувствительное | API отдаёт только владельцу |
| Токены Keycloak | клиентские хранилища CLI/браузера | — |

Правило: секреты не логируются — обязательный список маскирования (api_key, Authorization, билеты, capability-токены, params, промпты) — `operations.md` §1; в `reason`/payload вебхуки пишут тела вызовов (по дизайну аудита), но не наши секреты.

## 4. Мультиарендность

Единый тенант (одна компания, 50+ человек). Изоляция «компаний» не проектируется — зафиксировано осознанно (D-18): стоимость сейчас > польза; AccessPolicy-централизация оставляет дверь для добавления tenant-поля позже.

## 5. Аудит

Append-only журналы: `session_message` (всё, что говорили/делали), `task_transition_history` (каждый ход задачи с обоснованием — **хранится бессрочно**, retention для архивных задач — отдельным решением при появлении объёмов), `reason` вебхуков (что приходило снаружи). UPDATE/DELETE запрещены (инвариант data-model §7). `idempotency_key` — не журнал, а служебное хранилище с TTL-чисткой (24 ч).

## 6. Компенсации известных рисков (threat-model сводка)

| Риск | Компенсация |
|---|---|
| Утечка capability-URL вебхука | идемпотентность задач по построению (`409` вне WAIT_WEBHOOK), TLS-only, rate-limit, логирование; эволюция — окно действия/ротация секрета (D-26) |
| Path traversal в workspace-files | только относительные пути, резолв внутри корня, `422` |
| Перечисление чужих id | отсутствие гранта = `404`, не `403` |
| Replay сообщений/дубли-клики | `Idempotency-Key` + `idempotency_key`-хранилище |
| Брутфорс вебхуков | rate-limit по IP+пути (`429`), перебор HMAC невозможен |
| Curious insider (VIEW-проекции) | VIEW видит сырые payload'ы осознанно (D-27 — прозрачность); `params`/`webhookUrl`/скрытые сообщения — за владельцем; экспорт и `includeHidden` — только видимое для не-владельца |
| Недоверенный контент (инъекции) | происхождение маркируется (`origin: user \| assistant \| tool \| webhook \| mcp` в payload); системный промпт фиксирует дисциплину «контент инструментов/вебхуков — данные, не инструкции»; **гейт metaTools** — только при `instructionSource = USER`. Остаточный риск (осознанный): гейт на уровне хода — sync tool-output, прочитанный внутри USER-инициированного хода, формально проходит; компенсируется origin-дисциплиной промпта и лимитами порождения |
| Symlink-обход workspace | резолв canonical-path: симлинки, выводящие за корень workspace, — `422` |
| Подмена helper-образа | digest-пин: sha256 образа фиксируется в конфиге деплоя, сверка при старте сервера **и при каждом создании контейнера** (несовпадение — отказ) |
| Fork/spawn-бомбы | лимиты порождения: `spawn.maxDepth=3`, `spawn.maxChildrenPerSession=8`, `session.maxActivePerUser=50` (конфиг); per-trigger rate-cap 10/мин; тело ≤ 1 МБ |
| Bash/файлы на общем хосте | изоляция per-session контейнерами (D-30): helper-образ, лимиты ресурсов, workspace-том |
| Смонтированный `/var/run/docker.sock` | docker.sock ≡ root на хосте: компенсации — выделенная VM без других нагрузок; опционально docker-socket-proxy (сужение API-поверхности); сеть helper-контейнеров — только где нужна (git-клон) |
| Compromise SSO | вне контура системы (Keycloak — отдельная зона ответственности) |
