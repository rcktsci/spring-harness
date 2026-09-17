# Review: m1-session-core (planning artifacts) — DeepSeek-V4.1-Flash

Объект: `openspec/changes/m1-session-core/` (proposal.md, 6 specs, design.md, tasks.md).
Эталон: `docs/glossary.md`, `docs/design/*` (D-01…D-42), `AGENTS.md`, фактический скелет (`pom.xml`, `src/main/resources/application.yml`).
`openspec validate m1-session-core --strict` — valid (структурная валидация пройдена).

## Findings

1. **blocker** — `design.md:80` (Risks) vs `specs/agent-turn/spec.md:50-53`, `specs/session-api/spec.md:32-34`, `docs/design/execution-model.md:42`.
   Дефект: design требует «все дописи строго под локом `sess-{id}` (включая POST messages: USER-допись без лока запрещена — берём лок краткосрочно)», но `execution-model` §2 держит `sess-{id}` на весь Turn (`tryStart`), а спеки требуют 202 на POST во время активного Turn и дополнительный раунд, видящий новое сообщение. Пока Turn держит лок, HTTP-поток `LockProvider.lock("sess-"+id)` вернёт empty (ShedLock не блокирующий) → допись либо отклоняется, либо нарушает «строго под локом». То есть сценарий «сообщение пришло во время хода» невыполним.
   Предложение: развести два механизма — монотонный `seq` через короткую транзакционную денормализацию (`UPDATE session SET last_seq = last_seq + 1 ... RETURNING`, row-lock), а `sess-{id}` оставить исключительно как mutex Turn'а в `tryStart`; явно записать это в design и убрать «USER-допись без лока запрещена».

2. **major** — `specs/agent-turn/spec.md:29` vs `design.md:41` (D-M1-4), `design.md:76` (Risks).
   Дефект: требование «Один и тот же Turn не может исполняться дважды параллельно ни на одном, ни на нескольких инстансах» — жёсткая гарантия, которую выбранный дизайн не даёт: «Fencing-токенов нет — осознанно (D-40) … риск принят». Сценарий «зависший процесс не блокирует навсегда» (spec.md:36-39) прямо опирается на истечение TTL, т.е. на окно двойного исполнения.
   Предложение: ослабить формулировку до «не более одной активной попытки при нормальной работе; при истечении TTL возможен повторный запуск — риск принят (D-40)» и сослаться на риск в design.

3. **major** — `specs/llm-gateway/spec.md:43,50-53` + `docs/design/execution-model.md:32` + `docs/design/data-model.md:147,152`.
   Дефект: при исчерпании ретраев Turn = FAILED, но ни одно событие в журнал не пишется, `last_consumed_seq` не двигается. Eligibility (`last_seq > last_consumed_seq` И не терминальна) выполняется, FREE-сессия не терминальна → POLL каждые ~5 с поднимает новый Turn с тем же контекстом и снова упирается в LLM-ошибку: бесконечный retry-шторм. Семантика «после FAILED» не определена ни в спеках, ни в design.
   Предложение: задать поведение при FAILED — например, дописывать SYSTEM-событие об ошибке (двигает `last_consumed_seq`) или вводить backoff/счётчик попыток Turn'а; отразить требованием в `agent-turn`/`llm-gateway` и задачей.

4. **major** — `tasks.md:9` (1.3) vs `design.md:14` (Goals «Конфиг-инвентарь: все числа…»), `design.md:36` (D-M1-3), `specs/workspace-tools/spec.md:53`, `specs/session-api/spec.md:66`, `design.md:41`.
   Дефект: инвентарь `@ConfigurationProperties` неполон. Не покрыты как конфиг: (а) LLM request timeout — design D-M1-3 требует явный `.timeout()` в каждой `OpenAiChatOptions`; (б) лимит таймаута `bash` — «аргумент, ограничен конфигом»; (в) интервал SSE-ping — design D-M1-8 «по таймеру конфига»; (г) `lockAtMostFor` POLL-джобы (~10 с) — параметр джобы. При этом AGENTS.md: «хардкод чисел запрещён», а design объявляет конфиг-инвентарь целью M1.
   Предложение: дополнить 1.3 ключами (`harness.llm.timeout`, `harness.limits.bash-timeout`, `harness.sse.ping-interval`, `harness.turn.poll-lock-at-most`) либо явно закрепить эти числа как контрактные константы.

5. **major** — `tasks.md` (нет задачи), `design.md:7` (Context).
   Дефект: design утверждает в настоящем времени «автоконфигурации отключены … datasource и LLM-клиенты собираются вручную. Порядок инициализации: пул → preliquibase → liquibase → JPA». Фактически в репозитории только `HarnessApplication`, `application.yml` и тест-база; `config`-пакеты пусты, `DataSourceAutoConfiguration` исключён (`application.yml:5`), но ручного `DataSource`/JPA/Liquibase/preliquibase-wiring нет. Ни одна задача 1.x/2.x этого не создаёт → блокирует весь блок 2–5.
   Предложение: добавить задачу фундамента «ручная сборка `DataSource` + порядок preliquibase → liquibase → JPA, smoke-тест старта контекста»; скорректировать Context (это to-do, а не данность).

6. **major** — `tasks.md:3` («Порядок групп = порядок зависимостей»), `tasks.md:26` (4.2), `tasks.md:44` (7.1).
   Дефект: 4.2 «Допись событий под локом `sess-{id}`» опирается на программный `LockProvider` `sess-{id}`, который создаётся только в 7.1 (группа 7). Порядок зависимостей нарушен.
   Предложение: перенести инфраструктуру лока (`LockProvider` bean, helper-обёртка) до группы 4 либо вынести её в группу 1 и сослаться из 4.2/7.1.

7. **major** — `proposal.md:41`, `tasks.md:7` (1.1) vs `pom.xml:110-114`, `pom.xml:147-156`.
   Дефект: фактические ошибки о скелете: preliquibase-стартер уже в pom (`net.lbruun.springboot:preliquibase-spring-boot-starter:2.0.0`), `testcontainers-junit-jupiter` и `testcontainers-postgresql` тоже. Формулировки «preliquibase (есть? — проверить)» и «Дополнить pom: … preliquibase … testcontainers (postgres, keycloak)» вводят в заблуждение; реально не хватает только Keycloak-модуля (`com.github.dasniko:testcontainers-keycloak`), `docker-java`, archunit и oauth2-resource-server.
   Предложение: переписать Impact/1.1 по факту («уже есть: …; добавить: …»), заменить «есть? — проверить» на констатацию.

8. **minor** — `proposal.md:42` vs `docs/design/data-model.md:8`, `design.md:84`, `tasks.md:14`.
   Дефект: в списке миграций фигурирует таблица `users`, тогда как канон — `app_user` (glossary/data-model/design/tasks); также в списке пропущена таблица `agent`, которая есть в Migration Plan и в 2.1.
   Предложение: заменить `users` → `app_user`, добавить `agent`.

9. **minor** — `design.md:36` (D-M1-3).
   Дефект: обоснование кэша «кэш по id ревизии модели, инвалидация не нужна — ревизии иммутабельны». У `llm_model` нет ревизий (`data-model.md:31-38`: id, credentials_id, model_id, params_jsonb, created_at) — ревизируемы `agent`/`workflow_revision`. Обоснование ссылается на несвойственную сущности семантику.
   Предложение: «кэш по `llm_model.id`; инвалидация не нужна — записи конфигурации меняются вручную и перечитываются при рестарте» (или явно ввести rev для llm_model, если это задумано).

10. **minor** — `specs/workspace-tools/spec.md:23-30` vs `tasks.md:37` (6.1).
    Дефект: требование «pull из registry — только retry/backoff; недоступность registry не блокирует существующие сессии» и сценарий «registry недоступен» не имеют ни реализующей, ни верифицирующей задачи (6.1 — только Dockerfile + smoke-сборка).
    Предложение: добавить в 6.2/6.4 тест «registry недоступен, образ локально → инструменты работают» либо вынести pull-политику в отдельную задачу.

11. **minor** — `specs/llm-gateway/spec.md:18-21` vs `tasks.md:31` (5.1).
    Дефект: сценарий «отсутствие учётных данных → ошибка конфигурации, а не падение при старте процесса» не покрыт задачей (5.1 упоминает лишь «unit-тесты сборки»).
    Предложение: явно добавить в 5.1 проверку dangling `credentials_id` при сборке клиента.

12. **minor** — `design.md:56` (D-M1-7), `tasks.md:38` (6.2) vs `docs/glossary.md:55,80`.
    Дефект: design/задачи вводят workspace-том `harness-ws-<sessionId>`, тогда как glossary определяет FREE-workspace как каталог `workspaces/sessions/{sessionId}` на хосте (bind). Named volume и host-dir — разные вещи; для будущего `GET /sessions/{id}/workspace/files` (api-contracts §8) это существенно.
    Предложение: зафиксировать «bind-mount хостового каталога `workspaces/sessions/{sessionId}`» (или осознанно названный volume) одним термином во всех артефактах.

13. **minor** — `design.md:41` (D-M1-4), `tasks.md:47` (7.4).
    Дефект: «чистка старых `sess-*`-строк — batch delete по возрасту». Возраст строки не отражает активность лока (`lock_until` обновляется при extend) → возможна очистка живого лока и двойное исполнение Turn.
    Предложение: критерий очистки — `lock_until < now()` (просроченные), а не возраст записи.

14. **minor** — `specs/llm-gateway/spec.md:53` + `specs/session-store/spec.md:25`.
    Дефект: «ошибка зафиксирована в журнале» при FAILED не определяет `kind` и payload события; перечень kinds (USER/ASSISTANT/SYSTEM/TOOL_CALL/TOOL_RESULT/COMPACT) не сопоставлен с ошибкой. Нетестопригодно и связано с находкой 3.
    Предложение: задать «SYSTEM-событие с payload ошибки» (или `last_turn_outcome` без события — но тогда закрыть находку 3 иначе).

15. **nit** — `specs/session-api/spec.md:5,66`, `tasks.md:57` (8.5).
    Дефект: (а) спека заявляет «подмножество api-contracts v3 (§1–§3)», но ошибки/общие правила берутся из §0 и §6; (б) `retry: 5000` захардкожен в спеке и в задаче (число — контрактная константа, но в свете правила «числа — конфиг» стоит пометить это явно).
    Предложение: расширить ссылку до «§0, §1–§3, §6»; пометить `retry`/`ping` как контрактные константы (не конфигурируемые) либо вынести пинг-интервал в конфиг-инвентарь (см. находку 4).

16. **nit** — `design.md:64-66` (D-M1-9), расположение capability-путей `specs/identity/sso-gate/` vs плоские остальные.
    Дефект: D-M1-9 без «Альтернативы» (design-instruction требует alternatives для каждого решения); смешанная вложенность capability-путей (один `domain/name`, пять плоских) — косметическая непоследовательность.
    Предложение: добавить альтернативу в D-M1-9; унифицировать раскладку `specs/` (один стиль).

## Summary

- Blockers: 1; major: 6; minor: 7; nit: 2.
- Главный дефект: внутреннее противоречие design.md:80/`agent-turn` — USER-допись «строго под локом `sess-{id}`» несовместима с удержанием этого лока на весь Turn, из-за чего сценарий «сообщение пришло во время хода» невыполним.
- Системные пробелы: не определена семантика после LLM-FAILED (риск retry-шторма через POLL); конфиг-инвентарь 1.3 неполон вопреки правилу владельца «все числа — конфиг»; отсутствует задача ручной сборки DataSource/JPA/Liquibase (Context design.md выдаёт это за готовое).
- Ошибки фактов о скелете: preliquibase и Testcontainers-postgres уже в `pom.xml`; в proposal фигурирует `users` вместо канонического `app_user`.
- Границы M1 в целом соблюдены: М2–М5-сущности (workflow/task/triggers/webhooks/spawn/async/tree/CLIENT_EXEC) не протекли, вырезанное D-41 не воскресло (нет `message_count`/`tokens_total`, прав, шар, билетов), энтерпрайз-раздутия нет.
- Готовность: артефакты структурно валидны и близки к apply, но до вливания требуется закрыть blocker №1 и перечисленные major (особенно №3–№5); после фиксов — re-review.

## Cross-check

Сверка с `m1-propose-review-glm.md` (18 находок) и `m1-propose-review-mercury.md` (10 находок).

| находка коллеги | verdict | комментарий |
|---|---|---|
| GLM-1: не определён сброс `cancel_requested` | agree | Подтверждаю: ни `agent-turn/spec.md:66`, ни `glossary §5`, ни `execution-model §6`, ни D-04 сброса не задают. Согласен с behavioural-последствием (флаг переживает CANCELLED → следующий Turn гаснет на первой проверке, `execution-model.md:51`). |
| GLM-2: pull-or-local helper-образа без задачи | agree | Дубль моей находки 10 (`workspace-tools/spec.md:25-30` vs `tasks.md:37-38`). Расхождение только в severity: оставляю minor — в M1 образ собирается при деплое и обязан лежать локально, pull-ветка вторична; факт пробела в тестах верен. |
| GLM-3: `ls` в `proposal.md:31` вне дизайн-базиса | agree | Факт верен: baseline `agent-tools.md §1`/`glossary §6` дают ровно read_file/write_file/edit_file/bash/glob/grep. Добавлю: там же искажены имена (`read/write/edit` вместо `read_file/write_file/edit_file`). |
| GLM-4: `users` вместо `app_user` (`proposal.md:42`) | agree | Дубль моей находки 8; подтверждается `data-model.md:8`, `design.md:84`, `tasks.md:14`. |
| GLM-5: пропущена таблица `agent` (`proposal.md:42`) | agree | Дубль моей находки 8 (`design.md:84`, `tasks.md:14`). |
| GLM-6: MCP-клиент не добавлен и перенос не зафиксирован | agree | `architecture.md:48` буквально требует MCP-клиент в pom «на M1», при этом roadmap M1 MCP не содержит. Согласен: нужна строка в Non-Goals design.md. |
| GLM-7: `design.md:84` ссылается на D-41 вместо D-39 | agree | Точное попадание: bootstrap-сид вырезан именно D-39 («без bootstrap-сида (начальные данные — руками в БД)»); D-41 про правило доступа. |
| GLM-8: «поздний результат» в EVENT — источник M3 | agree | `agent-turn/spec.md:11`; в M1 EVENT-источник один — USER. Пограничная формулировка, не противоречие execution-model. |
| GLM-9: неточный THEN в «отсутствие учётных данных» | agree | `llm-gateway/spec.md:18-21`; сценарий ненаблюдаем (нет ни кода каталога §6, ни места проявления). Дубль-подобно моей находке 11. |
| GLM-10: «факт таймаута» bash не имеет поля в контракте отчёта | agree | `workspace-tools/spec.md:53,63` vs контракт `:67` (`agent-tools.md §5`): поля timeout нет. Тест 6.3 «таймаут bash» нечем проверить. |
| GLM-11: в 6.3 потерян canonical-path/symlink-гвард | agree | `security-multitenancy.md §3` требует canonical-path-резолв; `tasks.md:39` оставляет только «относительные пути, запрет `..`». |
| GLM-12: 8.4 тестирует появление COMPACT-события до группы 9 | dispute | `tasks.md:56` перечисляет только коды (202/409) и не требует проверять событие на границе раунда; сценарий `session-api/spec.md:59-62` целиком закрывается 9.2 (`tasks.md:62`). Инверсии зависимости в задачах нет, есть лишь нюанс трактовки покрытия. |
| GLM-13: мнимое «ревизии иммутабельны» у `llm_model` | agree | Дубль моей находки 9 (`design.md:36` vs `data-model.md:31-38`). |
| GLM-14: SessionDto не специфицирован | agree | `session-api/spec.md:20` — «201 SessionDto» без полей; эталон `api-contracts.md:40` содержит M1-недостижимые поля (`workspaceBinding`, `taskId?`, `stateCode?`, `PARKED_*`). Тесты 8.2 без эталона. |
| GLM-15: «Sequence» в D-M1-5 | agree | Косметика `design.md:46`; смысл — упорядочивание доставки по seq. |
| GLM-16: нетестопригодное «мутация запрещена» | agree | `session-store/spec.md:32-35`; нужен конкретный код (405 `method-not-allowed` или 404). |
| GLM-17: ветка `409 wrong-session-kind` недостижима в M1 | agree | STATE-сессий в M1 нет; тест 8.4 требует DB-фикстуры либо переноса в M2. |
| GLM-18: `late?` — поле M3 | agree | `session-api/spec.md:48`; унаследовано из `api-contracts.md:42`, в M1 всегда пусто. |
| MERCURY-1: в design нет конкретных значений TTL | dispute | Требовать числа (30 min/5 min) в design конфликтует с правилом владельца «все числа — конфиг» (`AGENTS.md`, `architecture.md:51`, D-39) и с D-40 («TTL = lockAtMostFor (конфиг)»). Риск уже принят явно (`design.md:76`); дефекта нет. |
| MERCURY-2: нет конвенции имён partial-индекса | dispute | В baseline (`data-model.md:152`) имён индексов тоже нет, так что чендж не расходится с эталоном. Предложенный предикат `status != TERMINAL` ошибочен: в `session` нет ни `status`, ни терминальной колонки — eligibility = `last_seq > last_consumed_seq`. |
| MERCURY-3: execution-model определяет только COMPLETED/CANCELLED/LOST | dispute | Premise неверен: `execution-model.md:72` (строка 6) явно даёт «FAILED — после исчерпания попыток», а `data-model.md:148` фиксирует `last_turn_outcome: COMPLETED\|FAILED\|CANCELLED`; `LOST` — статус TOOL_RESULT (`agent-tools.md:56`), не исход Turn'а. Реальный пробел — не классификация FAILED, а поведение после него (моя находка 3, retry-шторм POLL). |
| MERCURY-4: нет ключа bash-timeout в 1.3 | agree | Дубль моей находки 4 (в 1.3 нет ни `bash-timeout`, ни LLM-timeout, ни ping). Уточнение: префикс — `harness.limits.*`, а не `harness.turn.*` (там poll/retry backlog, лимиты вывода уже в limits). |
| MERCURY-5: не задано, когда контейнер уничтожается (ARCHIVED / смена rev) | disagree | Предложенные триггеры противоречат базе: статуса `ARCHIVED` не существует (архивация выброшена D-41/`api-contracts.md:139`), а ревизия агента пинится иммутабельно и не может «смениться» (`data-model.md:194`). В M1 разрушение контейнера = orphan-cleanup рестарт-скана (`execution-model.md:21`, `design.md:56`, `tasks.md:49`) — это и есть осознанное lifecycle-правило. |
| MERCURY-6: ping-интервал не описан в execution-model §8 | dispute | Пинг — забота публичного контракта, а не исполнительной модели: он уже зафиксирован в `api-contracts.md:49` (15 с) и в чендже (`session-api/spec.md:66`, `design.md:61`). Расхождения нет. |
| MERCURY-7: путаница job-lock TTL и session TTL у 7.4 | dispute | Уже покрыто: `design.md:41` явно разделяет — джоба `poll-wake` с `lockAtMostFor ~10с` «параметр джобы», сессия — `harness.lock.session-ttl`; то же в `tasks.md:47`. Дефекта нет. |
| MERCURY-8: не показано место `truncated` в payload | dispute | Контракт уже определяет поле `truncated?` (`workspace-tools/spec.md:67`, `agent-tools.md:56-58`); пример payload необязателен, наблюдаемость полная. |
| MERCURY-9: выбрать базовый образ сейчас, иначе CI упадёт | dispute | Выбор образа — ровно содержание задачи 6.1 (`tasks.md:37`), open question не меняет ни спеки, ни контракт инструментов (`design.md:88`), а smoke-сборка проверяет уже выбранный образ. Опасности для CI нет. |
| MERCURY-10: 7.6 не закрывает контейнеры, оставшиеся mid-execution на краше | disagree | Уже покрыто: контейнер после рестарта процесса — «без живой сессии», т.е. orphan, и удаляется старт-сканом (`execution-model.md:21`, `design.md:56`, `tasks.md:49`). Периодический cleanup/docker-events design относит к M3 (`design.md:56`) — это осознанная граница, а не gap. |

## Fixes approval

Проверка по содержимому файлов (не по описаниям). `openspec validate m1-session-core --strict` — valid.

| # | находка / вердикт судьи | approve/reject | комментарий (что именно проверено) |
|---|---|---|---|
| 1 | DS-1 seq-lock (blocker) → J-1 | approve | `design.md:41` — `sess-{id}` «исключительно mutex Turn'ов», seq через `UPDATE session SET last_seq=last_seq+1 … RETURNING`; `design.md:81` (Risk); `tasks.md:27` явно «лок `sess-{id}` НЕ используется» + конкурентный тест «при активном Turn'е»; `agent-turn/spec.md:29` «допись событий его не требует». Закрыто корректно. |
| 2 | DS-2 mutex vs fencing → J-8 | approve | `agent-turn/spec.md:29` смягчено: «при нормальной работе … патологически замороженный … риск принят (D-40, без fencing)». |
| 3 | DS-3 FAILED-семантика (major) → J-3 | approve (с замечанием) | Закрыто: `agent-turn/spec.md:43`, `llm-gateway/spec.md:43`, `design.md:82`, `tasks.md:34` — SYSTEM-событие + `last_consumed_seq := last_seq`, без счётчиков/автоповтора. Замечание (не блокирует): заявлено «потребление батча», но `:= last_seq` перепотребляет USER-сообщения, пришедшие во время упавшего Turn'а — точнее было бы `:= seq границы раунда`. |
| 4 | DS-4 конфиг-инвентарь → J-4 | approve | `tasks.md:9`: добавлены `harness.lock.job-ttl`, `harness.llm.timeout`, `harness.sse.ping-interval`, `harness.limits.*` (…, bash-timeout-cap). Все 4 пропуска из находки закрыты. |
| 5 | DS-5 DataSource task → J-5 | approve | `tasks.md:11` (1.5: пул → preliquibase → liquibase → JPA + smoke-тест); `design.md:7` Context переписан: «сама первая задача M1 (задача 1.5), не данность скелета». |
| 6 | DS-6 инверсия 4.2→7.1 → R-10 (отклонено) | approve | Вердикт судьи принимаю: `tasks.md:27` больше не использует sess-лок, зависимость от 7.1 исчезла; ULID нужен лишь из 1.2. Инверсии нет. |
| 7 | DS-7 pom-факты → J-6 | approve | `proposal.md:41` и `tasks.md:7` сверены с `pom.xml`: preliquibase 2.0.0, testcontainers junit-jupiter/postgresql, wiremock, awaitility — «уже есть»; добавить oauth2-resource-server, docker-java, archunit, testcontainers-keycloak. Соответствует. |
| 8 | DS-8 `users`→`app_user` + `agent` → J-11 | approve | `proposal.md:42`: `app_user`, `agent`, `session`, `session_message`, `llm_model`, `llm_credentials`. |
| 9 | DS-9 «ревизии иммутабельны» у llm_model → J-11 | approve | `design.md:36`: «кэш по `llm_model.id`; `llm_model` правится вручную — актуализация рестартом». Расхождения со схемой нет. |
| 10 | DS-10 pull-or-local без задачи → J-7 | approve | `tasks.md:39` (6.2): pull-политика + тест «registry недоступен, образ локально»; `design.md:56` D-M1-7. |
| 11 | DS-11 dangling credentials → J-11 | approve | `tasks.md:32` (5.1): «unit-тесты сборки, включая dangling `credentials_id` (Turn FAILED с SYSTEM-событием, старт штатен)»; согласовано со сценарием `llm-gateway/spec.md:20-21`. |
| 12 | DS-12 bind-mount vs named volume → J-9 | approve | `design.md:56` bind-mount `workspaces/sessions/{sessionId}` + альтернатива `:57`; `proposal.md:15`; `tasks.md:39`; `workspace-tools/spec.md:11`. Остаточное слово «тома» в `workspace-tools/spec.md:76` и `tasks.md:41` — косметика, на контракт не влияет. |
| 13 | DS-13 чистка лока по возрасту → J-10 | approve | `design.md:41` «критерий `lock_until < now()` (не возраст записи)»; `tasks.md:48` «чистка просроченных `sess-*` (`lock_until < now()`)». |
| 14 | DS-14 kind/payload ошибки FAILED → J-3/J-11 | approve | `agent-turn/spec.md:43` и `llm-gateway/spec.md:43` — «SYSTEM-событие с причиной». Вид события определён. |
| 15 | DS-15 §0/§6 + retry-константа → J-11 | approve | `session-api/spec.md:5` «§0, §1–§3, §6»; `:68` «`retry: 5000` — контрактная константа api-contracts §3.1». |
| 16 | DS-16 альтернатива D-M1-9 + унификация specs-путей → J-11 | **reject** | Первая половина закрыта (`design.md:67` — альтернатива UUID v4/снежинки есть). Унификация путей не доделана: каталог переименован в `specs/sso-gate/`, а `tasks.md:18` по-прежнему «## 3. Identity (specs/identity/sso-gate)» — ссылка на несуществующий путь; H1 самой спеки `specs/sso-gate/spec.md:1` — «# Spec Delta: identity/sso-gate». |
| R-1…R-9 | Mercury-миноры, отклонены судьёй | approve | С отклонением согласен: R-1 (TTL-числа vs «числа — конфиг»), R-2 (нет колонки `status`), R-3 (FAILED есть в `execution-model.md:72`/`data-model.md:148`), R-4 (ARCHIVED/смена rev не существуют), R-5 (ping — контракт API), R-6 (job-ttl/session-ttl разведены), R-7 (`truncated` в контракте), R-8 (образ — задача 6.1), R-9 (M3-граница docker events). |

| DS-16 (re) | повторный аппрув после фикса | approve | Проверено в файлах: `specs/sso-gate/spec.md:1` = «# Spec Delta: sso-gate»; `tasks.md:18` = «## 3. Identity (specs/sso-gate)» — ссылок на несуществующий `specs/identity/sso-gate` не осталось. Косметический хвост «тома» → «каталога» закрыт в `specs/workspace-tools/spec.md:76`. Остаточные ниты вне scope (на контракт не влияют): `tasks.md:41` и `design.md:92` всё ещё употребляют «тома». |

**Итог: 25 approve / 0 reject** (24 первичных + повторный DS-16). Все blocker/major-находки закрыты по существу, а не декларативно; reject'ов не осталось.
