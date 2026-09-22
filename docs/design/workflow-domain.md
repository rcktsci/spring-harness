# Workflow-домен spring-harness

> Шаблоны, ревизии, типы состояний, переходы, переменные, инструменты оркестратора.

## 1. Принцип шаблон/инстанс

- **Шаблон** (`Workflow` + иммутабельные `WorkflowRevision`) описывает логику: состояния, переходы, scope-выражения, переменные.
- **Инстанс** — задача: несёт ревизию (пин при создании), текущее состояние, `params_jsonb`, зависимости. Отдельной сущности «инстанс workflow» нет.
- Все конкретные ID/токены/пути живут в задаче; шаблон ссылается на них только выражениями: `${task.id}`, `${task.params.*}`, `${session.id}` (+ env). Язык выражений минимальен: подстановки, без скриптинга.

## 2. Контракт графа (`graph_jsonb`, JSON-Schema)

```
states[]:
  code            — varchar, уникален в ревизии (придумывает оркестратор)
  type            — AGENT | BASH_SCRIPT | WAIT_WEBHOOK | WAIT_TASKS | TERMINAL
  workspace       — { type: SERVER_DIR, mode: AUTO | PATH, path?: "${...}" }
                  | { type: CLIENT_EXEC }   # ЗАРЕЗЕРВИРОВАНО (D-84): валидатором допускается,
                                            # релеем НЕ исполняется — задача идёт серверно (SERVER_DIR fallback)
  agent_key       — для AGENT (конкретный агент; ревизия фиксируется на сессии)
  script          — для BASH_SCRIPT (текст, запуск через WorkspaceTools)
  scope           — для WAIT_TASKS: ALL_CHILDREN | BLOCKED_BY | TAGGED(x) | EXPLICIT(${task.params.key})
  condition       — для WAIT_TASKS: ALL_TERMINAL | ALL_SUCCESS
  payloadSchema   — опционально (для WAIT_WEBHOOK): JSON Schema валидации тела входящего вебхука
  paramsSchema    — опционально: JSON Schema для params задач, идущих по ревизии (создание задачи/триггера валидирует, `422 params-schema`)
  timeout         — опционально: для BASH_SCRIPT и WAIT_* — обязателен по правилам §2; для AGENT — необязательный предохранитель (долгое зависание без прогресса → TIMEOUT-переход)
  outcome         — для TERMINAL: SUCCESS | FAILED | CANCELLED
transitions[]:
  from, to        — codes
  kind            — NEXT | ERROR | TIMEOUT | CANCEL
```

**Правила валидации:**
1. codes уникальны; переходы замкнуты на существующие codes.
2. Циклы (возвраты) — легальны, ограничений на итерации движок не накладывает.
3. Fan-out (несколько одновременных следующих состояний) — **запрещён**.
4. Каждый нетерминальный код имеет ≥1 исходящий переход; BASH_SCRIPT обязан иметь ERROR и TIMEOUT переходы; WAIT_* — TIMEOUT.
5. Хоть один TERMINAL достижим из любого состояния.

**Стартовое состояние (`workflow_revision.start_state`)**: ревизия хранит `start_state` — code начального состояния задачи (объявляется явно при создании workflow/ревизии; определение «по графу» запрещено — эвристики ломаются на циклах). Задача при создании получает `current_state := start_state`. Валидация: `start_state ∈ states[].code`, иначе `422 graph-invalid` (`rule=unknown-state`).

## 3. Типы состояний — семантика

| Тип | Сессия? | Что происходит | Переходы |
|---|---|---|---|
| AGENT | да (пара «задача×code», резюмируется) | агент работает; переводит задачу инструментом `transition` с обязательным обоснованием | по разрешённым NEXT; обоснование → `task_transition_history.reason` |
| BASH_SCRIPT | нет | скрипт через `WorkspaceTools` в task-контейнере `harness-task-<taskId>` (обычно SERVER_DIR с `${task.id}`); перед исполнением инкремент `state_attempt` — повторный прогон после crash помечается новой попыткой, side-effect может выполниться дважды ⇒ **скрипты состояний должны быть идемпотентны** (клоны/пуллы — таковы) | код выхода 0 → NEXT; не 0 → ERROR; таймаут → TIMEOUT; вывод+exit → reason |
| WAIT_WEBHOOK | нет | ждёт вызов `POST /api/webhooks/tasks/{taskId}/{token}` (capability-URL, §7); проверка без БД; payload → в reason | приём валидного payload → NEXT; payload не прошёл схему состояния (если декларирована) → ERROR; таймаут → TIMEOUT |
| WAIT_TASKS | нет | переоценка на каждый терминал подзадач/разблокированных (scope); поиск «кто ждёт» — по индексам `(parent_task_id, status_projection)`, `(blocked_task_id)`, GIN `(tags)` | `condition`: `ALL_TERMINAL` — ждать терминалов всех (CANCELLED-ребёнок — терминал → NEXT); `ALL_SUCCESS` — как ALL_TERMINAL, но первый FAILED **или CANCELLED** немедленно ведёт по ERROR; таймаут → TIMEOUT; состав закрывшего условия → reason |
| TERMINAL | нет | финал задачи | status_projection = outcome |

**Отличие BASH_SCRIPT от агентского async-bash**: состояние скрипта — детерминированный системный контур движка (синхронное исполнение с таймаутом состояния, без LLM); async-окно агента — про инструмент внутри Turn'а. Долгий скрипт режется таймаутом состояния → TIMEOUT-переход.

**Принудительная отмена (стоп) задачи**: `stop` (suspend + отмена Turn'ов) завершает задачу со `status_projection = CANCELLED` через **зарезервированный псевдо-код `$CANCELLED`**: `current_state = '$CANCELLED'` — движковый резерв вне `codes` ревизии (инвариант data-model §7.2 содержит явное исключение), запись в `task_transition_history` с `kind = CANCEL` и `to_state = '$CANCELLED'`, без требования CANCEL-рёбер в графе. Resume для остановленной задачи → `409 task-already-terminal`.

**Переходы из AGENT-состояния**: цель инструмента `transition` — **любое исходящее ребро** текущего состояния (NEXT или ERROR: агент может сознательно сигнализировать неудачу по ERROR-ребру); kind выбранного ребра пишется в историю переходов.

## 4. Двухуровневая параллельность

- Движок: строго последовательно, задача в одном состоянии, у состояния одна активная сессия (поле-список, валидация length==1 — дверь к multi-instance в будущем).
- Реальный параллелизм: (а) подзадачи идут одновременно, родитель собирает их барьером `WAIT_TASKS` (аналог стейджа GitLab CI); (б) внутри сессии агент свободен — субагенты через `spawn_subagent`.

## 5. Пример-эталон («Сделай биллинг»)

Пользователь начинает сессию с оркестратором → описывает задачу → оркестратор создаёт задачу с workflow `feature-delivery` и подзадачи (аналитика, contract-first, реализация-модуль-1, реализация-модуль-2, тесты, e2e), расставляет `blocked_by` → стейджи родительского workflow (`WAIT_TASKS` на группы подзадач) открываются по мере готовности → упавшая подзадача → ERROR-переход в сессию-разборщик.

## 6. Инструменты оркестратора

`create_workflow` / `edit_workflow` (правка = новая ревизия), `create_task` (пин ревизии, params), `create_subtask`, `set_dependency` (blocked_by), `configure_trigger` (вебхук → задача по workflow). Сценарий «простая сессия» без задачи — обычная FREE-сессия с любым агентом.

**Доступ** (M3, D-62/D-70): инструменты оркестратора внедряются в манифест только агентам с `permissions_jsonb.metaTools = true` (оркестратор). Для них D-59-гейт `instructionSource = USER` НЕ применяется — инструменты доступны в любом ходе (в т.ч. TOOL_RESULT-продолжении и реакции на WAIT_TASKS); без флага инструменты скрыты, явный вызов → `TOOL_RESULT forbidden (no-metaTools)`. D-70 частично supersede D-41: metaTools-гейт возвращается для оркестраторских инструментов; `transition` остаётся под D-59 в прежней силе, обычные агенты — под D-41. flag — per-agent-revision (D-31): субагентам не наследуется (D-69).

## 7. Вебхуки (stateless, capability-URL)

- Эндпоинты (см. `api-contracts.md` §4.4): `POST /api/webhooks/tasks/{taskId}/{token}?source=…` и `POST /api/webhooks/triggers/{triggerId}/{token}`, где `token = HMAC(server_secret, kind + ':' + entityId)`.
- Сервер пассивен: URL (с токеном) выдаётся API (поле `webhookUrl` у TaskDto/TriggerDto), регистрацию во внешней системе делает агент предыдущего состояния или человек.
- Проверка — чистая функция (пересчёт HMAC), без БД для задач; триггеры проверяются по `revoked_at`.
- `409` вне WAIT_WEBHOOK — осознанно: повторная доставка — политика отправителя (GitHub и прочие шлюзы ретраят сами); буферизации «пришёл слишком рано» нет (stateless by design).
- Принятый payload → `reason` перехода (кратко) + по решению workflow доступен следующему состоянию.
- Threat-model capability-URL — `decisions.md` D-26: идемпотентность задач по построению (`409` вне WAIT_WEBHOOK), TLS-only, логирование вызовов в `reason`.

## 8. Задача-аналог /goal

Жёсткие рельсы: агент не может «проскочить» — только разрешённые переходы, каждое движение — с обоснованием в истории. Сессии состояний резюмируются: возврат задачи в состояние продолжает диалог того же агента с полным контекстом.
