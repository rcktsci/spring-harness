# Tasks: m1-session-core

> Порядок групп = порядок зависимостей. Верификация — в каждой задаче. Спеки — `specs/`, решения — `design.md`. Правило: числа только в конфиге.

## 1. Фундамент

- [x] 1.1 Дополнить pom по факту (уже есть: preliquibase 2.0.0, testcontainers junit-jupiter/postgresql, wiremock, awaitility): добавить `spring-boot-starter-oauth2-resource-server`, `docker-java` (+транспорт httpclient5), `archunit`, `testcontainers-keycloak`; `mvn verify` зелёный на пустых тестах
- [x] 1.2 `IdGenerator`: UUID v7 + ULID (монотонный) через единую точку; unit-тесты на формат/сортируемость; перепроверка генератора владельца на Boot 4
- [x] 1.3 Скелет `@ConfigurationProperties`: `harness.security.*`, `harness.lock.*` (session-ttl, heartbeat, job-ttl), `harness.turn.*` (poll-interval, llm-retries, backoff-base), `harness.llm.timeout`, `harness.compact.threshold`, `harness.docker.*`, `harness.sse.ping-interval`, `harness.limits.*` (body, tool-output, bash-timeout-cap); тест на привязку дефолтов из application.yml
- [x] 1.4 Jackson 3 `FormatMapper` для jsonb (единая точка); интеграционный тест roundtrip `payload_jsonb` на Testcontainers-Postgres
- [x] 1.5 DataSource: автоконфигурация Boot (ручная сборка убрана директивой владельца — D-43), порядок пул → preliquibase → liquibase → JPA — preliquibase-стартером; smoke-тест старта контекста на Testcontainers-Postgres

## 2. БД (Liquibase)

- [x] 2.1 Чейнджсет `m1-core`: `app_user`, `llm_credentials`, `llm_model`, `agent`, `session`, `session_message`, таблица ShedLock; индексы по `data-model.md` §5 (partial index eligible-скан, PK `(session_id, seq)`, UNIQUE ULID); preliquibase-хук
- [x] 2.2 Интеграционный тест: миграции накатываются с нуля на чистом Postgres; append-only-guard (попытка UPDATE `session_message` отклоняется на уровне приложения)

## 3. Identity (specs/sso-gate)

- [x] 3.1 Resource server: JWT-валидация (issuer, подпись) на всех `/api/v1/**`; без/кривой токен → `401 unauthenticated` (Problem Details, без challenge); тест с самоподписанным JWT
- [x] 3.2 Groups-гейт: claim `groups` ∩ `harness.security.allowed-groups`, иначе `401 unauthenticated`; тесты «в группе / вне группы»
- [x] 3.3 Синхронизация `app_user` (upsert по `keycloak_subject`, обновление username/display_name); интеграционный тест на Testcontainers-Keycloak

## 4. SessionStore (specs/session-store)

- [x] 4.1 Сущности/репозитории + контракт `SessionStore` (Javadoc-инварианты); создание FREE с пином ревизии агента (`agentRev`|latest, `404 agent-not-found`); интеграционные тесты
- [x] 4.2 Допись событий: монотонный `seq` через транзакционный row-lock строки сессии (`UPDATE session SET last_seq = last_seq+1 … RETURNING`; лок `sess-{id}` НЕ используется), ULID, `last_seq/last_consumed_seq` транзакционно; конкурентный тест (2 параллельных дописи, в т.ч. при активном Turn'е — без дыр и дублей seq)
- [x] 4.3 Рендер видимости: журнал минус `COMPACT.covers` (проекция → SET покрытий → payload видимых); unit-тесты на вложенные/граничные случаи

## 5. LlmGateway (specs/llm-gateway)

- [x] 5.1 Сборка клиента из `llm_model`+`llm_credentials` (кэш по `llm_model.id`; AES-GCM api_key с `key_version`); явные `.timeout()`/`.maxRetries(0)` в каждой options (#6915); unit-тесты сборки, включая dangling `credentials_id` (Turn FAILED с SYSTEM-событием, старт процесса штатен)
- [x] 5.2 Стриминг + отмена: блокирующий стрим deltas, отмена прекращает поток; WireMock-тесты (стрим-чанки, abort)
- [x] 5.3 Ретраи 429/5xx: экспоненциальный backoff, лимит попыток из конфига, исчерпание → FAILED (SYSTEM-событие причины + `last_consumed_seq := last_seq`, без автоповтора Turn'а); фиксация tokens из usage; WireMock-тесты (429→успех, исчерпание)

## 6. WorkspaceTools (specs/workspace-tools)

- [x] 6.1 `docker/Dockerfile` helper-образа (минимальная ОС + find/grep/coreutils/git; выбор базового образа — Open Question design.md, зафиксировать в Dockerfile) + smoke-сборка в CI
- [x] 6.2 docker-java: ленивое создание `harness-<sessionId>` (workspace — bind-mount хост-каталога `workspaces/sessions/{sessionId}`, лимиты cpu/mem из конфига, сеть off), удаление при чистке; pull-политика: локальный образ приоритетен, pull — backoff-обновление, недоступность registry не фейлит вызов; интеграционный тест на реальном Docker (в т.ч. «registry недоступен, образ локально → инструменты работают»)
- [x] 6.3 Нативные инструменты `read_file/write_file/edit_file/glob/grep/bash`: контракты `agent-tools.md` §1 (created|overwritten, not-found/ambiguous, exitCode, таймаут bash с `timedOut`, stdout+stderr); containment-гварды: относительные пути, запрет `..`, canonical-path-резолв внутри корня workspace (symlink-обход — ошибка); единый отчёт `{callId, tool, status, output?, exitCode?, truncated?, timedOut?}`; лимит вывода + `truncated`; unit+docker-интеграционные тесты
- [x] 6.4 Отказы: смерть контейнера → LOST с причиной; ошибка монтирования workspace-каталога → ERROR с причиной; docker-интеграционные тесты (kill контейнера mid-bash)

## 7. TurnManager (specs/agent-turn)

- [ ] 7.1 Программный лок `sess-{id}` через `LockProvider` (TTL `harness.lock.session-ttl`), heartbeat `LockExtender` (интервал — конфиг), unlock в finally; тест конкурентного tryStart (1 победитель)
- [ ] 7.2 Агентный цикл: раунды «рендер → LLM → write-ahead (ASSISTANT+TOOL_CALL) → sync-инструменты → TOOL_RESULT → раунд»; выход при отсутствии tool-calls (COMPLETED, `last_consumed_seq := last_seq`); FAILED при исчерпании ретраев LLM — SYSTEM-событие причины + `last_consumed_seq := last_seq` (без автоповтора); новые события во время хода → доп. раунд; интеграционный тест на WireMock-LLM с tool-calling
- [ ] 7.3 Wake EVENT: после дописи USER-сообщения — немедленный tryStart (тот же виртуальный поток/пул); тест задержки старта (< poll-interval)
- [ ] 7.4 Wake POLL: `@Scheduled`+`@SchedulerLock` джоба (имя `poll-wake`, `lockAtMostFor` = `harness.lock.job-ttl`), eligible-скан `last_seq > last_consumed_seq`; тест «пропущенная EVENT-ом сессия подобрана POLL-ом»; чистка просроченных `sess-*`-строк (`lock_until < now()`)
- [ ] 7.5 Отмена: `POST /stop` → `cancel_requested`; проверка между вызовами; in-flight bash — убийство процесса в контейнере → TOOL_RESULT CANCELLED, Turn CANCELLED; сброс флага при завершении Turn'а (любой исход) и на старте нового; повторный stop идемпотентен; тесты, включая «сообщение после отмены обрабатывается штатно»
- [ ] 7.6 Рестарт-скан: (1) TOOL_CALL без результата у сессий со свободным `sess-{id}`-локом → синтетический LOST (залоченные не трогаем); (2) удаление осиротевших `harness-*`-контейнеров; тест на kill -9 + рестарт контекста

## 8. API (specs/session-api)

- [ ] 8.1 Инфраструктура ошибок: RFC 9457 Problem Details + `code`-каталог M1-подмножества, `errors[]` для 422, лимит тела → `413`; тесты каждой ошибки
- [ ] 8.2 `GET /agents`; `POST/GET/PATCH /sessions` (201+Location, фильтры `?mine&kind&q`, конверт-пагинация, сортировка lastActivityAt desc); MockMvc+Testcontainers тесты
- [ ] 8.3 `POST /sessions/{id}/messages` (202 `{messageId, seq}`, атрибуция из JWT) и `GET /sessions/{id}/messages ?since&limit` (только видимые); тесты
- [ ] 8.4 `POST /compact` (только FREE, иначе `409 wrong-session-kind` — STATE-строка в тесте фиксируется напрямую в БД) и `POST /stop` (202); тесты кодов (появление COMPACT-события в потоке — задача 9.2)
- [ ] 8.5 SSE `GET /sessions/{id}/events`: `message.created` (id=seq), снапшот `session.status` при коннекте, `Last-Event-ID` > `?since`, ping-комментарий + `retry: 5000`; тест с реальным SSE-клиентом (доставка, реконнект без дублей)

## 9. Компакция (specs/session-store)

- [ ] 9.1 Страховочная компакция: порог из конфига (~80% окна) → LLM-сводка покрытых → COMPACT-событие (`covers`+пересказ) → продолжение раунда; тест с WireMock (порог достигнут, раунд продолжен)
- [ ] 9.2 `/compact` команда: на границе раунда/после wake, COMPACT в потоке; тест

## 10. Приёмка фазы

- [ ] 10.1 ArchUnit: границы модулей `identity/session/execution/intelligence/api` (кросс-импорты мимо контрактов запрещены); тест в CI
- [ ] 10.2 Приёмочный end-to-end (критерий `roadmap.md` M1): FREE-сессия через минимальный attach (SSE+POST): сообщение → модель (WireMock) → инструменты в helper-контейнере (реальный Docker: write_file→bash→read_file) → стриминг; зелёный в CI
- [ ] 10.3 Рестарт-устойчивость: kill -9 сервера между фазами Turn'а → подъём: сессии целы, зависший TOOL_CALL закрыт LOST, контейнер пересоздан/удалён; автотест
- [ ] 10.4 Полный прогон `mvn verify` + сводка покрытия/результатов в `openspec/changes/m1-session-core/` (apply-заметки)
