# Ревью дизайн-корпуса spring-harness (сквозное)
**Ревьюер:** Mercury
**Дата:** 2026-09-16
**Объект:** все конечные документы `docs/glossary.md`, `docs/design/*.md` (11 шт.)

## Сводка

| Severity | Кол-во |
|---|---|
| CRITICAL | 0 |
| MAJOR | 4 |
| MINOR | 5 |
| **Итого** | **9** |

**Топ-3:** **M-1** (recover from docker container crash), **M-2** (task dependency cycle validation), **M-3** (transition history retention)

---

## MAJOR

### M-1. Реакция на сбой Docker-контейнера в mid-execution не определена
- **Severity:** MAJOR
- **Место:** `execution-model.md` §4 (`D-30`), `decisions.md` D-30
- **Описание:** D-30 вводит per-session Docker-контейнеры с lifecycle = сессия. Однако `execution-model.md` §4 описывает только async-инструменты (окно ~30с → ASYNC_ACCEPTED → поздний RESULT). Если контейнер падает во время выполнения tool-call (например, docker-java client теряет связь с daemon), ни один документ не определяет: получает ли инструмент `LOST`-результат как при рестарт-скане? Восстанавливается ли контейнер? Как обновляется `task_transition_history`? В `glossary.md` §6 `ContainerWorkspaceTools` упоминается, но без сценариев сбоя.
- **Предложение:** Добавить правило в `execution-model.md` §4: «при потере связи с контейнером tool-call получает `LOST`-результат с reason=container-crash; контейнер не восстанавливается автоматически; сессия переходит в PARKED_CLIENT/ERROR в зависимости от типа состояния» — в `ContainerWorkspaceTools` spec.

### M-2. Валидация циклов в task_dependency не определена
- **Severity:** MAJOR
- **Место:** `data-model.md` §4 (`task_dependency`), `workflow-domain.md` §2 (правила валидации), `api-contracts.md` §4.1
- **Описание:** `api-contracts.md` §4.1 `POST /tasks/{id}/dependencies` упоминает `422` для self/cycle, но не определяет, как именно проверяется цикл — по `blocked_by` графу (только direct dependencies) или включая транзитивные зависимости? `task_dependency` хранит только direct edges. При создании зависимости `A → B` где `B → ... → A` (транзитивный цикл), должна ли система проверять весь граф задачи? `workflow-domain.md` §2 правила валидации касаются workflow графа, не task dependencies.
- **Предложение:** Явно зафиксировать правило в `workflow-domain.md` или `data-model.md`: «цикл проверяется по всем транзитивным blocked_by зависимостям задачи» — с указанием алгоритма (DFS) и ошибки.

### M-3. Политика удержания task_transition_history не определена
- **Severity:** MAJOR
- **Место:** `data-model.md` §4 (`task_transition_history`), `security-multitenancy.md` §5
- **Описание:** `task_transition_history` описана как append-only журнал (инвариант data-model §7.1), но нет политики удержания — сколько хранить историю переходов? В `idempotency_key` есть TTL 24 ч (§6), но для `task_transition_history` нет. Это влияет на storage growth при активных workflow с циклами.
- **Предложение:** Добавить политику retention в `data-model.md` §4: «history хранится N дней/событий (конфиг); для архивации задач можно применять каскадную очистку старого» — или явно заявить «без limit, хранится бессрочно как аудит».

### M-4. Helper-образ: retry/backoff policy для docker image pull отсутствует
- **Severity:** MAJOR
- **Место:** `decisions.md` D-30, `execution-model.md` §4
- **Описание:** `ContainerWorkspaceTools` создаёт контейнер лениво при первом вызове (§4). Если helper-образ ещё не скачан с registry, docker pull может временно не работать (network issues, rate limits). `execution-model.md` не описывает retry-логику — будет ли ошибка tool-call сразу? Сколько раз повторять? Какой экспоненциальный backoff?
- **Предложение:** Добавить в `execution-model.md` §4: «docker pull helper-образа при создании контейнера: максимум N попыток с экспоненциальным backoff (1с, 2с, 4с); после исчерпания — tool-call LOST, container не создаётся» — в `ContainerWorkspaceTools` spec.

---

## MINOR

### m-1. session_message ULID формат не формализован
- **Severity:** MINOR
- **Место:** `glossary.md` §4 (`session_message.id`), `data-model.md` §5
- **Описание:** `glossary.md` §4 описывает `session_message.id` как «короткий ULID» без формального определения. `data-model.md` §5 — `text` тип ULID, но без ограничений длины. `D-31` (decisions.md) говорит «кроме короткого ULID session_message.id» но не объясняет, почему ULID вместо UUIDv7, когда UUIDv7 используется для всех остальных PK.
- **Предложение:** Добавить формализацию в `glossary.md` §4: «ULID — 26 символов Crockford Base32, гарантирующая монотонность в пределах сессии» — и объяснить выбор в `D-31`.

### m-2. Индексы для WAIT_TASKS: BLOCKED_BY не индексирован
- **Severity:** MINOR
- **Место:** `data-model.md` §4 (`task` индекс), `workflow-domain.md` §2 (`scope`-выражения)
- **Описание:** `data-model.md` §4 упоминает только индекс `(parent_task_id, status_projection)` для `WAIT_TASKS / ALL_CHILDREN`. Но `workflow-domain.md` §2 перечисляет также `BLOCKED_BY`, `TAGGED(x)`, `EXPLICIT(${params.key})`. Для `BLOCKED_BY` нет индекса в `task_dependency` для обратного поиска — кто ждёт эту задачу?
- **Предложение:** Добавить композитный индекс в `data-model.md` §4: `INDEX task_dependency_blocked_by(blocked_task_id, blocker_task_id)` или явно зафиксировать, что `BLOCKED_BY`-валидация без индекса допустима для MVP.

### m-3. Container workspace volume mount failure не обработан
- **Severity:** MINOR
- **Место:** `execution-model.md` §4 (`ContainerWorkspaceTools`)
- **Описание:** D-30 говорит workspace примонтирован томом. Если volume mount fails (host volume missing, permission issues), `ContainerWorkspaceTools` не имеет fallback. Инструмент должен работать или давать понятную ошибку.
- **Предложение:** Добавить правило: «при failed mount — контейнер не создается, tool-call получает LOST с reason=mount-failure; логом volume path и error».

### m-4. Agent revision lifecycle: кто удаляет старые ревизии?
- **Severity:** MINOR
- **Место:** `data-model.md` §2 (`agent`), `decisions.md` D-20
- **Описание:** Агент иммутабелен, правка = новая ревизия. Но нет политики удаления старых ревизий — сколько хранить? Как влияют на storage?
- **Предложение:** Добавить политику retention в `data-model.md` §2: «ревизии хранятся N дней; после этого архивировать/удалять (кроме активных задач)».

### m-5. Docker container resource limits не зафиксированы
- **Severity:** MINOR
- **Место:** `decisions.md` D-30, `security-multitenancy.md` §6
- **Описание:** D-30 говорит «ограничения ресурсов» но не зафиксирован тип (CPU, memory, disk?). `security-multitenancy.md` §6 упоминает container isolation, но без конкретики.
- **Предложение:** Зафиксировать в `D-30` или `execution-model.md`: «memory=2G, CPU=1.0, disk quota=10G» — или ссылку на конфиг.

---

## Находки, которых нет у коллег

| Находка | Описание |
|---|---|
| **M-1 container crash** | None of GLM/DeepSeek/minimax addressed docker container failure during async tool execution |
| **M-2 task cycle validation** | Cycle validation for task dependencies not covered (only workflow graph validation was discussed) |
| **M-3 transition history retention** | Storage retention policy for append-only journals (task_transition_history) not defined |
| **M-4 image pull retry** | Helper-image pull retry/backoff not specified in any document |
| **m-2 BLOCKED_BY index** | Specific index gap for backward lookup of BLOCKED_BY dependencies |
| **m-3 mount failure** | Volume mount failure scenario not documented |
| **m-5 resource limits** | Specific container resource limits not quantified |

---

## Round 2 (verify)

| ID | Статус | Комментарий |
|---|---|---|
| M-1 (container crash) | **fixed** | synthetic LOST + scan orphaned containers (execution-model §1) |
| M-2 (task cycles) | **fixed** | transitive validation с 422 dependency-invalid (data-model) |
| M-3 (history retention) | **fixed** | explicitly indefinitely (security §5) |
| M-4 (image pull) | **fixed** | local presence on VM + backoff only on update |
| m-1 (ULID format) | **not-addressed** | формализация не внесена |
| m-2 (BLOCKED_BY index) | **fixed** | INDEX `(blocked_task_id)` добавлен |
| m-3 (mount failure) | **fixed** | ERROR-путь состояния зафиксирован |
| m-4 (agent retention) | **not-addressed** | политика удаления ревизий не определена |
| m-5 (resource limits) | **fixed** | cpus=2/mem=2g/pids=512, сеть по необходимости |

**D-30-контуры:** новых проблем не внесено; все container-level сценарии покрыты.

---

## Вердикт

**approve.** Все MAJOR-дефекты закрыты; MINOR-находки m-1/m-4 косметические, не влияют на M1–M3.
