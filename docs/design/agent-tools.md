# Инструменты агента spring-harness

> Каталог и контракты. Источники инструментов — ровно два: нативные (workspace) и MCP. Термины — `docs/glossary.md`.

## 1. Нативные инструменты рабочего каталога (`WorkspaceTools`)

Исполняются по биндингу состояния: `ContainerWorkspaceTools` (per-session Docker-контейнер из helper-образа: минимальная ОС + find/grep/coreutils/git; workspace примонтирован томом — D-30) или `ClientRelayWorkspaceTools` (релей на подключённый клиент). Все пути — относительные, резолв от корня workspace. Серверные read/write/bash/glob/grep используют утилиты образа, хост не трогается.

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
| `transition` | `(target_state_code, reason)` — **reason обязателен** | AGENT-состояние задачи | валидация по разрешённым переходам ревизии → запись в `task_transition_history` → перевод задачи → обеспечение сессии следующего состояния → wake |
| `spawn_subagent` | `(agent_key, prompt, params?)` | любая сессия | синхронный; дочерняя сессия (`parent_session_id`); завершение = «ход окончен И pending_tool_calls == 0»; финальный ответ → `TOOL_RESULT` родителю; отмена каскадом по поддереву; owner дочерней наследуется от родительской. **Единственный лимит**: `spawn.maxDepth` (конфиг); превышение → `TOOL_RESULT forbidden (limit)` |
| `read_compacted` | `(compact_message_id)` | любая сессия | возвращает оригиналы, скрытые COMPACT-событием (по `covers`) — агент/субагент может вернуться к деталям |
| `stop_subtree` | `(session_id?)` | — | недоступен агенту; операция пользователя (API §2) |

## 2b. Инструменты оркестратора

Доступны только агентам, у которых `permissions_jsonb.metaTools = true` (роль оркестратора). Всё созданное наследует `owner_user_id` от породившей сессии (глоссарий, Session). Права людей не моделируются (D-41) — гейтов нет; `edit_workflow` доступен любому агенту с metaTools (внутренняя команда).

| Инструмент | Сигнатура | Семантика |
|---|---|---|
| `create_workflow` / `edit_workflow` | `(key, name?, graph)` | создание (rev=1) / новая ревизия; та же валидация графа, что у REST (`422 graph-invalid` агенту в тексте ошибки) |
| `create_task` | `(title, description, workflow_key, params?, tags?)` | пин последней ревизии (или явной); owner = owner сессии оркестратора |
| `create_subtask` | `(parent_task_id, title, description, workflow_key, params?, tags?)` | owner = owner родительской задачи |
| `set_dependency` | `(blocked_task_id, blocked_by: [task_id])` | с валидацией циклов (транзитивно), как в REST |
| `configure_trigger` | `(name, workflow_key, params?, tags?)` | создаёт триггер; URL capability возвращается в ответе инструмента |

## 3. MCP-инструменты

- Подключение: клиент Spring AI; серверы из конфигурации (корпоративные 12+ за SSO-прокси), токены обновляет сервер — агент про OAuth не знает.
- Привязка к агенту: `tools_jsonb` агента перечисляет MCP-серверы (+ include/exclude инструментов); manifest инструментов попадает в промпт как обычные tool-declarations.
- MCP-сервер наружу от нас — вне MVP (D-21).

## 4. Разрешения (`permissions_jsonb` агента)

- `allowedTools` — белый список имён инструментов (нативные + MCP).
- `workspaceScope` — поддерево путей workspace, доступное агенту (относительные префиксы).
- Текстовые deny-паттерны bash (типа Claude Code) **не считаются** security-границей (исследование CC: «fragile») — они подсказка модели; реальная граница — биндинг workspace и (эволюция) контейнеры.

## 5. Отчёт исполнения (единый контракт результата)

```
{ callId, tool, status: OK | ERROR | ASYNC_ACCEPTED | CANCELLED | LOST,
  output?, exitCode?, truncated?, late? }
```
`LOST`/`CANCELLED` — синтетические результаты (рестарт-скан, отмена): модель видит честную причину, сессия не висит.

**Маппинг на протокол провайдера**: наш `callId` — внутренняя корреляция; плейсхолдер и поздний результат отдаются модели как **отдельные** tool-сообщения с уникальными провайдерскими `tool_call_id` (обход ограничения дублей id в OpenAI-совместимых API).
