# Ревью пачки P (orchestrator metaTools) — GLM-5.3-Flash

Дата: 2026-09-19. Объект: OrchestratorTools (6 metaTools), AgentTurnEngine.executeToolCall (orchestrator-ветка + манифест-гейт), permissions_jsonb.metaTools (jsonb, без миграции). Сборки не запускались.

**Вердикт: APPROVE** — незакрытых: 0; 1 nit вне критического пути.

---

## (1) D-70 supersession / D-59 — ✅
Гейт разделён по инструментам: orchestrator-набор (`OrchestratorTools.NAMES`) — при `metaTools=true` исполняется **без** USER-source проверки (частичный supersession D-41 → ADR D-70, регистрация в S.3); `transition` — по-прежнему под полным D-59-гейтом (USER + max-per-turn, отдельная ветка executeToolCall) для **всех** агентов, включая оркестратора. Манифест: orchestrator-tools добавляются только `isOrchestrator(agent)` (строки 112–116), у обычных отсутствуют.

## (2) D-69 non-inheritance — ✅
Флаг читается из ревизии агента, **запинненной в сессии** (`agent.permissions().get("metaTools")`), а не наследуется: субагентская сессия пинится на ревизию `agentKey` субагента (O-пачка createChildSession) — у суб-кодера metaTools=false → orchestrator-tools и spawn_subagent ему недоступны (обе ветки гейта: строки 387–390 и 401–403). Горизонтальная/вертикальная эскалация закрыта.

## (3) configure_trigger — ✅
`TriggerRegistry.create(owner=session.owner, name, workflowKey, rev=null → latestRev, params, tags)` → `TOOL_RESULT { triggerId, url }` (capability-URL из реестра) — ровно по спеке orchestrator-metaTools (Open Question 3: регистрацию URL во внешней системе делает агент).

## (4) set_dependency — ✅
Новый batch-метод `TaskRegistry.addDependencies(blockedTaskId, Collection<blocker>)` — один `@Transactional` вызов реестра (атомарная пачка: distinct, self-loop pre-check, H-6-локи + DFS под локом, rollback всей пачки при любом нарушении); инструмент передаёт список целиком — частичной установки нет.

## (5) Гейт — ✅
false → `forbidden (no-metaTools)` (и вне манифеста, и на прямом вызове); true → ветка orchestrator-tools мимо D-59-гейта. Порядок веток executeToolCall: orchestrator-NAMES → spawn_subagent → transition (USER+limit) → нативные. isOrchestrator — строго `Boolean.TRUE.equals(permissions.get("metaTools"))`.

## (6) D-58 профиль — ✅
params-валидация — в реестрах (TaskRegistryImpl.createTask / TriggerRegistryImpl.create — LimitedJsonSchemaValidator по paramsSchema start_state, H-8-семантика); инструмент пробрасывает `ParamsSchemaInvalidException` → `422 params-schema` + errorsText в TOOL_RESULT. Дубля валидации на слое инструмента нет — единая точка.

## (7) ArchUnit — ✅
OrchestratorTools в `execution.impl`, импорты — только контракты task/workflow (+ common.jackson ObjectMapper) — направление execution → {task, workflow} легально, чужих `.impl` нет.

## (8) Коды ошибок — ✅
WorkflowGraphInvalid → `422 graph-invalid`; ParamsSchema → `422 params-schema`; DependencyInvalid → `422 dependency-invalid`; KeyAlreadyExists → `409 workflow-key-exists`; Workflow/RevisionNotFound и TaskNotFound → `404 workflow-not-found / task-not-found`; все — c errors[]/контекстом в тексте TOOL_RESULT, неизвестные исключения — общий error-лог + сообщение. Owner созданных сущностей — владелец сессии; автор задач — NULL (агент).

---

## Nit (не блокирует)

- **n-1**: `OrchestratorTools.ok()` пишет в TOOL_RESULT `tool = "orchestrator"` вместо конкретного имени (`create_task` и т.д.) — журнальная пара TOOL_CALL(create_task)/TOOL_RESULT(orchestrator) расходится по tool-полю (модель парит по callId — функционально безвредно, но аудит/рендер клиента теряют соответствие). Предложение: прокинуть фактическое `tool` в ok() (одна строка, пачка-доработка или S).

## Позитив

- Все 6 инструментов — над существующими контрактами реестров, 0 новых REST-операций и 0 дублирующей валидации (D-22/D-58 выдержаны).
- Ошибки реестров маппятся в машиночитаемые коды внутри TOOL_RESULT — модель может корректировать поведение (например, сменить key при 409), не роняя Turn.
- Гейт трёхслойный (манифест → ветка исполнения → отдельные ветки transition/spawn) — обход невозможен через аргументы.

## Вердикт

**APPROVE** — незакрытых: 0 (1 nit: tool-имя в TOOL_RESULT оркестраторских инструментов). Пачка закрывает orchestrator-metaTools; готова к Q (MCP-клиент).
