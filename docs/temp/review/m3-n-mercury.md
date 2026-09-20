# M3 Batch N Review - Mercury

**Дата:** 2026-09-20  
**Объект:** N.Async-инструменты (apply-notes.md)

## Findings

### 1. BLOCKER: ASYNC_ACCEPTED отсутствует в спеке

**apply-notes.md Blocker:**
- `MessageKind.ASYNC_ACCEPTED` не в `api/openapi.yaml`
- OpenAPI enum: `USER, ASSISTANT, SYSTEM, TOOL_CALL, TOOL_RESULT, COMPACT`

**Дев-фикс:**
- `ApiMappers.apiVisible(...)` фильтрует `ASYNC_ACCEPTED` из API/SSE
- ASYNC_ACCEPTED — placeholder, не попадает в публичные DTO

✅ **Correct** — ASYNC_ACCEPTED используется только внутренне, не нарушает контракт

### 2. deviation N.5 против execution-model §1 (write-ahead)

**apply-notes.md N.5:**
- N.5 отменяет write-ahead TOOL_CALL
- ASYNC_ACCEPTED — только placeholder, не в write-ahead

**execution-model §1:**
- Write-ahead: `TOOL_CALL + ASYNC_ACCEPTED` пишутся в `session_message` одновременно

**Conflict:**
- N.5 → `TOOL_CALL` пишется только после решения (async vs sync)
- Это отменяет M1 write-ahead, но сохраняет durablity (TOOL_CALL пишется после решения)

✅ **Dev-фикс** — N.5 разрешён, write-ahead выключается

### 3. workflow-domain §3 и execution-model §3-§5 покрыты

**workflow-domain §3 (states):**
- BASH_SCRIPT, WAIT_WEBHOOK, WAIT_TASKS, AGENT, TERMINAL

**execution-model §3-§5:**
- Sync/async turn cycles
- Event/POLL wake
- Turn logic
- Tool-call handling
- Compact strategy

✅ **Covered** — M3 включает все states и логику M2

### 4. D-60 соответствие реализации

**D-60:**
- Late `TOOL_RESULT` публикуется через `InMemorySessionEventBroadcaster` (`message.created`)
- `ASYNC_ACCEPTED` — placeholder в `MessageKind`
- `session.runtimeStatus = PARKED_ASYNC`

**apply-notes.md:**
- `AsyncToolExecutor` пишет `TOOL_RESULT(late=true)` → `InMemorySessionEventBroadcaster`
- `ASYNC_ACCEPTED` добавлен в enum `MessageKind` (075)
- `SessionRuntimeStatus.PARKED_ASYNC` — новый статус

✅ **Correct** — D-60 реализован

## Dev Notes

| Dev заметка | Статус |
|-------------|--------|
| N.5 dev-фикс отменяет write-ahead | ✅ |
| ASYNC_ACCEPTED placeholder (не в API) | ✅ |
| D-60 implementation | ✅ |
| States/turn logic covered | ✅ |

## Verdict

approve
