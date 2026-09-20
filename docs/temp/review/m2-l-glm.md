# Ревью пачки L (триггеры + вебхуки + workflows REST, L.5) — GLM-5.3-Flash

Дата: 2026-09-19. Объект: task/{TriggerRegistry(+Impl), Trigger, исключения}, common/security/WebhookSignatureVerifier (+тесты), execution/TaskWebhookPort, api/impl/WebhookHandlers, api/{WebhooksController, TriggersController, WorkflowsController} реальные, ApiExceptionHandler+спека, sweep not-implemented, apply-notes L (8 отклонений). Сборки не запускались.

**Вердикт: APPROVE** — блокирующих находок 0; 2 nit вне критического пути (перечислены, фикс по желанию).

---

## (1) TriggerRegistry (L.1) — ✅
Create: пин ревизии (явный rev / latestRev — собственный SQL с JOIN workflow по key, task не зависит от workflow-классов — прецедент H), params-валидация по paramsSchema **start_state** пиннутой ревизии (H-8-консистентно с TaskRegistryImpl), `422 params-schema`; revoke — идемпотентен (уже отозван → false; отсутствует → TriggerNotFound; `UPDATE … WHERE revoked_at IS NULL` — race-safe, URL умирает мгновенно); list — курсор `(created_at, id)` desc + фильтр mine; capability-URL — stateless из `base-url + HMAC` (expectedToken переиспользован); `TEXT[]` через createArrayOf (стиль пачки I).

## (2) Verifier (L.2) — ✅
Чистая функция: `HMAC-SHA256(secret, kind+':'+entityId)`, null-токен → false, сравнение в постоянном времени (`MessageDigest.isEqual`), `expectedToken` переиспользуется при выдаче URL (единый источник токена); тесты расширены (WebhookSignatureVerifierTest).

## (3) WebhookHandlers (L.3) — ✅
Задача: verify (кривой токен → 401 signature-invalid до парсинга тела) → несуществующая задача/не WAIT_WEBHOOK/терминальная → 409 task-not-waiting-webhook (отклонение D-№6 — «задачи нет» = 409, согласовано со спекой inbound-triggers) → `TaskWebhookPort.onWebhookArrived` (payloadSchema D-58 → NEXT/ERROR, CAS) → NOT_WAITING (ретрай/гонка) → снова 409; иначе 202 (исход в истории/SSE). Триггер: verify → revoked → 410 trigger-revoked → createTask (пин триггера, params/tags/owner из триггера, **author NULL**, title=name — отклонение #3 зафиксировано) → 202 {taskId}; AGENT-старт — EVENT-wake из createTask (подтверждено: `publishWakeAfterCommit` в TaskRegistryImpl.createTask) → диспетчер → bootstrap; повторный POST триггера создаёт новую задачу — R5 (без дедупликации, осознанно).

## (4) Контроллеры (L.4) — ✅
WebhooksController — тонкая делегация в `api.impl.WebhookHandlers` (HMAC-гейт внутри), сохранён `@RequestMapping("/api")`-обход; TriggersController — create (404 workflow-not-found при latestRev-резолве, 201 + Location `/api/v1/triggers/{id}` — отклонение #4, capability-URL в теле), list (mine), DELETE (revoke → 204/404).

## (5) Sweep (L.5) — ✅
grep `not-implemented|NotImplemented|NOT_IMPLEMENTED` по src/main и src/test — пусто; ApiNotImplementedException/хендлер/ProblemCodes-константа удалены; openapi.yaml и api-contracts §6 вычищены; WebhooksRoutingTest 501-тесты переписаны на реальные исходы. Пробел плана (Workflows REST не был покрыт задачами D…M) — честно задокументирован как отступление, спека уже заморожена и полна, 5 интеграционных тестов WorkflowsApiTest.

## (6) D-26/D-29/D-41/D-59 — ✅
D-26: stateless capability-URL, HMAC в пути, 409-идемпотентность по построению, TLS — env-конфиг деплоя (вне кода — по плану); D-29: полное тело вебхука не хранится (payloadSummary с byte-size-limit из пачки I); D-41: `/api/v1/**` — JWT, `/api/webhooks/**` — permitAll-цепочка без Bearer-резолвера (K-фикс); D-59: автор триггер-задачи NULL (не пользователь) — атрибуция через историю.

## (7) WorkflowsController — ✅
5 эндпоинтов §4.2: list (cursor/limit), create (rev=1, 422 graph-invalid errors[] / 422 key-unique rule=key-unique — отклонение D-№5), GET {key} (метаданные + revisions — расширение контракта `WorkflowRegistry.revisions`, отклонение #7), GET revisions/{rev}, POST revisions (rev=prev+1). startState (R-1) прокинут в create/newRevision. Мапперы графа — ручные toGraph/toGraphMap (отклонение #8: absent-поля не воскресают, agent_key snake_case).

## (8) ArchUnit — ✅
`TaskWebhookPort` — контракт в корне execution (отклонение #1): api.impl → execution-контракт (не `execution.impl.WaitWebhookStateExecutor` — «чужой .impl» соблюдён); WebhookHandlers/контроллеры → task/workflow контракты; task (`TriggerRegistryImpl`) — common/security + config, без workflow-классов (SQL-чтение ревизии). Направления чистые.

## (9) Отклонения #1–#8 — ✅ задокументированы в apply-notes L
Порт в корне execution; WebhookHandlers в api.impl с делегацией; title/description триггер-задачи; Location ≠ capability-URL; 404-защитная ветка отсутствующего триггера; пин ревизии двумя путями (latestRev — контроллер, явный — реестр); `WorkflowRegistry.revisions` расширение; ручные мапперы графа. Плюс «Отступление от плана» (L.5 Workflows) — прозрачно.

---

## Nits (не блокируют)

- **n1**: handleTaskWebhook дважды читает задачу/граф (handler `tasks.get` + порт внутри) — 2–3 PK-чтения на вебхук; не горячий путь, оставлено на совместимость слоёв (порт пере-проверяет NOT_WAITING перед CAS) — приемлемо, при желании порт может принять снимок.
- **n2**: `TriggerRegistryImpl.list` — `criteria.limit() + 1` при null → NPE; контрактно limit обязателен (как в TaskRegistry) — желательно такую же строку Javadoc (`limit >= 1`).

## Позитив

- Единственная точка HMAC (Verifier) обслуживает и выдачу URL, и верификацию — нет расползания криптографии; constant-time везде.
- 409/410/401-семантика вебхуков и триггеров — точно по спеке inbound-triggers, включая «несуществующая задача → 409» и «CAS-промах → 409».
- Пробел плана (Workflows REST) закрыт без единого нового дизайн-решения — с отступлением, задокументированным для ревью.
- 433 теста, включая интеграции через сгенерированный клиент.

## Вердикт

**APPROVE** — незакрытых: 0 (2 nit вне критического пути). Пачка закрывает inbound-домен и последний REST-пробел; M2 готов к приёмке (M.1 ArchUnit-верификация + M.2 acceptance e2e).
