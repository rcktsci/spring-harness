## Context

M2 закрыл домен задач и базовый движок состояний (`archive/2026-09-20-m2-workflow-engine`, 439 тестов). Агентский слой остался в проекте как дизайн-замысел (`agent-tools.md`, `execution-model.md` §3-§7), но не реализован: спавн субагентов — `parent_session_id` уже моделируется в `session`, но нет metaTool и lifecycle; async-инструменты — задокументированы в `execution-model §3/§4/§5`, но в `AgentTurnEngine` нет ASYNC_ACCEPTED/плейсхолдера; оркестратор-metaTools — гейт D-59 действует на всех агентов, оркестраторский путь не открыт (`agent-tools.md` §2b `permissions_jsonb.metaTools` — только буква); MCP-клиент — только декларация (`agent-tools.md` §3, D-21). M3 закрывает именно это.

## Goals / Non-Goals

**Goals:**
- Async-инструменты с окном (bash объявлен `async-capable`), поздний `TOOL_RESULT` через сессионный канал `message.created`/`session_message.seq` (M1), рестарт-скан и `AsyncTimeoutWatcher` закрывают долгие.
- `spawn_subagent` (строго синхронный — блокирующий, с owner-наследованием, `harness.spawn.max-depth`); отмена поддерева по `parent_session_id`.
- `read_compacted` через id→seq (D-44).
- Оркестратор-агент: `agent.permissions_jsonb.metaTools=true` снимает D-59 для оркестраторских metaTools; обычные агенты — те же правила.
- MCP-клиент: Spring AI MCP client; `tools_jsonb.mcp` в ревизии агента; manifest-инжекция.
- Приёмочный e2e «Сделай биллинг» (roadmap M3) проходит на живом Keycloak + WireMock-LLM + реальный helper-контейнер.

**Non-Goals (явно вне M3):**
- Селективная компакция поверх `COMPACT` — M3 делает только `read_compacted` (read-only), изменение COMPACT — отдельный чендж.
- Раннеры (control/execution split).
- MCP-сервер наружу (D-21 reverse — эволюция).
- Ротация capability-секретов.
- Адаптеры Jira/Trello/GitLab (нужны конкретные MCP — владелец подтвердит список).
- Multi-instance состояния.
- WebUI/CLI/M4.
- ACL-4-множитель полностью (D-38) — в M3 расширяем гейт только для metaTools оркестратора, остальные 3 множителя (effective parent, agent-declaration, права инициатора) пока = 1.

## Decisions

### D-60: Async-инструменты через существующий wake-канал сессии
Поздний `TOOL_RESULT` доставляется через сессионный канал M1: `message.created` / `session_message.seq` через `InMemorySessionEventBroadcaster` — запись журнала инкрементирует `last_seq` сессии, что вызывает wake существующим `AgentTurnEngine.run`-путём (M2 J-1: USER-source rewake + новый Turn с early-exit pending). Не плодим отдельные каналы; M2 `task_event_seq` (счётчик задач, канал `tasks/{id}/events`) на сессионном канале НЕ используется. `ASYNC_ACCEPTED` — новое значение `MessageKind` (enum M1 расширяется; миграция `075_alter_session_message_kind_add_async_accepted.xml` — ALTER CHECK `ck_session_message__kind`). Парковка — состояние сессии, не Turn'а: `session.runtimeStatus = PARKED_ASYNC` (значение уже зарезервировано в api-contracts §2: `IDLE|TURN_RUNNING|PARKED_ASYNC|PARKED_CLIENT`); `last_turn_outcome` раунда — COMPLETED при `pending_tool_calls > 0` (enum TurnOutcome НЕ расширяется).
Альтернатива: отдельный `InProcessAsyncBus` (как M2 `InProcessTaskWakeBus` для задач) с собственным seq. Отклонено: дублирование; wake-семантика одинакова (seq=порядок, snapshot+lazy-result); усложняет event sourcing.

### D-61: `depth` в отдельной колонке `session.depth`
`depth` хранится в колонке `session.depth` (INT NOT NULL DEFAULT 0) — миграция `074_create_column_session_depth.xml`. У таблицы `session` НЕТ `params_jsonb` (он есть у `task`/`trigger`), поэтому jsonb-вариант неисполним. При создании дочерней сессии — `depth = parent.depth + 1`. Лимит `harness.spawn.max-depth: 2` (дефолт; конфиг). Проверка в `SubagentSpawner.spawn(...)` ДО создания сессии.
Альтернатива: хранение depth в jsonb. Отклонено: колонки `session.params_jsonb` не существует; заводить её ради одного числа — та же миграция, но без типизации.

### D-62: Мета-инструмент оркестратора — `permissions_jsonb.metaTools=true`
`agent.permissions_jsonb.metaTools` (boolean, дефолт false). Если true — orchestrator-tools (`create_workflow`/`edit_workflow`/`create_task`/`create_subtask`/`set_dependency`/`configure_trigger`) доступны без D-59 USER-source гейта. D-59 ОСТАЁТСЯ для `transition` и обычных metaTools. Поле boolean — никаких прав/`scope`-градаций в M3 (D-41 де-скоуп). Частичный supersession D-41 (metaTools-гейт для оркестраторов возвращается; D-41 для обычных metaTools остаётся) фиксируется ADR **D-70** при apply (см. R9).
Альтернатива: новая таблица `agent_meta_tools`. Отклонено: overkill, одно boolean-поле достаточно.

### D-63: MCP через Spring AI MCP client, без новых зависимостей
`spring-ai-mcp-client` уже в Spring AI BOM (M1). Конфиг `harness.mcp.servers[*]` в `application.yml`. Auth: `harness.mcp.auth.proxy-url` для обновления токенов. `agent.tools_jsonb.mcp[servers]` — массив + filters. Manifest включает MCP-инструменты с namespace `{server}.{tool}`.
Альтернатива: кастомный JSON-RPC клиент. Отклонено: D-21 уже зафиксировал Spring AI.

### D-64: Гонка «первый финальный выигрывает» для late TOOL_RESULT vs LOST
Финальные статусы — `OK|ERROR|CANCELLED|LOST` (ASYNC_ACCEPTED — placeholder «в полёте», финальным не считается). Механизм: публикация late-результата и LOST-синтетика (рестарт-скан / AsyncTimeoutWatcher) выполняются под программным локом сессии `sess-{sessionId}` (ShedLock, D-40), сериализующим обе вставки; внутри лока — проверка «уже есть TOOL_RESULT с данным callId в payload_jsonb и terminal-статусом» — идемпотентно, кто первый.
Альтернативы: частичный уникальный индекс по `(session_id, payload_jsonb->>'callId')` — отклонено: лишняя миграция против минимализма; отдельная таблица `tool_result_outbox` — отклонено (D-33: outbox не заводим).

### D-65: Late-result delivery — late-event с собственным `tool_call_id`
OpenAI-совместимые API не разрешают дубль `tool_call_id`. Поэтому: плейсхолдер `ASYNC_ACCEPTED` идёт с уникальным `tool_call_id` (= callId), поздний `TOOL_RESULT` — с **другим** уникальным id (callId + "-late"). Модель видит как два разных tool-сообщения (по спеке agent-tools.md §5 «обход ограничения дублей id»).
Альтернатива: дубль id. Отклонено: OpenAI API reject.

### D-66: Workspace-стратегия субагента
`harness.spawn.workspace-strategy: inherit|new` (дефолт `inherit` — субагент работает в workspace родителя). `new` — отдельный workspace через `clone` (или иной механизм внедрения; M3 делает только `inherit`, `new` откладывается).
Альтернатива: только `inherit`. Отклонено: `new` — точка эволюции (multi-instance подзадачи); держать конфиг.

### D-67: Read-compacted лимит
`harness.compact.read-max-bytes` (конфиг, дефолт 16 КБ) — на одну запись; если оригинал больше — TOOL_RESULT усечённый + маркер `truncated` (как нативные). Позволяет агенту вернуться к сути, не раздувая контекст.
Альтернатива: без лимита. Отклонено: угроза контексту.

### D-68: Субагентская сессия и событийный канал
Субагентская сессия — полноценная сессия с собственным событийным каналом (`message.created`/`session_message.seq`, M1), `parent_session_id`, owner-inherited. Никаких специальных event-types — события субагентской сессии идут по её собственному сессионному каналу, родитель видит финал через `TOOL_RESULT` (orphan events не подмешиваются).
Альтернатива: события субагента в родительский канал (трансляция). Отклонено: усложнение, теряется изоляция.

### D-69: org-bias в `spawn_subagent`
Запрет subagent'у спавнить собственного subagent'а, если `permissions_jsonb.metaTools=false` (оркестратор-metaTools — не наследуется subagent'у). У оркестратора (metaTools=true) — sub-spawn разрешён, depth лимит тот же. Это закрывает горизонтальную эскалацию (D-38 логика без 4-го множителя).
Альтернатива: наследовать metaTools-флаг. Отклонено: эскалация принципала — запрет.

## Risks / Trade-offs

- **R1 (большая фаза)**: 5 новых capability + 1 modification = по сути одна большая поставка. Митигация: 6 пачек §N..§S (N — async-инфра, O — spawn+subagent-lifecycle, P — orchestrator-meta-tools, Q — MCP-клиент, R — ACL/owner+рестарт-скан, S — acceptance).
- **R2 (MCP без серверов)**: в M3 `harness.mcp.servers` пустой; реальные серверы — владелец добавит позже. Acceptance M3 не требует ни одного подключённого сервера (только `tools_jsonb.mcp` конфигурация).
- **R3 (async-окно и LLM-провайдер)**: плейсхолдер требует поддержки OpenAI streaming tool_call с идентификатором и поздним обновлением. Протокольное решение D-65 (другой id для late) снижает риск.
- **R4 (новые миграции в M3)**: две узких аддитивных миграции — `074` (колонка `session.depth NOT NULL DEFAULT 0`) и `075` (расширение kind-CHECK значением `ASYNC_ACCEPTED`). Хранение в jsonb отклонено — у `session` нет `params_jsonb` (D-61); обе миграции обратно совместимы.
- **R5 (subagent owner inheritance)**: если субагент пересекает границу другого пользователя — owner = parent.owner (subagent не имеет своего). Это сознательное упрощение, security-issue в будущем = D-38 4-й множитель.
- **R6 (LLM-профили)**: resolved в D-62 (revision-as-profile): first-class «профиль» не вводим — `agent.permissions_jsonb.metaTools` + `agent.tools_jsonb` достаточно (profile-as-tool-binding + metaTools-гейт); правка = новая ревизия (D-31 иммутабельность). Roadmap-пункт «llm-профили» закрыт без нового поля.
- **R7 (реальный MCP в tests)**: WireMock имитирует JSON-RPC MCP-сервер (как LLM через WireMock). Реальный MCP — отдельный testcontainer.
- **R8 (`read_compacted` same-session)**: в M3 резолв `id → seq` — только в пределах текущей сессии; cross-session-чтение отсутствует, защиты от утечки контекста через чужую сессию не требуется. Cross-session — точка эволюции (потребует глобальной уникальности ULID + явного параметра сессии + отдельного сценария/решения).
- **R9 (supersession D-41)**: D-62/D-69 частично supersede D-41 — metaTools-гейт для оркестраторов возвращается; D-41 для обычных metaTools остаётся. ADR **D-70** («metaTools-гейт для оркестратора supersede D-41 частично») регистрируется в `docs/design/decisions.md` при apply (не до apply).

## Migration Plan

- **БД**: 0 новых таблиц; 2 миграции: `074_create_column_session_depth.xml` (`session.depth INT NOT NULL DEFAULT 0`, D-61), `075_alter_session_message_kind_add_async_accepted.xml` (ALTER CHECK `ck_session_message__kind`: + `ASYNC_ACCEPTED`, D-60/D-65). Глобальный уникальный индекс `session_message.id` не нужен — резолв `read_compacted` только в пределах текущей сессии (R8).
- **Конфиг** в `application.yml`:
  - `harness.async.window.default-ms: 30000`
  - `harness.late-result.timeout-ms: 900000`
  - `harness.late-result.watch-schedule: ...` (расписание `AsyncTimeoutWatcher`)
  - `harness.spawn.max-depth: 2`
  - `harness.spawn.workspace-strategy: inherit`
  - `harness.compact.read-max-bytes: 16384`
  - `harness.mcp.servers: []` (владелец заполняет)
  - `harness.mcp.auth.proxy-url: ...`
- **Деплой**: один инстанс; restart — без миграций данных; preliquibase → liquibase → JPA.
- **Откат**: дективация через `@ConditionalOnProperty` — async-инструменты можно фичефлагнуть, spawn_subagent аналогично. Без частичного отката фич (по D-41 — YAGNI).

## Open Questions

1. **LLM-профили** — resolved в D-62 (revision-as-profile): отдельного first-class поля не вводим; `permissions_jsonb.metaTools` + `tools_jsonb` достаточно (см. R6; отложенное — задача §T.1 в tasks).
2. **Список MCP-серверов** для acceptance — нужен от владельца (R2); без списка — пустой `harness.mcp.servers`, e2e проверяет только manifest-инжекцию и auth-refresh путь с WireMock.
3. **`configure_trigger` возвращает URL — кто делает реальную регистрацию?** Если orchestrator tool — агент сам программно; если внешняя интеграция — нужен внешний триггер (Jira webhook и т.п.). В M3 `configure_trigger` только создаёт триггер + отдаёт URL; агент дальше сам решает, что с URL делать.
