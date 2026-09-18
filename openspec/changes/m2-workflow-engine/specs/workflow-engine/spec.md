# workflow-engine Specification

## Purpose

Шаблоны и ревизии workflow: описание графа состояний и переходов, иммутабельность ревизий, валидация графа на соответствие правилам построения (уникальность codes, отсутствие fan-out, ≥1 исходящий переход, обязательные ERROR- и TIMEOUT-рёбра у BASH/WAIT, достижимость TERMINAL). Циклы разрешены.

## ADDED Requirements

### Requirement: Создание workflow (rev=1)

Система SHALL принимать `POST /api/v1/workflows { key, name, graph } → 201` и сохранять новый workflow с первой ревизией (`rev=1`). Граф валидируется (§workflow-engine валидация); при ошибке — `422 graph-invalid` с `errors[]: { pointer, rule, message }`. `key` — kebab-case, уникален среди workflows.

#### Scenario: валидный граф

- **WHEN** клиент создаёт workflow с уникальным key и валидным графом
- **THEN** `201`, ревизия `rev=1` сохранена, возвращается WorkflowDto с `latestRev=1`

#### Scenario: невалидный граф

- **WHEN** клиент создаёт workflow с графом, нарушающим правила
- **THEN** `422` с кодом `graph-invalid` и `errors[]` по нарушенным правилам

### Requirement: Новая ревизия workflow

`POST /api/v1/workflows/{key}/revisions { graph } → 201` SHALL создавать новую ревизию (`rev = prev + 1`) с тем же `key`. Существующие ревизии иммутабельны (`UNIQUE (workflow_id, rev)`); правка = новая строка. Задачи пинятся к конкретной ревизии — новые задачи могут идти по новой ревизии, идущие — по старой.

#### Scenario: добавление ревизии

- **WHEN** клиент создаёт ревизию существующего workflow с валидным графом
- **THEN** `201`, ревизия сохранена, `latestRev` увеличен

#### Scenario: правка графа не задевает идущие задачи

- **WHEN** у workflow уже есть задачи на rev=1 и клиент создаёт rev=2
- **THEN** идущие задачи остаются на rev=1, новые задачи могут идти по rev=2

### Requirement: Список и просмотр

Система SHALL реализовать: `GET /api/v1/workflows?cursor=` — `{ items: [{ key, name, latestRev }] }`; `GET /api/v1/workflows/{key}` — метаданные + список ревизий `[{ rev, createdAt }]`; `GET /api/v1/workflows/{key}/revisions/{rev}` — граф конкретной ревизии.

#### Scenario: неизвестный workflow

- **WHEN** клиент запрашивает GET по неизвестному key
- **THEN** `404` с кодом `workflow-not-found`

### Requirement: Валидация графа (правила §2)

Граф SHALL проходить валидацию перед сохранением:
1. `states[].code` уникальны в ревизии.
2. Переходы `from`/`to` ссылаются на существующие `states[].code`.
3. Каждый нетерминальный state имеет ≥1 исходящий переход.
4. `BASH_SCRIPT`-state имеет ERROR и TIMEOUT переходы.
5. `WAIT_WEBHOOK` и `WAIT_TASKS`-states имеют **и** ERROR, **и** TIMEOUT переходы (движок обязан иметь путь выхода и по неудаче валидации payload/условия, и по таймауту — без ERROR-ребра движок клинчится).
6. Из любого state достижим хотя бы один `TERMINAL`.
7. Fan-out запрещён: для каждого `state.code` — не более одного исходящего перехода каждого `kind` (≤1 NEXT, ≤1 ERROR, ≤1 TIMEOUT). Единственный NEXT без ERROR — допустим; единственный ERROR без NEXT — допустим (задача сразу в терминал по неудаче); несколько переходов одного kind — запрещены.
8. `transition.kind ∈ { NEXT, ERROR, TIMEOUT }`; CANCEL зарезервирован для движка stop (запись в истории принудительной отмены через `'$CANCELLED'` — псевдо-код вне `codes` ревизии); CANCEL-рёбра в присылаемом графе невалидны → `422 graph-invalid` (rule=cancel-edge-forbidden).

Нарушения → `422 graph-invalid` с массивом `errors[]`.

#### Scenario: нарушение уникальности codes

- **WHEN** в графе два состояния с одинаковым `code`
- **THEN** `422 graph-invalid` с error `pointer=/states/1/code`, `rule=code-unique`

#### Scenario: fan-out

- **WHEN** у state два исходящих NEXT-перехода
- **THEN** `422 graph-invalid` с error `rule=fan-out-forbidden`

#### Scenario: BASH без TIMEOUT

- **WHEN** `BASH_SCRIPT`-state не имеет TIMEOUT-перехода
- **THEN** `422 graph-invalid` с error `rule=bash-timeout-required`

#### Scenario: WAIT без ERROR

- **WHEN** `WAIT_WEBHOOK`-state имеет только TIMEOUT-переход, без ERROR
- **THEN** `422 graph-invalid` с error `rule=wait-error-required`

### Requirement: Контракт графа (graph_jsonb, JSON-Schema)

`graph_jsonb` SHALL соответствовать JSON-Schema (см. `docs/design/workflow-domain.md` §2): `states[]: { code, type: AGENT|BASH_SCRIPT|WAIT_WEBHOOK|WAIT_TASKS|TERMINAL, workspace?, agent_key?, script?, scope?, condition?, payloadSchema?, paramsSchema?, timeout?, outcome? }`; `transitions[]: { from, to, kind: NEXT|ERROR|TIMEOUT|CANCEL }`. Семантика полей — по `workflow-domain.md` §2–§3. Схемы `paramsSchema`/`payloadSchema` в M2 — **ограниченный профиль JSON-Schema** (`required`, `type`, `enum`, `items`, `properties` первого уровня), валидация — ручным обходом; полная JSON-Schema — точка эволюции.

#### Scenario: неизвестный тип состояния

- **WHEN** в графе state с `type=FORK`
- **THEN** `422 graph-invalid` с error `rule=enum`

#### Scenario: WAIT_TASKS без scope/condition

- **WHEN** `WAIT_TASKS`-state без `scope` или `condition`
- **THEN** `422 graph-invalid` с error `rule=required`
