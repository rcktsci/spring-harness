# Ревью M3 batch P: оркестратор-metaTools (D-62/D-69/D-70)

> Ревьюер: DeepSeek-V4.1-Flash (субагент).
> Дата: 2026-09-20.
> Объект: `execution/impl/OrchestratorTools`, `execution/AgentTurnEngine` (манифест/диспетчер/гейт), тест `OrchestratorMetaToolsTest`, `apply-notes.md` §P.
> Контекст: `agent-tools.md` §2b/§4, `workflow-domain.md` §6, `api-contracts.md` §2/§4.1, `decisions.md` (D-38/D-41/D-52/D-59), спека `orchestrator-meta-tools`, tasks P.1–P.4/S.3.
> Сборки не запускались; сверка по исходникам.
> Severity: **MEDIUM**; **MINOR/NIT**.

## Сводка

| Severity | Кол-во |
|---|---|
| MEDIUM | 1 |
| MINOR | 3 |
| NIT | 2 |
| **Итого** | **6** |

---

## Проверка фокусных пунктов

### (1) D-70 — supersession D-41 в части metaTools — ✅ по содержанию (см. P-1/P-2 про регистрацию)
- **apply-notes §P** формулирует корректно: `permissions_jsonb.metaTools = true` (per-agent-revision, D-31) открывает 6 оркестраторских metaTools + `spawn_subagent` **без** D-59-гейта; **частично** supersede D-41 (для этих инструментов гейт возвращается), `transition` остаётся под D-59 (USER-source + лимит), обычные агенты — инструменты скрыты. Альтернативы (чистый D-41 / полный ACL-4 D-38) отвергнуты. Цепочка D-38→D-41→D-59→D-70 логична.
- **Оговорка:** формулировка «D-41 остаётся в силе для обычных агентов (metaTools=false, инструменты скрыты)» неточна: `metaTools`-флаг — это гейт для **всех** агентов (без флага инструменты скрыты), т.е. D-41 в части «гейтов нет» для этих инструментов не действует ни для кого; фактически D-41 сохранён лишь для `transition` (D-59) и отсутствия иных ACL. Стоит уточнить.
- **Регистрация:** **D-70 отсутствует в `docs/design/decisions.md`** (журнал заканчивается D-59); apply-notes держит решение, регистрация запланирована S.3 («ADR D-60…D-70»). До S-пачки ADR не зафиксирован — приемлемо по плану, но проверить исполнение S.3.

### (2) D-69 — subagent non-inheritance — ✅ (с оговоркой)
- Реализация: `AgentTurnEngine.isOrchestrator(agent)` читает `permissions_jsonb.metaTools` **своей** ревизии сессии; дочерняя сессия пинит ревизию по `agentKey` спавна (`SubagentSpawner` → `createChildSession` → latest ревизия этого агента). «Наследование» флага от родителя невозможно по построению — соответствует D-69 («оркестратор-metaTools не наследуется»; у sub-orchestrator с собственным `metaTools=true` sub-spawn разрешён).
- **Оговорка (P-6):** tasks R.1 формулирует жёстче («при spawn_subagent субагент-агент — metaTools=false») — код флаг не форсирует; поведение по design D-69 (per-agent-declaration), но теста «spawn'нутый ребёнок без своего metaTools не может звать orchestrator-tools» нет (есть только `explicitSpawnByPlainAgentIsForbidden` — про спавн, не про гейт инструментов ребёнка).

### (3) `workflow-domain.md §6` «Инструменты оркестратора» — ❌ не обновлён (P-1)
- §6 (стр. 66–68) перечисляет `create_workflow`/`edit_workflow`/`create_task`/`create_subtask`/`set_dependency`/`configure_trigger` **без** условия доступа (`permissions_jsonb.metaTools=true`) и без D-70. `AgentTurnEngine` же добавляет их в манифест только оркестраторам и гейтит `forbidden (no-metaTools)`. Design-док рассинхронизирован с кодом; в плане синхронизации (S.3 перечисляет только `agent-tools.md §2/§4`) §6 не упомянут.

### (4) `api-contracts.md` §2/§4.1 — `owner` в TaskDto — ❌ не уточнён (P-2)
- `SessionDto` §2 явно: «`owner` — **username** владельца…»; `TaskDto` (§4.1, стр. 72) перечисляет `owner` **без** пояснения, что это username (резолв `owner_user_id → username`, D-41 JWT). При этом `OrchestratorTools` проставляет owner созданных задач = `session.ownerUserId()` (owner-наследование, не JWT) — стоит зафиксировать это в §4.1 и согласовать формулировку с §2.

---

## Findings

### P-1 [MEDIUM]. `workflow-domain.md §6` не синхронизирован с metaTools-гейтом и не включён в план (S.3)
- **Где:** `docs/design/workflow-domain.md:66-68`; `tasks.md` S.3; `execution/impl/OrchestratorTools` / `AgentTurnEngine` (гейт).
- **Цитата:** §6: «`create_workflow` / `edit_workflow`…, `create_task`…, `set_dependency`…, `configure_trigger`…» (без условий); S.3: «синхронизация `docs/design/agent-tools.md` §2/§4 с M3-решениями».
- **Проблема:** §6 не отражает D-62/D-70 (доступ только `metaTools=true`, без D-59-гейта), и не заведён в задачи синхронизации — риск, что при закрытии M3 §6 останется неверным.
- **Предложение:** добавить в §6 строку о гейте (`permissions_jsonb.metaTools=true`, D-62/D-70) и включить §6 в S.3.

### P-2 [MINOR]. `api-contracts.md`: `TaskDto.owner` не задокументирован как username (и не в плане синхронизации)
- **Где:** `docs/design/api-contracts.md:72` (`TaskDto`) vs `:40` (`SessionDto`).
- **Проблема:** §2 для SessionDto явно указывает `owner` = username (D-41 JWT-резолв); для TaskDto то же не сказано; созданные оркестратором задачи наследуют owner от сессии (`OrchestratorTools:202`).
- **Предложение:** уточнить в §4.1: `owner` — username (резолв `owner_user_id`), при агентском/триггерном создании — от сессии/триггера; добавить в S.3.

### P-3 [MINOR]. `create_task`/`create_subtask` не принимают явный `rev`
- **Где:** `OrchestratorTools.CreateTaskArgs` / `CreateSubtaskArgs` (нет `rev`); `createTask` всегда берёт `latestRev`.
- **Цитата:** agent-tools §2b: «`create_task` … пин последней ревизии (**или явной**)».
- **Проблема:** инструмент не может запинить конкретную ревизию workflow (расхождение с каталогом/§2b).
- **Предложение:** добавить опциональный `rev` (передавать в `workflows.getRevision`), либо зафиксировать latest-only как осознанное решение.

### P-4 [MINOR]. `create_workflow`/`edit_workflow` не валидируют kebab-case `key`
- **Где:** `OrchestratorTools.createWorkflow` (проверка только non-blank); REST-контракт `CreateWorkflowRequest.key` имеет `@Pattern` kebab-case.
- **Проблема:** оркестратор может создать workflow с ключом вне контракта (`key` — kebab-case по api-contracts §0.8/§4.2, workflow-engine spec).
- **Предложение:** валидировать `^[a-z0-9]+(-[a-z0-9]+)*$` (общий хелпер с REST) → `422 validation-failed`.

### P-5 [NIT]. `ok(...)` пишет `tool = "orchestrator"` вместо фактического имени инструмента
- **Где:** `OrchestratorTools.ok` — `ToolResult.ok(callId, "orchestrator", …)`.
- **Проблема:** в `TOOL_RESULT` отображается `orchestrator` (дисплей покрывается `pendingCall.tool()` в движке, но поле `result.tool` неконсистентно для прямых вызовов/логов).
- **Предложение:** передавать реальное имя инструмента.

### P-6 [NIT]. Нет теста гейта инструментов у субагента (D-69)
- **Где:** `OrchestratorMetaToolsTest` (9), `SubagentSpawnTest.explicitSpawnByPlainAgentIsForbidden`.
- **Проблема:** D-69 «subagent non-inheritance» фактически обеспечивается per-agent-revision, но теста «ребёнок без своего `metaTools=true` не может вызвать orchestrator-tools» нет.
- **Предложение:** добавить интеграционный тест (оркестратор → spawn worker → worker вызывает `create_task` → `forbidden (no-metaTools)`).

---

## Позитив (проверено)

- Гейт: 6 оркестраторских инструментов + `spawn_subagent` добавляются в манифест только при `metaTools=true`; явный вызов без флага → `forbidden (no-metaTools)`; `transition` под D-59 не тронут; `read_compacted` — всем.
- Owner-наследование: `createWorkflow`/`createTask`/`configureTrigger` используют `session.ownerUserId()` (не JWT, не ключ агента); `author_user_id = null` (агент).
- `set_dependency` — атомарная пачка `TaskRegistry.addDependencies` (K-1), без частичного коммита; ошибки реестров маппятся в машиночитаемые коды (`graph-invalid`/`params-schema`/`dependency-invalid`/`workflow-not-found`/`workflow-key-exists`).
- Реализация поверх существующих контрактов/реестров (не ломает contract-first); пакет `execution/impl` (без ArchUnit-цикла).

## Вердикт

**REJECT — 6 находок (1 MEDIUM: P-1 `workflow-domain.md §6` не синхронизирован с metaTools-гейтом и не в плане S.3; 3 MINOR: P-2 TaskDto.owner, P-3 нет `rev`, P-4 нет kebab-валидации key; 2 NIT: P-5, P-6).** Блокер приёмки — P-1 (дизайн-док противоречит реализации и выпал из плана синхронизации).

---

# Re-approval M3 batch P (2026-09-20)

> Проверены: `OrchestratorTools`, `tasks.md` S.3, `api-contracts.md` §4.1, `OrchestratorMetaToolsTest`. Сборки не запускались.

## Статусы находок

| # | Sev | Статус | Проверка |
|---|---|---|---|
| P-1 | MEDIUM | **закрыто** | `tasks.md` S.3 дополнен: «sync `docs/design/workflow-domain.md` §6 + `docs/design/api-contracts.md` §4.1 (`TaskDto.owner` = username, как SessionDto §2 — D-41)» — §6 и §4.1 включены в план синхронизации apply-фазы. `api-contracts.md:72` уже дополнен пояснением `owner` = username (резолв `owner_user_id → username`) и `author?` (username или NULL для агента) |
| P-2 | MINOR | **закрыто** | `api-contracts.md` §4.1 `TaskDto.owner` — «**username** владельца (как `owner` в SessionDto §2: `preferred_username` из JWT; резолв `owner_user_id → username`)»; `author?` уточнён |
| P-3 | MINOR | **закрыто** | `CreateTaskArgs`/`CreateSubtaskArgs` получили `Integer rev`; `createTask` использует `workflows.getRevision(key, requestedRev == null ? latestRev : requestedRev)`; описание инструмента «…or to rev when given explicitly»; тест `createTaskWithExplicitRevPinsThatRevision` |
| P-4 | MINOR | **закрыто** | `KEY_PATTERN = "[a-z][a-z0-9-]*"`; `createWorkflow`/`editWorkflow` → `422 validation-failed (rule=kebab-case)` при несоответствии; тест `createWorkflowRejectsNonKebabKey` |
| P-5 | NIT | **закрыто** | `ok(callId, tool, output)` принимает фактическое имя инструмента (callers: `CREATE_WORKFLOW`, `EDIT_WORKFLOW`, `CREATE_TASK`, `SET_DEPENDENCY`, `CONFIGURE_TRIGGER`) — `ToolResult.tool` больше не `"orchestrator"` |
| P-6 | NIT | **закрыто** | Тест `OrchestratorMetaToolsTest.subagentOfOrchestratorCannotCallOrchestratorTools` — spawn'нутый ребёнок без своего `metaTools=true` не может вызвать orchestrator-tools (D-69) |

## Проверка

- Все 6 находок закрыты; гейт (`metaTools=true`), owner-наследование (`session.ownerUserId()`), атомарный `set_dependency`, машиночитаемые коды ошибок и D-59-неприкосновенность `transition` — без регрессий.
- D-70 остаётся в apply-notes; регистрация в `decisions.md` — по S.3 (в плане), плюс синхронизация `workflow-domain.md §6`/`api-contracts §4.1` там же зафиксирована.
- `mvn verify` 477.

## Остаток (не блокирует)

- Косметика `tasks.md`: дублирующаяся строка T.1 (дважды) — почистить при финальной вычитке S.3.

**APPROVE — 0 блокеров.** Пачка P принята.