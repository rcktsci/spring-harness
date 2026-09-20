# Ревью ченджа `m3-agent-layer` (плановые артефакты)

> Ревьюер: DeepSeek-V4.1-Flash (субагент).
> Дата: 2026-09-20.
> Объект: `openspec/changes/m3-agent-layer/{proposal.md, design.md, tasks.md, specs/*/spec.md}`.
> Контекст: `docs/design/{execution-model,agent-tools,roadmap,decisions,data-model,architecture}.md`, M1/M2-спеки (`openspec/specs/*`), миграции M1 (`005/006`).
> `openspec validate m3-agent-layer --strict` → **exit 1** (2 warning'а, см. N-1). Сборки не запускались.
> Severity: **HIGH** — ломает контракт/неисполнимо; **MEDIUM** — внутреннее противоречие/расхождение; **MINOR/NIT** — косметика.

## Сводка

| Severity | Кол-во |
|---|---|
| HIGH | 3 |
| MEDIUM | 8 |
| MINOR/NIT | 6 |
| **Итого** | **17** |

---

## HIGH

### H-1. `task_event_seq` ошибочно назван каналом позднего `TOOL_RESULT` сессии
- **Пункт:** proposal.md:7; design.md D-60 (стр. 28); specs/async-instruments/spec.md:9,28.
- **Цитата:** «поздний `TOOL_RESULT` с `late=true` через существующий канал **`task_event_seq`** сессии»; design D-60: «`task_event_seq` (M2) инкрементируется»; async-instruments: «existing M2 `task_event_seq`-механизм **для сессии**».
- **Проблема:** `task_event_seq` — счётчик **задач** (M2 `task.task_event_seq`, SSE `tasks/{id}/events`, data-model §4). Канал сессии — `session_message.seq` + `message.created` через `InMemorySessionEventBroadcaster` (M1). Смешение task-канала и session-канала противоречит M1/M2-спекам.
- **Предложение:** заменить на «`session_message.seq` / `message.created` (M1 `InMemorySessionEventBroadcaster`)» во всех трёх артефактах.

### H-2. D-61 хранит `depth` в `session.params_jsonb`, которого не существует
- **Пункт:** design.md D-61 (стр. 31-33); tasks.md 2.1.
- **Цитата:** «`depth` хранится в `session.params_jsonb`… `depth = parent.params_jsonb.depth + 1`»; tasks 2.1: «`depth = parent.params_jsonb.depth + 1` через `params_jsonb` (D-61)».
- **Проблема:** у таблицы `session` (data-model §5; миграция 005) **нет колонки `params_jsonb`**; `params_jsonb` есть у `task` и `trigger`, но не у сессии. Механизм хранения depth неисполним; миграция колонки `session.depth` при этом «отклонена».
- **Предложение:** либо принять миграцию `session.depth` (и снять «0 новых миграций»), либо хранить depth в `agent_revision`/отдельном поле; привести D-61/tasks в соответствие.

### H-3. `ASYNC_ACCEPTED` как запись журнала требует нового `MessageKind` (миграции), вопреки «0 новых миграций»
- **Пункт:** specs/async-instruments/spec.md:9; specs/agent-turn/spec.md:7; proposal.md:15/33-34; design.md D-65.
- **Цитата:** «журнал фиксирует **`TOOL_CALL(callId) + ASYNC_ACCEPTED(callId)`**»; proposal: «**БД**: 0 новых таблиц, 0 новых миграций»; при этом `ck_session_message__kind CHECK (kind IN ('USER','ASSISTANT','SYSTEM','TOOL_CALL','TOOL_RESULT','COMPACT'))` (миграция 006).
- **Проблема:** `ASYNC_ACCEPTED` фигурирует и как `MessageKind` (запись журнала), и как `ToolStatus` (agent-tools.md §5). Если это kind — нужен ALTER CHECK-констрейнта (миграция) и обновление `MessageKind` enum M1; если это статус `TOOL_RESULT` — формулировка «две записи» неверна (будет `TOOL_CALL` + `TOOL_RESULT(status=ASYNC_ACCEPTED)`).
- **Предложение:** определить однозначно (рекомендую: `TOOL_RESULT` со `status=ASYNC_ACCEPTED`, без нового kind) и убрать противоречие с «0 миграций».

---

## MEDIUM

### M-1. Capability `orchestrator-metaTools` — не kebab-case
- **Пункт:** proposal.md:23; каталог `specs/orchestrator-metaTools/`; tasks.md 6.4.
- **Цитата:** `` `orchestrator-metaTools` ``.
- **Проблема:** остальные capability — kebab-case (`async-instruments`, `subagent-lifecycle`, `mcp-client`); camelCase нарушает конвенцию имён (и путь архивации `openspec/specs/orchestrator-metaTools`).
- **Предложение:** переименовать в `orchestrator-meta-tools` (папка + все упоминания).

### M-2. `PARKED_ASYNC`: исход Turn'а vs `runtimeStatus`
- **Пункт:** design.md D-60 (стр. 28); tasks.md 1.2.
- **Цитата:** D-60: «парковка `PARKED_ASYNC` — **новый исход Turn'а** (`runtimeStatus = PARKED_ASYNC`)»; tasks 1.2: «**Расширение `last_turn_outcome` enum**».
- **Проблема:** в M2 `PARKED_ASYNC` — значение `runtimeStatus` сессии (`IDLE|TURN_RUNNING|PARKED_ASYNC|PARKED_CLIENT`), а `last_turn_outcome` (`TurnOutcome`) = `COMPLETED|FAILED|CANCELLED`. Одновременное «исход Turn'а» и «runtimeStatus», плюс расширение `last_turn_outcome` — конфликт моделей.
- **Предложение:** зафиксировать: `PARKED_ASYNC` — только `runtimeStatus` (сессия), `last_turn_outcome` не расширять (или явно ввести новое значение TurnOutcome и обосновать).

### M-3. Пачки: метки `N..S` отсутствуют, состав ≠ design R1, «>1 подсистемы на пачку»
- **Пункт:** design.md R1 (стр. 69); tasks.md §1–§6.
- **Цитата:** R1: «6 пачек (N..S — async/spawn+orchestrator/MCP/**revisions+wiring**/acceptance)».
- **Проблема:** tasks.md использует числовые секции 1..6 без меток N..S; в списке design есть «revisions+wiring», которого нет в tasks (там §5 «ACL/owner + рестарт-скан»). Секция 2 объединяет spawn+read_compacted+subtree-cancel, секция 5 — ACL/owner + restart-scan → нарушает «≤1 подсистемы на пачку».
- **Предложение:** переименовать пачки в N..S, привести состав к design R1, разнести §2/§5 по подсистемам.

### M-4. Доступность `spawn_subagent`: каталог agent-tools.md §2 vs M3
- **Пункт:** agent-tools.md:25; specs/subagent-lifecycle/spec.md:9,19.
- **Цитата:** agent-tools.md: «`spawn_subagent` … **любая сессия**»; subagent-lifecycle: «Гейт metaTools: для обычных агентов инструмент **НЕ доступен** (только оркестраторы)».
- **Проблема:** M3 меняет доступность инструмента, но design-док `agent-tools.md` не обновлён → рассинхрон каталога и спеки.
- **Предложение:** обновить agent-tools.md §2 (доступность только `metaTools=true`) или зафиксировать как отклонение.

### M-5. Дублирование требований `spawn_subagent`/`read_compacted` между `agent-turn` и `subagent-lifecycle`
- **Пункт:** specs/agent-turn/spec.md:26-52; specs/subagent-lifecycle/spec.md:7-70.
- **Проблема:** обе capability объявляют требования `spawn_subagent` и `read_compacted`; при архивации неясно, в какую именно capability попадает требование (двойное владение → расхождение при sync).
- **Предложение:** оставить `spawn_subagent`/`read_compacted` в `subagent-lifecycle`, а в `agent-turn` — только интеграционную часть (доступность в Turn'е/гейт) со ссылкой.

### M-6. D-62/D-69 противоречат D-41 без явного supersession
- **Пункт:** design.md D-62 (стр. 36), D-69 (стр. 64); decisions.md D-41.
- **Цитата:** D-41: «Выброшено: … делегирование (**D-32/D-38-формула**, instructionSource, **metaTools-гейт**)»; D-62: «`permissions_jsonb.metaTools` … (D-41 де-скоуп)».
- **Проблема:** M3 возвращает `permissions_jsonb.metaTools` (механизм D-38) и оркестраторский путь, но не оформляет supersession D-41 явной строкой (в отличие от D-59). Цепочка D-38→D-41→D-59→D-62 не задокументирована.
- **Предложение:** ввести ADR (D-62/D-69) с явной формулировкой «частично supersede D-41 в границах M3» (по образцу D-59).

### M-7. Roadmap M3 («ревизии агентов + llm-профили») не покрыт ченджем
- **Пункт:** docs/design/roadmap.md (M3); design.md R6 (стр. 74), Open Questions 1 (стр. 94).
- **Цитата:** roadmap M3: «**ревизии агентов + llm-профили**…»; design R6: «нового поля profile **не вводим**… решение переносится в apply-фазу».
- **Проблема:** объём M3 из roadmap включает llm-профили; чендж их откладывает (Open Question), не помечая как Non-Goal/scope change.
- **Предложение:** либо включить профили, либо явно перенести в M4/эволюцию с правкой roadmap и Non-Goals.

### M-8. `agent-tools.md §4` (`allowedTools`, `workspaceScope`) не покрыт и не де-скоуплен явно
- **Пункт:** agent-tools.md:47-51; design.md Non-Goals (стр. 15-23).
- **Цитата:** agent-tools.md §4: «`allowedTools` — белый список… `workspaceScope` — поддерево путей…».
- **Проблема:** M3 добавляет `metaTools` в `permissions_jsonb`, но `allowedTools`/`workspaceScope` не реализует и не выносит в Non-Goals (там только про D-38-множитель). Неясно, действуют ли они.
- **Предложение:** явно причислить `allowedTools`/`workspaceScope` к вне-M3 (или реализовать) и синхронизировать agent-tools.md.

---

## MINOR / NIT

### N-1. `openspec validate --strict` — 2 предупреждения (нет SHALL/MUST)
- **Пункт:** mcp-client/spec.md «Default MCP — пусто»; orchestrator-metaTools/spec.md «Защита metaTools от injection».
- **Проблема:** требования без RFC-2119 глагола → warning, exit 1.
- **Предложение:** добавить SHALL/MUST в формулировки требований.

### N-2. Placeholder `D-M3-1` не разрешён
- **Пункт:** design.md D-61; specs/subagent-lifecycle/spec.md:23.
- **Цитата:** «миграция под новую колонку — **точка решения D-M3-1** в design».
- **Предложение:** заменить на конкретное решение/номер ADR.

### N-3. «BREAKING … аддитивно — без bump» — противоречивая маркировка
- **Пункт:** proposal.md:16.
- **Предложение:** переформулировать как «аддитивное расширение (без BREAKING)».

### N-4. Расхождения конфиг-имён
- **Пункт:** proposal.md:35 (`harness.mcp.refresh-token-buffer-min`, `spawn.maxDepth`) vs design Migration Plan (`harness.spawn.max-depth`, `harness.mcp.auth.proxy-url`).
- **Предложение:** свести к единым kebab-case именам; `refresh-token-buffer-min` отсутствует в design/mcp-spec.

### N-5. design Context ссылается на архивированный M2
- **Пункт:** design.md:3 — «`2026-09-20-m2-workflow-engine`».
- **Проблема:** архивация M2 (N.1) не выполнена (AGENTS.md), путь `openspec/changes/m2-workflow-engine`.
- **Предложение:** поправить путь/статус.

### N-6. D-64 «lock строки `session_message` по `callId`» — callId не колонка
- **Пункт:** design.md D-64 (стр. 44).
- **Цитата:** «lock строки `session_message` по callId».
- **Проблема:** `callId` лежит в `payload_jsonb` (не колонка) — лок «по callId» невыразим без индекса/колонки.
- **Предложение:** уточнить механизм (индекс по `payload_jsonb->>'callId'` или вынести корреляцию).

### N-7. execution-model §3 п.1 (смесь sync+async) не отражён
- **Пункт:** specs/async-instruments, specs/agent-turn; execution-model.md:67.
- **Проблема:** описано поведение при превышении окна одним async-инструментом; смешанный раунд sync+async (условие 1 §3) явно не заспецифицирован.
- **Предложение:** добавить сценарий смешанного раунда.

---

## Проверка по чек-листу промпта

| # | Пункт | Статус |
|---|---|---|
| 1 | Внутренняя непротиворечивость | ❌ H-1, H-2, M-2, M-3, M-4, N-4, N-5 |
| 2 | Покрытие execution-model §3/§4/§5 + agent-tools §1-§4 | ⚠️ §4 tools покрыты; M-8 (`allowedTools`/`workspaceScope`), N-7 (mixed sync+async), M-4 (каталог) |
| 3 | D-60…D-69 vs D-01…D-59 | ❌ M-6 (D-41), M-2 (PARKED_ASYNC); D-09/D-21/D-44/D-59 — ок |
| 4 | Capabilities kebab-case | ❌ M-1 (`orchestrator-metaTools`) |
| 5 | Delta headers ADDED/MODIFIED | ⚠️ технически ок (ADDED у новых, ADDED-only у existing), но N-1 (warning'и), M-5 (двойное владение) |
| 6 | `agent-turn` delta — только ADDED | ✅ (нет MODIFIED M2-transition; H1 + ADDED) |
| 7 | tasks ≤1 подсистемы на пачку (6 пачек N..S) | ❌ M-3 (метки, состав, §2/§5 многоподсистемны) |

## Вердикт

**REJECT — 17 находок (3 HIGH: H-1 `task_event_seq` вместо session-seq, H-2 несуществующий `session.params_jsonb`, H-3 `ASYNC_ACCEPTED` vs миграции; 8 MEDIUM: M-1…M-8; 6 MINOR/NIT: N-1…N-6/N-7).** Блокеры заморозки: H-1, H-2, H-3, M-1 (kebab-case), M-2 (PARKED_ASYNC), M-3 (пачки/traceability).

---

# Re-approval m3-agent-layer (2026-09-20)

> Проверены: `proposal.md`, `design.md`, `tasks.md`, `specs/*/spec.md`. `openspec validate m3-agent-layer --strict` — **valid (exit 0)**. Сборки не запускались.

## Статусы находок

| # | Sev | Статус | Проверка |
|---|---|---|---|
| H-1 | HIGH | **закрыто** | Везде сессионный канал: `` message.created ``/`session_message.seq` (M1 `InMemorySessionEventBroadcaster`), инкремент `last_seq`; design D-60 явно оговаривает «M2 `task_event_seq` (счётчик задач) на сессионном канале НЕ используется» (proposal §7/§21, design D-60/D-68, async-instruments §9/§28) |
| H-2 | HIGH | **закрыто** | D-61 переписан: `depth` в колонке `session.depth INT NOT NULL DEFAULT 0`, миграция `074_create_column_session_depth.xml`; «у `session` нет `params_jsonb`» признано явно (design D-61, proposal §15/§34, tasks N.0/O.1/R.2) |
| H-3 | HIGH | **закрыто** | `ASYNC_ACCEPTED` — новое значение `MessageKind`, миграция `075_alter_session_message_kind_add_async_accepted.xml` (ALTER CHECK `ck_session_message__kind`); «0 миграций» заменено на «2 миграции» (proposal §15/§34, design D-60, specs) |
| M-1 | MEDIUM | **закрыто** | Capability `orchestrator-meta-tools` (папка переименована, proposal §23, tasks S.4) — kebab-case |
| M-2 | MEDIUM | **закрыто** | `PARKED_ASYNC` = `session.runtimeStatus`; `last_turn_outcome` НЕ расширяется (COMPLETED при `pending_tool_calls>0`) — design D-60, tasks N.2, agent-turn spec |
| M-3 | MEDIUM | **закрыто** | Пачки `§N..§S` (tasks §N-§S; design R1 маппит N=async, O=spawn+subagent, P=orchestrator, Q=MCP, R=ACL/owner+restart-scan, S=acceptance); миграции вынесены в N.0; отдельная §T «отложено» |
| M-4 | MEDIUM | **закрыто** | tasks S.3 — синхронизация `agent-tools.md` §2/§4 (доступность `spawn_subagent` = только `metaTools=true`) |
| M-5 | MEDIUM | **частично** | Дублирование `spawn_subagent`/`read_compacted` между `agent-turn` и `subagent-lifecycle` осталось, но разведено по смыслу (agent-turn — поведение в Turn'е/гейт; subagent-lifecycle — жизненный цикл инструмента). Рекомендация: в agent-turn оставить ссылку. Не блокер |
| M-6 | MEDIUM | **закрыто** | Частичный supersession D-41 зафиксирован: design D-62/R9 + tasks S.3 — ADR **D-70** регистрируется в `decisions.md` при apply (по образцу D-59) |
| M-7 | MEDIUM | **закрыто** | design R6/Open Question 1: resolved «revision-as-profile» (без first-class поля), tasks T.1; roadmap-пункт закрыт |
| M-8 | MEDIUM | **закрыто** | tasks S.3/T.1 — `allowedTools`/`workspaceScope` синхронизируются как «вне M3» (D-41) |
| N-1 | MINOR | **закрыто** | `openspec validate --strict` → valid (добавлены SHALL/MUST в «Default MCP — пусто» и «Защита metaTools от injection») |
| N-2 | MINOR | **закрыто** | Placeholder `D-M3-1` удалён; решение — миграция `074` (D-61) |
| N-3 | MINOR | **закрыто** | proposal §16: «API (аддитивно, **без BREAKING**)» |
| N-4 | MINOR | **закрыто** | Конфиг сведён: `harness.spawn.max-depth`, `harness.mcp.auth.proxy-url`, добавлен `harness.late-result.watch-schedule`; `refresh-token-buffer-min` убран |
| N-5 | MINOR | **закрыто** | M2 заархивирован (`openspec/changes/archive/2026-09-20-m2-workflow-engine`); design Context ссылается корректно |
| N-6 | MINOR | **закрыто** | D-64: лок программный `sess-{sessionId}` + проверка `TOOL_RESULT` по `callId` в `payload_jsonb` (без лока по jsonb) |
| N-7 | MINOR | **закрыто** | agent-turn spec: сценарий «смешанный раунд (sync + async)» (execution-model §3 п.1) |
| MJ-1 | — | **закрыто** | `spawn_subagent` строго синхронный/блокирующий (proposal §8, design Goals, agent-turn/subagent-lifecycle, tasks O.1/O.2) |
| MJ-2 | — | **закрыто** | `read_compacted` — same-session резолв `id→seq`; cross-session вне M3 (R8, proposal §9, subagent-lifecycle, tasks O.4) |
| AsyncTimeoutWatcher | — | **закрыто** | Добавлена ShedLock-джоба (async-instruments §60, tasks N.6) — закрывает зависшие `TOOL_CALL` без рестарта |

## Остаточные замечания (не блокеры)

- **M-5**: `spawn_subagent`/`read_compacted` присутствуют и в `agent-turn`, и в `subagent-lifecycle`. Смысловое разведение есть, но при архивации требование окажется в двух capability — желательно оставить в `agent-turn` только Turn-интеграционную формулировку со ссылкой.
- **NIT**: номер миграций `074/075` (после M2 `010..016`) — разрыв нумерации; если не зарезервировано, ожидаются `017/018` (косметика).

## Проверка

- Все 3 HIGH и 8 MEDIUM (кроме смыслового M-5) закрыты; N-серия и MJ-1/MJ-2 закрыты; спека проходит `openspec validate --strict`.
- Внутренняя согласованность proposal↔design↔tasks↔specs по каналам (`session_message.seq`), парковке (`runtimeStatus`), depth (`session.depth`), `ASYNC_ACCEPTED` (kind+миграция), пачкам (`§N..§S`), supersession (D-70) — восстановлена.
- `agent-turn` delta — только `ADDED` (новые требования; M2-transition requirement не изменён), `session-api` — `ADDED`; новые capability с `Purpose`; `orchestrator-meta-tools` — kebab-case.

**APPROVE — 0 блокеров (1 остаточный MINOR: M-5 двойное владение spawn/read_compacted; 1 NIT: нумерация миграций 074/075).** Заморозка плана возможна.