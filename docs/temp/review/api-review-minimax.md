# API Review: api-contracts.md (reviewer: minimax-m2.7)

## Сводка замечаний

| Severity | Count |
|---|---|
| CRITICAL | 2 |
| MAJOR | 5 |
| MINOR | 7 |

---

## CRITICAL

### CR-1: Нет API для «дописать субагенту»

**Место:** отсутствует в §1

**Описание:** Glossary §5 и execution-model.md §4 описывают `spawn_subagent` как синхронный вызов: родительский поток ждёт. Однако в модельном цикле (execution-model.md §3 п.3) сказано, что во время хода могут прийти новые события (юзер, поздний результат) — и агент их видит. Клиент, подключившийся к подсессии через SSE `?since=`, получает поток событий подсессии, но **не имеет способа написать в неё**: `POST /sessions/{subagentId}/messages` не описан, а `PARTICIPANT`-проверка на share-уровне невозможна без шаринга.

**Предложение:** Добавить `POST /api/v1/sessions/{id}/messages` для subagent session id со следующими правилами: (a) сессия должна иметь `parent_session_id != null`; (b) автор — атрибуция из родительского вызова spawn_subagent; (c) результат — `202` + событие в SSE потоке подсессии. Право — только из родительской сессии-владельца (оркестратор/агент).

---

### CR-2: `/sessions/{id}/tree` — право не определено

**Место:** §1, строка 32

**Описание:** Эндпоинт возвращает поддерево сессий (включая субагентские). Право указано `VIEW`. Однако attach к поддереву по дереву — это фактически доступ к субагентским сессиям, которые могут содержать sensitive данные. Для подсессий может не быть отдельного share. Правило `AccessPolicy` из glossary §1 проверяет только явно выданные shares; косвенный доступ через `tree` не описан как разрешённый.

**Предложение:** Уточнить право: либо `tree` доступен только если все узлы поддерева покрыты явно выданными `VIEW`-грантами (т.е. `AccessPolicy` рекурсивно проверяет), либо ограничить `tree` только владельцем корневой сессии. Добавить в спеку явное правило.

---

## MAJOR

### MA-1: `compact` возвращает 202, но это синхронная операция

**Место:** §1, строка 28: `POST /api/v1/sessions/{id}/compact → 202`

**Описание:** glossary §4 говорит: «`/compact` — та же механика [страховочной компакции] по команде для свободных сессий». Страховочная компакция (§5 execution-model) — внутренняя, «система сама сворачивает старые сообщения» и раунд **продолжается** (new round). Это синхронное действие в рамках Turn. Однако спека говорит `202 Accepted` — как у асинхронных действий. Семантика 202 implies the server may process later, but compaction completes in the same Turn synchronously.

**Предложение:** Изменить на `200 SessionDto` (компакция выполнена, возвращаем обновлённое состояние). Если компакция может быть вызвана на FREE-сессии без активного Turn — это всё равно CPU-лёгкая операция (COMPACT-событие пишется в append-only поток), нет основания для 202.

---

### MA-2: `visibility` enum не определён, противоречит data-model

**Место:** §1, SessionDto; data-model.md §5 session.visibility

**Описание:** data-model.md §5 определяет `visibility` как `enum PRIVATE | PUBLIC`. api-contracts.md §1 определяет `visibility?` в CreateSession и PATCH, но **enum не указан**. При этом glossary §1 говорит «плюс точечные share» — PRIVATE означает только owner, PUBLIC — все. В acl-стиле это два бита, но enum `PRIVATE | PUBLIC` не позволяет выразить «только specific users via share».

**Предложение:** Указать в спеке enum `visibility: PRIVATE | PUBLIC`. Точечные share — отдельный механизм (секция shares), не часть visibility. Это консистентно с data-model.

---

### MA-3: `shares` — нет PATCH, неясно как менять уровень

**Место:** §1, строка 31: `GET/POST/DELETE /api/v1/sessions/{id}/shares`

**Описание:** Share имеет уровень `VIEW | PARTICIPATE`. Если нужно изменить уровень (VIEW → PARTICIPATE), REST-практика — `PATCH /sessions/{id}/shares/{shareId}` с `{ level: "PARTICIPATE" }`. Текущая спека имеет только GET/POST/DELETE, но DELETE+POST = не idempotent для смены уровня. Кроме того, DELETE без body не позволяет удалить конкретный share если subjectId не уникален (например, несколько group-шарингов).

**Предложение:** Добавить `PATCH /api/v1/sessions/{id}/shares/{shareId}` с телом `{ level: "VIEW"|"PARTICIPATE" }`.

---

### MA-4: Входящий webhook для WAIT_TASKS не описан

**Место:** отсутствует в §3

**Описание:** workflow-domain.md §3 описывает WAIT_TASKS: задача ждёт подзадачи, scope-выражения `ALL_CHILDREN | BLOCKED_BY | TAGGED | EXPLICIT`. workflow-domain.md §4 показывает барьерный паттерн как GitLab CI stages. glossary §3 WAIT_TASKS — опциональный таймаут. **Однако в api-contracts.md нет эндпоинта, который внешняя система могла бы вызвать чтобы «доложить» о завершении задачи, блокирующей текущую.** В blling-сценарии (§5 workflow-domain) оркестратор создаёт подзадачи и расставляет blocked_by — но как внешняя система узнаёт что задача X завершена? Предполагается что движок сам мониторит, но это неexplained.

**Предложение:** Добавить `POST /api/webhooks/tasks/{taskId}/unblock?signature=...` — stateless webhook, который принимает `{ blockedTaskId, result? }` и продвигает задачу-блокер если это в рамках WAIT_TASKS scope. Либо документировать что WAIT_TASKS управляется только внутренними событиями (подзадачи создаются через API/оркестратор, не внешними системами).

---

### MA-5: Trigger как сущность — необходимость не подтверждена, но уже в API

**Место:** §3, строка 86–92

**Описание:** api-contracts.md §6 «Открытые концептуальные вопросы» п.3 спрашивает: «Триггер как сущность (trigger-таблица добавляется в data-model) — подтверждаете необходимость сейчас?». При этом сам trigger endpoint уже описан в §3. data-model.md не содержит таблицы `trigger`. glossary §7 говорит что stateless webhook endpoint один, а Trigger логически — это настройка (оркестратор настраивает workflowKey + params). Если trigger — только конфигурация которая генерирует URL, она может жить в task.params, а не отдельной сущностью. Но в API она уже есть.

**Предложение:** Либо убрать Trigger как ресурс из API (хранить в task params), либо подтвердить и добавить таблицу в data-model.md. Сейчас это противоречие между вопросом в §6 и наличием endpoint.

---

## MINOR

### MI-1: `MessageDto.author` не определён в спеке

**Место:** §1, строка 36

**Описание:** `MessageDto` содержит `author?` без типа и без описания. data-model: USER-сообщения имеют `author_user_id`, ASSISTANT/SYSTEM — NULL. Какой тип у `author`? Username string? User ID? Когда null?

**Предложение:** Добавить `author?: string` с комментарием: username для USER-сообщений, null для остальных.

---

### MI-2: `TaskDto.author` отсутствует несмотря на data-model

**Место:** §3, строка 72

**Описание:** data-model.md §4 task имеет `author_user_id` (может быть NULL для агентских задач). TaskDto в спеке не содержит `author`. Для оркестратора важно знать, кто создал задачу.

**Предложение:** Добавить `author?: string` в TaskDto.

---

### MI-3: `compact` — нет параметра что именно компактировать

**Место:** §1, строка 28

**Описание:** glossary §5 «селективная компакция» зарезервирована как future. SPEC (§1) предлагает только страховочную `/compact`. Но в execution-model §5 сказано что COMPACT events уже first-class. Если агент захочет выборочно сжать конкретные сообщения (селективная компакция), API не даёт механизма.

**Предложение:** Добавить параметр `POST /sessions/{id}/compact` с телом `{ ranges: [{since, until}] }` или пометить эндпоинт как «system-driven only» если селективная компакция不在 MVP scope.

---

### MI-4: Идемпотентность на `POST /sessions/{id}/messages` — что в теле?

**Место:** §1, строка 24; §0 правило 3

**Описание:** Правило §0.3 говорит `Idempotency-Key` обязателен на `POST .../messages`. Но тело запроса `POST /sessions/{id}/messages` содержит `{ text, attachments? }`. Что происходит при повторной отправке того же key с другим текстом? Каталог ошибок говорит `idempotency-conflict` (409) — но это правило относится к POST без id. Для messages replay-safe, `text` тоже должен совпадать или игнорироваться.

**Предложение:** Уточнить в спеке: при `Idempotency-Key` дубликат возвращает последнийseq без изменений (идемпотентный read). При том же key но другом text — 409 `idempotency-conflict`.

---

### MI-5: `runtimeStatus` в SessionDto не включает все значения из glossary

**Место:** §1, строка 34

**Описание:** SessionDto: `runtimeStatus: IDLE | TURN_RUNNING | PARKED_ASYNC`. glossary §4 определяет дополнительно `CANCELLED` (cancel_requested ведёт к этому статусу). Также из execution-model §2: Turn выходит с статусом CANCELLED. Отсутствие CANCELLED в enum — пробел.

**Предложение:** Добавить `CANCELLED` в `runtimeStatus` enum.

---

### MI-6: SSE `session.status` — что такое `lockedBy`?

**Место:** §2, строка 48

**Описание:** Событие `session.status` содержит `{ runtimeStatus, lockedBy? }`. glossary §4 определяет `locked_by` как идентификатор инстанса-владельца лока. Но в клиентском SSE потоке это бессмысленная техническая деталь. К тому же glossary §9 инвариант говорит что visibility проверяется через AccessPolicy, а lock — внутренняя деталь движка.

**Предложение:** Убрать `lockedBy` из публичного `session.status` события. Если клиенту нужно знать кто держит лок — это служебная информация для debugging, не для UI. Или переименовать в `lockedByInstance?: string` с комментарием «для diagnostic».

---

### MI-7: `POST /api/v1/triggers` — params не типизированы

**Место:** §3, строка 90

**Описание:** Trigger создаёт задачу на workflow триггера. Workflow определяет `params_jsonb` schema через JSON-Schema-контракт в workflow_revision.graph. При создании trigger можно передать `params?` — но какую схему ожидать? Нет ссылки на schema endpoint.

**Предложение:** Добавить `GET /api/v1/workflows/{key}/revisions/{rev}` (уже есть, но не упоминается в trigger flow). В документации trigger указать что `params` валидируется по JSON-Schema ревизии workflow и при невалидном params返回 422 с деталями.

---

## Round 2: результаты проверки v2

### 1. Статус своих замечаний раунда 1

| ID | Замечание | Статус в v2 | Где зафиксировано |
|---|---|---|---|
| CR-1 | Нет API «дописать субагенту» | **FIXED** | §2: «POST /messages на любую сессию дерева»; наследование прав по дереву сессий |
| CR-2 | `/tree` — право не определено | **FIXED** | §2: VIEW на корень, права наследуются по дереву; §2 explicitly описывает механизм «проваливания» |
| MA-1 | `compact` → 202 но синхронная | **NOT ADDRESSED** | §2 всё ещё `→ 202`; D-17 не пересмотрен; компакция внутри Turn — всё ещё 202 |
| MA-2 | visibility enum не определён | **FIXED** | §2 SessionDto: `visibility: PRIVATE\|PUBLIC` |
| MA-3 | shares нет PATCH | **FIXED** | §2: `PATCH /shares/{shareId}` добавлен |
| MA-4 | Нет webhook для WAIT_TASKS | **WONT-FIX-ACCEPTED** | WAIT_TASKS управляется внутренними событиями (subtasks/dependencies); внешняя система не должна дёргать; обоснование принято |
| MA-5 | Trigger не подтверждён | **FIXED** | D-25: trigger-таблица принята; data-model §6 обновлён |
| MI-1 | author не типизирован | **FIXED** | §2: `author?: string (username; только USER)` |
| MI-2 | TaskDto.author отсутствует | **FIXED** | §4.1 TaskDto: `author?: string` |
| MI-3 | compact без параметров | **WONT-FIX-ACCEPTED** | §8: селективная компакция явно в scope v1-out; D-17 не пересмотрен; принято |
| MI-4 | Идемпотентность семантика | **FIXED** | §0.3: «тот же ключ + то же тело → исходный ответ (replay-safe)»; TTL 24ч; D-29 добавляет хранилище |
| MI-5 | runtimeStatus неполный | **FIXED** | §2: добавлены PARKED_CLIENT, lastTurnOutcome (COMPLETED/FAILED/CANCELLED) |
| MI-6 | lockedBy в SSE | **FIXED** | §3.1: session.status без lockedBy; только runtimeStatus + lastTurnOutcome |
| MI-7 | Trigger params не типизированы | **FIXED** | §4.3: params валидируются по JSON-Schema ревизии, 422 при невалидном |

**Итого: 9 FIXED / 2 WONT-FIX-ACCEPTED / 1 NOT ADDRESSED**

---

### 2. Новые проблемы v2

#### NEW-MIN-1: Противоречие D-29 vs data-model.md §6

**Severity:** MAJOR

**Место:** D-29 (decisions.md) vs data-model.md §6

**Описание:** D-29 фиксирует решение: «идемпотентность POST — хранилище idempotency_key (TTL 24 ч)». При этом data-model.md §6 (датированная тем же днём) утверждает: «Отдельных таблиц нет: вебхуки — stateless». D-29 явно вводит таблицу `idempotency_key`, а data-model это отрицает. Это прямое противоречие между двумя синхронизированными документами.

**Предложение:** data-model.md §6 должна быть обновлена: добавить описание таблицы `idempotency_key (id, key, session_id, response, expires_at)` с TTL-очисткой, сноску что для stateless вебхуков хранилище используется только для replay-protection, а не для сущностей.

---

#### NEW-MIN-2: MA-1 остался без внимания — compact → 202

**Severity:** MAJOR

**Место:** §2, строка 54: `POST /sessions/{id}/compact → 202`

**Описание:** Изменение v2 не затронуло этот пункт. Компакция по-прежнему возвращает 202, хотя samaя спека §2 описывает её как «команда; результат — COMPACT-событие в потоке (внутри активного Turn — на границе раунда)». Это синхронное действие в рамках Turn; 202 создаёт ложное ожидание.

**Предложение:** Изменить на `200 SessionDto` (компакция выполнена, seq увеличился на 1). Это консистентно с поведением fork (201), rewind (204), stop (202 — асинхронная отмена). Компакция — не асинхронная операция.

---

### 3. Кросс-проверка коллег

#### GLM C-1 (task authorization undefined) → **already-covered-v2**
D-28 + §4.1 + §7矩阵 полностью определяют модель: TASK-VIEW/PARTICIPATE, Share(TASK), STATE inherits from task.

#### GLM C-2 (no webhookUrl) → **already-covered-v2**
§4.1 TaskDto содержит `webhookUrl?` заполняемый в WAIT_WEBHOOK.

#### GLM C-3 (idempotency no storage) → **disagree** (с обоснованием)
GLM утверждает что хранилища нет. D-29 явно вводит таблицу `idempotency_key`. D-29 принят и подписан архитектором. Претензия GLM была валидна для v1, но D-29 её снимает. **Однако** — NEW-MIN-1: data-model.md §6 противоречит D-29, т.е. на уровне документов противоречие осталось.

#### GLM M-7 (browser SSE/WS no auth) → **already-covered-v2**
§1.4 ticket-механизм; §5 WS использует ticket.

#### GLM M-8 (webhook signature in URL) → **disagree** (D-26 accepted threat model)
Схема изменилась: `HMAC(secret, kind + ':' + entityId)` vs старый `HMAC(secret, taskId)`. Паттерн — capability URL (unsubscribe-style). D-26 explicitly принял угрозы (URL в логах, бесконечный replay через Idempotency-Key, нет покрытия тела) как осознанный компромисс. **Это архитектурное решение владельца, не дефект.** Балл снимается.

#### GLM остальные MA → **already-covered-v2** (M-1,M-2,M-3,M-4,M-5,M-6,M-9,M-10,M-11,M-12,M-13)

#### DeepSeek C-1/C-2 (task auth) → **already-covered-v2** (D-28)

#### DeepSeek C-3 (webhook body not covered) → **disagree** (D-26 accepted threat model)
Аналогично GLM M-8. D-26 принял осознанно: подпись тела невозможна т.к. внешний отправитель не владеет секретом. Архитектор принял угрозу. **DISPUTE: коллеги правы технически, но аргумент «D-26 — компромисс» имеет силу.**

#### DeepSeek C-4 (две схемы webhook) → **already-covered-v2**
v2 использует `/api/webhooks/tasks/{taskId}/{token}` и `/api/webhooks/triggers/{triggerId}/{token}`. Единая схема из D-05 трансформировалась в два эндпоинта с общей capability-логикой. glossary и workflow-domain синхронизированы в v2.

#### DeepSeek C-5 (includeHidden раскрывает) → **already-covered-v2**
§2: `includeHidden=true — только владелец`. §7 матрица: «includeHidden — владелец». Исправлено.

#### DeepSeek MA items → все **already-covered-v2** кроме:
- M-7 (caching/rate-limit): **already-covered-v2** (§0.9-10)
- M-9 (relay errors): **already-covered-v2** (§5 error frames + close codes)
- M-14 (export POST): **disagree** (D-26 explicitly accepted POST for export; не дефект)
- M-18 (versioning): **already-covered-v2** (§0.5, §9 закрывает вопрос)

---

### Итоговая сводка Round 2

| Категория | Кол-во |
|---|---|
| Round 1: FIXED | 9 |
| Round 1: WONT-FIX-ACCEPTED | 2 |
| Round 1: NOT ADDRESSED | 1 (MA-1: compact→202) |
| Новые находки | 2 (NEW-MIN-1: D-29↔data-model противоречие; NEW-MIN-2: compact всё ещё 202) |
| DISPUTE с коллегами | 2 (GLM-C3, DeepSeek-C3 — webhook body coverage; DeepSeek-M14 — export POST) |

**Топ-3 оставшихся проблем:**
1. **NEW-MIN-1** — Противоречие D-29 vs data-model §6 по idempotency_key: таблица принята в решениях, но отсутствует в модели данных. Блокирующий дефект документации.
2. **MA-1 (NOT ADDRESSED)** — `compact` по-прежнему возвращает `202` для синхронной операции; D-17 не пересмотрен. Стилистический дефект, но не блокирующий.
3. **DISPUTE: webhook body coverage** — GLM и DeepSeek настаивают что подпись должна покрывать тело. D-26 принял альтернативу (capability URL). Аргумент коллег технически сильнее, но решение владельца зафиксировано — рекомендация: зафиксировать D-26 как «accepted risk» в security-документе, не в спеке.

---

## Round 3: финальная верификация

| # | Точка | Ок/Не-ок | Примечание |
|---|---|---|---|
| 1 | webhookUrl только TASK-PARTICIPATE/владельцу | **OK** | TaskDto: `отдаётся только TASK-PARTICIPATE и владельцу — capability не выдаётся READ-уровню`; §7 матрица: VIEW без webhookUrl, PARTICIPATE +webhookUrl |
| 2 | Идемпотентность: PK `(scope, key)`, дубликаты триггеров документированы | **OK** | §0.3: ключ опционален на вебхуках, дубликаты триггеров задокументированы; data-model §6: PK `(scope, key)` |
| 3 | workspace/files — path traversal защита | **OK** | §2: `path — только относительный, без .. и абсолютных префиксов; резолв строго внутри корня workspace; иначе 422 validation-failed` |
| 4 | task.events — снапшот при реконнекте | **OK** | §3.2: `При коннекте/реконнекте первым — снапшот task.status (правила §3.1)` |
| 5 | Relay: registered-ack; папки: folder-cycle | **OK** | §5: `register` успех → `← registered { workspaceId }`; §1.6: перемещение с циклом → `422 folder-cycle` |
| 6 | Рассинхрон доков устранён | **OK** | workflow-domain §7: `POST /api/webhooks/tasks/{taskId}/{token}` (能力-URL); D-05/D-26 согласованы |
| 7 | MA-1 compact → 202: обоснование принято | **OK** | §2 обоснование: `202 осознанно: синхронного ответа с результатом не бывает — вердикт судьи`. Принято: компакция на границе раунда активного Turn или через wake простаивающей — результат приходит как COMPACT-событие, не как HTTP-ответ |

---

### Итоговый вердикт: **APPROVE**

Все 7 верификационных точек покрыты; все замечания раунда 1 устранены или приняты; противоречия документов сняты; обоснование compact→202 (MA-1) принято — синхронного результата компакции в HTTP-ответе действительно не бывает, событийный характер корректен.
