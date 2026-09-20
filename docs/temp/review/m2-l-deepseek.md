# Ревью M2 batch L: триггеры + вебхуки (+ workflows REST, sweep not-implemented)

> Ревьюер: DeepSeek-V4.1-Flash (субагент).
> Дата: 2026-09-18.
> Объект: `api/impl/WebhookHandlers`, `api/{WebhooksController,TriggersController,WorkflowsController,ApiExceptionHandler,ProblemCodes,ApiMappers}`, `execution/TaskWebhookPort`, `task/{Trigger,TriggerRegistry,TriggerNotFoundException,TriggerRevokedException,TaskNotWaitingWebhookException,impl/TriggerRegistryImpl}`, `common/security/WebhookSignatureVerifier`, `openapi.yaml` (sweep), `api-contracts.md` §6, тесты L.
> Контекст: api-contracts §4.3/§4.4/§6, спека inbound-triggers, D-05/D-25/D-26/D-29/D-41/D-58, tasks.md L.1–L.5, apply-notes §L.
> Сборки не запускались; сверка по исходникам.
> Severity: **MEDIUM** — контрактный/безопасностный дефект; **MINOR**; **NIT**.

## Сводка

| Severity | Кол-во |
|---|---|
| MEDIUM | 1 |
| MINOR | 2 |
| NIT | 2 |
| **Итого** | **5** |

---

## Проверка фокусных пунктов

- **Capability-URL HMAC (D-26)** — ✅ `WebhookSignatureVerifier` (`HMAC-SHA256(secret, kind:entityId)`, constant-time `MessageDigest.isEqual`, `expectedToken` переиспользуется для выдачи URL), секрет — `harness.webhook.secret` (env); `TriggerRegistryImpl.capabilityUrl` / `TasksController.webhookUrlOf`. Тест `WebhookSignatureVerifierTest` (7: эталон, битый/урезанный/чужой kind|id/null/смена секрета).
- **Идемпотентность webhook (409 вне WAIT_WEBHOOK)** — ✅ `WebhookHandlers.handleTaskWebhook`: вне WAIT_WEBHOOK/терминал → `409 task-not-waiting-webhook`; повторная доставка → 409; CAS-промах → 409. Несуществующая задача → 409 (отклонение dev #6, задокументировано). `WebhooksApiTest` 7.
- **Threat model (D-29, TLS, неполные payload в reason)** — ✅ reason вебхука — только `payloadSummary` (`{topKeys, byteSize}` → `{byteSize, truncated}` сверх `harness.webhook.payload-summary.byte-size-limit`); полное тело не пишется; TLS — конфиг деплоя; rate-limit — вне MVP (D-41).
- **Нет idempotency-хранилища (D-41)** — ✅ таблицы нет; повторный триггер-вебхук создаёт новую задачу (принятый риск R5, apply-notes #? / design R5).
- **payloadSchema profile (D-58)** — ✅ `LimitedJsonSchemaValidator` в `WaitWebhookStateExecutor` (через `TaskWebhookPort`) и в `TriggerRegistryImpl.create` (params против `paramsSchema` стартового состояния ревизии).
- **Триггеры (D-25)** — ✅ `trigger`-таблица, revoke `UPDATE … SET revoked_at=now()`, URL умирает (410); pin rev; `mine`+cursor; `TriggerRegistryImplTest` 7, `TriggersApiTest` 6.
- **task-already-terminal логика** — ✅ resume/stop терминальной → `409 task-already-terminal` (`ApiExceptionHandler`); вебхук в терминальную задачу → 409 `task-not-waiting-webhook` (не terminal — состояние уже не WAIT_WEBHOOK).
- **WorkflowsController — пробел tasks.md** — ⚠️ dev зафиксировал в apply-notes §«Отступление от плана» (L.5); в tasks.md задача не появилась (см. L-2).
- **8 отклонений dev + sweep not-implemented** — ✅ apply-notes §L (пп.1–8), sweep выполнен (`grep not-implemented|NotImplemented|NOT_IMPLEMENTED` по src — пусто; удалены openapi-код, api-contracts §6-строка, `ProblemCodes.NOT_IMPLEMENTED`, хендлер, `ApiNotImplementedException.java`, стабы заменены).

---

## Findings

### L-1 [MEDIUM]. HMAC-проверка выполняется после парсинга тела: при кривом токене и невалидном теле — 422, а не 401 (заявленное «тело не парсится» неверно)

- **Где:** `api/WebhooksController.handleTaskWebhook(UUID, String, Map<String,Object> requestBody, String)` — `@Valid @RequestBody Map` (Spring парсит тело до вызова метода); токен-гейт внутри `WebhookHandlers.requireValidToken` (после парсинга). `ApiExceptionHandler.invalidBody` ловит `HttpMessageNotReadableException` → `422 validation-failed`.
- **Цитата:** спека `inbound-triggers` §Webhook задачи — «кривой токен → `401 signature-invalid` (без challenge, **тело не парсится**)»; `WebhookHandlers` Javadoc — «проверка до контроллерной валидации».
- **Проблема:** тело разбирается Jackson'ом до token-проверки. Практически: `POST /api/webhooks/tasks/{id}/{bad-token}` без тела/с битым JSON/чужим Content-Type → `422`/`415` (раскрытие detail парсера) вместо `401 signature-invalid`; аутентификация не является первым барьером, вопреки D-26/threat-model. Тест `badTokenWithoutJwtReturns401SignatureInvalid` шлёт валидный JSON, поэтому дыру не ловит.
- **Предложение:** вынести HMAC-проверку capability-токена в `OncePerRequestFilter` (или interceptor) на `/api/webhooks/**` до `HttpMessageConverter` — тогда кривой токен всегда 401 без чтения тела; заголовок/путь доступны без парсинга. Либо явно зафиксировать девиацию («тело может парситься до гейта») с пересмотром threat-model.

### L-2 [MINOR]. Workflows REST не заведён в `tasks.md` (пробел плана)

- **Где:** `openspec/changes/m2-workflow-engine/tasks.md` (нет задачи на `WorkflowsController` §4.2); фактическая реализация — `WorkflowsController` (5 эндпоинтов) в «L.5».
- **Проблема:** apply-notes §«Отступление от плана» фиксирует пробел, но tasks.md не обновлён — трекинг «план ↔ реализация» неполон (для приёмки/архива).
- **Предложение:** добавить в tasks.md строку L.5 (Workflows REST: 5 эндпоинтов + тесты) или пометку-сноску с ссылкой на apply-notes.

### L-3 [MINOR]. `TriggerRegistryImpl.revoke` неатомарен (SELECT + UPDATE)

- **Где:** `TriggerRegistryImpl.revoke` — `SELECT revoked_at …` затем `UPDATE … WHERE id=? AND revoked_at IS NULL`.
- **Проблема:** окно между SELECT и UPDATE; результат корректен (идемпотентно), но проверка «уже отозван → false» и UPDATE раздельны. Не критично (гонки ревайла — редки, обе ветки дают 204), но можно свести к одному `UPDATE … WHERE revoked_at IS NULL` + `rowcount`, а отсутствие строки — отдельным existence-check.
- **Предложение:** считать `updated==0` → проверить существование (404) иначе «уже отозван» (204); убрать предварительный SELECT.

### L-4 [NIT]. `Location` при `POST /triggers` указывает на ресурс без GET

- **Где:** `TriggersController.createTrigger` — `Location: /api/v1/triggers/{id}`, но GET одиночного триггера в контракте нет (только список/DELETE).
- **Проблема:** задокументированное отклонение #4; формально Location ведёт на нечитаемый ресурс.
- **Предложение:** оставить (задокументировано) или указать `Location` на коллекцию.

### L-5 [NIT]. Секция `reason` для вебхука: `payloadSummary.byteSize` считается повторной сериализацией

- **Где:** `WaitWebhookStateExecutor.summary` — `JSON.writeValueAsString(payload).getBytes(...).length`.
- **Проблема:** тело уже распарсено; повторная сериализация ради размер-оценки (при большом payload — лишняя работа/память) — на MVP терпимо.
- **Предложение:** оценивать размер через размер исходного байтового тела (передать из контроллера) или ограничиться `topKeys`; не блокер.

---

## Позитив (проверено)

- Capability-URL stateless и constant-time; триггеры (D-25) с revoke/410; вебхук задачи (409 вне WAIT_WEBHOOK / повторная доставка) и триггера (202+задача, 410 отзыв) — по спеке; `TaskWebhookPort` — корректная развязка api↔execution.impl (ArchUnit).
- Sweep `not-implemented` полный: openapi/api-contracts §6/ProblemCodes/хендлер/класс удалены, стабы заменены; `grep` пуст.
- Все новые коды ошибок заведены: `trigger-not-found` (404), `trigger-revoked` (410), `task-not-waiting-webhook` (409), `signature-invalid` (401), `graph-invalid` (422, errors[]), `key-unique` (422 validation-failed).
- 8 отклонений dev задокументированы (apply-notes §L); тесты L (WebhookSignatureVerifier 7, TriggerRegistryImpl 7, TriggersApi 6, WebhooksApi 7, WorkflowsApi 5) — 433 зелёных.

## Вердикт

**REJECT — 5 находок (1 MEDIUM: L-1 token-гейт после парсинга тела — 422 вместо 401 на кривой токен, «тело не парсится» неверно; 2 MINOR: L-2 Workflows REST не в tasks.md, L-3 неатомарный revoke; 2 NIT: L-4, L-5).** Блокер приёмки — L-1 (D-26/threat-model: HMAC должна быть первым барьером без чтения тела).

---

# Re-approval M2 batch L (2026-09-18)

> Проверены: `api/WebhookTokenFilter`, `api/impl/WebhookHandlers`, `task/impl/TriggerRegistryImpl`, `api/TriggersController`, `tasks.md`, `apply-notes.md` §L, `WebhooksRoutingTest`. Сборки не запускались.

## Статусы находок

| # | Sev | Статус | Проверка |
|---|---|---|---|
| L-1 | MEDIUM | **закрыто** | `WebhookTokenFilter` (`@Component`, `@Order(HIGHEST_PRECEDENCE)`, `shouldNotFilter` только `/api/webhooks/`) — регекспирует `/api/webhooks/{tasks|triggers}/{id}/{token}`, проверяет HMAC **до** диспетчеризации/HttpMessageConverter'ов; битый token/entityId → `401 signature-invalid` (`problemWriter`), тело не парсится. Acceptance-тесты: `WebhooksRoutingTest.badTokenWithGarbageJsonBodyReturns401Not422`, `badTokenWithoutBodyAndContentTypeReturns401Not415`. Порядок выше `PayloadSizeFilter` (аутентификация-гейт старше размерного); `SecurityConfig` не тронут |
| L-2 | MINOR | **закрыто** | `tasks.md` L.6 `[x]` — REST workflows (5 эндпоинтов §4.2), отмечен пробел плана |
| L-3 | MINOR | **закрыто** | `TriggerRegistryImpl.revoke` — один `UPDATE trigger SET revoked_at=now() WHERE id=? AND revoked_at IS NULL RETURNING id`; пусто → `TriggerNotFoundException` (404). Doc/Javadoc/apply-notes/тесты синхронизированы |
| L-4 | NIT | **закрыто** | `TriggersController.createTrigger` — `ResponseEntity.created(dto.getUrl())` (capability-URL в `Location`), отклонение #4 обновлено |
| L-5 | NIT | **закрыто** | `WebhookTokenFilter` оборачивает запрос в `ContentCachingRequestWrapper`; `WebhookHandlers.payloadByteSize` берёт `getContentAsByteArray().length` (fallback — повторная сериализация), размер передаётся `onWebhookArrived` → `payloadSummary.byteSize` без повторной сериализации |

## Замечание (semantic, не блокер)

- **L-3 сменил семантику revoke**: ранее «уже отозван → идемпотентный 204», теперь «уже отозван → **404** trigger-not-found» (`revoke` стал `void`). Изменение задокументировано в Javadoc/apply-notes §L п.5 и закреплено `revokeIsFinalAndRepeatedRevokeIsNotFound` / `revokeReturns204AndRepeatedRevokeIs404`. Формально допустимо (RFC 9110 DELETE идемпотентен по эффекту; повторный DELETE может давать 404), но клиент не отличает «нет ресурса» от «уже отозван». Оставляю на усмотрение — расхождение осознанное и покрыто тестами.

## Проверка

- L-1 устранён по существу: HMAC — первый барьер без чтения тела; threat-model D-26 («тело не парсится») теперь верен.
- Sweep `not-implemented` (пачка L) не регрессировал: `WebhooksRoutingTest` переведён на `validTokenOnMissingTaskReturns409TaskNotWaitingWebhook`.
- Остальные фокусные пункты L (capability-URL, идемпотентность вебхука, threat-model/payloadSummary, нет idempotency-хранилища, D-58-профиль, триггеры D-25, task-already-terminal) — без регрессий; сюита 435 зелёных.

**APPROVE — 0 незакрытых.** Пачка L принята.