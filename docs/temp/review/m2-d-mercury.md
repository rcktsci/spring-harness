# M2 OpenAPI Spec Review - Mercury (D.1)

**Дата:** 2026-09-18  
**Ревьюер:** Mercury-2.5  
**Объект:** `src/main/resources/api/openapi.yaml` (M1+M2)

## Summary

**Статус:** APPROVE (0 критических находок)

Специя корректно отражает api-contracts §4.1–§4.4 + §3.2, capability-URL семантику, SSE task events, и D-58/D-59 решения. Все 12 отклонений dev найдены в ревью m2-judge.md (J-1…J-31) применены.

---

## Verification Results

| Пункт | Статус | Примечание |
|-------|--------|------------|
| (1) Полнота покрытия api-contracts §4.1–§4.4 + §3.2 | ✅ | Все endpoints задач, workflows, triggers, вебхуков присутствуют. SSE задач `/tasks/{id}/events` с `task_event_seq` cursor. |
| (2) capability-URL семантика | ✅ | `WebhookToken` parameter: "HMAC-SHA256(server_secret, kind+':'+entityId)". /webhooks/tasks/{taskId}/{token} и /webhooks/triggers/{triggerId}/{token} без JWT, без /v1. |
| (3) SSE task events | ✅ | `/tasks/{id}/events` с `task_event_seq` cursor (lines 798-818). События: task.transition, task.status, subtask.terminal, task.comment. Снапшот при коннекте. |
| (4) D-58 JSON-Schema ограниченный профиль | ✅ | `payloadSchema`/`paramsSchema` в WorkflowState (lines 2238-2245): "ограниченный профиль D-58 — required, type, enum, items, properties первого уровня". |
| (5) D-59 metaTools-гейт | ✅ | WrongTransition response (lines 1331-1340): "Достигается инструментом агента `transition` (гейт metaTools), не REST-операцией M2". |
| (6) D-44 covers seq-интервалы vs task_event_seq | ✅ | MessageDto.payload.covers (lines 1561-1562): "D-44; видимость = журнал минус объединение covers" — отдельно от task_event_seq для SSE задач. |
| (7) Отклонения dev | ✅ | Все 12 найдены в m2-judge.md (J-1…J-31) — применены до заморозки спеки. |

---

## Key Findings

### 1. coverage-URL (lines 1060-1134)
- ✅ Без JWT: `security: []`
- ✅ Без /v1: servers.url = `/api`
- ✅ Token в пути: `WebhookToken` parameter (line 1246): "HMAC-SHA256(server_secret, kind+':'+entityId)"
- ✅ Ошибки: signature-invalid (401), task-not-waiting-webhook (409), trigger-revoked (410)

### 2. SSE Task Events (lines 788-848)
- ✅ Cursor: `task_event_seq` (line 798): "SSE `id:` = task_event_seq"
- ✅ События: task.transition, task.status, subtask.terminal, task.comment
- ✅ Снапшот при коннекте: "Пepым при коннекте/реконнекте отправляется снапшот — последний `task.status`"
- ✅ Durable: "колонка `task.task_event_seq`", "транзакционно с эмиссией события"

### 3. Problem Code Catalog (lines 1419-1448)
- ✅ `wrong-transition` включён (line 1436)
- ✅ Все M2 коды: task-not-found, workflow-not-found, trigger-not-found, task-not-waiting-webhook, task-already-terminal, wrong-transition, graph-invalid, dependency-invalid, params-schema, trigger-revoked, signature-invalid

### 4. D-58 JSON-Schema Profile (lines 2238-2245)
- ✅ `payloadSchema`: "ограниченный профиль D-58 — required, type, enum, items, properties первого уровня"
- ✅ `paramsSchema`: "ограниченный профиль D-58"

### 5. D-44 Covers vs task_event_seq
- ✅ D-44: `MessageDto.payload.covers` (lines 1561-1562): D-44 format для сессий
- ✅ D-44: `task_event_seq` для SSE задач (line 798)
- ✅ Разные курсоры для разных целей — нет путаницы

---

## No Issues Found

Все проверки пройдены. Спека готова к генерации (шаг 2).

## Re-approval

**Дата:** 2026-09-18  
**Статус:** **APPROVE**  
**Проверка:** Fix-цикл F-1..F-9 + GLM minor + 12 отклонений применён. Контракт сохранён.

| Пункт | Статус | Примечание |
|-------|--------|------------|
| api-contracts §4.1–§4.4 + §3.2 | ✅ | Покрытие сохранено (tasks CRUD, workflows, triggers, вебхуки, SSE задач). |
| capability-URL HMAC | ✅ | Описание HMAC-SHA256 в пути сохранено; /webhooks/** без JWT, без /v1. |
| D-58 профиль paramsSchema | ✅ | Ограниченный профиль (required/type/enum) зафиксирован в WorkflowState. |
| D-59 metaTools-гейт | ✅ | Инструмент переходов остаётся агент-только, REST-операций нет. |
| Dev-фиксы | ✅ | Все 12 отклонений внесены в apply-notes.md для прозрачности генерации. |

**Итого:** 0 нарушенных требований. Спека готова к генерации (шаг 2).
