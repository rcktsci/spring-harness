# Ревью m2-workflow-engine (плановые артефакты) — GLM-5.3-Flash

Дата: 2026-09-18. Ревьюер: GLM-5.3-Flash (субагент). Объект: proposal.md, design.md, tasks.md, specs/{workflow-engine,task-engine,inbound-triggers}. Контекст: roadmap, workflow-domain, architecture, data-model, decisions (D-01…D-46), api-contracts, спеки M1.

**Вердикт: REJECT** — 24 находки (7 major / 12 minor / 5 nit). Ядро замысла здравое (домены, CAS-переходы, capability-URL, STATE-сессии), но есть контракто-разрывы и конфликт с D-41, которые обязаны быть устранены до заморозки плана.

---

## MAJOR

### M1. `wrong-transition` отсутствует в каталоге ошибок
- **Где**: specs/task-engine/spec.md «Мета-инструмент transition» (сценарии «пустойreason», «недостижимое состояние»); proposal.md (строка 31: список «10 новых кодов», строка 37: «Ошибки — расширение каталога §6 (10 кодов)»).
- **Цитата**: «`409 wrong-transition` (не разрешённое ребро)» / «`409 wrong-transition` с error `rule=reason-required`».
- **Проблема**: api-contracts §0.2: «любой `code` вне каталога — дефект реализации». `wrong-transition` нет ни в api-contracts §6, ни в списке 10 кодов proposal. Попутно: все 10 «новых» кодов proposal уже присутствуют в api-contracts §6 (они новые только относительно реализованного M1-подмножества) — формулировка «расширение каталога §6» неверна.
- **Предложение**: добавить `wrong-transition` (409) в каталог §6 и в задачу 1.1 (OpenAPI-спека M2); в proposal заменить «расширение каталога §6 (10 кодов)» на «реализация кодов §6 в M2-подмножестве + 1 новый код wrong-transition».

### M2. Гейт metaTools по `instructionSource` конфликтует с D-41 и не подкреплён кодом/задачами
- **Где**: proposal.md (строки 11, 32, 39); design.md D-52; tasks.md 4.2, 5.1.
- **Цитата**: D-41: «Выброшено: … делегирование (D-32/D-38-формула, **instructionSource, metaTools-гейт**) …». tasks.md 5.1: «`author_user_id = JWT principal` или `NULL + агент-пометка` (для агентских — из `Caller.instructionSource()`)».
- **Проблема**: (а) M2 возвращает `instructionSource` и metaTools-гейт, явно выброшенные D-41, без строки supersession в decisions.md — конфликт с существующим ADR; (б) в коде M1 `instructionSource` отсутствует полностью (grep по src/main/java — 0 вхождений), `Caller` не имеет ни `instructionSource()`, ни `sessionOwner()` (проверено) — а tasks.md использует оба как существующие; задач на введение plumbing'а нет; (в) roadmap M2 требует только «минимальный мета-инструмент transition (обязательный reason)» — гейт есть инициатива ченджа (легитимная, но требующая оформления).
- **Предложение**: 1) строка в decisions.md (новый D-5x или правка D-52): «гейт metaTools частично supersede D-41 в точке instructionSource»; 2) задача в пачке 4: введение instructionSource в точках входа сообщений (USER/TOOL_RESULT/внутренний) + расширение Caller/sessionOwner; 3) R7 дополнить: «требует новой инфраструктуры, которой нет в M1».

### M3. JSON-Schema-валидация params/payload: D-58/R8 не реализует то, что требуют спеки
- **Где**: design.md D-58 и R8; specs/workflow-engine «Контракт графа», specs/task-engine «Создание задачи» (`422 params-schema`), specs/inbound-triggers «Webhook задачи» (payloadSchema → validationErrors).
- **Цитата**: D-58: «Валидация JSON-Schema — через `hibernate.validator` (уже в Boot) + ручной обход»; R8: «hibernate.validator (JSON-Schema не валидирует, но проверка обязательных полей и типов работает); полная JSON-Schema — точка эволюции».
- **Проблема**: hibernate.validator — Bean Validation по аннотациям Java-классов; произвольный JSON против произвольной пользовательской `paramsSchema`/`payloadSchema` (JSON-Schema draft) им не провалидировать. Спеки при этом обещают `422 params-schema` с `errors[]` по схеме и ERROR-переход с `validationErrors`. Также конфликт с workflow-domain §2, где paramsSchema/payloadSchema объявлены именно как JSON Schema.
- **Предложение**: выбрать одно: (а) `networknt/json-schema-validator` (пересмотр D-58 «без новых зависимостей» — обосновать в ADR); (б) дескоуп: paramsSchema/payloadSchema в M2 поддерживают ограниченный профиль (required/type/enum) с ручным обходом — тогда явно записать профиль в спеки workflow-engine/task-engine/inbound-triggers и в workflow-domain-цитату.

### M4. WAIT_WEBHOOK/WAIT_TASKS: ERROR-переходы без требования ERROR-ребра — клинч движка
- **Где**: specs/workflow-engine «Валидация графа», правило 5; specs/task-engine «WAIT_TASKS» (ALL_SUCCESS + FAILED → ERROR), «WAIT_WEBHOOK» (payloadSchema fail → ERROR).
- **Цитата**: правило 5: «`WAIT_WEBHOOK` и `WAIT_TASKS`-states имеют TIMEOUT переход»; task-engine: «первый `FAILED|CANCELLED` ведёт немедленно по ERROR».
- **Проблема**: граф, где у WAIT_TASKS/WAIT_WEBHOOK нет ERROR-ребра, проходит валидацию, но предписанные spec'ом ERROR-переходы неисполнимы (цели не существует) — задача зависает. Унаследовано из workflow-domain §2 правило 4, но спека ченджа обязана была закрыть дыру, т.к. сама же её эксплуатирует.
- **Предложение**: правило 5 дополнить: «WAIT_WEBHOOK и WAIT_TASKS обязаны иметь и ERROR, и TIMEOUT переходы» (сценарии-тесты: WAIT_TASKS без ERROR → `422 rule=wait-error-required`); либо явно определить fallback-семантику при отсутствии ERROR-ребра.

### M5. Момент применения агентского transition: tool-call (spec) vs turn-finish (tasks)
- **Где**: specs/task-engine «Мета-инструмент transition» (сценарий «агент переводит по NEXT») vs tasks.md 4.2.
- **Цитата**: spec: «THEN запись в `task_transition_history`, `current_state` атомарно обновлён, `status_projection` транзакционно сменён» — читается как эффект вызова инструмента; tasks 4.2: «write-ahead раунда теперь **при успешном turn-finish** дополнительно пишет `task_transition_history` (если сессия — STATE и был вызван `transition` tool-call в этом раунде)».
- **Проблема**: разная семантика. При применении на turn-finish: crash/отмена Turn'а после вызова инструмента теряет переход; CAS-сценарий «двойной transition» из spec'а перестаёт работать описанным образом; suspend/stop между tool-call и finish даёт несогласованность. При применении на tool-call: что делать, если Turn после этого FAILED — вопрос отката (переход уже применён).
- **Предложение**: зафиксировать одно поведение (рекомендую: применение транзакционно в момент исполнения tool-call, как у обычных инструментов; turn-finish ничего не дозаписывает) и привести tasks 4.2 в соответствие; в design D-52/D-54 добавить абзац о тайминге.

### M6. Нет дельта-спек для модифицируемых capabilities session-api и agent-turn
- **Где**: proposal.md «Modified Capabilities» (строки 29–32); каталог openspec/changes/m2-workflow-engine/specs/ (только workflow-engine, task-engine, inbound-triggers); tasks.md 8.1.
- **Цитата**: tasks 8.1: «спеки синхронизируются в `openspec/specs/{workflow-engine,task-engine,inbound-triggers,session-api,agent-turn}/spec.md`».
- **Проблема**: proposal заявляет существенные модификации (SessionDto: kind/taskId/stateCode/runtimeStatus; новый SSE-поток задач; мета-инструмент transition; write-ahead расширение AgentTurnEngine), но дельта-спек для session-api и agent-turn в чендже нет — архивировать будет нечего, изменения M2 не покрыты требованиями.
- **Предложение**: добавить specs/session-api/spec.md и specs/agent-turn/spec.md (MODIFIED/ADDED Requirements: SessionDto-расширение, SSE задач, ограничение POST /sessions → FREE, transition-инструмент и гейт, write-ahead для системных состояний).

### M7. Границы модулей и оркестрация stop не определены; риск нарушения ArchUnit
- **Где**: tasks.md 3.1–3.5 (пакеты не указаны), 2.3 (`TaskRegistry.stop`), 5.2 (REST stop), 6.3 (`WebhookHandlers` в `integration/impl/`); design.md D-48/D-50; tasks 3.2 («уточнение в D-M2-… при реализации»).
- **Цитата**: D-50: «bash-состояние запускает `script` через `WorkspaceTools.executeBash(...)`»; architecture §2: «api → execution → { task, session, workflow } → identity».
- **Проблема**: (а) если `TaskEngine`/`BashStateExecutor` лежат в `task.impl`, вызов `WorkspaceTools` (контракт execution) создаёт зависимость task → execution — запрещённое направление; если в `execution.impl` — в tasks/design это нигде не сказано; (б) `WebhookHandlers` в integration вызывает `TaskRegistry.createTask` — зависимость integration → task правилами 7.1 не покрыта (правило интеграции в architecture: «их знают только execution/api»); (в) spec Stop требует «отменить активные Turn'ы сессий состояния задачи (и подзадач…)», но `TaskRegistry.stop` (2.3) делает только CAS `'$CANCELLED'` — отмена Turn'ов/каскад никому не назначена (execution/api-слой в tasks отсутствует); (г) отсылка к плейсхолдеру «уточнение в D-M2-…» — несуществующий идентификатор решения.
- **Предложение**: в design зафиксировать: исполнители состояний — в `execution.impl` поверх контрактов `TaskRegistry`/`WorkflowRegistry` (направление соблюдено); webhook-HTTP — api-контроллер на сгенерированном интерфейсе, делегирует в execution/task-контракты (integration в M2 для вебхуков не нужен либо ArchUnit-правило расширяется явно); stop-оркестрация: api/execution-фасад `stopTask` = TaskRegistry.stop + отмена Turn'ов STATE-сессий (+ каскад), добавить задачу; заменить плейсхолдеры на конкретные решения.

---

## MINOR

### m1. Число таблиц и миграций расходится
- proposal.md Impact: «БД: 6 таблиц, … миграции `010..015`» — при этом сам proposal перечисляет 7 новых сущностей, а tasks 2.1 создаёт `010..016` (7 миграций). Исправить на 7 таблиц / 010..016.

### m2. Индексы data-model §4 не полностью попали в план
- В tasks 2.1 и proposal отсутствуют: PARTIAL INDEX `WHERE current_state_kind='AGENT' AND status_projection='RUNNING'` (bootstrap-скан AGENT-без-сессии — на нём держится POLL-bootstrap из spec task-engine) и INDEX `(blocked_task_id)` на task_dependency (обратный поиск для WAIT_TASKS/BLOCKED_BY). Добавить в tasks 2.1.

### m3. Сигнатура transition расходится между артефактами; сценарий с M3-инструментом
- proposal: «`transition(taskId?, from?, to, kind, reason)`»; spec task-engine: «`transition(taskId, toState, kind, reason)`» (нет `from`, taskId обязателен). Сценарий «Подзадачи»: «агент создаёт подзадачу через инструмент `create_subtask`» — этого инструмента в M2 нет (roadmap: только transition; create_subtask — M3). Унифицировать сигнатуру; сценарий переписать через REST POST /tasks/{id}/subtasks.

### m4. POLL-страховка: WAIT_WEBHOOK внутри переоценки vs «пассивен»
- tasks 3.5 и spec task-engine: «подбирать … (current_state_kind IN ('WAIT_WEBHOOK','WAIT_TASKS')) (переоценка)» и тут же «webhook — пассивен, не опрашивается». Явно исключить WAIT_WEBHOOK из POLL-выборки (для него только таймаут-скан), поправить условие выборки и partial-индекс.

### m5. Правило fan-out не ограничивает кратность TIMEOUT-рёбер
- Правило 7 валидации нормирует только NEXT/ERROR; два TIMEOUT-ребра из одного состояния пройдут валидацию, нарушая workflow-domain §2 «fan-out запрещён». Дополнить: не более одного исходящего ребра каждого kind (NEXT/ERROR/TIMEOUT).

### m6. Конфиг-дыры
- design D-52 вводит `harness.task.transition.max-per-turn` — нет ни в списке конфигов proposal Impact, ни в tasks (не реализуется/не верифицируется). tasks 6.1 использует `harness.webhook.base-url` — в proposal Impact отсутствует. `harness.task.scheduler.batch-size` (proposal) не упомянут в tasks 3.5. Свести списки конфигов.

### m7. Остатки deliberation в финальных текстах
- specs/inbound-triggers «Webhook задачи», п.3: «не прошёл → `409 task-not-waiting-webhook` (или явная семантика в reason? — уточнение D-M2-…: …). Уточнённое поведение: …» — в спеке должен остаться один финальный вариант. tasks 3.2: «уточнение в D-M2-… при реализации» — заменить решением (см. M7).

### m8. sessions/tree обещан, но не задачен; опечатка метода
- proposal: «`POST /api/v1/sessions/{id}/tree` дополняется `taskId`+`stateCode`» — метод GET (api-contracts §2); задачи на реализацию tree/выдачи новых полей SessionDto в REST-слое в tasks нет (только контрактный шаг 1.1). Добавить задачу в пачку 5 (или явно указать, что регенерированные интерфейсы заставят обновить контроллеры, и включить это в verify 1.2/5.1).

### m9. Семантика resume неполная
- Resume снимает флаг у нетерминальной — но не определено, что дальше: suspended-гвард CAS блокировал переходы/POLL; после resume WAIT_TASKS/WAIT_WEBHOOK/AGENT-задачи нужно wake/переоценка, иначе задача зависнет до следующего события. Дополнить spec/task 5.2: resume инициирует переоценку (или зафиксировать, что POLL подберёт — тогда убрать suspended из условия выборки противоречие).

### m10. R5 описывает несуществующую гонку
- «если внешняя система шлёт webhook на задачу, которая ещё не создана (POST /api/webhooks/triggers/... race), сервер создаёт задачу синхронно» — синхронное создание задачи в handler'е и есть штатное поведение по спеке, гонки нет. Переформулировать риск (реальный риск здесь — долгая транзакция в HTTP-потоке / отсутствие дедупликации повторных доставок триггера).

### m11. Stop-каскад не зафиксирован в контракте
- spec Stop: «отменить активные Turn'ы … (и подзадач, если `cascade` — поведение по умолчанию для stop)» — параметр cascade в API stop (api-contracts §4.1, tasks 5.2) отсутствует. Зафиксировать: stop всегда каскадный (и убрать упоминание параметра) либо добавить поле.

### m12. «Каталог ошибок расширен (10 новых кодов)» — фактически 0 новых
- Все 10 кодов из proposal уже есть в api-contracts §6 (замороженном дизайне); реальный новый — только `wrong-transition` (см. M1). Поправить формулировку в proposal/tasks 1.1, чтобы ревью спеки не искало несуществующую дельту каталога.

---

## NIT

### n1. Miscitation в R2
- «stop выигрывает через CAS без гварды `NOT suspended` (D-47 в data-model §7.2)» — в data-model §7.2 это инвариант схемы, а не D-47 (D-47 в design.md ченджа — про контракт-first). Убрать ссылку на D-47.

### n2. Грамматика сценария POLL
- specs/task-engine: «WHEN у AGENT-задачи нет STATE-сессии после EVENT-wake не сработал» — «если EVENT-wake не сработал».

### n3. Созвучные имена InProcessTaskWakeBus / TaskWakeBroadcaster
- tasks 3.5 vs 4.4: разные роли (wake-шина vs SSE fan-out), но имена легко перепутать. Добавить по строке в Javadoc-план/комментарий tasks о различии.

### n4. D-47…D-58 пронумерованы заранее, в decisions.md отсутствуют
- decisions.md заканчивается на D-46; номера D-47…D-58 займутся при apply. Следить, чтобы номера в decisions.md совпали с design.md (иначе рассинхрон ссылок из proposal/tasks на «D-5x»).

### n5. BREAKING-заметка устарела
- proposal: «клиенты M1-эпохи, читающие "всё через `agent.key`", должны мигрировать на `{key,rev}`» — SessionDto в спеке M1 уже содержит `agent {key, rev}` (openspec/specs/session-api/spec.md). Пометку «BREAKING» снять или переформулировать (единственное реальное изменение — обязательность/дополнение полей kind/taskId/stateCode, обратно совместимо).

---

## Что не вызвало замечаний (позитив)

- Покрытие сущностей data-model §3–§4 спеками полное (workflow, workflow_revision, task, task_dependency, task_comment, task_transition_history, trigger).
- Покрытие api-contracts §4.1–§4.4 и §3.2 эндпоинтами/событиями полное; SSE-события совпадают.
- CAS-семантика переходов/stop (spec «Атомарность переходов») согласована с data-model §7.2 и workflow-domain §3.
- Capability-URL HMAC (D-05/D-26) воспроизведён корректно; revoke-семантика триггера соответствует D-25.
- Пачки tasks в целом ≤1 подсистемы, каждая задача имеет Verify; приёмочный сценарий 7.2 соответствует критерию M2 из roadmap.
- Open Questions конкретны и привязаны к пачкам; в целом реалистичны (кроме R5, m10).

## Вердикт

**REJECT** — 24 находки (7 major / 12 minor / 5 nit). После устранения major-пунктов (каталог ошибок, supersession D-41 + plumbing instructionSource, JSON-Schema-история, ERROR-рёбра WAIT_*, тайминг transition, дельта-спеки session-api/agent-turn, границы модулей/stop-оркестрация) и косметики minors — готов к re-approve.

---

# Re-approval (2026-09-18, после фиксов по m2-judge J-1…J-31)

## Статус моих находок первого прохода

### Major — закрыты все 7

| # | Находка | Статус | Где проверено |
|---|---|---|---|
| M1 | `wrong-transition` вне каталога | **Закрыто** | api-contracts §6 (строка 127, 409); proposal/session-api-дельта/tasks D.1 — «единственный новый код», остальные 10 уже в §6 (m12 тоже закрыт) |
| M2 | metaTools-гейт vs D-41, нет plumbing | **Закрыто** | decisions.md D-59 (supersession D-41 в границах M2, с альтернативами); tasks J.2 — plumbing `Caller.instructionSource()`/`sessionOwner()` + тесты гейта; R7 уточнён |
| M3 | JSON-Schema params/payload нереализуем | **Закрыто** | D-58 переписан: ограниченный профиль (required/type/enum/items/properties 1-го уровня), ручной обход, hibernate.validator явно отвергнут («им не является»); профиль отражён в workflow-engine «Контракт графа», task-engine, inbound-triggers, H.2 |
| M4 | WAIT_* без ERROR-ребра — клинч | **Закрыто** | Правило 5: «и ERROR, и TIMEOUT»; сценарий «WAIT без ERROR» (`rule=wait-error-required`); H.2 — негативный тест |
| M5 | Тайминг transition (tool-call vs turn-finish) | **Закрыто** | Единообразно: proposal (agent-turn), D-52, tasks J.2, дельта agent-turn (MODIFIED Write-ahead + сценарий «рестарт после transition tool-call») |
| M6 | Нет дельта-спек session-api/agent-turn | **Закрыто** | 4 дельты добавлены: session-api, agent-turn, session-store (STATE-сессия, атомарный seed), workspace-tools (task-контейнер); tasks N.1 — 7 спек |
| M7 | Границы модулей / stop-оркестрация | **Закрыто** (с оговоркой → N-1 ниже) | D-49 (J-9): TaskEngine/BashStateExecutor/StopTaskFacade → `execution.impl`; WebhookHandlers → `api/impl` с делегацией в `TaskRegistry`; TriggerRegistry — внутренность `task`; отмена Turn'ов назначена StopTaskFacade; плейсхолдеры «D-M2-…» удалены |

### Minor

- m1 (7 таблиц / 010..016) — **закрыто** (proposal Impact, Migration Plan, H.1).
- m2 (индексы AGENT+RUNNING, blocked_task_id) — **закрыто** (proposal §Миграции, H.1, spec POLL).
- m3 (сигнатура transition; create_subtask) — **закрыто** (единая сигнатура `transition(taskId, toState, kind, reason)`, taskId резолвит адаптер; сценарий подзадач переведён на REST).
- m4 (POLL vs WAIT_WEBHOOK) — **закрыто** (WAIT_WEBHOOK исключён из выборки везде; индекс только по WAIT_TASKS).
- m5 (кратность TIMEOUT-рёбер) — **закрыто** (правило 7: ≤1 ребро каждого kind).
- m6 (конфиг-дыры) — **частично закрыто**: max-per-turn появился в D-52, но в списке конфигов proposal Impact по-прежнему нет `harness.task.transition.max-per-turn`, `harness.webhook.base-url` (используется в L.1) и `harness.task.transition.kind-timeouts.*` (I.1). **Открыто.**
- m7 (остатки deliberation) — **закрыто** (inbound-triggers переписан, сценарий «невалидный payload» добавлен; плейсхолдеры в tasks убраны).
- m8 (sessions/tree) — **закрыто по сути** (задачи D.2/K.1 добавлены), но сама опечатка метода осталась и расползлась → N-3.
- m9 (resume-семантика) — **закрыто** (resume публикует `task-wake`; K.2/spec Scenario).
- m10 (R5) — **закрыто** (переписан честно: синхронный HTTP-путь, без дедупликации).
- m11 (stop-каскад) — **закрыто** («всегда каскадный», параметра нет — spec/D-54/K.2 согласованы).
- m12 (каталог «10 кодов») — **закрыто** (см. M1).

### Nit

- n1 (miscitation D-47) — **закрыто** (R2 теперь «(data-model §7.2)»).
- n2 (грамматика POLL-сценария) — **закрыто**.
- n3 (InProcessTaskWakeBus vs TaskWakeBroadcaster) — **закрыто** (Javadoc-различия в I.5/J.4).
- n4 (D-47…D-58 prenumeration) — **закрыто** (M.3 переносит; см. новый N-5 про D-59).
- n5 (BREAKING-заметка) — **закрыто** («SessionDto — аддитивное расширение (без BREAKING)»).

## Новые находки re-review

### N-1 (minor). ArchUnit: правило M1 запрещает api → task/workflow, а дизайн M2 требует
- **Цитата**: `ArchitectureRulesTest.java:65` — `.whereLayer("api").mayOnlyAccessLayers("execution", "intelligence", "session", "identity")`; design D-49 (J-9): «`WebhookHandlers` — в `api/impl/` … webhook-handler делегирует в `task` через `TaskRegistry`».
- **Проблема**: `api → task` текущим слоёвым правилом запрещён. WebhookHandlers в api/impl (и REST-контроллеры задач K.1, если они идут в `TaskRegistry` напрямую) уронят `moduleLayeringIsRespected` уже в пачке L. tasks M.1 расширяет правило только для `execution → {task, session, workflow}` — про расширение allowed-layers у `api` ни слова.
- **Предложение**: в M.1 (или H.3) явно добавить расширение: `api mayOnlyAccessLayers("execution", "intelligence", "session", "identity", "task", "workflow")` — симметрично тому, как api уже ходит в `session` (M1); зафиксировать в design D-49 одной строкой.

### N-2 (minor). `task_event_seq` не доведён до контракта и модели данных
- **Цитата**: api-contracts §3.2 (не изменён): «`task.transition` (id = id записи истории, он же курсор)»; proposal/tasks D.1/J.4/session-api-дельта: «курсор `since=` — монотонный `task_event_seq` (сквозная нумерация всех событий задачи, включая не-transition)».
- **Проблема**: (а) api-contracts.md §3.2 — источник истины — не синхронизирован с новым курсором; при заморозке спеки D.1 конфликт вылезет в OpenAPI; (б) data-model.md не определяет хранение `task_event_seq` (ни колонки в `task`, ни таблицы событий; in-memory-счётчик не обеспечивает «реконнект без пропусков и дублей» после рестарта, а это обещано в J.4/session-api-дельте).
- **Предложение**: синхронизировать §3.2 (курсор = `task_event_seq`, снапшот = последний `task.status`); в data-model §4 добавить носитель счётчика (например, колонка-счётчик в `task`, инкремент транзакционно с событием) и упомянуть в J.4.

### N-3 (minor). Метод sessions/tree: POST vs GET
- **Цитата**: session-api-дельта: «Дерево сессий `POST /api/v1/sessions/{id}/tree` дополняется…»; tasks D.1/K.1 и proposal:17 — тоже `POST`; api-contracts §2: `GET /api/v1/sessions/{id}/tree`.
- **Проблема**: опечатка метода из первого прохода не исправлена и теперь попала в дельта-спеку — прямое противоречие с источником истины.
- **Предложение**: заменить на `GET` в proposal, tasks D.1/K.1, session-api-дельте.

### N-4 (nit). workflow-engine Purpose отстал от правила 5
- **Цитата**: Purpose: «обязательные TIMEOUT-рёбра у BASH/WAIT» — после фикса M4 правило 5 требует у WAIT_* и ERROR, и TIMEOUT.
- **Предложение**: «обязательные ERROR- и TIMEOUT-рёбра у BASH/WAIT».

### N-5 (nit). tasks M.3: «добавление записи D-59» уже исполнено
- **Цитата**: M.3: «…добавление записи **D-59**…»; decisions.md D-59 уже присутствует (добавлен судьёй сразу, минуя пачку M).
- **Предложение**: переформулировать M.3: «перенос D-47…D-58 + проверка наличия D-59» (иначе на apply будет дубль/конфуз).

## Вердикт re-approval

**REJECT** — 7/7 major закрыты, замысел и архитектура согласованы; незакрытых: **6** (4 minor: m6-частично, N-1, N-2, N-3 + 2 nit: N-4, N-5). Все — механические однострочные фиксы без изменения дизайна; после них — approve без повторного полного цикла (кросс-чек по этому файлу).

---

# Cross-check (2026-09-18) — по ревью DS (m2-deepseek.md) и Mercury (m2-mercury.md)

> Проверка каждой чужой находки: (1) воспроизводимость по артефактам; (2) дубль/не-дубль моего; (3) разночтения DS↔Mercury. Сборки не запускались.

## DeepSeek (первый проход: 21 находка)

| # | Статус | Обоснование |
|---|---|---|
| C-1 | duplicate-of-mine (M6) + валидное расширение | Дельты session-api/agent-turn — мой M6. Часть про незаявленные session-store/workspace-tools — сверх моего, воспроизводится и обоснована. Закрыто: 4 дельты на месте, формат корректный |
| C-2 | valid (мною пропущено) | Подтверждено: 005 уже содержит колонки + `uidx_session__task_id_state_code` (проверено в re-approval). Закрыто: H.1/J.1/Migration Plan — «переиспользовать» |
| H-1 | duplicate-of-mine (M1) | Закрыто (api-contracts §6:127) |
| H-2 | duplicate-of-mine (m7 + M7-плейсхолдер) | Закрыто (единое поведение 202+ERROR, сценарий добавлен) |
| H-3 | duplicate-of-mine (M2) + валидный остаток | Ядро (D-52 vs D-41, нет ADR) — моё M2, закрыто D-59. Остаток «обновить agent-tools.md» — DS сам пометил закрытым, но не перепроверил: строка 31 «Права людей не моделируются (D-41) — гейтов нет» всё ещё противоречит D-59 → моё новое открытие №1 ниже |
| M-1 | duplicate-of-mine (m1) | Закрыто |
| M-2 | valid (мною не было в таком фокусе) | Воспроизводится частично: `TaskEngine`/`TriggerRegistry` не в таблице контрактов architecture.md §3. Разводка InboundTriggers↔TriggerRegistry в D-49 корректна и намеренна (InboundTriggers остаётся для будущих интеграций) — эту половину считаю закрытой; осталась только синхронизация architecture.md → новое открытие №4 |
| M-3 | valid (мною пропущено) | Двойной писатель истории (AgentTurnEngine для BASH/WAIT_*). Воспроизводилось по старому proposal:32; закрыто — формулировка удалена, владелец перехода = `TaskEngine.processTaskTransition` (I.1), AgentTurnEngine только вызывает (J.2) |
| M-4 | valid (мною пропущено) | Roadmap M3 содержал триггеры/вебхуки. Закрыто: roadmap синхронизирован (M2 + «решение владельца»), M3 очищен |
| M-5 | valid (мною пропущено) | Атомарность seed-SYSTEM. Закрыто: session-store-дельта + task-engine §STATE-сессии + J.1 verify |
| M-6 | valid; остаток = duplicate-of-mine (N-2) | Первый проход — справедливо (потока не было в спеках). Re-approval-остаток «нет durable-носителя task_event_seq» подтверждается: data-model не определяет хранение — это ровно мой N-2 (обе стороны: §3.2-sync + носитель). Открыто |
| M-7 | duplicate-of-mine (m2) | Закрыто (H.1) |
| M-8 | valid (мною пропущено) | Курсор `id` vs индекс `(task_id, created_at)`. Исправление в спеке/tasks/design сделано (`(created_at,id)` + `(task_id, created_at, id)`), НО data-model.md:130 всё ещё `INDEX (task_id, created_at)` → новое открытие №2 (подтверждаю DS-остаток) |
| M-9 | valid (мною пропущено) | Метки пачек. Закрыто (D/H/I/J/K/L/M/N; ссылки design валидны) |
| M-10 | duplicate-of-mine (n4, расширенный) | Закрыто через M.3 (перенос на приёмку — приемлемо); остаток «M.3 всё ещё говорит „добавление записи D-59“ при уже записанном D-59» — мой N-5, DS не заметил |
| L-1 | duplicate-of-mine (m3) + валидный остаток | Внутри ченджа закрыто (единая сигнатура). Остаток: agent-tools.md:24 — старая `(target_state_code, reason)` → новое открытие №1 |
| L-2 | duplicate-of-mine (n5) | Закрыто |
| L-3 | duplicate-of-mine (m6) | Открыто у обоих согласованно (proposal §39 без max-per-turn/base-url/kind-timeouts) |
| L-4 | valid (мною пропущено, было в моих черновых заметках) | Имя ключа. Закрыто (везде `task-scheduler`) |
| L-5 | valid (мною пропущено) | H1. Закрыто (у всех 7 спек `# <capability> Specification`) |
| L-6 | valid (мною пропущено) | «12 операций» → «~24». Закрыто (proposal:37) |

### DeepSeek re-approval: новые находки

| # | Статус | Обоснование |
|---|---|---|
| DS N-1 (POST vs GET tree) | duplicate-of-mine (N-3) | Полное совпадение; открытo у обоих |
| DS N-2 (PARKED_CLIENT M3 vs M4) | valid + моё расширение | Подтверждено proposal:17. Дополнительно: «`PARKED_ASYNC` **уже в M1**» — тоже неверно (по session-api-дельте появляется в M3; M1 runtimeStatus = IDLE\|TURN_RUNNING) → новое открытие №3 |
| DS N-3 (ссылки на D-38 вместо D-38/D-59) | valid (nit) | proposal:11 и design Goals:13 цитируют только D-38. Существо не ломают (D-59 записан, D-52 ссылается), но единообразие ссылок стоит привести — присоединяюсь как nit (в мой счёт незакрытых не добавляю отдельно, поглощено m6-классом косметики… нет: фиксирую как nit-открытие, см. итог) |

## Mercury (первый проход: 4 находки)

| # | Статус | Обоснование |
|---|---|---|
| Merc #1 (D-29 idempotency «конфликт») | misread | Пропущен D-41, который **явно** выбросил «идемпотентность-хранилище» — более позднее де-скоуп-решение владельца; M2 корректно ссылается «(D-29/D-41)». Претензия «M2 противоречит D-29» бук¬вально верна, но разрешена существующим ADR-ом; судья (R-1) подтвердил «без хранилища». Я в первом проходе это место проверил и не завёл именно по этой причине |
| Merc #2 (metaTools без ADR) | duplicate-of-mine (M2) / DS H-3 | Закрыто D-59 |
| Merc #3 (нет task-already-terminal в §6) | misread | Код был в api-contracts §6 (строка 126) изначально — я проверял в первом проходе; Mercury сам признал ошибку в Re-approval |
| Merc #4 (7 миграций > «≤1 подсистема») | disagree-with-reasoning | Оценочное суждение: все 7 таблиц — схема одного домена M2, каждая миграция атомарна, ревью-цикл на пачку есть; судья (J-31) принял как риск. Не блокер |
| Merc Re-approval (APPROVED, 0 незакрытых) | disagree-with-reasoning | Проверены только 4 собственные находки; чужие зоны (POST/GET tree, task_event_seq-носитель, конфиг-список, agent-tools/data-model-синки) не_cross-check'нулись. DS-REJECT фактурно прав (см. выше) — approve Mercury поверхностен |

## Разночтения DS ↔ Mercury

1. **Итог re-approval: DS REJECT (8) vs Mercury APPROVE (0)** — прав DS: из его списка M-6 (носитель курсора) и N-1 (POST/GET tree) воспроизводятся мной независимо (мой N-2 и N-3), плюс L-3 подтверждён. Mercury-approve сделан без кросс-чека чужих находок.
2. **D-29 (Mercury) vs молчание DS** — прав «молчащий» DS: конфликт разрешён D-41 существующим ADR-ом, нового решения не требовалось (судья R-1 согласен).
3. **Каталог ошибок: DS H-1 (wrong-transition) vs Mercury #3 (task-already-terminal)** — прав DS: wrong-transition реально отсутствовал (добавлен), task-already-terminal был с самого начала.

## Новые открытия кросс-чтения (не было в моём ревью)

| # | Sev | Находка | Источник |
|---|---|---|---|
| X-1 | minor | `agent-tools.md` не синхронизирован с M2/D-59: строка 24 — старая сигнатура `transition(target_state_code, reason)` (нет taskId/toState/kind, нет гейта); строка 31 — «Права людей не моделируются (D-41) — гейтов нет» противоречит D-59. Обновить при пачке D (каталог M3-инструментов, но противоречие с зафиксированным ADR уже сейчас) | DS L-1/H-3-остатки, подтверждены мной |
| X-2 | minor | `data-model.md:130` — `INDEX (task_id, created_at)` не расширен до `(task_id, created_at, id)` под введённый курсор истории `(created_at, id)` (спека/tasks/design уже согласованы между собой) | DS M-8-остаток, подтверждён |
| X-3 | minor | `proposal:17` — неверные статусы PARKED_*: «`PARKED_ASYNC` уже в M1» (фактически появляется в M3 — session-api-дельта, roadmap) и «`PARKED_CLIENT` зарезервирован в M3» (дельта/roadmap — M4). Правильная формулировка уже есть в session-api-дельте:14 — привести proposal к ней | DS N-2 + моё расширение (PARKED_ASYNC-часть) |
| X-4 | nit | `architecture.md §3` (таблица контрактов) не упоминает `TaskEngine` (execution) и внутренний `TriggerRegistry` (task) — D-49 их фиксирует, дизайн-док отстал. InboundTriggers-часть претензии DS не находка (оставлен намеренно, D-49) | DS M-2-остаток, подтверждён |

## Итог кросс-чека

Мои 6 незакрытых (m6-частично, N-1, N-2, N-3, N-4, N-5) подтверждаются; находки DS пересекаются с моими по существу без противоречий; Mercury — 2 misread (D-29, каталог), 2 дубликата/оценочных, поверхностный re-approve. Добавляется 4 новых открытия (X-1…X-4), все — синхронизация дизайн-доков, без изменения дизайна.

---

# Re-approval 2 (2026-09-18, раунд 2)

## Статус моих находок раунда 1 (одной строкой каждая)

- **m6 (конфиг-список proposal)** — **закрыто**: proposal:39 теперь содержит `max-per-turn` (дефолт 1), `kind-timeouts.{bash,wait-webhook,wait-tasks,agent}.default`, `webhook.base-url`.
- **N-1 (ArchUnit api→task/workflow)** — **закрыто**: tasks M.1 явно расширяет allowed-layers (`api mayOnlyAccessLayers(..., "task", "workflow")`, с обоснованием «иначе HTTP-контроллеры задач/вебхуков не пройдут CI»); design:42 ссылается на architecture.md §2 + задачу M.1.
- **N-2 (task_event_seq: api-contracts §3.2 + носитель в data-model)** — **НЕТ (частично)**: носитель добавлен (data-model:93 — колонка `task.task_event_seq`, инкремент транзакционно с событием), НО api-contracts §3.2 по-прежнему: «`task.transition` (id = id записи истории, он же курсор)» — без `task_event_seq` и без «снапшот = последний task.status». Ровно тот конфликт «источник истины ↔ артефакты», из-за которого находка заводилась: D.1 проектирует OpenAPI по §3.2.
- **N-3 (tree POST→GET)** — **закрыто**: proposal:17, tasks D.1/D.2/K.1, session-api-дельта — везде `GET /api/v1/sessions/{id}/tree`.
- **N-4 (Purpose workflow-engine)** — **закрыто**: «обязательные ERROR- и TIMEOUT-рёбра у BASH/WAIT».
- **N-5 (M.3 ремарк D-59)** — **закрыто**: «+ проверка наличия D-59 (D-59 уже добавлен — не дублировать при переносе)».

## Попутно проверено (кросс-чек-находки X-1…X-4 и DS N-3)

- **X-1 (agent-tools.md)** — **частично**: строка 24 обновлена (`(taskId, toState, kind, reason)` + гейт D-38/supersession D-59), НО строка 31 всё ещё: «Права людей не моделируются (D-41) — **гейтов нет**» — теперь внутреннее противоречие файла (24 против 31) и с D-59. Остаток — одна оговорка.
- **X-2 (data-model индекс истории)** — **закрыто**: data-model:131 — `INDEX (task_id, created_at, id)` + пояснение курсора `(created_at, id)`.
- **X-3 (PARKED_*)** — **закрыто**: proposal:17 — «фактическое присвоение: `PARKED_ASYNC` — в M3, `PARKED_CLIENT` — в M4» (синхронно с session-api-дельтой и roadmap).
- **X-4 (architecture §3)** — **закрыто**: `TaskEngine` (execution, :37) и `TriggerRegistry` (внутренний контракт task, :38) добавлены в таблицу контрактов.
- **DS N-3 (ссылки D-38 → D-38/D-59)** — **частично**: proposal:11 исправлен («D-38; supersession в M2 — D-59»); design Goals:13 — осталось «(D-38: только instructionSource = USER)» без D-59. Nit.

## Вердикт re-approval 2

**REJECT** — незакрытых: **3** (N-2-остаток: одна строка в api-contracts §3.2 — курсор `task_event_seq` + снапшот `= последний task.status`; X-1-остаток: оговорка «гейтов нет» в agent-tools:31; nit: design Goals:13 без D-59). Все три — однострочные синки дизайн-доков, дизайн и спеки ченджа в порядке; после правки — approve без полного цикла.

---

# Re-approval 3 (final) (2026-09-18)

- **api-contracts §3.2 (остаток N-2)** — **закрыто**: «Курсор `since=` — `task_event_seq` (монотонный сквозной номер события задачи; durable в `task.task_event_seq`, инкремент транзакционно с эмиссией; см. data-model §4)» — источник истины синхронизирован с артефактами и data-model:93. N-2 закрыт полностью.
- **agent-tools.md:31 (остаток X-1)** — **закрыто**: «гейтов нет» убрано; добавлено «В M2 действует metaTools-гейт (D-59): инструмент `transition` разрешён только при `instructionSource = USER`…» — внутреннего противоречия со строкой 24 больше нет.
- **design.md Goals:13 (остаток DS N-3)** — **закрыто**: «(D-38: только `instructionSource = USER`; metaTools-гейт — D-59, supersession в M2)».

## Вердикт re-approval 3 (final)

**APPROVE** — незакрытых: 0. Все 7 major первого прохода, 12 minor, 5 nit, 4 находки кросс-чека (X-1…X-4) и 3 sync-остатка раунда 2 закрыты; план (proposal/design/tasks + 7 спек) согласован с дизайн-доками и ADR (D-41/D-59), готов к заморозке и apply.
