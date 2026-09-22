# Инструменты агента spring-harness

> Каталог и контракты. Источники инструментов — четыре: нативные (workspace), мета-инструменты (движок), MCP и клиентский оверлей WS-релея (M4, D-80/D-85). Термины — `docs/glossary.md`.

## 1. Нативные инструменты рабочего каталога (`WorkspaceTools`)

Исполняются в per-session Docker-контейнере из helper-образа (`ContainerWorkspaceTools`: минимальная ОС + find/grep/coreutils/git; workspace примонтирован томом — D-30). В SERVER-toolset работают как раньше; в **CLIENT-toolset нативные файловые (`bash`/`read_file`/`write_file`/`edit_file`/`glob`/`grep`) не резолвятся** (D-84) — их роль берёт клиентский оверлей. Все пути — относительные, резолв от корня workspace; хост не трогается.

| Инструмент | Сигнатура | Режим | Контракт |
|---|---|---|---|
| `read_file` | `(path, offset?, limit?)` | sync | содержимое; лимит вывода, маркер усечения `truncated` |
| `write_file` | `(path, content)` | sync | создание/перезапись; `created` \| `overwritten` |
| `edit_file` | `(path, oldString, newString, replaceAll?)` | sync | ровно одно вхождение иначе ошибка `not-found` / `ambiguous` |
| `bash` | `(command, timeout?, cwd?)` | **async-capable** | окно ~30 с → `ASYNC_ACCEPTED(callId)`; поздний результат: output + exitCode + `late=true`; stdout/stderr вместе, `truncated` при лимите |
| `glob` | `(pattern)` | sync | список путей |
| `grep` | `(pattern, include?)` | sync | пути+строки, лимит |

Общие правила: вывод инструмента ограничен (защита контекста), при превышении — усечение с маркером; идемпотентность результата по `callId`.

## 2. Мета-инструменты (внедряются движком в AGENT-состояния)

| Инструмент | Сигнатура | Когда доступен | Семантика |
|---|---|---|---|
| `transition` | `(taskId, toState, kind, reason)` — **reason обязателен**; `taskId` в контракте реестра обязателен, при вызове из STATE-сессии адаптер резолвит его из сессии неявно | AGENT-состояние задачи; гейт metaTools — только `instructionSource = USER` (D-38; supersession в M2 — D-59) | валидация по разрешённым рёбрам ревизии (иначе `409 wrong-transition`) → запись в `task_transition_history` → перевод задачи → обеспечение сессии следующего состояния → wake |
| `spawn_subagent` | `(agent_key, prompt, params?)` | агент-оркестратор (`permissions_jsonb.metaTools=true`); остальным скрыт, явный вызов → `forbidden (no-metaTools)` | синхронный (блокирующий); дочерняя сессия (`parent_session_id`); завершение = «ход окончен И pending_tool_calls == 0»; финальный ответ → `TOOL_RESULT` родителю; отмена каскадом по поддереву; owner дочерней наследуется от родительской. **Единственный лимит**: `harness.spawn.max-depth` (конфиг), превышение → `TOOL_RESULT forbidden (depth-limit)` |
| `read_compacted` | `(compact_message_id)` | любая сессия (в пределах своей сессии) | возвращает оригиналы, скрытые COMPACT-событием (по `covers`) — агент/субагент может вернуться к деталям; лимит `harness.compact.read-max-bytes` |
| `stop_subtree` | `(session_id?)` | — | недоступен агенту; операция пользователя (API §2) |

## 2b. Инструменты оркестратора

Доступны только агентам, у которых `permissions_jsonb.metaTools = true` (роль оркестратора). Всё созданное наследует `owner_user_id` от породившей сессии (глоссарий, Session). Права людей не моделируются (D-41); `edit_workflow` доступен любому агенту с metaTools (внутренняя команда). В M3 действует оркестраторский путь гейта (D-62/D-70): для агентов с `metaTools=true` инструменты §2b и `spawn_subagent` доступны в любом ходе (D-59 `instructionSource=USER` к ним не применяется); для агентов без флага инструменты скрыты, явный вызов → `forbidden (no-metaTools)`. `transition` остаётся под D-59; флаг не наследуется субагентам (D-69, правка — новая ревизия агента, D-31).

| Инструмент | Сигнатура | Семантика |
|---|---|---|
| `create_workflow` / `edit_workflow` | `(key, name?, graph, start_state?)` | создание (rev=1) / новая ревизия; та же валидация графа, что у REST (`422 graph-invalid` агенту в тексте ошибки); `start_state` по умолчанию — первое состояние графа |
| `create_task` | `(title, description?, workflow_key, rev?, params?, tags?)` | пин последней ревизии (или явной); owner = owner сессии оркестратора |
| `create_subtask` | `(parent_task_id, title, description?, workflow_key, params?, tags?)` | подзадача с `parent_task_id`; owner = owner сессии оркестратора |
| `set_dependency` | `(blocked_task_id, blocked_by: [task_id])` | атомарная пачка рёбер с валидацией циклов (транзитивно), как в REST |
| `configure_trigger` | `(name, workflow_key, params?, tags?)` | создаёт триггер; `{triggerId, url}` capability возвращается в ответе инструмента |

## 3. MCP-инструменты

- Подключение: клиент Spring AI; серверы из конфигурации (корпоративные 12+ за SSO-прокси), токены обновляет сервер — агент про OAuth не знает.
- Привязка к агенту: `tools_jsonb.mcp` агента перечисляет MCP-серверы (`[{server, include?, exclude?}]`); manifest инструментов (namespace `{server}.{tool}`) попадает в промпт как обычные tool-declarations.
- MCP-сервер наружу от нас — вне MVP (D-21).

## 3b. Клиентский оверлей (WS-релей, M4)

Инструменты, объявленные подключённым клиентом (`register { sessionId, client: { tools[] } }`, api-contracts §5): `{name, description, inputSchema, source}`. Оверлей — runtime-only (D-80), виден root-сессии и её sub-сессиям по parent-цепочке (D-84). Порядок резолва — после серверных колбэков; кривые args → `params-schema` без отправки `tool.call`; `source` (`client` | `client.mcp:<server>`) — информативно, сервер к MCP-клиента не ходит (D-82); `exitCode` в `tool.result` — информативный (non-zero ≠ ошибка инструмента). Отмена Turn'а/поддерева → `tool.cancel` клиенту + синтетический CANCELLED; разрыв с in-flight вызовом → LOST. Детали — `openspec/specs/client-tool-bridge`, `openspec/specs/client-relay`.

## 4. Разрешения (`permissions_jsonb` агента)

- `metaTools` — boolean (M3, D-62): `true` открывает оркестраторские metaTools и `spawn_subagent`; правка значения = новая ревизия (D-31).
- `allowedTools` — белый список имён инструментов (нативные + MCP). **Вне M3** (отложено, §T.1): дефолт — все нативные; поле-заготовка.
- `workspaceScope` — поддерево путей workspace, доступное агенту (относительные префиксы). **Вне M3** (отложено, §T.1).
- Текстовые deny-паттерны bash (типа Claude Code) **не считаются** security-границей (исследование CC: «fragile») — они подсказка модели; реальная граница — биндинг workspace и (эволюция) контейнеры.

## 5. Отчёт исполнения (единый контракт результата)

```
{ callId, tool, status: OK | ERROR | ASYNC_ACCEPTED | CANCELLED | LOST,
  output?, exitCode?, truncated?, late? }
```
`LOST`/`CANCELLED` — синтетические результаты (рестарт-скан, отмена): модель видит честную причину, сессия не висит.

**Маппинг на протокол провайдера**: наш `callId` — внутренняя корреляция; плейсхолдер и поздний результат отдаются модели как **отдельные** tool-сообщения с уникальными провайдерскими `tool_call_id` (обход ограничения дублей id в OpenAI-совместимых API).
