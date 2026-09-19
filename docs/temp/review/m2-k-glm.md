# Ревью пачки K (REST-поверхность задач, suspend/stop, дерево) — GLM-5.3-Flash

Дата: 2026-09-19. Объект: api/{TasksController, TaskCommandsController, SessionsController.tree}, session/SessionStoreImpl.findSubtree, task/TaskRegistryImpl (listComments, emit task.status, blocked-changed wake), TaskWakeDispatcher.handleStop, execution TurnManager.requestStop, WebhookProperties, ApiExceptionHandler, тесты (TasksApiTest, TaskCommandsApiTest). Сборки не запускались.

**Вердикт: REJECT** — 2 находки (1 minor / 1 nit). Контракт-поверхность соответствует замороженной спеке почти полностью; блокирует N+1 по usernames на самом горячем списковом пути.

---

## (1) Покрытие операций спеки — ✅
TasksController: create (201+Location), subtasks (404 на отсутствующего родителя), get, list (parent/status/mine/tags/q/cursor/limit — clamp на `limits.page`), patch, dependencies POST/DELETE, history, comments GET/POST, tree — все 11 операций сгенерированного TasksApi; TaskCommandsController — suspend/resume/stop. Ответы/коды — по §4.1 (201+Location, 204, 202, 404/409/422).

## (2) patch: params иммутабельны — ✅
`MergePatchBodyContext.current()` (сырое дерево из M1-конвертера): `patch.has("params")` → 422 `rule=immutable` — ловит и `"params": null` (наличие члена достаточно по RFC 7396-семантике запрета). title/description NOT NULL: явный null → 422 (отклонение #2), blank → 422; tags: null → очистка, массив → замена. Тело патча без members — no-op с 200.

## (3) Комментарии — ✅
`authorUserId = USER ? caller.userId() : null` (M2: все входы API — USER, D-59; агентская ветка — M3); append-only через реестр; listComments — батч-резолв usernames одной выборкой; 201 + Location.

## (4) sessions/tree — ✅
`SessionStoreImpl.findSubtree` — recursive CTE (depth, ORDER BY depth/created_at/id), корень первым, depth-фильтр `?::int IS NULL OR depth <= ?::int`; контроллер — плоский список узлов (родство несёт parentSessionId — по спеке §2), STATE-узлы несут taskId/stateCode (SELECT включает s.task_id/s.state_code, ApiMappers.toNode), agent summaries батчем.

## (5) suspend/resume/stop (K.2) — ✅
suspend — идемпотентный флаг (cascade — recursive UPDATE; null-тело — каскад=false, lenient); resume — `SELECT … FOR UPDATE` до проверки терминальности (H-4), 409 task-already-terminal, снятие флага + task-wake после коммита; stop — всегда каскад (поддерево, без параметра), узлы под `FOR UPDATE` (включая parent_task_id), CAS в `'$CANCELLED'` c гвардом `status IN (RUNNING,WAITING)` без suspended (§7.2), история kind=CANCEL в той же транзакции; отмена Turn'ов STATE-сессий задачи и подзадач — `TaskWakeDispatcher.handleStop` по wake после коммита (`CANCELLED_STATE` → handleStop, execution → session по контракту) → 202. task.status-кадры suspend/stop эмитирует реестр после коммита (закрыта отложка J-отклонения №4).

## (6) Зависимости (K.3) — ✅
addDependency: self-loop → 422; H-6-лок обеих задач (ORDER BY id FOR UPDATE) → цикл-DFS под локом → 422 `dependency-invalid (cycle)`; после persist — wake «blocked-changed» (универсальная переоценка барьеров диспетчером); несуществующий blocker → 422 `unknown-task` (отклонение #3: 404 — только для {id} пути); DELETE отсутствующего ребра — идемпотентный 204.

## (7) ApiExceptionHandler — ✅
Расширен: task-not-found, workflow-not-found (включая WorkflowRevisionNotFound), task-already-terminal (409), dependency-invalid (422), params-schema (422), signature-invalid, not-implemented + каталог M1. RFC 9457 + code/errors — единый писатель.

## (8) ArchUnit — ✅
api → workflow (`WorkflowRegistry` + nested-records) и api → task (`TaskRegistry`, `Comment`, …) — разрешено расширением из пачки H; execution (`TaskWakeDispatcher.handleStop`) → session через контракт `StateSessionService`; common.security (`WebhookSignatureVerifier`) — технический пакет, доступен всем; встречных `.impl`-импортов нет.

## (9) Отклонения #1–#5 — ✅ все видны в коде
#1 owner=JWT (USER; наследование от родителя — M3, задокументировано в Javadoc контроллера); #2 description null → 422; #3 unknown blocker → 422 dependency-invalid; #4 suspended-гвард stop по status_projection (§7.2) — CAS проходит по suspended-задаче (stop выигрывает), resume снимает; #5 `harness.webhook.base-url` — required в compact-constructor WebhookProperties (fail-fast на старте, вместе с secret).

---

## Находки

### M-1 (minor). listTasks: N+1 по usernames на самом горячем списковом пути (D-34)
- **Цитата**: TasksController.toDto → `usernameOf(task.ownerUserId())` + `usernameOf(task.authorUserId())` (строки 287, 301–303) — `users.usernames(List.of(...))` на каждую задачу страницы; вызывается из listTasks для каждого item.
- **Проблема**: страница до `limits.page` (50) → до 100 точечных SELECT на один запрос списка; D-34 прямо называет списковые API «самыми горячими путями» (ради них заводились денормализации). В этой же пачке listTaskComments делает батч-резолв (`users.usernames(authorIds)` одной выборкой) — непоследовательность.
- **Предложение**: собрать owner/author id страницы → один `users.usernames(ids)` → карта; toDto принимает готовую карту (как в comments/tree).

### N-1 (nit). suspendTask принимает null-тело вопреки required
- **Цитата**: `boolean cascade = suspendTaskRequest != null && Boolean.TRUE.equals(…)` — при отсутствующем теле (спека D.1: `required: [cascade]`) контроллер молча трактует как cascade=false.
- **Проблема**: расхождение с замороженным контрактом в мягкую сторону (HTTP-слой @Required обычно отфильтрует пустое тело раньше; ветка — мёртвая защита). Не ломает клиентов, но тишина против спеки.
- **Предложение**: либо убрать lenient-ветку (довериться @Valid-Required генерата), либо зафиксировать в apply-notes K как осознанную мягкость.

---

## Позитив

- Все 5 отклонений #1–#5 реализованы ровно и видны в коде без раскопок; Javadoc контроллеров ссылается на пункты спеки и решения (D-54/D-59/§4.1/§7.2).
- stop-оркестрация разложена по слоям чисто: реестр — CAS/история в транзакции; диспетчер — отмена Turn'ов после коммита; HTTP — 202.
- InvalidCursorException → 422 (E-J-3), clamp limit без хардкода, capability-URL только в WAIT_WEBHOOK.

## Вердикт

**REJECT** — 2 находки (1 minor: M-1 N+1 usernames в listTasks; 1 nit: lenient null-тело suspend). После батч-резолва usernames (и фиксации nit) — approve.

---

# Re-approval пачки K (2026-09-19)

## Статус моих находок

- **M-1 (N+1 usernames в listTasks)** — **закрыто**: enricher-паттерн (K-4) — usernames (owner+author) и выжимки пиннутых ревизий резолвятся одним `WHERE id IN` запросом на страницу (`enricherOf(items).toDto(task)`), единичные задачи — `enricherOf(List.of(...))`; comments/tree — как было, батчем. Горячий путь D-34 выровнен.
- **N-1 (lenient null-тело suspend)** — **закрыто**: отсутствующее тело или `cascade == null` → явный `422 rule=required` — контракт `required: [cascade]` больше не обходится.

## Вердикт re-approval

**APPROVE** — незакрытых: 0. Пачка K (REST-поверхность задач, suspend/resume/stop, дерево сессий/задач) соответствует замороженной спеке; готова к L (вебхуки) и M.2 (acceptance).
