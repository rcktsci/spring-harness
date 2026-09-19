# M2 Batch I Review - Mercury

**Дата:** 2026-09-18  
**Объект:** TaskEngine, executors, wake infra

## Findings

### 1. workflow-domain.md §3 — BASH/WAIT_WEBHOOK/WAIT_TASKS семантика

| Состояние | Реализация | Соответствие |
|-----------|------------|--------------|
| BASH_SCRIPT | BashStateExecutor: container `harness-task-<taskId>`, `state_attempt` инкремент, exit=0→NEXT, exit≠0→ERROR, timeout→TIMEOUT | ✅ |
| WAIT_WEBHOOK | WaitWebhookStateExecutor: passive (webhook-handler вызывает), payloadSchema→NEXT/ERROR, idempotent по CAS | ✅ |
| WAIT_TASKS | WaitTasksStateExecutor: scope/condition evaluation, reevaluation идемпотентна, all_terminal/all_success | ✅ |

### 2. D-53 STATE-сессии — bootstrap

**TaskSchedulerJob.poll()**: `bootstrapAgentWithoutSession()` — AGENT+RUNNING+no-STATE-session → `publishTaskWake()`.

**Отличие от session**: task-bootstrap не создаёт STATE-сессию (это задача пачки J.3), только будит.

✅ **Correct**

### 3. D-33 POLL-страховка двухуровневая

| Уровень | Где | Зона |
|---------|-----|------|
| Session | `session` модуль (M1) | eligible сессии |
| Task | `TaskSchedulerJob` (пачка I) | AGENT bootstrap, WAIT_TASKS переоценка |

**Отлично:** `WAIT_WEBHOOK` не перебирается в POLL (только таймаут-скан `task-timeout-scanner`).

✅ **Correct**

### 4. D-44 covers seq-интервалы

**TaskEngineImpl.CAS_UPDATE**: `task_event_seq = task_event_seq + 1` при переходе.

**Отличие от session:** task-события имеют `task_event_seq` (bigint, durable), session-COMPACT имеет `covers [{from,to}]` (seq-интервалы). Не путать.

✅ **Correct**

### 5. D-45 watermark

D-45 относится к session `last_consumed_seq` (M1). Задачное потребление не определено (задачи не имеют "watermark").

✅ **Correct** (не применимо к задачам)

### 6. ArchUnit — иностранные пакеты

**TaskRegistry**: `TaskEngine` (execution), `WorkflowGraphReader` (execution).

**TaskEngineImpl**: `TaskRegistry`, `InProcessTaskWakeBus`.

**Architecture**: `execution → {task, session, workflow}` (D-48).

✅ **Correct** (границы соблюдены)

### 7. Dev заметки

| Dev заметка | Статус |
|-------------|--------|
| task_event_seq в миграции 012 | ✅ |
| task-scheduler ShedLock TTL в конфиге | ✅ |
| BashStateExecutor.inFlight in-flight guard | ✅ |
| WaitTasksScope evaluation conditions | ✅ |
| WaitWebhookStateExecutor.payloadSummary | ✅ |

## Verdict

approve
