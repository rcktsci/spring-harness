# Ревью D.1: OpenAPI-подмножество M2 (contract-first, шаг 1) — GLM-5.3-Flash

Дата: 2026-09-18. Объект: `src/main/resources/api/openapi.yaml` (2454 строки, version 1.1.0-m2).
Контекст: api-contracts v3 (§0–§6, включая обновлённые §3.2/§6), спеки ченджа m2-workflow-engine (7 дельт), план (design D-47…D-59, tasks D.1/D.2).

**Вердикт: REJECT** — 7 находок (3 minor / 4 nit). Покрытие и типизация в целом добротные; блокируют не они, а три неточности контракта (см. M1–M3) — всё чинится однострочно до заморозки.

---

## Проверка по пунктам постановки

### (1) Покрытие эндпоинтов D.1 — ✅ полное
tasks: POST/GET `/tasks`, GET/PATCH `/tasks/{id}`, `/subtasks`, `/suspend`, `/resume`, `/stop`, `/dependencies` (POST), `/dependencies/{blockerTaskId}` (DELETE), `/history`, `/comments` (GET+POST), `/tree`, `/events` — все на месте. workflows: GET/POST `/workflows`, GET `/{key}`, POST/GET `/{key}/revisions`, GET `/{key}/revisions/{rev}` — все. triggers: POST/GET `/triggers`, DELETE `/{id}`. webhooks: оба POST. M1-секции: agents, sessions CRUD, messages, compact, stop, events, tree — на месте. Схемы из D.1 (TaskDto, TransitionDto, CommentDto, TaskTreeNode, WorkflowDto, WorkflowRevisionDto, TriggerDto, WebhookError) — все присутствуют + якоря SSE-кадров.

### (2) Схемы — ✅ с оговорками (см. находки)
Поля/типы/enum сверены с api-contracts §2/§4 и дельтами: TaskDto (author required+nullable — ок при openApiNullable=false), TransitionDto (id + пояснение «SSE id — task_event_seq, не этот id» — корректно разводит курсор и идентификатор записи), SessionDto (+taskId/stateCode только STATE, runtimeStatus 4 значения с фазами присвоения M3/M4), SessionTreeNode (+taskId/stateCode), WorkflowGraph/WorkflowState/WorkflowTransition (agent_key snake_case задокументирован как унаследованный от графа; condition enum; scope — параметрическая строка, enum невозможен — оправдано), TriggerDto (+revokedAt nullable), WebhookError/WebhookProblemCode (подмножество каталога без unauthenticated — корректно: на вебхуках JWT нет).

### (3) SSE — ✅ с одной двусмысленностью (M3)
`/tasks/{id}/events`: события task.transition/task.status/subtask.terminal/task.comment + ping (конфиг-интервал, retry 5000) — состав совпадает с api-contracts §3.2 и tasks J.4; курсор `since` int64 = task_event_seq, Last-Event-ID приоритетнее; снапшот = последний task.status; схемы кадров — «машинный якорь», тело не типизируется. `/sessions/{id}/events` — M1-семантика сохранена (seq, снапшот session.status, ping).

### (4) Каталог ошибок — ✅
ProblemCode = 9 кодов M1 + 11 кодов M2, из новых только `wrong-transition` (409) — ровно как зафиксировано (proposal: «единственный новый код»). Response-компоненты покрывают все коды; WrongTransition честно помечен «достигается инструментом агента, не REST-операцией».

### (5) Вебхуки без /v1, без JWT — ✅
Оба webhook-путь: path-item `servers: [url: /api]` (перекрывает корневой `/api/v1`), `security: []` на операциях; токен в пути (WebhookToken), 401 signature-invalid / 409 task-not-waiting-webhook (включая «задачи нет») / 410 trigger-revoked — по спекам; trigger-webhook без тела, ответ `202 {taskId}` (TriggerWebhookAccepted).

### (6) merge-patch RFC 7396 — ✅
PATCH /sessions/{id} и /tasks/{id}: content `application/merge-patch+json`, additionalProperties: true, nullable-поля для очистки (title/description/tags → null), params-в-патче → 422 rule=immutable (UpdateTaskRequest), семантика описана. Соответствует §0.6 и task-engine §PATCH.

### (7) openApiNullable=false в pom — ✅
`pom.xml`: опция выставлена в обоих executions (spring interfaceOnly сервер + java native тест-клиент), inputSpec — `src/main/resources/api/openapi.yaml`; jackson-annotations provided, jackson-databind/jsr310 test-scope — AGENTS.md соблюдён (проверено по pom, без сборки).

### (8) Совместимость с M1 — ✅
Аддитивность соблюдена: MessageDto не меняется (late помечен «в M1 не возвращается»), agents/messages/commands без изменений, enum-расширения (SessionKind +STATE, runtimeStatus +PARKED_*) и новые поля taskId/stateCode — объявлены в дельте session-api; новый GET /sessions/{id}/tree соответствует дельте; `?kind=`-фильтр и конверт-пагинация — в форме M1.

### (9) 12 отклонений dev — ⚠️ отчёт не найден как файл
Отдельного файла-отчёта dev с 12 отклонениями в репозитории нет (search по docs/, openspec/, api/ — единственная недавняя ссылка «12 отклонений» — в ревью Mercury со ссылкой на m2-judge.md J-1…J-31). Оценка выполнена независимой сверкой с api-contracts §4/§3.2 и спеками M2. Идентифицированные отклонения и статус:
- `blockerTaskId` вместо `{blockerId}` (§4.1) — улучшение camelCase-консистентности (§0.8), принято;
- курсор истории — opaque-пара `(createdAt, id)` вместо `?since=<seq>` (§0.4) — санкционировано task-engine-спекой (новее контракта), принято;
- `WorkflowState.agent_key` snake_case — унаследован контрактом графа, задокументирован в схеме, принято;
- `timeout` — ISO-8601 duration — формат не был определён нигде, выбор разумный, принято;
- WrongTransition-компонент без REST-операции — документирован, принято;
- WorkflowDto двухформенный (list без revisions / get с revisions) — задокументировано, принято;
- WebhookProblemCode-подмножество — корректно, принято;
- SSE: тело не типизируется, generated-but-unused метод — задокументировано, принято;
- author required+nullable (TaskDto/CommentDto/TaskCommentEvent) — последовательный паттерн, принято;
- POST /workflows дубликат key → 422 validation-failed rule=key-unique (не 409) — каталог §6 не имеет 409-conflict, принято;
- `SuspendTaskRequest.cascade` required (строже §4.1) — см. nit N2;
- отсутствие тел у 202-команд (schema: {}) — допустимо.
Возражения — только в находках M1–M3 и N1/N3 ниже.

---

## Находки

### M1 (minor). Вебхук задачи: requestBody required=true — поведение при отсутствии тела не оговорено
- **Цитата**: `/webhooks/tasks/{taskId}/{token}` → `requestBody: required: true`, «Произвольный JSON-payload вебхука»; при этом ответ 415 unsupported-media-type присутствует, а спека inbound-triggers говорит «**Если** `state.payloadSchema` объявлен — валидировать body» (условная валидация).
- **Проблема**: для WAIT_WEBHOOK-состояния без payloadSchema внешняя система, шлющая POST без тела (или без Content-Type), получит 415 — но контракт этого нигде не проговаривает; в дельте сценарий «body есть/нет» не зафиксирован. Замораживаемый контракт не должен определять это неявно через 415.
- **Предложение**: в description операции зафиксировать одно: либо «тело обязательно; POST без тела/Content-Type → 415 unsupported-media-type», либо сделать `required: false` с оговоркой «пустой payload → reason {payloadSummary: null}». Первое проще и согласуется с required=true.

### M2 (minor). ProblemDetail.errors: «Только для 422 validation-failed» — неточно
- **Цитата**: schema ProblemDetail, `errors.description`: «Только для 422 validation-failed.»
- **Проблема**: по §0.2 errors[] сопровождает все 422-кода; в этой же спеке GraphInvalid/DependencyInvalid/ParamsSchema-ответы ссылаются на ProblemDetail и явно обещают errors[] (description responses: «errors[] {pointer, rule, message}»). Описание поля противоречит трём же response-компонентам файла — генератор/клиент получат вводящее в заблуждение doc.
- **Предложение**: description → «Для 422-кодов: validation-failed, graph-invalid, dependency-invalid, params-schema».

### M3 (minor). Семантика `since=0`/отсутствие для task-events двусмысленна
- **Цитата**: `/tasks/{id}/events` param since: «task_event_seq — события с seq > since (интервал (since, …]); **0 или отсутствие — со снапшота**». Для сравнения session-events: «0 или отсутствие — **с начала журнала**».
- **Проблема**: «со снапшота» не определяет, получает свежий клиент весь backlog с seq=1 или только live-события после снапшота. Сценарий дельты session-api («первым снапшот, далее события с task_event_seq > since») подразумевает интервал, но для нового клиента (since отсутствует) это вырождается в «всё с 1» — для долгоживущей задачи неожиданно большой backlog; либо наоборот — пропуск истории, что сломает «догоняющего» клиента. Замораживать двусмысленность нельзя.
- **Предложение**: выбрать и записать явно: «отсутствие/0 — снапшот + полная история с seq=1 (интервал (0, …]); клиент, которому нужен только live, передаёт task_event_seq из снапшота» (и добавить в снапшот-кадр task.status поле taskEventSeq — см. ниже, опционально).
- **Опциональное усиление**: TaskStatusEvent (снапшот) сейчас не несёт текущий `task_event_seq` — клиенту после снапшота не с чего стартовать live-подписку, кроме как с 0/Last-Event-ID. Рассмотреть `taskEventSeq` в кадре task.status (аддитивно, ломать ничего не ломает).

### N1 (nit). WorkflowWorkspace: описание дефолта AUTO расходится с формулировкой спеки
- **Цитата**: yaml: «mode — AUTO (workspaces/tasks/{taskId}) или PATH (явный path)»; task-engine §BASH_SCRIPT: «`workspace = { type: SERVER_DIR, mode: PATH, path: ${task.id} }` (по умолчанию)».
- **Проблема**: семантически это одно и то же место на диске, но два разных формальных описания дефолта — читатель контракта и читатель спеки получат разные представления о том, что движок подставляет по умолчанию.
- **Предложение**: в description mode=AUTO дописать «эквивалентно mode=PATH, path=${task.id}» (или наоборот в спеке) — одна строка.

### N2 (nit). SuspendTaskRequest.cascade — required без обоснования
- **Цитата**: `required: [cascade]` + description «cascade=true — дополнительно приостановить всё поддерево».
- **Проблема**: api-contracts §4.1 показывает `{ cascade }` без признака обязательности; требовать явный boolean заставляет всех клиентов указывать поле. Если это осознанное отклонение (защита от «случайного» каскада) — оно нигде не зафиксировано.
- **Предложение**: либо `default: false` + required убрать, либо добавить в description «каскад всегда явный — осознанное отклонение от §4.1».

### N3 (nit). WorkflowTransition.kind допускает CANCEL во входном графе
- **Цитата**: `kind: $ref TransitionKind` (enum включает CANCEL); description: «CANCEL в графе не обязан (служебный движковый)».
- **Проблема**: «не обязан» ≠ «запрещён»: по схеме граф с CANCEL-ребром валиден, а правило 8 workflow-engine не определяет его статус при сабмите. Валидатор получит свободу трактовки.
- **Предложение**: в description `kind`/`WorkflowTransition` зафиксировать: «CANCEL-рёбра в присылаемом графе невалидны → 422 graph-invalid (rule=cancel-edge-forbidden)» — либо симметрично разрешить и описать семантику.

### N4 (nit). pom-комментарий генератора отстал от M2
- **Цитата**: pom: «SSE-операция (тег SessionEvents) исключена из генерации…» — TaskEvents в комментарии не упомянут, хотя в спеке оба SSE-эндпоинта помечены generated-but-unused.
- **Предложение**: при D.2 дополнить комментарий («SessionEvents + TaskEvents»), иначе следующий ревьюер pom'а будет искать механизм исключения, которого нет.

---

## Что сделано хорошо

- Развязка «SSE id = task_event_seq» vs «TransitionDto.id = id записи истории» проведена последовательно во всех трёх местах (параметр since, описание TransitionDto, TaskTransitionEvent) — частая путаница предотвращена.
- Якорные схемы SSE-кадров при нетипизируемом теле — правильный компромисс для ручного SseEmitter.
- WebhookProblemCode-подмножество (без unauthenticated) — аккуратная работа с «недостижимыми» кодами на без-JWT поверхностях.
- `'$'-префиксы зарезервированы` в WorkflowState.code — профилактика коллизии с `$CANCELLED`.
- Все числа вынесены (limit — «верхняя граница — конфиг», ping — конфиг) — правило «хардкод чисел запрещён» соблюдено.

## Вердикт

**REJECT** — 7 находок (3 minor / 4 nit). Все — точечные правки описаний/одной строки схем, дизайн и покрытие не страдают; после фиксов — re-approve без полного цикла.

---

# Re-approval D.1 (2026-09-18, после F-1…F-9 + GLM-фиксов)

## Статус моих 7 находок

- **M1 (тело вебхука)** — **закрыто**: yaml:1097–1098 — «Тело обязательно — POST без тела или без Content-Type application/json → 415 unsupported-media-type (до проверки состояния)»; requestBody required=true теперь подкреплён явным контрактом (apply-notes: F-9+GLM M1).
- **M2 (ProblemDetail.errors)** — **закрыто**: yaml:1746 — «Для 422-кодов — validation-failed, graph-invalid, dependency-invalid, params-schema».
- **M3 (семантика since=0)** — **закрыто по существу**: yaml:821/832–834 — «Отсутствие/0 `since` — снапшот + полная история задачи (интервал (0, …])». См. новую находку N-5 ниже — фикс принёс хвост.
- **N1 (AUTO-дефолт)** — **закрыто**: yaml:2283–2285 — `default: AUTO`, «AUTO эквивалентно PATH, path=${task.id}» — формулировки сведены к спеке.
- **N2 (cascade required)** — **отклонено судьёй, принимаю**: аргумент судьи корректен (J-22 касался stop — там каскад безусловный; у suspend явный `required: [cascade]` — осознанная явность, контракт самодокументируем). Претензия была nit-уровня о документировании — снимаю.
- **N3 (CANCEL-рёбра в графе)** — **закрыто**: yaml:2307–2309 — «graph-invalid (rule=cancel-edge-forbidden)… CANCEL-рёбра в графе не объявляются», enum переходов графа без CANCEL.
- **N4 (pom-комментарий)** — **закрыто отложкой** (совпадает с моим же предложением): apply-notes п.12 — расширение `<apis>`/комментария выполняется на шаге D.2, зафиксировано.

Попутно подтверждено: F-7 (`blockerId` — возврат к неймингу api-contracts §4.1, yaml:673/1213), F-1 (курсор истории выровнен в трёх источниках, включая api-contracts §0.4), F-5 (CommentDto.author nullable вне required), F-4 (вебхук-ошибки 406/413/415 — ProblemDetail). apply-notes.md создан, отклонения/решения зафиксированы.

## Новая находка re-approval

### N-5 (minor). Совет «передать task_event_seq из снапшота» неисполним — в снапшоте нет этого поля
- **Цитата**: yaml:832–834 (param since): «…клиенту, которому нужен только live, следует передать task_event_seq **из снапшота**»; схема TaskStatusEvent (снапшот-кадр): `required: [taskId, currentState, statusProjection, suspended]` — поля `taskEventSeq` нет.
- **Проблема**: live-клиент не может стартовать без текущего seq: в снапшоте его нет, full-history путь (since=0) для него избыточен. Совет в замораживаемом контракте ссылается на несуществующие данные.
- **Предложение** (одно из двух, одна строка): (а) добавить в TaskStatusEvent `taskEventSeq` (int64, «task_event_seq на момент кадра; в снапшоте — точка старта live-подписки») — это же было моим опциональным усилением в исходном M3; (б) либо убрать совет из описания since (live-клиент = since=0 + полная история).

## Вердикт re-approval

**REJECT** — 1 новая находка (N-5, minor, однострочный фикс). Остальные 6/7 закрыты, отклонение N2 судьёй принято. После N-5 — approve.

---

# Re-approval 2 D.1 (2026-09-18, final)

- **N-5 (task_event_seq в снапшоте)** — **закрыто**: TaskStatusEvent получил `taskEventSeq` (required, int64, «в снапшоте — отправная точка для реконнекта»); описание since-параметра теперь опирается на реальное поле — совет live-клиенту исполним.
- **DS nit (CANCEL в rule 8)** — **закрыто**: workflow-engine spec, правило 8 — «`transition.kind ∈ { NEXT, ERROR, TIMEOUT }`; CANCEL зарезервирован для движка stop…; CANCEL-рёбра в присылаемом графе невалидны → `422 graph-invalid` (rule=cancel-edge-forbidden)» — синхронно с yaml (enum без CANCEL + rule=cancel-edge-forbidden). (Строка 82 «Контракт графа» сохраняет `…|CANCEL` как дословную цитату workflow-domain §2 — операционным является правило 8; противоречия нет.)

## Вердикт re-approval 2 (final)

**APPROVE** — незакрытых: 0. Все находки GLM (7 + N-5) закрыты либо сняты судьёй (N2); спека openapi.yaml согласована с api-contracts и дельтами M2 — заморозка возможна, шаг D.2 (генерация) разблокирован.
