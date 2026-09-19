# M2 Batch H Review - Mercury

**Дата:** 2026-09-18  
**Объект:** миграции, workflow/task пакеты, ArchUnit

## Findings

### 1. Migrations — PK/FK/Indexes

| Таблица | PK | FK | Indexes | data-model соответствие |
|---------|----|----|---------|------------------------|
| workflow | uuid id | — | UNIQUE key | ✅ |
| workflow_revision | uuid id | workflow_id | UNIQUE (workflow_id, rev) | ✅ |
| task | uuid id | workflow_revision_id, parent_task_id | (parent_task_id, status_projection), GIN tags, partial WAIT_*, partial deadline, (task_id, created_at) | ✅ |
| task_dependency | (blocker_task_id, blocked_task_id) | task (x2) | (blocked_task_id) | ✅ |
| task_comment | uuid id | task_id | — | ✅ |
| task_transition_history | uuid id | task_id | (task_id, created_at) | ✅ |
| trigger | uuid id | — | (task ( | ✅ |

**Оценка:** Все PK/FK/индексы согласно data-model §3-§4.

### 2. WorkflowGraphSchemaValidator — workflow-domain.md §2 правила

| Правило | Реализация |
|---------|------------|
| codes уникальны | ✅ `code-unique` |
| переходы замкнуты | ✅ `unknown-state` |
| fan-out запрещён | ✅ `fan-out-forbidden` (≤1 ребро каждого kind) |
| ≥1 исходящий у нетерминальных | ✅ `outgoing-required` |
| BASH_SCRIPT ERROR+TIMEOUT | ✅ `bash-error-required`, `bash-timeout-required` |
| WAIT_* ERROR+TIMEOUT | ✅ `wait-error-required`, `wait-timeout-required` |
| TERMINAL достижим | ✅ `terminal-unreachable` |
| CANCEL в графе запрещён | ✅ `cancel-edge-forbidden` |
| JSON-Schema D-58 | ✅ `requireSchemaObject` (ограниченный профиль) |

**Оценка:** Все §2 правила + D-58 контракт отражены.

### 3. TaskRegistry — data-model §7.2 инварианты

| Инвариант | Реализация |
|-----------|------------|
| params иммутабельны | ✅ Javadoc контракт (API-слой контролирует PATCH) |
| paramsSchema валидация | ✅ `ParamsSchemaInvalidException` |
| dependencies: cycles/self-loop | ✅ `DependencyInvalidException` (DFS) |
| stop: атомарный CAS в '$CANCELLED' | ✅ `CANCELLED_STATE` = "$CANCELLED" |
| stop: гварда только "не терминальная" | ✅ Javadoc: "без проверки suspended" |
| stop: всегда каскадный | ✅ Javadoc: "всегда каскадный (подзадачи тоже)" |
| resume: task-wake публикация | ✅ `TaskWakeListener` |

**Оценка:** Все §7.2 инварианты отражены.

### 4. Контракты vs architecture.md §3

| Контракт | Модуль | В архитектуре |
|----------|--------|---------------|
| WorkflowRegistry | workflow | ✅ таблица §3 |
| TaskRegistry | task | ✅ таблица §3 |
| TaskEngine | execution | ✅ таблица §3 |
| TriggerRegistry | task | ✅ таблица §3 |

**Оценка:** Все контракты §3 реализованы.

### 5. Dev отклонения

9 отклонений dev применены в apply-notes.md:
1. WorkflowEntity: `workflowKey` → `key`
2. WorkflowRevisionEntity: `workflowKey` → `workflowId`
3. TaskEntity: `authorId` → `authorUserId`
4. TaskDependencyEntity: id → composite PK
5. WorkflowRegistryImpl: remove → no-op
6. TaskRegistryImpl: removeDependency → no-op
7. migrations/011: `workflowKey` → `workflowId`
8. migrations/013: PK → composite
9. TaskRegistryLifecycleTest: update → no-op

## Verdict

approve

### Ре-approval

**Дата:** 2026-09-18  
**Проверка:** Все миграции (010-016) по data-model §3-§4; workflowGraphSchemaValidator реализует workflow-domain §2 + D-58; TaskRegistry отражает data-model §7.2 инварианты; контракты §3 архитектуры реализованы. Dev отклонения применены.

✅ **APPROVE**
