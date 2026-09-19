# M2 Batch K Review - Mercury

**Дата:** 2026-09-18  
**Объект:** suspend/stop, task commands API

## Findings

### 1. workflow-domain.md §3 — `$CANCELLED`

**TaskRegistry.CANCELLED_STATE**: `"$CANCELLED"` — зарезервированный псевдо-код вне codes ревизии.

**TaskRegistry.stop**: CAS в `'$CANCELLED'` + запись в `task_transition_history` с `kind=CANCEL`, `to_state='$CANCELLED'`.

✅ **Correct**

### 2. D-29 — suspend+stop

| Операция | Реализация | D-29 соответствие |
|----------|------------|-------------------|
| suspend | `TaskRegistry.suspend(id, cascade)` → флаг `suspended=true` | ✅ |
| stop | `TaskRegistry.stop(id)` → CAS в `'$CANCELLED'` + отмена Turn'ов | ✅ |

**Resume терминальной**: `TaskAlreadyTerminalException` → 409.

✅ **Correct**

### 3. api-contracts §4.1 endpoints

| Endpoint | Реализация | Соответствие |
|----------|------------|--------------|
| POST /tasks/{id}/suspend | TaskCommandsController.suspendTask | ✅ |
| POST /tasks/{id}/resume | TaskCommandsController.resumeTask | ✅ |
| POST /tasks/{id}/stop | TaskCommandsController.stopTask | ✅ |

✅ **Correct**

### 4. api-contracts §6 коды ошибок

**task-already-terminal**: 409 в OpenAPI (lines 604, 627, 1348, 1457, 1469).

✅ **Correct**

### 5. D-41 — JWT auth gate

**Caller**: `jwt()` → SecurityContext → JWT → username. D-41: JWT = SSO-гейт, внутри «аутентифицированный видит всё».

✅ **Correct**

### 6. D-59 — stop без cascade параметра

**TaskCommandsController.stopTask**: `tasks.stop(id)` — без параметра cascade (D-54: всегда каскадный).

✅ **Correct**

## Dev Notes

| Dev заметка | Статус |
|-------------|--------|
| StopTaskFacade: отмена Turn'ов | ✅ (в TaskWakeDispatcher) |
| TaskAlreadyTerminalException | ✅ |
| SuspendTaskRequest.cascade | ✅ |
| CANCELLED_STATE = "$CANCELLED" | ✅ |

## Verdict

approve
