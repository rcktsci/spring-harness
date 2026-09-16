# Ревью дизайн-корпуса spring-harness (сквозное)

> Ревьюер: GLM (субагент). Дата: 2026-09-16.
> Объект: все конечные документы `docs/glossary.md`, `docs/design/*.md` (11 шт.).
> База сравнения: глоссарий как словарь; `api-contracts.md` (3×approve) — источник истины по контрактам; `docs/temp/review/api-review-{glm,deepseek,minimax}.md` — контекст прошлого цикла.
> Внутренние дефекты api-contracts не ищутся; проверены стыки с ним новых доков (security, agent-tools, client-cli, roadmap).
> Измерения: (1) междокументная согласованность; (2) внутренние дефекты новых доков; (3) исполнимость/полнота; (4) чистота.

## Сводка

| Severity | Кол-во |
|---|---|
| CRITICAL | 0 |
| MAJOR | 8 |
| MINOR | 11 |
| **Итого** | **19** |

Топ-3: **M-1** (workspace FREE-сессий не определён — под ним стоит флагманский сценарий v1 «скачать артефакт»), **M-6** (критерий готовности M2 недостижим в заявленном объёме фазы), **M-2** (инструменты оркестратора отсутствуют в каталоге agent-tools + не определён субъект прав).

---

## MAJOR

### M-1. Workspace FREE-сессии не определён — при этом на нём стоит флагманская функция v1
- **Severity:** MAJOR
- **Место:** `glossary.md` §6 (Workspace: «каталог исполнения по декларации **состояния** workflow»), §4 (Session: поле «workspace-резолв» без определения); `execution-model.md` §7.2; vs `api-contracts.md` §2 (`GET /sessions/{id}/workspace/files`, VIEW), §8 (в v1 — **только FREE**-сессии), `decisions.md` D-19 («WebUI — "скачать артефакт"»), `roadmap.md` M4.
- **Описание:** Workspace определён **только** через декларацию состояния workflow. У FREE-сессии состояния нет — источник workspace не определён ни одним документом. Но единственный покрываемый в v1 случай `workspace/files` — именно FREE (§8 явно выводит задачные состояния из v1), и «скачать артефакт» — ключевой сценарий WebUI (D-19) и критерий M4. Релей CLIENT_EXEC тоже не помогает: регистрация ключуется `taskId`, т.е. не покрывает FREE. Глоссарный пример `workspaces/sessions/{sessionId}/` (SERVER_DIR auto) — единственный намёк, но он про декларацию состояния. Дополнительно: в `data-model.md` §5 у `session` нет вообще никаких workspace-полей, хотя глоссарий обещает «workspace-резолв».
- **Предложение:** Принять и зафиксировать правило для FREE-сессий: например, «FREE-сессия получает авто-workspace `workspaces/sessions/{sessionId}/` (SERVER_DIR, AUTO) при первом обращении» — в глоссарий §6 + строку в execution-model; решить, доступны ли FREE-сессиям CLIENT_EXEC-биндинги (сейчас — нет по контракту релея). Отразить источник резолва в data-model (либо явно: «вычисляется, не хранится»).

### M-2. Инструменты оркестратора отсутствуют в каталоге agent-tools; не определён субъект их прав
- **Severity:** MAJOR
- **Место:** `agent-tools.md` (весь каталог: §1 workspace, §2 мета, §3 MCP) vs `workflow-domain.md` §6, `glossary.md` §3 (Orchestrator).
- **Описание:** workflow-domain §6 и глоссарий определяют инструменты оркестратора: `create_workflow`, `edit_workflow`, `create_task`, `create_subtask`, `set_dependency`, `configure_trigger`. Ни один не входит в каталог agent-tools — а этот документ заявлен «Каталог и контракты» и по architecture.md/tool-модели инструменты бывают ровно трёх категорий (нативные/MCP/мета), куда оркестраторские не помещаются. Реализатор каталога о них не узнает. Вторая дыра — субъект исполнения: `edit_workflow`/ревизии требуют «владелец или `harness-admin`» (D-28, api §4.2), но ни security, ни agent-tools не определяют, от чьего имени агент-инструменты обращаются к AccessPolicy (JWT владельца сессии? сервисный субъект?). Без этого либо эскалация (агент правит чужие workflow), либо нереализуемость сценария.
- **Предложение:** Добавить в agent-tools раздел «Оркестраторские инструменты» (сигнатуры, права, ошибки `graph-invalid`/`params-schema`); зафиксировать правило: «инструменты агента исполняются с эффективной идентичностью владельца сессии» (или иное) — в security §2 и agent-tools.

### M-3. Релей CLIENT_EXEC не умеет GLOB/GREP — половина нативных инструментов недоступна в CLIENT_EXEC
- **Severity:** MAJOR
- **Место:** `api-contracts.md` §5 (`tool.call { …, tool: BASH|READ|WRITE|EDIT }`) vs `agent-tools.md` §1 (шесть инструментов: + `glob`, `grep`), `glossary.md` §6 (WorkspaceTools: «(read/write/edit/bash)»).
- **Описание:** agent-tools декларирует оба адаптера (`ServerWorkspaceTools`, `ClientRelayWorkspaceTools`) реализациями одного контракта `WorkspaceTools`, т.е. все 6 инструментов доступны в обоих биндингах. Но фрейм релея перечисляет только 4 типа инструментов — `glob`/`grep` физически нечем передать на клиент. Глоссарий к тому же перечисляет 4 из 6. Агент в CLIENT_EXEC-состоянии, вызвав `glob`/`grep`, получает невоспроизводимую ситуацию.
- **Предложение:** Либо дополнить фрейм `tool: BASH|READ|WRITE|EDIT|GLOB|GREP`, либо явно зафиксировать (agent-tools §1 + api §8), что в CLIENT_EXEC-биндинге доступны только 4 инструмента и `glob`/`grep` должны исполняться серверно по метаданным клиента (сложнее) — но молчаливой асимметрии быть не должно. Заодно синхронизировать перечень в glossary §6.

### M-4. data-model не поддерживает обещания api-contracts: `session.title`, `agent.name/description`, источник `lastTurnOutcome`
- **Severity:** MAJOR
- **Место:** `data-model.md` §5 (`session`), §2 (`agent`) vs `api-contracts.md` §1.5, §2; `roadmap.md` M4.
- **Описание:** (а) В таблице `session` нет `title` — при том что SessionDto несёт `title`, `POST /sessions` принимает `title?`, `PATCH` правит его, список ищет `?q=` по title, CLI имеет `--title`; весь списковый UX стоит на отсутствующем поле. (б) В таблице `agent` нет `name`/`description` — `GET /agents` обещает `{ key, name, latestRev, description? }`; брать неоткуда (role_prompt — не name). (в) Перенос из прошлого цикла (GLM N-11, признан): `lastTurnOutcome` в SessionDto не имеет источника — ни таблицы Turn (D-04), ни колонки в `session`.
- **Предложение:** Добавить `session.title text`, `agent.name text`, `agent.description text NULL`; для `lastTurnOutcome` — колонку `session.last_turn_outcome` (обнуляется при старте Turn, пишется при завершении) или явное правило реконструкции.

### M-5. Roadmap M1: критерий готовности требует attach-CLI, который строится в M4
- **Severity:** MAJOR
- **Место:** `roadmap.md` M1 (критерий: «FREE-сессия через attach-CLI-минимум работает end-to-end»; объём фазы CLI не содержит) vs M4 («attach-CLI полный»).
- **Описание:** Приёмка M1 требует работающего клиента (login, список, создание, attach/SSE, отправка), но CLI как deliverable появляется только в M4; в объёме M1 клиента нет. Формально фаза невыполнима по собственному критерию либо подразумевает одноразовую утилиту, которой нигде нет в планах.
- **Предложение:** Либо включить в M1 явный пункт «attach-CLI-минимум (login/sessions/new/attach/messages)», либо переформулировать критерий M1 через REST/SSE-драйвер (Testcontainers + HTTP/SSE-клиент), оставив интерактивный CLI в M4.

### M-6. Roadmap M2: критерий фазы недостижим — переходы AGENT-состояний нечем делать
- **Severity:** MAJOR
- **Место:** `roadmap.md` M2 (STATE-сессии, задачи; критерий «двухфазное ревью с возвратом») vs M3 (мета-инструмент `transition`); `workflow-domain.md` §3, §8; `decisions.md` D-14; `api-contracts.md` §4 (нет REST-эндпоинта перехода).
- **Описание:** По D-14 и workflow-domain единственный способ перевести задачу из AGENT-состояния — инструмент агента `transition` с обязательным reason. Инструмент запланирован в M3. REST-альтернативы нет (осознанно). Значит, в M2 задачу в AGENT-состоянии **невозможно вывести** — сценарий «двухфазное ревью с возвратом» (WRITE → REVIEW → возврат → WRITE) на агентских состояниях не проходит приёмку. STATE-сессии M2 остаются «вечной парковкой».
- **Предложение:** Перенести `transition` (минимум: валидация перехода + запись history + wake) из M3 в M2, оставив в M3 только agent-фасад (выдачу инструмента агенту, строгий reason-контракт), либо определить для M2 тестовый граф без AGENT-состояний и переформулировать критерий.

### M-7. Семантика CANCEL-перехода не определена: CANCELLED-терминал недостижим
- **Severity:** MAJOR
- **Место:** `workflow-domain.md` §2 (`kind: NEXT | ERROR | TIMEOUT | CANCEL`), §3; `glossary.md` §3 (`status_projection`: CANCELLED); `api-contracts.md` §4.1 (`suspend`/`stop`/`resume` — без переходов).
- **Описание:** Четвёртый вид перехода CANCEL есть в enum и в схеме графа, но ни один документ не определяет, кто и когда его инициирует: suspend/stop лишь ставят флаг и гасят Turn'ы (задача остаётся в текущем состоянии), таймаут даёт TIMEOUT, ошибка — ERROR, вебхук — NEXT. Валидатор требует у нетерминальных состояний исходящие переходы, но сценарий «задача отменена и пришла в CANCELLED-терминал» не существует: `status_projection = CANCELLED` достижим только… ниоткуда. Либо мёртвое значение проекции, либо дыра жизненного цикла (что происходит с задачей, зависшей в suspended навсегда, — не определено).
- **Предложение:** Определить: например, «`POST /tasks/{id}/cancel` (владелец) → движок проводит CANCEL-переход из текущего состояния в достижимый CANCELLED-терминал (валидировать наличие в графе)» — либо объявить CANCEL-переходы и outcome CANCELLED резервом и убрать CANCELLED из статусной проекции MVP. Сейчас — третье состояние: enum есть, пути нет.

### M-8. Не определён bootstrap LlmCredentials/LlmModel/Agent-ревизий
- **Severity:** MAJOR
- **Место:** `data-model.md` §2; `api-contracts.md` §8 («управление ревизиями агентов через API» — вне v1); `security-multitenancy.md` §3; `roadmap.md` M1/M3.
- **Описание:** Ни API (явно out-of-scope), ни CLI (client-cli §3), ни seeds/Liquibase-практика не описывают, как в системе появляются `llm_credentials` (с `api_key_encrypted` — чем шифруем, где ключ), `llm_model` и ревизии агентов (включая оркестратора — без него M3 не работает; `agent_key` нужен уже для `POST /sessions` в M1). Реализатор M1 упирается в пустую БД и отсутствие пути заполнения; вопрос шифрования api_key (ключ из env — какой формат, ротация) тоже не раскрыт.
- **Предложение:** Зафиксировать MVP-механику: например, «seed через Liquibase/config: llm_credentials и agent-ревизии заводятся SQL-миграцией/админ-скриптом; ключ шифрования — env `HARNESS_SECRET_KEY` (AES-256-GCM)» — в data-model §2 + короткий раздел в security §3; в roadmap M1 добавить пункт «seed-профили LLM и агентов».

---

## MINOR

### m-1. Рассинхрон имён режимов SERVER_DIR: `auto|explicit` vs `AUTO|PATH`
- **Severity:** MINOR
- **Место:** `glossary.md` §6 vs `workflow-domain.md` §2 (контракт графа, JSON-Schema).
- **Описание:** Значения enum режима различаются буквально (`explicit` vs `PATH`); JSON-Schema-контракт требует дословных значений.
- **Предложение:** Выбрать канон (`AUTO | PATH` — он в схеме) и поправить глоссарий.

### m-2. `SUCCESS` (outcome) vs `SUCCEEDED` (status_projection) — присваивание буквально невозможно
- **Severity:** MINOR
- **Место:** `workflow-domain.md` §2/§3 («status_projection = outcome») vs `glossary.md` §3 / `data-model.md` §4.
- **Описание:** TERMINAL outcome — `SUCCESS`, проекция — `SUCCEEDED`; формула «= outcome» не работает без маппинга, реализатор споткнётся.
- **Предложение:** Либо унифицировать литералы, либо написать маппинг (SUCCESS → SUCCEEDED) в workflow-domain §3.

### m-3. Глоссарий перечисляет 4 инструмента WorkspaceTools из 6
- **Severity:** MINOR
- **Место:** `glossary.md` §6 («(read/write/edit/bash)») vs `agent-tools.md` §1 (+ glob, grep).
- **Предложение:** Дополнить перечень в глоссарии (связано с M-3).

### m-4. security §1: «единственный non-JSX контур» + фактически не единственный
- **Severity:** MINOR
- **Место:** `security-multitenancy.md` §1 (строка вебхуков).
- **Описание:** (а) Опечатка: «non-JSX» → «non-JWT». (б) По той же таблице SSE/WS тоже работают без JWT-заголовка (ticket) — «единственный» неточен; речь про единственный **анонимный** контур.
- **Предложение:** «единственный анонимный (без JWT) контур: capability-токен…».

### m-5. security §5 называет `idempotency_key` append-only аудит-журналом, хотя он чистится по TTL
- **Severity:** MINOR
- **Место:** `security-multitenancy.md` §5 vs `data-model.md` §6 (TTL 24 ч, чистка джобой), §7.1.
- **Описание:** «UPDATE/DELETE запрещены (инвариант data-model §7)» — но инвариант §7.1 покрывает только `session_message` и `task_transition_history`; `idempotency_key` удаляется джобой. Аудит-претензия на replay-след, живущий 24 часа, — некорректна.
- **Предложение:** Исключить `idempotency_key` из списка append-only журналов или пометить «операционное хранилище, TTL 24 ч — не аудит».

### m-6. agent-tools §2: заголовок противоречит содержимому строк
- **Severity:** MINOR
- **Место:** `agent-tools.md` §2 («Мета-инструменты (внедряются движком в AGENT-состояния)»).
- **Описание:** По заголовку мета-инструменты — только в AGENT-состояниях, но `spawn_subagent`/`read_compacted` — «любая сессия», а `stop_subtree` — вовсе «недоступен агенту». Категоризация вводит в заблуждение.
- **Предложение:** Переименовать раздел («Мета-инструменты движка») и перенести условие доступности целиком в колонку «Когда доступен».

### m-7. execution-model: WAIT_TASKS-наблюдатели названы «родителями»
- **Severity:** MINOR
- **Место:** `execution-model.md` §1 («переоценка `WAIT_TASKS` у родителей»), §7.6 («события получают родители-подписчики») vs `workflow-domain.md` §3 (scope включает `BLOCKED_BY` — не-потомков).
- **Описание:** При кросс-деревных зависимостях наблюдатель не является родителем; формулировка execution-model уже, чем семантика scope.
- **Предложение:** Заменить на «у ждущих задач (WAIT_TASKS по scope)».

### m-8. Roadmap: не распределены по фазам часть эндпоинтов api-contracts; WAIT_WEBHOOK строится раньше своих эндпоинтов
- **Severity:** MINOR
- **Место:** `roadmap.md` M1–M5 vs `api-contracts.md` §1.4–1.6, §2, §4.4.
- **Описание:** (а) Без фазы остались: папки CRUD+shares, shares сессий, archive/unarchive, export, `/compact` (не критично, но правило «фаза = вертикальный срез» нарушается тишиной). (б) Состояние WAIT_WEBHOOK — в M2, а вебхук-эндпоинты — в M3: в M2 состояние нельзя проверить e2e (критерий M2 его и не трогает, поэтому не MAJOR).
- **Предложение:** Распределить перечисленное по фазам (логично: папки/shares — M1, archive/export — M2/M3), вебхук-эндпоинты задач перенести в M2 или WAIT_WEBHOOK — в M3.

### m-9. Retention workspace упомянут, но не определён
- **Severity:** MINOR
- **Место:** `glossary.md` §6 («чистится по retention») — и нигде больше.
- **Описание:** Кто, когда и как чистит авто-каталоги; что с активной сессией/несохранёнными артефактами — не определено (политики нет ни в execution-model, ни в roadmap).
- **Предложение:** Одной строкой в execution-model §8: «retention: авто-workspace удаляются через N дней после `last_activity_at`, активные (лок/eligible) не трогаются; N — конфиг».

### m-10. `timeout` опционален, но TIMEOUT-переход для WAIT_* обязателен — мёртвое ребро
- **Severity:** MINOR
- **Место:** `workflow-domain.md` §2 (правило 4: «WAIT_* — TIMEOUT»; поле `timeout` — «опционально») и §3.
- **Описание:** Если таймаут не задан, обязательный TIMEOUT-переход недостижим; валидатор требует ребро без условия его срабатывания. Двусмысленно: обязателен ли `timeout` для WAIT_* на самом деле.
- **Предложение:** Либо сделать TIMEOUT-переобязательным только при наличии timeout, либо объявить timeout обязательным для WAIT_*.

### m-11. Перенос известных остатков прошлого цикла (не исправлены в этой итерации) + косметика
- **Severity:** MINOR
- **Место/описание:**
  - роль `harness-admin` не внесена в глоссарий (DS N-9);
  - термины `binding`/`workspaceId` релея не в глоссарии (DS N-6);
  - owner/author задач, созданных агентом/триггером, не определены (GLM N-8; `task.owner_user_id` NOT NULL — правило наследования отсутствует);
  - `TransitionDto` по-прежнему не описан (GLM N-7);
  - листинг `workspace/files` отсутствует (GLM N-5);
  - состав export (включает ли скрытые/rewind-оригиналы) не определён (DS N-12);
  - двусмысленность «только пустой или каскадом» в `PATCH/DELETE /folders/{id}` (DS Round-3 остаток) — стыкуется с матрицей security, которая на каскад не указывает;
  - косметика: сломанный markdown в D-24 («**Wake-очередь**»), опечатка «парсер-сессию» в `workflow-domain.md` §5.
- **Предложение:** Собрать в бэклог текстовых правок одним коммитом; для owner/author — зафиксировать правила (например: «задача агента: owner = владелец сессии, author = NULL; задача триггера: owner = владелец триггера»).

---

## Позитивные подтверждения (стыки, которые сошлись)

- Матрица прав `security-multitenancy.md` §2 **поимо имени совпадает** с `api-contracts.md` §7 по всем строкам (FREE/папка/задача/STATE/workflow/триггер/relay), включая правила `params`/`webhookUrl`/`includeHidden` и PUBLIC-семантику; судейские фиксы Round-3 (webhookUrl → PARTICIPATE, опциональный Idempotency-Key, `folder-cycle` в каталоге) применены во всех местах, включая `workflow-domain.md` §7 и D-05/D-26.
- Каталог agent-tools ↔ execution-model §4 (transition/spawn_subagent/async-окно/LOST-CANCELLED) и глоссарий §4–6 согласованы; capability-схема вебхуков идентична во всех четырёх доках; команды client-cli покрываются эндпоинтами api-contracts 1:1; форк/rewind/compact-границы (`seq ≤ atSeq` и т.п.) сходятся между глоссарием, data-model и API.
- Инвариантная сетка (одна сессия на состояние, fan-out запрещён, append-only, fork/rewind только FREE) проведена через все документы без исключений.

## Вердикт

**approve-with-comments.** Корпус согласован существенно лучше, чем на момент api-ревью: судейские фиксы применены, стыки security/client-cli/agent-tools с API чистые. Блокирующих для старта M1 дефектов нет, **но до старта M2/M4 обязательны решения по M-6 (переходы в M2), M-1 (workspace FREE) и M-3 (релей glob/grep)**; M-2/M-4/M-7/M-8 — в тот же дизайн-коммит. Внутренние дефекты новых доков отсутствуют на уровне CRITICAL.

---

# Cross-check (раунд 2, после правок D-30/D-31)

> Объект: CRITICAL/MAJOR находки коллег (`design-review-deepseek.md`, `design-review-minimax.md`), проверенные против **текущего** состояния доков (с D-30 — per-session Docker/`ContainerWorkspaceTools`/helper-образ, D-31 — `IdGenerator` UUID v7).
> Формат вердикта: `agree` / `already-fixed` / `disagree` / `DISPUTE`.

## DeepSeek — CRITICAL 3 / MAJOR 13

| ID | Находка | Вердикт | Комментарий |
|---|---|---|---|
| C-1 | `session.title` нет в схеме | **agree** | Подтверждено в текущем data-model §5: колонки `title` по-прежнему нет (D-30/D-31 её не касались). Дублирует мой M-4а. |
| C-2 | Владелец agent/trigger-created задач не определён | **agree** | Ни правила в data-model (owner NOT NULL), ни в glossary/workflow-domain; `session.owner` для субагентских — тоже. Мой перенос m-11; согласен с повышением до CRITICAL — блокирует M3 и всю матрицу прав. |
| C-3 | `binding` не выставлен наружу — релей не может зарегистрироваться | **agree** | SessionDto/TaskDto поля нет, CLI `relay --task` binding не принимает. Формально биндинг выводим из `GET /workflows/{key}/revisions/{rev}` (текущее состояние задачи → graph), но этот путь нигде не документирован и CLI его не автоматизирует — контрактная дыра валидна. |
| M-1 | Инструменты оркестратора вне каталога agent-tools | **agree** | Не исправлено; совпадает с моим M-2 (включая пробел субъекта прав). |
| M-2 | Workspace-резолв не хранится/не описан; `auto\|explicit` vs `AUTO\|PATH` | **agree** | Оба рассинхрона на месте (glossary §6 vs workflow-domain §2); D-30 не тронул именование режимов. Дублирует мой M-1 + m-1. |
| M-3 | WAIT_TASKS: ALL_TERMINAL vs «чужой FAILED → ERROR» | **agree** | Проверил workflow-domain §3 — приоритет не задан, поведение на CANCELLED-ребёнке тоже; при ALL_TERMINAL правила дают одновременно NEXT и ERROR. Моя находка, я её пропустил — валидна. |
| M-4 | WAIT_WEBHOOK: NEXT vs ERROR не выводимо | **agree** | «По семантике workflow» не определено; §2.4 требует у WAIT_* только TIMEOUT, ERROR-ребро может отсутствовать. |
| M-5 | Виды переходов из AGENT-состояния не определены | **agree** | `transition` без `kind`, AGENT — «по разрешённым NEXT»; судьба ERROR/CANCEL-рёбер из AGENT не описана. Родственно моему M-7 (CANCEL недостижим). |
| M-6 | BASH_SCRIPT vs async-bash; BASH_SCRIPT на CLIENT_EXEC | **agree** | execution-model §7.3 не разделяет engine-run (детерминированный) и агентский async-bash; поведение при CLIENT_EXEC не задано (глоссарийное «состояние ждёт/ошибка» — только про отсутствие клиента). D-30 добавил контейнер, но контракт не развёл. |
| M-7 | Обратный поиск ждущих WAIT_TASKS не поддержан схемой | **agree** | Индекс по-прежнему только `(parent_task_id, status_projection)`; BLOCKED_BY/TAGGED/EXPLICIT без обратного пути. Дублирует MI-8 minimax. |
| M-8 | security §5: `idempotency_key` append-only vs TTL-чистка | **agree** | Не исправлено; мой m-5. |
| M-9 | Mixed sync+async в одном ответе модели | **agree** | «Только async» в №2 буквально не покрывает смесь; заявленная исчерпываемость §3 нарушена. Дополнение композицией (№1 → при pending>0 №2) — правильное предложение. |
| M-10 | M2 требует `transition` из M3 | **agree** | Мой M-6; roadmap не менялся. |
| M-11 | Нет seed-пути agent/llm_model | **agree** | Мой M-8; не исправлено. |
| M-12 | CLI: переиздание ticket при SSE-реконнекте | **agree** | client-cli §2 не изменился; билет одноразовый — каждый ретрай требует новый `POST /auth/ticket`, в CLI не описано. |
| M-13 | `${params.*}` vs `${task.params.*}` | **agree** | Проверил: glossary инв. 3 (`${task.*}`, `${params.*}`) vs workflow-domain §1 (`${task.params.*}`) — грамматика резолвера неоднозначна. Моя находка, я её пропустил. |

**Итог DeepSeek: agree 16 / already-fixed 0 / disagree 0 / DISPUTE 0.**

## Minimax — CRITICAL 3 / MAJOR 7

| ID | Находка | Вердикт | Комментарий |
|---|---|---|---|
| CR-1 | webhookUrl: security (VIEW без URL) vs api §4.4 (TASK-VIEW) | **already-fixed** | Судейские фиксы Round-3 api-цикла: текущий api §4.4 (строка 127) — «TASK-PARTICIPATE/владельцу», §4.1 TaskDto и §7 (строка 173) — «без params и webhookUrl» для VIEW; security §2 согласован. minimax цитировал устаревший текст. |
| CR-2 | Роль `harness-admin` нигде не определена | **agree** | По-прежнему нет в глоссарии; в security §2 только инлайн «(Keycloak realm-role)» — частичное определение без способа назначения. Мой перенос m-11 (DS N-9). |
| CR-3 | Ticket TTL 60 с vs ping 15 с | **disagree** | Премиса ложна: ticket — одноразовый credential **установки** соединения, TTL — окно использования, а не срок жизни активного SSE; установленное соединение по истечении билета не рвётся, ping 15 с — keepalive транспорта. «Продление» и «судьба Turn при истечении» — несуществующие сущности. Реальный смежный пробел ровно один — CLI должен переиздавать билет на каждый (ре)коннект — это DS M-12 (agree). Плюс пункт продублирован в файле трижды (CR-3 = MA-7 = MI-6). |
| MA-1 | Fan-out «не проверен», нет индекса/ограничения в БД | **disagree** | Fan-out — статическое свойство графа: правила §2 workflow-domain — это правила валидации графа при сохранении ревизии (заголовок «Контракт графа… Правила валидации»), п.3 отсекает fan-out до появления инстансов. Runtime-fan-out невозможен: `current_state` — скаляр, переход двигает задачу. Предлагаемый UNIQUE `(workflow_revision_id, current_state)` сломал бы модель — разные задачи легитимно находятся в одном состоянии одновременно. |
| MA-2 | «Две сессии в одном состоянии возможны» | **disagree** | PARTIAL UNIQUE `(task_id, state_code) WHERE kind='STATE'` уже запрещает вторую сессию той же пары на уровне БД; нахождение в одном состоянии **разных** задач — легально и является основой параллелизма. Остаток — только словесный («поле-список» vs unique-инвариант, DS m-6/minor). |
| MA-3 | Нет получения COMPACT-оригиналов по ID | **disagree** | `GET /sessions/{id}/messages/{messageId}` существует (api §2; скрытые — владельцу), агентский путь — `read_compacted`. Премиса «компакция только для свободных сессий» неверна: страховочная компакция работает во всех сессиях (execution-model §5), FREE-ограничение — только у команды `/compact`. |
| MA-4 | execution-model не описывает поведение по типам состояний | **disagree** | execution-model §7 п.2–6 — отдельные пронумерованные пункты для AGENT / BASH_SCRIPT / WAIT_WEBHOOK / WAIT_TASKS / TERMINAL. Тезис «только AGENT и не-AGENT» не соответствует тексту. |
| MA-5 | Рассинхрон `current_state`/`status_projection` без recovery | **disagree** | Инвариант data-model §7.2: оба поля меняются в **одной** транзакции движком переходов; рассинхрон без ручного вмешательства в БД невозможен. Предложение — защитная проверка nice-to-have, не дизайн-дефект. |
| MA-6 | Термин `binding` не определён в глоссарии | **agree** | Подтверждено: термина нет; известный перенос (DS N-6 прошлый цикл, мой m-11). |
| MA-7 | Ticket TTL vs ping | **disagree** | Дубликат CR-3 — см. выше. |

**Итог Minimax: agree 2 / already-fixed 1 / disagree 7 / DISPUTE 0.**

## Сводка кросс-ревью

| Коллега | agree | already-fixed | disagree | DISPUTE |
|---|---|---|---|---|
| DeepSeek | 16 | 0 | 0 | 0 |
| Minimax | 2 | 1 | 7 | 0 |

**DISPUTE-пункты: нет.** Все расхождения с minimax закрываются цитатой из текущего текста (фактические ошибки прочтения: api §4.4 уже исправлен, §7.1–7.6 существует, PARTIAL UNIQUE существует, компакция не FREE-only, ticket-семантика).

## Наблюдение по правкам D-30/D-31 (новый рассинхрон)

Правка глоссария §6 под D-30 ввела новый дрейф перечня инструментов: глоссарий теперь пишет «read/write/edit/bash/**find**/glob» (grep пропал, find появился), таблица agent-tools §1 — `glob`/`grep` (find нет), преамбула agent-tools §1 — «read/write/bash/**find**/glob». Итог: канонического перечня нативных инструментов по-прежнему нет (см. мой m-3 и DS m-5) — теперь в трёх вариантах. D-31 отражён в data-model корректно и с глоссарием (`session_message.id` — короткий ULID как исключение) не противоречит. Рекомендация к судье: при финальной сборке зафиксировать один перечень (судя по intent D-30 — `read_file/write_file/edit_file/bash/glob/grep`, а «find» — либо синоним glob, либо седьмой инструмент, который надо добавить в таблицу).

---

# Round 2 (verify) — финальная верификация судейских фиксов

> Проверены мои 8 MAJOR раунда 1 против текущего текста; дополнительно — не внесли ли фиксы новых противоречий.

## 1. Статус моих MAJOR

| ID | Находка | Статус | Где / детали |
|---|---|---|---|
| M-1 | Workspace FREE-сессий не определён | **fixed** | glossary §4 Session: «workspace (FREE — всегда `SERVER_DIR auto` `workspaces/sessions/{sessionId}`; STATE — из декларации состояния)». Стык с api §2 workspace-files и M4 закрыт. Косметический остаток: §6 глоссария всё ещё определяет workspace только «по декларации состояния» — одну фразу можно добавить и туда (не блокер). |
| M-2 | Инструменты оркестратора вне каталога + субъект прав | **fixed** | agent-tools §2b: сигнатуры всех шести, гейт `permissions_jsonb.metaTools`, «субъект прав — сессия агента, всё созданное наследует owner_user_id»; наследование — glossary §4 Session + data-model (session/owner note, task note). Эскалации на edit_workflow нет (owner = пользователь сессии; D-28 соблюдается). |
| M-3 | Релей не умеет GLOB/GREP | **fixed** | api §5: `tool: BASH\|READ\|WRITE\|EDIT\|GLOB\|GREP`; перечень в glossary §6 унифицирован (`read_file, write_file, edit_file, bash, glob, grep`). Остаток: преамбула agent-tools §1 всё ещё упоминает «find» (см. п.3.5). |
| M-4 | data-model: session.title, agent.name/description, last_turn_outcome | **fixed** | data-model: `session.title text NULL` (§5), `agent.name`/`agent.description` (§2), `session.last_turn_outcome enum COMPLETED\|FAILED\|CANCELLED` (§5) — enum совпадает с SessionDto; поле отражено и в glossary §4 Session. |
| M-5 | Критерий M1 требует CLI из M4 | **fixed** | roadmap M1: «минимальный attach (стриминг + отправка)» в объёме и в критерии; M4 — «attach-CLI полный» (иерархия непротиворечива). |
| M-6 | M2 недостижим без `transition` | **fixed** | roadmap M2: «минимальный мета-инструмент `transition` (обязательный reason)» в объёме, критерий — «агент переводит задачу инструментом transition»; M3 оставил себе spawn/read_compacted. Цикл-валидация зависимостей тоже в M2 ✓. |
| M-7 | CANCELLED недостижим, семантика CANCEL не определена | **fixed** | workflow-domain §3: стоп задачи → виртуальный терминал (kind=CANCEL, to_state=CANCELLED, без CANCEL-рёбер в графе). Решение рабочее; оговорка о стыке — п.3.1 ниже. |
| M-8 | Нет bootstrap LlmCredentials/Agent | **fixed** | architecture §4: «Bootstrap: … админ-командой CLI (читает env)»; roadmap M1: «bootstrap-сид … админ-команда CLI из env; см. architecture §4». Два места согласованы. |

**Итог: 8/8 fixed, not-addressed — 0.**

## 2. Проверка судейских фиксов из смежных раундов (выборочно, стыки)

- `workspaceBinding { type, pathTemplate?, logicalKey? }` в SessionDto (api §2) + `relay --task [--binding]` с листингом биндингов (client-cli §1) — замыкает DS C-3. Остаток — п.3.4.
- `harness-admin` в glossary §1 (realm-role, назначение, область применения) — закрывает MM CR-2; согласовано с api §4.2 и security §2.
- Билет: «одноразовый на соединение», CLI прозрачно переиздаёт при реконнекте (client-cli §2) — закрывает DS M-12; с api §1.4 не противоречит.
- WAIT_TASKS: ALL_TERMINAL / ALL_SUCCESS («первый FAILED немедленно ведёт по ERROR») — закрывает DS M-3; согласовано с glossary scope и reason.
- WAIT_WEBHOOK: NEXT/ERROR по схеме payload — закрывает DS M-4; оговорка — п.3.2.
- BASH_SCRIPT ≠ async-bash (workflow-domain §3, отдельный абзац) — закрывает DS M-6; согласовано с agent-tools и execution-model.
- Индексы `(blocked_task_id)` + GIN `(tags)` — закрывают DS M-7/MI-8; оговорка о размещении — п.3.3.
- security §5: `idempotency_key` — «не журнал, а служебное хранилище с TTL», `task_transition_history` — бессрочно — закрывает мой m-5 и DS M-8; с data-model §7.1 согласовано.
- Отказы контейнера (LOST / pull-backoff / volume-ERROR, лимиты cpus=2/mem=2g/pids=512, скан сирот) — execution-model §1/§4; согласовано с D-30, architecture §4 и security §6.

## 3. Новые противоречия после фиксов (все — MINOR, не блокируют)

1. **stop ↔ resume в api §4.1 и D-29.** workflow-domain §3 сделал `stop` финальным (CANCELLED-виртуальный терминал), но api §4.1 по-прежнему описывает stop как «suspend + отмена Turn'ов» рядом с resume без оговорки, а D-29 формулирует stop без терминальности. Следствие не зафиксировано: resume остановленной задачи должен отдавать `409 task-already-terminal`. Нужна одна строка в api §4.1 и пометка в D-29.
2. **WAIT_WEBHOOK: «payload не прошёл схему состояния (если декларирована)»** — поле декларации схемы payload отсутствует в контракте графа (workflow-domain §2 `states[]`: нет `payloadSchema`). Либо добавить опциональное поле в схему, либо переформулировать через явный ERROR-переход.
3. **Размещение строк в data-model §4.** `INDEX (tags) GIN` и правило наследования `task.owner_user_id` вставлены в конец секции **task_dependency**, а относятся к таблице **task** (у task_dependency нет ни tags, ни owner). Содержание верное, место — нет.
4. **client-cli §1 relay** ссылается на «`workspaceBinding.logicalKey` из SessionDto/**TaskDto**» — в TaskDto (api §4.1) такого поля нет; источник только SessionDto (или добавить поле в TaskDto).
5. **agent-tools §1 преамбула** всё ещё перечисляет «read/write/bash/**find**/glob» среди серверных инструментов — таблица и унифицированный глоссарий знают только glob/grep (остаток моего Round-1-наблюдения о «find»).

## 4. Вердикт

**approve-with-comments.** Все 8 моих MAJOR закрыты по существу, консенсус-находки коллег подтверждены в тексте, новых противоречий уровня MAJOR/CRITICAL фиксы не внесли. Пять текстовых оговорок из п.3 (синхронизация stop/resume, payloadSchema в графе, перенос двух строк в data-model §4, TaskDto-workspaceBinding, «find» в преамбуле agent-tools) — одним коммитом до старта кодогенерации.

---

# Cross-check Mercury

> Объект: 9 находок `design-review-mercury.md` (4 MAJOR + 5 MINOR), написанных до судейских фиксов; каждый пункт проверен против **текущего** текста доков. Формат: `agree` / `already-fixed` (с указанием закрывающей правки) / `disagree`.

## MAJOR

| ID | Находка | Вердикт | Комментарий |
|---|---|---|---|
| M-1 | Реакция на краш Docker-контейнера mid-execution не определена | **already-fixed** | Валидна на момент написания. Закрыто: execution-model §1 «Отказы контейнера в рантайме (D-30)» — смерть контейнера во время async-выполнения → синтетический `TOOL_RESULT` `LOST` «контейнер исполнения умер» + скан сиротских контейнеров при старте (§1 п.3). Автовосстановление исключено (lifecycle = сессия). |
| M-2 | Валидация циклов task_dependency не определена (транзитивность?) | **already-fixed** | Валидна. Закрыто: data-model §4 task_dependency — «циклы (включая транзитивные) запрещены валидацией (обход в глубину при установке ребра, `422 dependency-invalid`)»; стыкуется с api §4.1 (атомарный `422 dependency-invalid`) и roadmap M2 («цикл-валидация»). Алгоритм (DFS) и ошибка — ровно как предлагал Mercury. |
| M-3 | Retention `task_transition_history` не определён | **already-fixed** | Валидна. Закрыто: security §5 — «хранится **бессрочно**, retention для архивных задач — отдельным решением при появлении объёмов»; с data-model §7.1 (append-only) согласовано. Выбран второй из предложенных Mercury вариантов. |
| M-4 | Retry/backoff для docker image pull отсутствует | **already-fixed** | Валидна. Закрыто изменением дизайна: execution-model §1 — «образ обязан присутствовать локально на VM (собирается при деплое); pull — только retry/backoff на случай обновления, недоступность registry не блокирует существующие сессии» + architecture §4 (Dockerfile в репозитории, локальное присутствие, «pull — только backoff-обновление»). Pull при создании контейнера исключён по построению. |

## MINOR

| ID | Находка | Вердикт | Комментарий |
|---|---|---|---|
| m-1 | Формат ULID не формализован | **agree** | Подтверждено в текущем тексте: glossary §4 и data-model §5 — по-прежнему «короткий ULID» без формата (26 символов Crockford Base32, монотонность), обоснование выбора в D-31 отсутствует. Остаток открыт (совпадает с его самооценкой not-addressed). |
| m-2 | BLOCKED_BY без обратного индекса | **already-fixed** | Закрыто: data-model §4 task_dependency — `INDEX (blocked_task_id)` «обратный поиск "кто ждёт эту задачу"» (прямой поиск покрыт PK). Одноколоночный вариант вместо предложенного композитного — достаточен: сортировка по blocker при необходимости достраивается поверх выборки. |
| m-3 | Volume mount failure без обработки | **already-fixed** | Закрыто: execution-model §1 — «отказ монтирования workspace-тома при старте контейнера → инструмент отвечает `ERROR` с причиной → задача идёт по ERROR-пути состояния». Вариант судьи (ERROR + детерминированный переход состояния) лучше предложенного LOST: задача получает определённую траекторию, а не парковку. |
| m-4 | Lifecycle старых ревизий агента | **agree** | Подтверждено: data-model §2 без политики retention; immutability + UNIQUE(key, rev), никаких правил удаления/архивации. Замечание валидно, severity MINOR корректен (объёмы малы, ссылки пинят конкретные ревизии — но «хранить всегда» нигде не сказано; одна строка «ревизии хранятся бессрочно, ссылки пинят конкретную» закрыла бы пункт). |
| m-5 | Ресурсные лимиты контейнера не квантифицированы | **already-fixed** | Закрыто: execution-model §4 — «Лимиты по умолчанию: `cpus=2`, `memory=2g`, `pids-limit=512`; сеть — только состояниям, которым нужен git-клон». Квантификация внесена (набор отличается от предложенного — cpus=2 вместо 1.0, pids вместо disk-quota; остаток: disk quota по-прежнему не оговорён — косметика). |

## Сводка

| Вердикт | Кол-во | Пункты |
|---|---|---|
| already-fixed | 7 | M-1, M-2, M-3, M-4, m-2, m-3, m-5 |
| agree | 2 | m-1, m-4 |
| disagree | 0 | — |
| DISPUTE | 0 | — |

Самопроверка Mercury (его Round 2) совпадает с моей независимой верификацией по всем девяти пунктам; оба открытых остатка (m-1 ULID-формат, m-4 retention ревизий агентов) — MINOR, в бэклог текстовых правок рядом с пятью оговорками моего Round 2 (п.3). На вердикт не влияют.
