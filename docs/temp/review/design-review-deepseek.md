# Ревью дизайн-корпуса `docs/**` (междокументная согласованность)

> Ревьюер: DeepSeek-V4.1-Flash (роль — ревьюер дизайн-корпуса).
> Дата: 2026-09-16.
> Объект: все конечные документы — `glossary.md`, `design/{architecture,data-model,execution-model,workflow-domain,api-contracts,security-multitenancy,agent-tools,client-cli,roadmap,decisions}.md`.
> Контекст: `docs/temp/review/api-review-*.md` (api-contracts v2 прошёл 3×approve — его внутренние дефекты НЕ пересматриваются; проверяются только стыки).
> Severity: **CRITICAL** — блокирует реализацию/корректность контракта; **MAJOR** — существенный пробел/противоречие; **MINOR** — практическое/стилевое.

## Сводка

| Severity | Кол-во |
|---|---|
| CRITICAL | 3 |
| MAJOR | 13 |
| MINOR | 19 |
| **Всего** | **35** |

Новых внутренних дефектов самого `api-contracts.md` не заявлено. Найденные ниже пункты — стыки документов, написанных после api-ревью, внутренние дефекты этих документов и их связки с утверждённой спекой.

---

## CRITICAL

### C-1. `session.title` не существует в схеме, но требуется контрактом
- **Место:** `api-contracts.md` §2 (`POST /sessions { title? }`, `PATCH { title? }`, `GET /sessions?q=` по title, `SessionDto.title`); `data-model.md` §5 `session`; `glossary.md` §4 `Session`.
- **Описание:** `SessionDto` отдаёт `title`, `POST/PATCH` его принимают, список фильтруется по `q` (title). В `data-model.md` §5 (`session`) колонки `title` нет (единственный `title` в §4 — у `task`). В `glossary.md` §4 перечисленные поля сессии title тоже не содержат. Контракт (спека = источник истины) опирается на отсутствующее хранилище — реализатор обязан либо добавить ad-hoc колонку, либо не выполнить спеку.
- **Предложение:** добавить `title text` в `data-model.md` §5 `session` и в перечень полей `Session` глоссария; синхронно указать дефолт (например, `NULL` / автогенерация из агента).

### C-2. Владелец задачи/триггера, создаваемых агентом, не определён
- **Место:** `data-model.md` §4 `task.owner_user_id` (NOT NULL FK) / `author_user_id` (NULL для агента); `workflow-domain.md` §6 инструменты оркестратора (`create_task`, `create_subtask`, `configure_trigger`); `glossary.md` §3 `Orchestrator`; `security-multitenancy.md` §2 (владение = грант).
- **Описание:** оркестратор-агент создаёт задачи, подзадачи и триггеры инструментами. Владение (`owner_user_id`) — обязательный FK на `app_user`, `author_user_id` допускает NULL (агент), но **не описано, кто становится владельцем** при создании агентом. От владельца зависит вся матрица прав (`security §2`/`api §7`: PATCH/suspend/stop/params/shares). То же для `trigger.owner_user_id` и для дочерних сессий `spawn_subagent` (`session.owner_user_id`). Без правила создание агентом нереализуемо без ad-hoc решений, что запрещено инвариантом `data-model §7.3`.
- **Предложение:** зафиксировать правило («владелец = пользователь, инициировавший сессию агента/задачу-родителя; для STATE — владелец задачи»), продублировать в `glossary.md` (Task/Session/Trigger) и в `data-model.md`. В api-review это уже отложено как N-8 — сейчас зависимость стала блокирующей (M3-оркестратор).

### C-3. Логический workspace `(taskId, binding)` не выставляется наружу — релей не может зарегистрироваться
- **Место:** `api-contracts.md` §5 (`register { taskId, binding, basePath }`); `client-cli.md` §1 (`relay --task <taskId>`); `agent-tools.md` §1 / `glossary.md` §6 (`Workspace` — биндинг из декларации состояния); `api-contracts.md` §2 `SessionDto` / §4.1 `TaskDto`.
- **Описание:** WS-протокол релея требует от клиента передать `binding` — логический ключ workspace. Ни `SessionDto`, ни `TaskDto`, ни какой-либо другой публичный ответ не содержат поля «binding / workspace тип состояния». CLI-команда `relay --task <taskId>` тоже не принимает binding. Следовательно, клиент не может узнать, какой `binding` регистрировать, и флагманский сценарий роуминга (M4, критерий «одна сессия, два офиса») нереализуем.
- **Предложение:** выставить биндинг в контракте (например, поле `workspace: { type, binding }` в `SessionDto`/`TaskDto` при CLIENT_EXEC-состоянии) либо добавить эндпоинт «какие CLIENT_EXEC-workspace ожидают исполнителя»; синхронизировать `client-cli.md` (`relay --task <taskId> [--binding]`).

---

## MAJOR

### M-1. Инструменты оркестратора отсутствуют в каталоге `agent-tools.md`
- **Место:** `workflow-domain.md` §6 (`create_workflow`, `edit_workflow`, `create_task`, `create_subtask`, `set_dependency`, `configure_trigger`); `agent-tools.md` §1–§3 (каталог инструментов).
- **Описание:** `agent-tools.md` объявлен каталогом инструментов агента, но содержит только workspace-инструменты, `transition`, `spawn_subagent`, `read_compacted`, `stop_subtree` и MCP. Шесть инструментов оркестратора из workflow-дока в каталог не попали: нет сигнатур, режима (sync), семантики, прав. Реализатор M3 не имеет контракта на них.
- **Предложение:** добавить в `agent-tools.md` раздел «Инструменты оркестратора» с сигнатурами/семантикой и явной привязкой к `TaskRegistry`/`WorkflowRegistry`.

### M-2. Резолв workspace не хранится и не описан; конфликт режимов `auto|explicit` vs `AUTO|PATH`
- **Место:** `glossary.md` §6 (`SERVER_DIR`: `auto` / `explicit`); `workflow-domain.md` §2 (`mode: AUTO | PATH`); `decisions.md` D-11 (`auto/explicit`); `execution-model.md` §7.2 («записать … workspace-резолв»); `data-model.md` §5 `session`; `glossary.md` §4 `Session` («workspace-резолв» — среди полей).
- **Описание:** (а) глоссарий и `Session` называют workspace-резолв полем сессии, `execution-model §7.2` требует его «записать», но колонки в `data-model` нет; (б) перечисление режимов `SERVER_DIR` расходится: `auto|explicit` (глоссарий/D-11) против `AUTO|PATH` (workflow-domain). Реализатор не знает ни где хранить резолв, ни как называть режим.
- **Предложение:** унифицировать имена (`AUTO`/`EXPLICIT`, SCREAMING_SNAKE как enum) и либо добавить колонку (например, `session.workspace_resolved jsonb`), либо явно объявить резолв эфемерным (пересчитывается из `state`+`task`+`session`) и убрать «поле»/«записать» из глоссария/execution-model.

### M-3. `WAIT_TASKS`: ALL_TERMINAL vs «чужой FAILED → ERROR» — противоречие
- **Место:** `workflow-domain.md` §3 (таблица, строка WAIT_TASKS: «условие выполнено → NEXT; чужой FAILED → ERROR»); §2 (`condition: ALL_TERMINAL | ALL_SUCCESS`); `glossary.md` §3 `Scope-выражения WAIT_TASKS` («все в терминале / все успешно»).
- **Описание:** при `ALL_TERMINAL` проваленный ребёнок тоже «в терминале» → условие выполнено → NEXT, но правило «чужой FAILED → ERROR» даёт ERROR. Приоритет не задан. Дополнительно не определено поведение для `TERMINAL/CANCELLED` ребёнка (обнуляет ли условие `ALL_SUCCESS`, ведёт ли к ERROR). Это ядро движка — неоднозначность даёт разную траекторию задач.
- **Предложение:** задать явную таблицу «condition × исходы детей → переход» и правило приоритета (например, любой FAILED → ERROR раньше проверки условия).

### M-4. `WAIT_WEBHOOK`: NEXT vs ERROR не выводимо
- **Место:** `workflow-domain.md` §3 (WAIT_WEBHOOK: «приём → NEXT (или ERROR по семантике workflow)»); §7; `glossary.md` §7.
- **Описание:** не определено, **чем** «семантика workflow» решает NEXT или ERROR при приёме вебхука: от payload? от HTTP-кода отправителя? от заголовка? `reason` хранит payload, но правило маппинга отсутствует. При этом §2 требует у WAIT_* только TIMEOUT-переход, т.е. ERROR-переход у вебхук-состояния вообще может быть не объявлен.
- **Предложение:** либо зафиксировать «приём = всегда NEXT, ветвление — следующим AGENT/BASH/условным состоянием», либо ввести декларацию (например, `webhook.errorBodyField`/`expectedField`) и согласовать с правилом валидации §2.4.

### M-5. Виды переходов из AGENT-состояния не определены
- **Место:** `workflow-domain.md` §3 (AGENT: «по разрешённым NEXT»); §2 (`kind: NEXT | ERROR | TIMEOUT | CANCEL`); `execution-model.md` §4 (`transition(target_state_code, reason)`).
- **Описание:** сигнатура инструмента не несёт `kind`, а таблица говорит только про «разрешённые NEXT». Что делать, если у AGENT-состояния объявлен ERROR- или CANCEL-переход: может ли агент его выбрать, как, и используется ли `kind` движком по обстоятельствам — не сказано. Инструмент `transition` и модель переходов рассинхронизированы.
- **Предложение:** либо добавить `kind` в сигнатуру `transition`, либо явно объявить нелегальными для AGENT любые kind, кроме NEXT/CANCEL, и описать, кто инициирует CANCEL.

### M-6. BASH_SCRIPT vs async `bash`: синхронность движка и `CLIENT_EXEC` не заданы
- **Место:** `execution-model.md` §7.3 («выполнить скрипт через WorkspaceTools … по коду выхода/таймауту — детерминированный переход»); §4 (bash async, окно ~30 с → `ASYNC_ACCEPTED`); `agent-tools.md` §1 (`bash` — **async-capable**); `workflow-domain.md` §3 (BASH_SCRIPT).
- **Описание:** движок BASH_SCRIPT заявлен как детерминированный (ждёт exit/таймаут и сразу переходит), но использует тот же `WorkspaceTools`, чей `bash` описан как async-capable. Что делать при превышении ~30 с: парковать состояние (тогда у него нет «сессии», в которую дописать поздний `TOOL_RESULT`) или ждать без окна — не определено. Также не задано поведение BASH_SCRIPT при биндинге `CLIENT_EXEC` и отсутствии клиента (ждать/ERROR — по глоссарию CLIENT_EXEC «ждёт/ошибка»).
- **Предложение:** разделить контракты: «engine-run script» (всегда синхронно, свой timeout, без ASYNC_ACCEPTED) vs «agent tool `bash`» (async-capable); описать поведение BASH_SCRIPT при CLIENT_EXEC.

### M-7. Обратный поиск «ждущих» задач WAIT_TASKS не поддержан схемой
- **Место:** `data-model.md` §4 (единственный индекс `task (parent_task_id, status_projection)`); `workflow-domain.md` §2 (`scope: BLOCKED_BY | TAGGED(x) | EXPLICIT(...)`); `execution-model.md` §1/§7.5–7.6 (wake по терминалу подзадач/разблокированных).
- **Описание:** на терминал задачи движок должен найти все задачи в `WAIT_TASKS`, чьё scope-условие затронуто. Для `ALL_CHILDREN` есть индекс. Для `BLOCKED_BY`, `TAGGED(x)`, `EXPLICIT(...)` обратного пути нет: `task_dependency` индексируется `(blocker, blocked)`, но не «кто ждёт»; тег-поиск не индексирован; явный список — в `params_jsonb` без индекса. На масштабе это либо фулл-скан, либо отсутствующая фича.
- **Предложение:** добавить индексы/таблицу ожиданий (например, `task_transition_history`-подобный индекс по `status_projection='WAITING'` + `current_state`, или материализованный `waiters`), либо явно задокументировать допустимость скана и его условия.

### M-8. `security §5` объявляет `idempotency_key` append-only, а `data-model` его чистит по TTL
- **Место:** `security-multitenancy.md` §5 («Append-only журналы: … `idempotency_key` (replay-след) … UPDATE/DELETE запрещены»); `data-model.md` §6 (`idempotency_key`: `expires_at` — «TTL 24 ч; чистка джобой»).
- **Описание:** прямое противоречие: security-док запрещает DELETE и называет таблицу append-only, схема же штатно удаляет записи по TTL. Аудит-инвариант в security недостижим; ссылка на §7 data-model тоже неверна для `idempotency_key`.
- **Предложение:** вывести `idempotency_key` из списка append-only журналов, описав его как «replay-хранилище с TTL», либо перевести аудит replay в отдельный неизменяемый журнал.

### M-9. Смешанные sync+async tool-calls в одном ответе модели не покрыты
- **Место:** `execution-model.md` §3 (условия №1 «только синхронные» и №2 «только async»); §4.
- **Описание:** «исчерпывающий список» не описывает ответ, где модель вызвала и sync-, и async-инструмент: синхронные надо выполнить, но ответ уже нельзя считать завершённым из-за pending async. Должен ли Turn парковаться после выполнения sync-части или уходить в новый раунд? Неоднозначность ломает заявленную исчерпывающую полноту условий.
- **Предложение:** добавить строку «mixed sync+async»: выполнить sync-результаты; если после них `pending_tool_calls > 0` — парковка (как №2), иначе — новый раунд.

### M-10. Критерий M2 требует AGENT-состояний и `transition`, отнесённых к M3
- **Место:** `roadmap.md` M2 (объём и критерий «двухфазное ревью с возвратом»); M3 («мета-инструменты (`transition` …)»); `workflow-domain.md` §3–§4.
- **Описание:** «двухфазное ревью с возвратом» подразумевает AGENT-состояния (ревьюеры) и возврат через `transition` с обоснованием. В M2 объёме AGENT-состояния и `transition` не упомянуты — они в M3. Либо M2-критерий невыполним, либо M2 скрыто включает M3-часть (фазирование не сходится).
- **Предложение:** перенести `transition`/AGENT-состояния в M2 (они нужны движку возвратов) либо заменить критерий M2 на чисто системные состояния (bash/webhook/WAIT_TASKS).

### M-11. Нет пути наполнения `agent`/`llm_model` (seed/admin) для M1/M3
- **Место:** `roadmap.md` M1 («`LlmGateway` (ChatModel из LlmModel)»), M3 («ревизии агентов + llm-профили»); `api-contracts.md` §1.5 («Управление ревизиями — вне v1 (только глоссарий/БД)») и §8 (управление ревизиями агентов вне v1).
- **Описание:** и API-управление, и MCP, и REST-эндпоинты для `agent`/`llm_credentials`/`llm_model` отсутствуют. Ни миграций-seed, ни CLI-команды, ни админ-эндпоинта не описано. Без них M1 не может собрать `ChatModel`, а M3 — сослаться на агента.
- **Предложение:** зафиксировать механизм первичного наполнения (seed-миграция / админ-эндпоинт / CLI), пусть и «вне v1» — но явно, с владельцем фазы.

### M-12. CLI: переиздание ticket для SSE-реконнекта не описано
- **Место:** `client-cli.md` §2 («Переподключение SSE: авто-ретрай с `?since=`»); `api-contracts.md` §1.4 (ticket — одноразовый, TTL 60 с), §3.1 (SSE требует `ticket=`), §0.10 (лимит билетов на пользователя).
- **Описание:** билет одноразовый и живёт 60 с, а attach-сессия долгоживущая с автопереподключением. CLI не описывает ни переиздание билета при реконнекте, ни обход лимита на пользователя — основной UX-режим «attach» нереализуем по букве контракта.
- **Предложение:** добавить в `client-cli.md` шаг «перед каждым коннектом/реконнектом — `POST /auth/ticket`»; при необходимости уточнить в api-contracts, что билет можно переиздавать в рамках лимита.

### M-13. Рассинхрон пространств имён выражений: `${params.*}` vs `${task.params.*}`
- **Место:** `glossary.md` §3 инвариант 3 (`${task.*}`, `${params.*}`); `glossary.md` §3 `Scope` (`EXPLICIT(${params.key})`); `workflow-domain.md` §1 (`${task.id}`, `${task.params.*}`, `${session.id}`); `data-model.md` §4 (`${params.*}`); `workflow-domain.md` §2 (`EXPLICIT(${params.key})`).
- **Описание:** три разных формы для одного и того же: `${params.*}`, `${task.params.*}`, и «поля задачи» `${task.*}`. Глоссарий объявляет шаблон содержащим `${task.*}` и `${params.*}` — это несовместимо с формой из workflow-domain (§1). Резолвер получит неоднозначную грамматику.
- **Предложение:** один канонический префикс (например, `${task.*}`, `${session.*}`, `${params.*}`) и правка глоссария/workflow-domain под него.

---

## MINOR

### m-1. Псевдокод Turn расходится с «исчерпывающим списком» условий
- **Место:** `execution-model.md` §2 (псевдокод) vs §3 (таблица).
- **Описание:** в псевдокоде нет ветки парковки async (№2), хотя §3 объявляет его исчерпывающим. Псевдокод — то, что читает реализатор.
- **Предложение:** добавить в псевдокод обработку `pending>0 && модель закончила → парковка`.

### m-2. «статус CANCELLED/FAILED» — чей статус?
- **Место:** `execution-model.md` §2 и §3 (№5, №6); `api-contracts.md` §2 `SessionDto.runtimeStatus/lastTurnOutcome`; `data-model.md` §5 `session`.
- **Описание:** неясно, статус сессии, Turn'а или задачи устанавливается в CANCELLED/FAILED. `runtimeStatus` не имеет CANCELLED, есть `lastTurnOutcome`, но его источник в схеме отсутствует (в api-review отмечено как N-11). Ощутимая неоднозначность для SSE-статусов.
- **Предложение:** назвать сущность явно (`lastTurnOutcome`) и завести её источник в `data-model` (колонка `session.last_turn_outcome`).

### m-3. Неверная ссылка на ADR в `data-model.md`
- **Место:** `data-model.md` §6 («история попыток обработки — не хранится (D-04)»).
- **Описание:** D-04 — про отсутствие таблицы Turn/Run, а не про вебхуки. Ссылка вводит в заблуждение (уместнее D-05/D-26).
- **Предложение:** заменить ссылку на D-05/D-26.

### m-4. `task_comment`: «агент-пометка в payload», а колонки `payload` нет
- **Место:** `data-model.md` §4 `task_comment`.
- **Описание:** примечание ссылается на `payload`, в таблице поле — `body`.
- **Предложение:** привести к `body` (или ввести `payload_jsonb`).

### m-5. Состав `WorkspaceTools` в глоссарии у́же каталога
- **Место:** `glossary.md` §6 (`read/write/edit/bash`); `agent-tools.md` §1 (`read_file/write_file/edit_file/bash/glob/grep`).
- **Описание:** глоссарий перечисляет 4 инструмента, каталог — 6. Термин-«единый словарь» отстал.
- **Предложение:** обновить перечень в глоссарии.

### m-6. «Поле-список» сессий состояния vs partial unique
- **Место:** `glossary.md` §3 инв. 2 и `workflow-domain.md` §4 («поле-список … валидация length==1»); `data-model.md` §5 (partial unique `(task_id, state_code) WHERE kind='STATE'`).
- **Описание:** дверь к multi-instance описана как «поле-список», в схеме это ограничение уникальности (списка нет). Формулировки расходятся.
- **Предложение:** либо формулировать как «инвариант единственности (partial unique)», либо ввести явное поле.

### m-7. Опечатка «без-JSX контур»
- **Место:** `security-multitenancy.md` §1 (строка «Вебхуки»).
- **Описание:** очевидно, имелся в виду «без-JWT».
- **Предложение:** исправить.

### m-8. Опечатка в заголовке «Невходит в CLI v1»
- **Место:** `client-cli.md` §3.
- **Предложение:** «Не входит».

### m-9. Опечатка «идентемпонтентен»
- **Место:** `execution-model.md` §8.
- **Предложение:** «идемпотентен».

### m-10. CLI не упоминает обязательный `Idempotency-Key`; строки `attach` конфликтуют
- **Место:** `client-cli.md` §1/§2; `api-contracts.md` §0.3 (`Idempotency-Key` обязателен на `POST .../messages`).
- **Описание:** CLI-ввод во время хода описан как «обычный POST /messages», но ключ обязателен; две отдельные строки `attach <sessionId>` и `attach <sessionId> --inject` дублируют один режим.
- **Предложение:** указать генерацию ключа; объединить `--inject` в описание интерактивного режима.

### m-11. «Идемпотентность результата по callId» vs два результата на callId
- **Место:** `agent-tools.md` §1 (общие правила), §5; `glossary.md` §4 (`ASYNC_ACCEPTED` → поздний второй `TOOL_RESULT` с тем же callId).
- **Описание:** при async на один callId приходится плейсхолдер и финальный результат (плюс синтетические LOST/CANCELLED). «Идемпотентность результата» читается как «ровно один результат».
- **Предложение:** уточнить: идемпотентность повторной доставки, но допускается пара placeholder→final.

### m-12. `stop_subtree` в разделе «мета-инструменты агента»
- **Место:** `agent-tools.md` §2 (строка `stop_subtree`, «недоступен агенту»).
- **Описание:** в каталоге мета-инструментов агента присутствует инструмент, агенту недоступный. Путает назначение раздела.
- **Предложение:** вынести в примечание «не инструменты агента» или удалить из таблицы.

### m-13. `spawn_subagent`: ревизия/владелец дочернего агента не определены
- **Место:** `agent-tools.md` §2 (`spawn_subagent(agent_key, …)`); `glossary.md` §2 («ссылки на агента хранят конкретную ревизию»).
- **Описание:** по `agent_key` без `rev` — какая ревизия дочернего агента пинится (последняя?) и кто владелец дочерней сессии — не сказано.
- **Предложение:** добавить опциональный `agent_rev?` и правило владельца (см. C-2).

### m-14. Граница M2/M3 по вебхук-эндпоинтам неоднозначна
- **Место:** `roadmap.md` M2 («WAIT_WEBHOOK (stateless)») vs M3 («триггеры + вебхук-эндпоинты»).
- **Описание:** неясно, попадает ли `POST /api/webhooks/tasks/...` в M2 (нужен для проверки WAIT_WEBHOOK) или целиком в M3.
- **Предложение:** явно указать, какие вебхук-эндпоинты входят в какую фазу.

### m-15. `idempotency_key.scope` для анонимных вебхуков не определён
- **Место:** `data-model.md` §6 (`scope` = «путь эндпоинта + субъект»); `api-contracts.md` §0.3 (ключ на вебхуках опционален).
- **Описание:** у анонимного вебхука субъекта нет; как строится `scope`, не сказано — при этом от него зависит PK `(scope,key)` и replay.
- **Предложение:** задать scope для без-JWT контуров (например, «путь + entityId»).

### m-16. Курсор `task.transition` (UUIDv7-id) не обслуживается индексом
- **Место:** `api-contracts.md` §3.2 («SSE `id:` = id записи истории (UUIDv7, монотонный)», он же `?since=`), §4.1 `GET /history?since=`; `data-model.md` §4 (`INDEX (task_id, created_at)`).
- **Описание:** пагинация по `since=<id>` требует сравнения/сортировки по `id`, а индекс — по `created_at`. Плюс UUIDv7 не строго монотонен в пределах миллисекунды.
- **Предложение:** заменить индекс на `(task_id, id)` либо явно объявить курсором `created_at`/`seq`.

### m-17. Форматирование таблицы `decisions.md`
- **Место:** `decisions.md`, строка D-24 (`| D-24 | 2026-09-16 |Wake-очередь …`).
- **Описание:** пропущен пробел после разделителя — ячейка слипается, рендер таблицы ломается.
- **Предложение:** косметическая правка.

### m-18. Критерий M1 опирается на attach-CLI, отнесённый к M4
- **Место:** `roadmap.md` M1 (критерий: «FREE-сессия через attach-CLI-минимум»); M4 («attach-CLI полный»); `client-cli.md`.
- **Описание:** CLI как документ и объём — в M4, но M1-приёмка без него не проверяется. Либо M1 неявно включает мини-клиент.
- **Предложение:** явно указать «CLI-минимум для приёмки входит в M1».

### m-19. «Проверка без БД» для задач-вебхуков читается буквально
- **Место:** `glossary.md` §7, `workflow-domain.md` §7, `data-model.md` §6 («для задач без БД»; «проверка — чистая функция»); `api-contracts.md` §4.4 (нужны `409` вне WAIT_WEBHOOK и переход).
- **Описание:** без БД проверяется только HMAC, но сам переход/`409`/запись `reason` требуют чтения задачи и ревизии. Формулировка провоцирует неверную реализацию.
- **Предложение:** уточнить «HMAC проверяется без БД; переход — обычная работа с задачей».

---

## Сводка по измерениям

| Измерение | Итог |
|---|---|
| Междокументная согласованность (термины/схема/права/инструменты/команды/фазы) | Матрица прав `security §2` == `api §7` — совпадает. Термины в целом выдержаны, но: workspace mode (M-2), `${params}` (M-13), `WorkspaceTools` (m-5), «поле-список» (m-6). Схема не поддерживает `session.title` (C-1), владение агентских сущностей (C-2), reverse-lookup WAIT_TASKS (M-7). Каталог инструментов неполон (M-1). Команды CLI существуют в api, кроме `binding` (C-3). Фазы roadmap расходятся с M2/M3 (M-10) и M1/M4 (m-18). |
| Внутренние дефекты новых доков | security (M-8, m-7), agent-tools (M-1, m-11, m-12, m-13), client-cli (M-12, m-8, m-10), roadmap (M-10, M-11, m-14, m-18), workflow-domain (M-3, M-4, M-5). |
| Исполнимость/полнота | C-2, C-3, M-2, M-6, M-7, M-9, M-11. |
| Чистота (TBD/опечатки/рассинхрон) | m-3, m-4, m-7, m-8, m-9, m-17. Заглушек-TBD в тексте не найдено; рассинхрон формулировок — M-2, M-6, m-1, m-2, m-6. |

**Топ-3 (по влиянию):** C-3 (релей `binding` — блокирует флагманский сценарий M4 и уже заложенный CLIENT_EXEC), C-1 (`session.title` — контракт на несуществующем поле), C-2 (владение сущностей, созданных оркестратором, — блокирует M3 и всю матрицу прав).

---

## Cross-check (кросс-ревью отчётов коллег)

> Объект вердиктов: CRITICAL/MAJOR пункты `design-review-glm.md` (0 CRITICAL / 8 MAJOR) и `design-review-minimax.md` (3 CRITICAL / 7 MAJOR). Проверено против **текущего** состояния доков, включая D-30 (per-session Docker, `ContainerWorkspaceTools`) и D-31 (`IdGenerator`, UUID v7).
> Важное наблюдение: минимум 4 пункта minimax (CR-1, часть CR-2, MA-3, часть MA-7/CR-3) процитированы из **v1** `api-contracts.md` (до трёхстороннего ревью): «webhookUrl у TASK-VIEW», «только `?since=`, по id нельзя», «runtimeStatus без CANCELLED». В текущем v2 это уже не так. Ни один пункт коллег не закрыт D-30/D-31 — они лишь добавили контекст.
> Вердикты: `agree` | `already-fixed` | `disagree` | `DISPUTE`.

| ID | Ревьюер | Severity | Вердикт | Комментарий (текущее состояние) |
|---|---|---|---|---|
| GLM M-1 | GLM | MAJOR | **agree** | Workspace FREE-сессии по-прежнему определён только «по декларации состояния», а флагманский v1-кейс `workspace/files` — FREE (§8); data-model §5 без workspace-полей. D-30 (per-session контейнер, lifecycle = сессия) лишь усиливает намёк, но default-путь/источник резолва для FREE не задан. Совпадает с моим M-2. |
| GLM M-2 | GLM | MAJOR | **agree** | `agent-tools.md` без изменений: инструментов оркестратора (`create_workflow`/`create_task`/`set_dependency`/`configure_trigger`…) в каталоге нет; эффективная идентичность агентских вызовов к `AccessPolicy` не определена ни в security, ни в agent-tools. Совпадает с моими M-1 и C-2. |
| GLM M-3 | GLM | MAJOR | **agree** | `api-contracts §5` по-прежнему `tool: BASH\|READ\|WRITE\|EDIT`, а `agent-tools §1` — 6 инструментов (`glob`/`grep`). Асимметрия CLIENT_EXEC сохраняется. Пост-D-30 добавлен новый нюанс: glossary §6 теперь `read/write/edit/bash/**find**/glob` — то есть `find`, а не реальный `grep` из каталога. |
| GLM M-4 | GLM | MAJOR | **agree** | `session.title`, `agent.name/description`, `session.last_turn_outcome` в data-model §2/§5 отсутствуют; `SessionDto`/`GET /agents` их требуют. Совпадает с моим C-1 (title). D-30/D-31 не затрагивают. |
| GLM M-5 | GLM | MAJOR | **agree** | Критерий M1 требует attach-CLI-минимум, CLI как deliverable — в M4. Совпадает с моим m-18. |
| GLM M-6 | GLM | MAJOR | **agree** | `transition` в M3, а критерий M2 («двухфазное ревью с возвратом») требует AGENT-переходов; REST-перехода нет. Совпадает с моим M-10. |
| GLM M-7 | GLM | MAJOR | **agree** | CANCEL-переход есть в enum, `status_projection=CANCELLED` — в схеме, но инициатора нет: `stop` = suspend + гашение Turn'ов, состояние не меняется → CANCELLED недостижим (мёртвое значение / дыра жизненного цикла). |
| GLM M-8 | GLM | MAJOR | **agree** | Bootstrap `llm_credentials`/`llm_model`/ревизий агентов не описан (API вне v1, CLI вне, seed-практики нет); формат ключа шифрования не раскрыт. Совпадает с моим M-11. D-31 (`IdGenerator`) не про это. |
| MM CR-1 | minimax | CRITICAL | **already-fixed** | Закрыто в `api-contracts` v2: §4.4 «webhookUrl выдаётся … TASK-PARTICIPATE/владельцу», §7 «TaskDto **без** `params` и `webhookUrl`» для VIEW; `security §2` совпадает. Пункт построен на v1-тексте. |
| MM CR-2 | minimax | CRITICAL | **disagree** | Утверждение «роль `harness-admin` нигде не определена» неверно: `security §2` определяет её как **Keycloak realm-role** с назначением «ревизии workflow»; то же в `api §4.2/§7`, D-28. Резидуал — отсутствие статьи в глоссарии (это MINOR-класс, ср. GLM m-11), не CRITICAL. Severity завышена. |
| MM CR-3 | minimax | CRITICAL | **disagree** | Фактическая посылка неверна: TTL 60 с — у **неиспользованного** билета (capability на handshake), а не у длительности SSE-соединения; ping 15 с к истечению билета отношения не имеет. Реальный остаток — переиздание билета при реконнекте (уже зафиксирован как мой M-12), это не CRITICAL. |
| MM MA-1 | minimax | MAJOR | **disagree** | Fan-out — статическое свойство графа, валидируется при сохранении ревизии (`workflow-domain §2` правила валидации; `api §4.2` → `422 graph-invalid`). БД-индекс `(workflow_revision_id, current_state)` для fan-out неприменим; «два состояния переходят в один target» — это не fan-out, а легальная сходимость рёбер. |
| MM MA-2 | minimax | MAJOR | **disagree** | Инвариант «одна сессия на пару (задача, состояние)» уже закодирован: `data-model §5` PARTIAL UNIQUE `(task_id, state_code) WHERE kind='STATE'` + `glossary §3` инв. 2. Отдельное `409`-правило не требуется — уникальность держит БД. |
| MM MA-3 | minimax | MAJOR | **disagree** | `api-contracts §2` содержит `GET /api/v1/sessions/{id}/messages/{messageId}` (скрытые — только владелец). Утверждение «по конкретному ID невозможно» противоречит текущему тексту спеки. |
| MM MA-4 | minimax | MAJOR | **disagree** | `execution-model §7` перечисляет все пять типов состояний (пп. 1–6: AGENT, BASH_SCRIPT, WAIT_WEBHOOK, WAIT_TASKS, TERMINAL), а `workflow-domain §3` даёт таблицу семантики по типам. «Только два вида поведения» — неверное чтение. |
| MM MA-5 | minimax | MAJOR | **disagree** | `current_state` меняется только движком и **транзакционно** с пересчётом `status_projection` (`data-model §7.2`, `glossary §3` инв. 4). При коммите одной транзакции расхождение невозможно; сценарий «current_state изменился, а проекция нет» противоречит зафиксированному механизму. |
| MM MA-6 | minimax | MAJOR | **agree** | `binding` не определён в глоссарии и, что важнее, негде взять клиенту (нет в `SessionDto`/`TaskDto`, `relay --task` его не принимает) — тождественно моему C-3. Находка валидна; severity у меня выше (CRITICAL), т.к. блокирует M4. |
| MM MA-7 | minimax | MAJOR | **disagree** | Дубликат CR-3 (тот же текст про TTL/ping) — вердикт тот же. |

### Итоги по вердиктам

| Вердикт | GLM | minimax | Всего |
|---|---|---|---|
| agree | 8 | 1 | **9** |
| already-fixed | 0 | 1 | **1** |
| disagree | 0 | 8 | **8** |
| DISPUTE | 0 | 0 | **0** |
| **Всего (с дублем MM MA-7=CR-3)** | 8 | 10 | **18** |

Уникальных находок: 17 (MM CR-3 и MA-7 — один пункт).

### DISPUTE-пункты

**Нет.** Два пограничных пункта разрешены без судьи: MM CR-2 (`harness-admin` определена в `security §2`; остаток — статья в глоссарии, MINOR) и MM CR-3/MA-7 (TTL относится к погашению билета, а не к длительности стрима). Оба — `disagree` с зафиксированным резидуалом в мой бэклог (M-12).

### Выводы кросс-ревью

- **Полное согласие по GLM (8/8 agree):** замечания GLM точны и по текущим докам не закрыты. Приоритет для правок: GLM M-1 (workspace FREE), M-6 (переходы в M2), M-3 (релей glob/grep) — совпадают с моими топ-находками.
- **minimax: 6 из 10 — против устаревшего v1-текста api-contracts** (CR-1 — already-fixed; CR-3/MA-7, MA-3 — фактически неверны; CR-2, MA-4 — severity/чтение завышены). Валидны MA-6 (дубль C-3) — согласен.
- **Ни D-30, ни D-31 не закрыли ни одного пункта коллег;** D-30 добавил лишь контекст изоляции исполнения и новый рассинхрон перечня инструментов (`find` vs `grep`, см. GLM M-3).
- Сводная приоритизация к судье: мои C-1/C-2/C-3 + GLM M-1/M-3/M-6/M-7 — блокеры M2/M4; остальное — один дизайн-коммит.

---

## Round 2 (verify)

> Проверка моих Round-1 CRITICAL/MAJOR против **текущего** состояния доков (после консенсус-фиксов + D-30/D-31). Статус: `fixed` (указано, где) / `not-addressed`. Дополнительно — контроль, что фиксы не внесли новых противоречий.

### Статус Round-1 находок

| ID | Sev | Статус | Где закрыто / комментарий |
|---|---|---|---|
| C-1 `session.title` | CRITICAL | **fixed** | `data-model §5 session.title text NULL`; `glossary §4` Session — поле `title`; контракт SessionDto/`?q=` поддержан. |
| C-2 owner, созданного агентом | CRITICAL | **fixed** | `glossary §4` Session «Наследование владельца» (подзадачи/субагентские сессии/триггеры, транзитивно до человека); `data-model §4/§5` (owner наследуется от породившей сессии); `agent-tools §2b` + `spawn_subagent` (owner дочерней = owner родителя). |
| C-3 релей `binding` не выставлен | CRITICAL | **fixed** | `api §2` SessionDto: `workspaceBinding { type, pathTemplate?, logicalKey? … }`; `client-cli §1`: `relay --task <taskId> [--binding <logicalKey>]` + листинг биндингов без `--binding`. Резидуал — см. N-3. |
| M-1 инструменты оркестратора в каталоге | MAJOR | **fixed** | `agent-tools §2b` «Инструменты оркестратора»: сигнатуры, `metaTools`, owner-наследование, валидация графов/циклов. |
| M-2 workspace + режимы SERVER_DIR | MAJOR | **fixed (частично)** | FREE определён: `glossary §4` Session «FREE — всегда `SERVER_DIR auto` `workspaces/sessions/{sessionId}`; STATE — из декларации»; `SessionDto.workspaceBinding`. **Резидуал:** `workflow-domain §2` по-прежнему `mode: AUTO \| PATH` vs `glossary/D-11` `auto/explicit`; `execution-model §7.2` «записать workspace-резолв» vs отсутствие колонки в `data-model` (не сказано «вычисляется, не хранится»). |
| M-3 WAIT_TASKS: ALL_TERMINAL vs FAILED | MAJOR | **fixed** | `workflow-domain §3`: `ALL_TERMINAL` — ждать терминалов всех; `ALL_SUCCESS` — первый FAILED немедленно ведёт по ERROR. |
| M-4 WAIT_WEBHOOK NEXT/ERROR | MAJOR | **fixed** | `workflow-domain §3`: приём валидного payload → NEXT; payload не прошёл схему состояния (если декларирована) → ERROR. |
| M-5 виды переходов из AGENT | MAJOR | **not-addressed** | `workflow-domain §3` по-прежнему «по разрешённым NEXT»; `agent-tools` `transition(target_state_code, reason)` без `kind`; могут ли AGENT-состояния выбирать ERROR/иные kind — не определено. |
| M-6 BASH_SCRIPT vs async-bash | MAJOR | **fixed** | `workflow-domain §3`: явное отличие — детерминированный синхронный системный контур, таймаут состояния → TIMEOUT, без LLM. Резидуал — см. N-2 (контейнер для BASH_SCRIPT). |
| M-7 обратный поиск WAIT_TASKS | MAJOR | **fixed** | `data-model §4`: `INDEX (blocked_task_id)`, `INDEX (tags) GIN`; `workflow-domain §3`: поиск «кто ждёт» по этим индексам. |
| M-8 `idempotency_key` как append-only журнал | MAJOR | **fixed** | `security §5`: `idempotency_key` — «не журнал, а служебное хранилище с TTL-чисткой (24 ч)»; append-only оставлены `session_message`, `task_transition_history`. |
| M-9 смешанные sync+async вызовы | MAJOR | **not-addressed** | `execution-model §3` по-прежнему только №1 (sync) и №2 (только async); комбинация в одном ответе модели не описана. |
| M-10 `transition` перенесён в M2 | MAJOR | **fixed** | `roadmap M2`: «минимальный мета-инструмент `transition` (обязательный reason)»; критерий M2 переформулирован (агент переводит задачу `transition`); M3 без `transition`. |
| M-11 bootstrap LLM/агентов | MAJOR | **fixed** | `roadmap M1`: «bootstrap-сид (первые llm_credentials/agent — админ-команда CLI из env)»; `architecture §4`: Bootstrap-абзац. |
| M-12 переиздание билета CLI | MAJOR | **fixed** | `client-cli §2`: «билет одноразовый на соединение — при реконнекте CLI прозрачно берёт новый (`POST /auth/ticket`)». |
| M-13 пространства имён выражений | MAJOR | **not-addressed** | `workflow-domain §1` `${task.params.*}` vs `glossary §3` `${task.*}`/`${params.*}` vs `data-model §4` `${params.*}`; `EXPLICIT(${params.key})` в обоих. Канон не зафиксирован. |

**Итог:** CRITICAL — **3/3 fixed**. MAJOR — **10 fixed**, **3 not-addressed** (M-5, M-9, M-13). Все CRITICAL закрыты; неадресованные MAJOR — не блокеры M1, но касаются семантики движка (M2).

### Новые противоречия, внесённые фиксами

#### N-1 (MAJOR, новое). «Виртуальный терминал CANCELLED» нарушает инвариант `current_state ∈ codes ревизии`
- **Место:** `workflow-domain §3` («Принудительная отмена»: `status_projection = CANCELLED` виртуальным терминалом, `to_state = CANCELLED`) vs `data-model §7.2` («`current_state ∈ codes` своей `workflow_revision`») и `glossary §3` инв. 4 («`status_projection` — проекция **текущего состояния**, обновляется транзакционно вместе с `current_state`»).
- **Описание:** `CANCELLED` отсутствует в `states[]` ревизии, но фикс предписывает писать его в `to_state` перехода. Если `current_state` тоже становится `CANCELLED` — прямое нарушение `data-model §7.2`; если остаётся прежним — `status_projection` перестаёт быть проекцией `current_state`, что ломает инв. 4 и запросы по `status_projection` (плюс остаётся задача в нетерминальном состоянии с терминальной проекцией). Две взаимоисключающие трактовки — интеграционные тесты M2 неоднозначны.
- **Предложение:** ввести зарезервированный псевдо-код (например, `$CANCELLED`) и явно исключить его из проверки «∈ codes ревизии», либо записывать `kind=CANCEL` с `to_state` = ближайший достижимый TERMINAL, а `CANCELLED` хранить только в `status_projection`; отразить в `data-model §7.2`/`workflow-domain §2`.

#### N-2 (MAJOR, новое). D-30 «per-session контейнер» не покрывает `BASH_SCRIPT` (у состояния нет сессии)
- **Место:** `decisions.md D-30` + `execution-model §4` («Серверное исполнение — в per-session Docker-контейнере … имя `harness-<sessionId>`, lifecycle = lifecycle сессии») vs `workflow-domain §3` (`BASH_SCRIPT — Сессия? **нет**`) и `execution-model §7.3` (скрипт через `WorkspaceTools`).
- **Описание:** системные состояния (BASH_SCRIPT) исполняются через `WorkspaceTools`, но сессии у них нет, а значит нет ключа `sessionId` для per-session контейнера и нет «lifecycle сессии». Чей контейнер поднимает bash-состояние (task-scoped? общий системный?), как он называется и когда удаляется — не определено. До D-30 вопрос не стоял (хостовый `ServerWorkspaceTools`), сейчас — дыра в исполнительной модели.
- **Предложение:** задать контейнер для системных состояний (например, `harness-task-<taskId>` с lifecycle состояния) и отразить в `execution-model §4/§7.3` + D-30-уточнении.

#### N-3 (MINOR, новое). `client-cli` ссылается на `workspaceBinding` у `TaskDto`, которого там нет
- **Место:** `client-cli §1` (`relay --task`: «`workspaceBinding.logicalKey` из SessionDto/**TaskDto**») vs `api §4.1` **TaskDto** (поля `workspaceBinding` нет; добавлено только в `SessionDto`).
- **Описание:** фикс C-3 выставил `workspaceBinding` лишь в `SessionDto`; CLI обещает взять ключ и из `TaskDto`. Плюс `logicalKey` определён только «для CLIENT_EXEC» (у FREE-сессии его нет, хотя команда берёт и её).
- **Предложение:** либо добавить `workspaceBinding` в `TaskDto`, либо убрать упоминание `TaskDto` в CLI и оговорить, что у FREE логического ключа нет.

#### N-4 (MINOR, новое/остаточное). `WAIT_TASKS`: не задан исход CANCELLED-ребёнка
- **Место:** `workflow-domain §3` (WAIT_TASKS: `ALL_TERMINAL`/`ALL_SUCCESS`, «первый FAILED → ERROR»); `workflow-domain §3` (принудительная отмена → `status_projection=CANCELLED`).
- **Описание:** для `ALL_TERMINAL` отменённый ребёнок тоже «терминал» → NEXT; для `ALL_SUCCESS` отмена (не FAILED) не описана. Возможен проскок барьера по отменённой подзадаче.
- **Предложение:** добавить строку «CANCELLED-ребёнок: ALL_SUCCESS → ERROR; ALL_TERMINAL → NEXT» (или иное явно).

#### N-5 (MINOR, косметика/рассинхрон после унификации перечня)
- **Место:** `agent-tools §1` проза «Серверные read/write/bash/**find**/glob» vs таблица `read_file/write_file/edit_file/bash/glob/**grep**`; `glossary §6` — канонический список с `grep` (унификация GLM M-3/m-5 выполнена в таблицах).
- **Описание:** остаточное `find` в прозе agent-tools (и в описании helper-образа — там уместно как утилита ОС).
- **Предложение:** в прозе agent-tools §1 заменить `find` на `grep` для консистентности с каноническим перечнем.

### Round 2 — вердикт

**approve-with-comments.** Все 3 CRITICAL и 10 из 13 MAJOR закрыты адресно и непротиворечиво; неадресованные M-5/M-9/M-13 и новые N-1..N-5 не блокируют старт M1. Перед M2 обязательны: **N-1** (инвариант `current_state` vs виртуальный CANCELLED) и **N-2** (контейнер для BASH_SCRIPT); M-5/M-9/M-13 и N-3/N-4 — в тот же дизайн-коммит.

---

## Cross-check Mercury

> Объект: `design-review-mercury.md` (0 CRITICAL / 4 MAJOR / 5 MINOR). Mercury подключился после раундов правок и его находки не проходили перекрёстную проверку. Ниже — независимая проверка каждого пункта против **текущего** состояния доков. Вердикты: `agree` | `already-fixed` | `disagree`.

| ID | Sev | Вердикт | Комментарий (где закрыто / почему валидно) |
|---|---|---|---|
| Mercury M-1 (crash контейнера mid-exec) | MAJOR | **already-fixed** | `execution-model §1` (Отказы контейнера): смерть контейнера во время async → синтетический `TOOL_RESULT LOST`; скан №3 — удаление осиротевших helper-контейнеров. Совпадает с предложением Mercury. |
| Mercury M-2 (валидация циклов task_dependency) | MAJOR | **already-fixed** | `data-model §4 task_dependency`: «циклы (включая транзитивные) запрещены валидацией (обход в глубину при установке ребра, `422 dependency-invalid`)»; `api §4.1` `POST /tasks/{id}/dependencies` → `422` (self/цикл). |
| Mercury M-3 (retention `task_transition_history`) | MAJOR | **already-fixed** | `security §5`: «`task_transition_history` … **хранится бессрочно**, retention для архивных задач — отдельным решением при появлении объёмов». Явно зафиксирован «без limit». |
| Mercury M-4 (image-pull retry/backoff) | MAJOR | **already-fixed** | `execution-model §1`: образ обязан присутствовать локально; pull — только retry/backoff на случай обновления, недоступность registry не блокирует сессии; `architecture §4`: helper-образ на VM, pull — только backoff-обновление. |
| Mercury m-1 (формат ULID) | MINOR | **agree** | Не закрыто: `glossary §4`/`data-model §5` — по-прежнему «короткий ULID» без формата (длина/алфавит/монотонность), `D-31` не объясняет выбор ULID вместо UUIDv7. Валидно (то же отмечал minimax MI-2). |
| Mercury m-2 (индекс BLOCKED_BY) | MINOR | **already-fixed** | `data-model §4`: `INDEX (blocked_task_id)` — обратный поиск «кто ждёт эту задачу»; плюс GIN `(tags)`. |
| Mercury m-3 (mount failure тома) | MINOR | **already-fixed** | `execution-model §1`: отказ монтирования workspace-тома → инструмент отвечает `ERROR` с причиной → задача идёт по ERROR-пути состояния. Механизм иной, чем предлагал Mercury (`LOST`), но сценарий покрыт. |
| Mercury m-4 (retention ревизий `agent`) | MINOR | **agree** | Не закрыто: политики удаления/архивации старых ревизий `agent` нет ни в `data-model §2`, ни в `D-20`. Валидно (ср. мой m-4-класс для иммутабельных ревизий). |
| Mercury m-5 (лимиты контейнера) | MINOR | **already-fixed** | `execution-model §4`: `cpus=2`, `memory=2g`, `pids-limit=512`, сеть — только где нужна (git-клон). **Остаток:** disk quota не задан (Mercury предлагал 10G) — незначительный резидуал. |

### Итоги Cross-check Mercury

| Вердикт | Кол-во |
|---|---|
| already-fixed | 7 |
| agree | 2 |
| disagree | 0 |
| **Всего** | **9** |

### Замечания к выводам Mercury

- **Согласие по всем MAJOR (4/4 already-fixed)** и по 3 из 5 MINOR — находки точны и закрыты правками; расхождений нет.
- **Валидны как не-закрытые:** m-1 (формат ULID) и m-4 (retention ревизий агента) — оба MINOR-класса, реально отсутствуют в доках.
- **Оспариваю вывод Mercury «D-30-контуры: новых проблем не внесено; все container-level сценарии покрыты»:** именно контейнерный контур D-30 оставил дыру, зафиксированную мной как **N-2 (MAJOR)** — у `BASH_SCRIPT` нет сессии (workflow-domain §3), поэтому «per-session контейнер / lifecycle = сессия» (D-30, execution-model §4) не определяет, какой контейнер поднимает скрипт системного состояния. Остальные container-сценарии (crash/pull/mount/limits) действительно закрыты — см. M-1/M-4/m-3/m-5.
- Общий вердикт Mercury (**approve**) считаю завышенным: см. Round 2 — сохраняются новые MAJOR **N-1** (виртуальный CANCELLED vs `current_state ∈ codes ревизии`) и **N-2**, поэтому мой вердикт — **approve-with-comments**.
