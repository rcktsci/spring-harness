# Ревью планировочных артефактов `m1-session-core` (GLM-5.3-Flash)

> Статическое ревью proposal.md / specs (6 capability) / design.md / tasks.md на соответствие дизайн-базису
> (`docs/glossary.md`, `docs/design/*`, D-01…D-42, AGENTS.md). Дата: 2026-09-17.

## Findings

1. **major** — `specs/agent-turn/spec.md:64-77` (Requirement «Отмена Turn'а (stop)»), смежно `specs/session-store/spec.md`.
   Дефект: семантика сброса `cancel_requested` не определена. Спека фиксирует установку флага и идемпотентность stop, но не говорит, когда флаг снимается. В дизайн-базисе сброс тоже нигде не задан (glossary §5, execution-model §6, D-04 — только «bool-флаг»). Следствие в M1: после CANCELLED Turn'а флаг остаётся → следующее USER-сообщение будит Turn, который на первой же проверке мгновенно гасится → сессия фактически сломана. Сценарий «stop → новое сообщение → Turn отрабатывает» не покрыт ни спекой, ни тестами (tasks 7.5).
   Предложение: зафиксировать в спеке точку сброса (например: флаг снимается при завершении Turn'а — COMPLETED/FAILED/CANCELLED — либо при новой USER-дописи) + сценарий «сообщение после отмены обрабатывается штатно».

2. **major** — `specs/workspace-tools/spec.md:23-30` (Requirement «Helper-образ локально») vs `tasks.md:37-38` (6.1/6.2).
   Дефект: спека требует «Pull из registry — только retry/backoff на случай обновления; недоступность registry не блокирует существующие сессии» + сценарий «registry недоступен → контейнеры создаются». В tasks нет ни одной задачи, покрывающей эту логику: 6.1 — только Dockerfile + smoke-сборка, 6.2 — ленивое создание/удаление контейнера. Нарушение оси «каждая спека-требование покрыта задачей».
   Предложение: дополнить 6.1/6.2 пунктом «pull-or-local: при создании контейнера — локальный образ приоритетен, pull только при обновлении, недоступный registry не фейлит вызов» + тест (реальный Docker с поднятым/погашенным registry или заглушка transport-слоя).

3. **minor** — `proposal.md:31`.
   Дефект: в перечне нативных инструментов указан `ls` — «нативные инструменты (ls/read/write/edit/bash/grep/glob)». В дизайн-базисе инструмента `ls` нет (glossary §6 «WorkspaceTools»: read_file, write_file, edit_file, bash, glob, grep; agent-tools §1 — то же); в spec/tasks он также отсутствует. По правилу владельца «каждая сущность обязана иметь сценарий необходимости» новый инструмент из proposal не может появиться молча.
   Предложение: убрать `ls` из proposal.md (покрытие списком каталога — glob).

4. **minor** — `proposal.md:42`.
   Дефект: в списке Liquibase-миграций указана таблица `users`, тогда как в `data-model.md` §1 таблица называется `app_user` (в tasks 2.1 и design.md Migration Plan имя верное).
   Предложение: исправить на `app_user`.

5. **minor** — `proposal.md:42`.
   Дефект: в списке миграций отсутствует таблица `agent`, хотя она обязательна в M1 (пин ревизии при создании сессии — session-store spec; `GET /agents` — session-api spec) и присутствует в design.md Migration Plan и tasks 2.1.
   Предложение: добавить `agent` в перечень миграций.

6. **minor** — `proposal.md:41` / `tasks.md:7` (1.1) vs `docs/design/architecture.md` §4.
   Дефект: architecture.md предписывает «в pom на M1 добавить: docker-java (+транспорт), **MCP-клиент Spring AI**, spring-boot-starter-oauth2-resource-server». Чендж MCP-клиент не добавляет (обоснованно — MCP-инструментов в M1 нет), но осознанный перенос нигде не зафиксирован (в design.md Non-Goals MCP не упомянут).
   Предложение: строкой в design.md Non-Goals: «MCP-клиент — с появлением MCP-инструментов (M3-слой агентов)»; либо включить зависимость в 1.1, если владелец хочет строгого следования architecture.md.

7. **minor** — `design.md:84` (Migration Plan).
   Дефект: «Начальные `llm_credentials`/`agent` — вручную в БД (MVP, **D-41**)» — ссылка на неверный ADR. Де-скоуп bootstrap-сида зафиксирован в **D-39** («без bootstrap-сида (начальные данные — руками в БД)»); D-41 — про единое правило доступа и выброшенные сущности.
   Предложение: заменить ссылку на D-39.

8. **minor** — `specs/agent-turn/spec.md:11` (Requirement «Wake-контур EVENT»).
   Дефект: перечень источников — «сообщение пользователя, **поздний результат**». Поздние TOOL_RESULT — сущность M3 (async-инструменты, D-09); в M1 единственный EVENT-источник — USER-сообщение. Требование фазы описывает недостижимый в ней источник (не ошибка согласованности с execution-model §1, но нарушение границы объёма в спеке).
   Предложение: ограничить перечень объёмом M1 («сообщение пользователя; поздние результаты — с M3») или пометить источник «(M3)».

9. **minor** — `specs/llm-gateway/spec.md:18-21` (Scenario «отсутствие учётных данных»).
   Дефект: THEN расплывчат — «система сообщает ошибку конфигурации, а не падает при старте процесса». Ни кода (в каталоге §6 api-contracts такого кода нет, а «код вне каталога — дефект реализации»), ни HTTP-статуса, ни места проявления (Turn FAILED? отказ запуска сессии? лог?). Сценарий нетестопригоден.
   Предложение: конкретизировать наблюдаемое поведение, например: «Turn не стартует, сессия получает последний исход FAILED с причиной в журнале/логе; процесс стартует штатно».

10. **minor** — `specs/workspace-tools/spec.md:53,63` vs строка 67 (единый отчёт).
    Дефект: «результат содержит факт таймаута» / «результат помечен как превышение таймаута», но контракт отчёта `{callId, tool, status: OK|ERROR|CANCELLED|LOST, output?, exitCode?, truncated?}` поля для этого факта не имеет. Представление не определено → тест из tasks 6.3 («таймаут bash») нечем проверять.
    Предложение: зафиксировать представление (например, `status: ERROR` + причина таймаута в output/отдельном поле) в Requirement «Лимит вывода и единый отчёт».

11. **minor** — `tasks.md:39` (6.3) vs `docs/design/security-multitenancy.md` §3.
    Дефект: containment-гварды задачи — «относительные пути, запрет `..`»; canonical-path/symlink-гвард из дизайн-базиса («Workspace-пути: только относительные, canonical-path-резолв внутри корня (symlink-обход — 422)» — «дёшево и осталось») в задаче потерян. Сценарий спеки («путь… указывает вне корня workspace») косвенно покрывает, но исполнителю задача явно предписывает только `..`.
    Предложение: в 6.3 дополнить: «canonical-path-резолв внутри корня workspace (symlink-обход — ошибка)» + кейс в тестах.

12. **minor** — `tasks.md:56` (8.4) vs `tasks.md:61-62` (группа 9).
    Дефект: задача 8.4 тестирует `/compact` включая поведение «COMPACT-событие появляется в потоке на границе раунда» (сценарий `specs/session-api/spec.md:59-62`), но механика компакции реализуется группой 9, которая идёт после группы 8, при заявленном «порядок групп = порядок зависимостей».
    Предложение: перенести группу 9 (компакция) до группы 8 либо сузить тесты 8.4 до контрактных кодов (202/409), перенеся проверку появления события в 9.2.

13. **minor** — `design.md:36` (D-M1-3).
    Дефект: «кэш по id ревизии модели, инвалидация не нужна — **ревизии иммутабельны**». В `data-model.md` иммутабельность зафиксирована для `agent` и `workflow_revision`; `llm_model` версий не имеет (`id` + `params_jsonb`, без rev) и её иммутабельность не декларирована. Ручная правка строки llm_model в БД даст stale-клиент до рестарта — обоснование кэша не соответствует схеме.
    Предложение: скорректировать обоснование («llm_model правится вручную в БД; актуализация — рестартом, MVP») либо зафиксировать иммутабельность llm_model строкой в data-model/decision.

14. **minor** — `specs/session-api/spec.md:20` (Requirement «CRUD сессий»).
    Дефект: ответ `201 SessionDto` не специфицирован — в отличие от MessageDto (перечислен полностью, строка 48), поля SessionDto в спеке не определены. Эталонный SessionDto из api-contracts §2 содержит поля, недостижимые/неопределённые для M1 (`workspaceBinding`, `taskId?`, `stateCode?`, `parentSessionId?`, статусы `PARKED_*`); для FREE в M1 неясно, что попадает в контракт (в т.ч. чем заполняется workspaceBinding при `SERVER_DIR auto`). Тесты 8.2 не имеют эталона полей.
    Предложение: перечислить M1-подмножество SessionDto явно (id, kind, title, owner, agent {key, rev}, runtimeStatus: IDLE|TURN_RUNNING, lastTurnOutcome?, lastSeq, lastActivityAt, createdAt + решение по workspaceBinding для FREE).

15. **nit** — `design.md:46` (D-M1-5).
    Дефект: «in-memory broadcaster на сессию (subscribers list, `Sequence`-упорядочивание по seq)» — «Sequence» реакторный термин и выглядит инородно рядом с «никаких реактивных цепочек»; по смыслу — упорядочивание доставляемых deltas по seq.
    Предложение: переформулировать: «доставка подписчикам строго по возрастанию seq».

16. **nit** — `specs/session-store/spec.md:32-35` (Scenario «мутация запрещена»).
    Дефект: THEN — «операция невозможна: API не предоставляет такого поведения» — не конкретизирован наблюдаемый ответ (404? 405 `method-not-allowed` из каталога?). Отрицательный сценарий «любым методом» нетестопригоден в текущем виде.
    Предложение: зафиксировать ожидаемый код (например, PATCH/DELETE /messages → 405 method-not-allowed или 404) и убрать «любым методом» в пользу конкретных методов.

17. **nit** — `specs/session-store/spec.md:48`, `specs/session-api/spec.md:57`, `tasks.md:56`.
    Дефект: ветка «для STATE → `409 wrong-session-kind`» в M1 недостижима: API создаёт только FREE, STATE-сессии появляются в M2 — для теста потребуется ручная вставка STATE-строки в БД. Это не ошибка (каталог ошибок полный), но способ теста в задаче не оговорён.
    Предложение: в 8.4 оговорить механизм теста (фикстура со вставкой STATE-сессии напрямую в БД) либо пометить проверку как переносимую в M2.

18. **nit** — `specs/session-api/spec.md:48`.
    Дефект: MessageDto включает `late?` — признак поздних результатов async-инструментов (M3); в M1 поле всегда отсутствует. Унаследовано от api-contracts §2 (не дефект согласованности), но читатель спеки M1 получит лишнюю сущность.
    Предложение: пометить «`late?` (M3, всегда отсутствует в M1)».

## Summary

- **Blocker: 0. Major: 2. Minor: 12. Nit: 4** (всего 18).
- Противоречий с дизайн-базисом уровня «ломает контракт» не найдено: коды ошибок, пути, форматы (ULID/seq/UUID v7), SSE-механика, ShedLock `sess-{id}`, write-ahead, рестарт-скан, компакция — соответствуют api-contracts/execution-model/data-model/D-30/D-35/D-40. Ничего из вырезанного D-41/D-42 не воскресло; утечек M2–M5 по существу нет (косметика — п.8, п.18).
- Оба major — пробелы полноты, не искажения базы: (1) неопределённый сброс `cancel_requested` (реальный поведенческий риск для сценария «stop → новое сообщение»), (2) спека pull-or-local helper-образа без покрывающей задачи.
- Minor — в основном точечные расхождения proposal с базой (`ls`, `users`, отсутствие `agent`), неточные ссылки на ADR (D-41→D-39), недоконкретизированные тестопригодные THEN и потерянные в tasks детали (symlink-гвард, порядок групп 8/9).
- Вывод: артефакты готовы к фиксу-циклу; после устранения 2 major и косметики minor — можно выводить на аппрув ревьюеров. MVP-дисциплина (числа в конфиг, без энтерпрайза) соблюдена.

## Cross-check

Обозначения: DS-n — находка n из `m1-propose-review-deepseek.md`, M-n — из `m1-propose-review-mercury.md`. Фактические утверждения проверены по объекту ревью, эталонам в `docs/design/`, скелету (`pom.xml`, `src/`).

| Находка коллеги | Verdict | Комментарий |
|---|---|---|
| DS-1 (blocker) USER-допись «строго под локом sess-{id}» несовместима с локом на весь Turn | agree | Подтверждено: design.md:80 против execution-model.md:42 (лок держится весь Turn, `LockProvider.lock()` неблокирующий) — допись при активном Turn невозможна, сценарий «сообщение во время хода» (agent-turn spec:53) ломается. В моём файле находки нет — признаю пропуск. Позиция: seq — транзакционным `UPDATE session SET last_seq = last_seq+1 … RETURNING` (row-lock строки сессии), `sess-{id}` — только mutex Turn'а в tryStart; строку «USER-допись без лока запрещена» из Risks убрать. |
| DS-2 (major) гарантия «Turn не исполняется дважды параллельно» сильнее принятого в D-40 риска | agree | agent-turn spec:29 — абсолютная формулировка; при отсутствии fencing (D-40) окно двойного исполнения после истечения TTL существует. Смягчить формулировку со ссылкой на принятый риск. |
| DS-3 (major) FAILED → не двигается last_consumed_seq → POLL-шторм | dispute | Премиса «ни одно событие в журнал не пишется» неверна: llm-gateway spec:53 уже требует «ошибка зафиксирована в журнале». Суть валидна: kind события не задан (это DS-14) и продвижение last_consumed_seq при FAILED не определено — шторм реален. Позиция: при исчерпании ретраев — SYSTEM-событие с причиной + `last_consumed_seq := last_seq`; повторная попытка — новой USER-дописью (без счётчиков/backoff — D-35, MVP). |
| DS-4 (major) конфиг-инвентарь 1.3 неполон | agree | Проверено по tasks 1.3: отсутствуют llm request timeout (D-M1-3 требует явный `.timeout()`), лимит таймаута bash (workspace-tools spec:53), SSE ping (D-M1-8), `lockAtMostFor` POLL-джобы. В моём файле находки нет — признаю пропуск. |
| DS-5 (major) нет задачи на ручную сборку DataSource/JPA/Liquibase; design.md:7 подаёт как данность | agree | Проверено: src = `HarnessApplication.java` + `application.yml` (exclude на :5) + тест-база; wiring отсутствует, задачи 1.x его не создают — блокирует группы 2–5. В моём файле находки нет — признаю пропуск. |
| DS-6 (major) 4.2 зависит от лока, создаваемого в 7.1 (порядок групп) | dispute | Формально верно, но при принятии DS-1 допись перестаёт использовать `sess-{id}` — 4.2 переписывается на транзакционный UPDATE и зависимость исчезает; отдельный перенос инфраструктуры лока не нужен. |
| DS-7 (major) фактические ошибки о pom: preliquibase и testcontainers (junit-jupiter, postgresql) уже есть | agree | pom.xml:110-114 (`preliquibase-spring-boot-starter:2.0.0`), pom.xml:147-156 — подтверждено; «preliquibase (есть? — проверить)» (proposal:41) — ложная неопределённость. Реально не хватает: oauth2-resource-server, docker-java, archunit, `testcontainers-keycloak`. |
| DS-8 (minor) `users` → `app_user`, пропущен `agent` | agree | Дубль моих #4 и #5. |
| DS-9 (minor) D-M1-3: у llm_model нет ревизий | agree | Дубль моей #13. |
| DS-10 (minor) pull/registry-требование без задачи | agree | Дубль моей major #2. |
| DS-11 (minor) сценарий dangling `credentials_id` не покрыт задачей 5.1 | agree | Дополняет мою #9 (там — расплывчатый THEN, здесь — отсутствие задачи); чинятся одним правком. |
| DS-12 (minor) «том `harness-ws-<sessionId>`» vs host-каталог workspace | agree | glossary.md:55,80 фиксируют workspace FREE как host-каталог `workspaces/sessions/{sessionId}` (SERVER_DIR auto, retention-чистка); design.md:56 и tasks 6.2 говорят «том» — named volume ≠ bind-mount хост-каталога, существенно для retention и будущего скачивания файлов (api-contracts §8). Зафиксировать bind-mount. |
| DS-13 (minor) чистка `sess-*` по возрасту строки | agree | Возраст записи не учитывает extend: критерий должен быть `lock_until < now()`, иначе POLL удалит живой продлённый лок → двойное исполнение. |
| DS-14 (minor) kind/payload события ошибки FAILED не заданы | agree | Перечень kinds (session-store spec:25) не сопоставлен с ошибкой — задать SYSTEM-событие с причиной (сопряжено с DS-3 и моей #9). |
| DS-15 (nit) ссылка «§1–§3» без §0/§6; `retry: 5000` без пометки «константа контракта» | agree | session-api spec:5 уточнить до «§0, §1–§3, §6»; retry/ping-дефолт — контрактные константы api-contracts §3.1, пометить явно. |
| DS-16 (nit) D-M1-9 без «Альтернативы»; смешанная вложенность specs/ | agree | У D-M1-1…8/10 альтернативы есть, у D-M1-9 нет — добавить (или сослаться на альтернативы D-31); вложенность унифицировать. |
| M-1 (minor) прописать конкретные TTL-значения в design.md | disagree | Против правила владельца «все числа — конфиг» (AGENTS.md, D-39): значения — в application.yml, не в planning-артефакте. Инвариант уже зафиксирован: D-M1-4 + execution-model.md:42 («TTL заведомо больше максимального Turn'а», heartbeat — конфиг). |
| M-2 (minor) нейминг-конвенции индексов | disagree | Имплементационная деталь; data-model.md:152 уже задаёт состав индексов точно. Предложенный предикат `status != TERMINAL` выдумывает несуществующий атрибут: терминальности сессий нет — сессии резюмируемы (glossary §5), eligibility = `last_seq > last_consumed_seq`. |
| M-3 (nit) FAILED — не Turn-outcome, execution-model §2 «только COMPLETED/CANCELLED/LOST» | disagree | Оба утверждения неверны: data-model.md:148 — `last_turn_outcome: COMPLETED\|FAILED\|CANCELLED`; execution-model.md:72 (§3, строка 6) — «FAILED — после исчерпания попыток». LOST — статус TOOL_RESULT (agent-tools §5), не исход Turn'а. Предложение инвертирует модель: FAILED — именно исход Turn'а; runtimeStatus (IDLE\|TURN_RUNNING\|PARKED_*) — другая ось (api-contracts §2). |
| M-4 (minor) bash-timeout не в 1.3 | agree | Дубль DS-4б (подтверждаю по tasks 1.3). |
| M-5 (major) lifecycle контейнера: «когда уничтожается» | disagree | Упомянутые триггеры не существуют: архивации сессий нет (D-41 вырезал), FREE→STATE не бывает (kind фиксируется при создании, session-store spec:11), ревизия агента пинится и не меняется. Lifecycle для M1 определён полностью: ленивое создание (workspace-tools spec:13-21), смерть в момент вызова → LOST (design.md:56, sync-мир), рестарт-скан «harness-* минус живые сессии» (agent-turn spec:80); «уничтожение при чистке» отложено вместе с retention (data-model.md:168, осознанно). |
| M-6 (minor) ping-интервал «не упомянут в execution-model» | disagree | Не дефект: параметр зафиксирован в design.md:61 (D-M1-8 «ping-комментарий по таймеру конфига») и в самой спеке («конфиг, дефолт 15 с»); execution-model SSE-транспорт вообще не описывает. Реальный остаток (ключ не в 1.3) — это DS-4в. |
| M-7 (minor) 7.4 противоречит design.md:41 | disagree | Противоречия нет: tasks 7.4 дословно повторяет design.md:41 и execution-model.md:13 («`lockAtMostFor` ~10с — параметр джобы»); имя `poll-wake` задано в D-M1-4; разделение job-TTL/session-TTL уже сделано в обоих текстах — ровно то, что Mercury предлагает «уточнить». |
| M-8 (minor) «не показано, где truncated в стриме» | disagree | Поле `truncated?` зафиксировано в контракте отчёта (workspace-tools spec:67; agent-tools.md §5), а отчёт и есть payload TOOL_RESULT-события — место определено. Пример-payload — опциональная косметика, не дефект. |
| M-9 (nit) выбрать базовый образ сейчас (alpine 3.19) | disagree | Перенос решения в момент написания Dockerfile — зафиксированное Open Question (design.md:88) с точкой принятия в tasks 6.1 («выбор базового образа… зафиксировать в Dockerfile»); контракт инструментов не меняет, риск «CI failure» не обоснован (smoke-сборка 6.1 поймает сразу). |
| M-10 (major) orphan-контейнеры «left mid-execution on crash» не покрыты | disagree | Оба кейса покрыты: смерть контейнера при исполнении детектируется в момент вызова → LOST (design.md:56, «sync-мир M1»); после падения процесса рестарт-скан удаляет все `harness-*` без живых локов (agent-turn spec:80, execution-model.md:21). docker events/периодическая чистка — осознанно M3 (design.md:56); предложение реанимирует отложенный механизм и противоречит sync-модели M1. |

### Итог кросс-чека

- DeepSeek: 14 agree (в т.ч. 3 дубля моих), 2 dispute (DS-3, DS-6); blocker DS-1 признаю — реальная дыра, пропущенная мной.
- Mercury: 1 agree (M-4, дубль DS-4), 9 disagree — премисы не подтверждаются эталонами (M-3 — факт-ошибка, M-5/M-10 — несуществующие/отложенные механизмы, M-1 — против правила «числа — конфиг»).

## Fixes approval

Аппрув-раунд по вердиктам `m1-propose-judge.md` (J-1…J-11 / R-1…R-10). Проверено содержимое файлов `openspec/changes/m1-session-core/` (не описания фиксов). Все мои находки приняты судьёй (отклонённых R-* среди моих нет), ниже — проверка «заявленное ≠ сделанное» по файлам.

| Находка | Verdict | Evidence (проверено в файлах) |
|---|---|---|
| GLM-1 (major) сброс `cancel_requested` | approve | agent-turn spec:66 (сброс при завершении Turn'а любого исхода и на старте нового; stop на IDLE без эффекта) + сценарий :78-81 «сообщение после отмены» + design.md:83 + tasks 7.5 |
| GLM-2 (major) pull-or-local без задачи | approve | tasks 6.2: pull-политика + тест «registry недоступен, образ локально → работают»; design.md:56 |
| GLM-3 `ls` / канонические имена инструментов | approve | proposal:31 — `read_file`/`write_file`/`edit_file`/`bash`/`glob`/`grep` |
| GLM-4 `users` → `app_user` | approve | proposal:42 |
| GLM-5 `agent` в миграциях | approve | proposal:42 |
| GLM-6 MCP в Non-Goals | approve | design.md:17 — «MCP-клиент… осознанный перенос из architecture.md §4» |
| GLM-7 ссылка D-41 → D-39 | approve | design.md:87 «(MVP, D-39)» |
| GLM-8 EVENT «поздний результат» M3-пометка | approve | agent-turn spec:11 «в M1 — сообщение пользователя; поздние результаты — с M3» |
| GLM-9 расплывчатый THEN (dangling credentials) | approve | llm-gateway spec:21 — конкретно: «старт штатен; Turn → FAILED с SYSTEM-событием причины» |
| GLM-10 маркер таймаута bash | approve | workspace-tools spec:53 (`timedOut: true`), :67 (отчёт с `timedOut?`, только bash); tasks 6.3 |
| GLM-11 canonical-path в 6.3 | approve | tasks 6.3 — «canonical-path-резолв внутри корня (symlink-обход — ошибка)» |
| GLM-12 порядок 8.4 vs 9 | approve | tasks 8.4 — «тесты кодов (появление COMPACT-события… — задача 9.2)» |
| GLM-13 обоснование кэша D-M1-3 | approve | design.md:36 — «кэш по `llm_model.id`; правится вручную — актуализация рестартом, MVP» |
| GLM-14 SessionDto M1-подмножество | approve | session-api spec:27 — поля перечислены, недостижимые помечены «M2–M3» |
| GLM-15 термин «Sequence» | approve | design.md:46 — «доставка строго по возрастанию seq» |
| GLM-16 сценарий «мутация запрещена» | approve | session-store spec:34-35 — PATCH/DELETE → `405 method-not-allowed` |
| GLM-17 STATE-фикстура для 409 | approve | tasks 8.4 — «STATE-строка в тесте фиксируется напрямую в БД» |
| GLM-18 `late?` M3-пометка | approve | session-api spec:50 — «(поле M3; в M1 всегда отсутствует)» |

Чек-лист по чужим принятым фиксам (J-*) — проверен по файлам, без замечаний по существу: J-1 row-lock дописи (design.md:41-42,81; tasks 4.2; agent-turn spec:29), J-3 FAILED-семантика (agent-turn spec:43; llm-gateway spec:43; tasks 5.3/7.2), J-4 конфиг-инвентарь (tasks 1.3: `job-ttl`, `harness.llm.timeout`, `harness.sse.ping-interval`, `bash-timeout-cap`), J-5 задача 1.5 + переписанный Context (design.md:7), J-6 pom по факту (proposal:41, tasks 1.1), J-8 смягчение mutex (agent-turn spec:29), J-9 bind-mount (design.md:56-57, proposal:15, tasks 6.2, workspace-tools spec:11), J-10 чистка по `lock_until` (design.md:41, tasks 7.4), D-M1-9 альтернатива (design.md:67).

**Итог: 18 approve / 0 reject.** Все мои находки закрыты корректно, расхождений «заявлено vs сделано» нет. Два косметических остатка вне моего списка находок (не блокируют аппрув): (1) tasks.md:18 — заголовок группы 3 всё ещё ссылается на старый путь `specs/identity/sso-gate` после переименования в `specs/sso-gate/`; (2) workspace-tools spec:76 — «ошибка монтирования workspace-тома» (оставшийся термин «том» при повсеместном bind-mount). Отклонённые судьёй R-1…R-9 — находки Mercury, R-10 — DeepSeek; по своим вердиктам судьи согласен (подтверждены кросс-чеком).
