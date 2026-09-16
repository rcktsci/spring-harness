# Конкурентный анализ: Pi (earendil-works/pi)

> Исследование по общему брифу `docs/research/competitors/BRIEF.md`.
> Объект: Pi — минималистичный модульный AI-кодинг-агент на TypeScript.
> Репозиторий: https://github.com/earendil-works/pi (монорепо), сайт https://pi.dev/,
> ядро — npm-пакет `@earendil-works/pi-agent-core`.
> Все ссылки на файлы — относительно корня репозитория, если не указано иное.

---

## 0. Общая справка

Pi создан Марио Зехнером (badlogic, pi-mono), в мае 2026 проект перешёл под крыло
Earendil Inc. (org `earendil-works`, npm-scope `@earendil-works`, первая версия там 0.74.0).
Источник: https://pi.dev/news/2026/5/7/pi-has-a-new-home

Лицензия MIT. Позиционирование: «минимальный терминальный coding harness, который
адаптируется под ваш воркфлоу через код (extensions/skills/packages), а не диктует его».
В ядре сознательно нет: MCP, субагентов, permission-попапов, plan mode, встроенных
todos, фонового bash. Источник: `packages/coding-agent/README.md`, раздел Philosophy.

Состав монорепо (`README.md` корня):

| Пакет | Назначение |
|---|---|
| `@earendil-works/pi-ai` (`packages/ai`) | Унифицированный мультипровайдерный LLM API (OpenAI, Anthropic, Google, …) |
| `@earendil-works/pi-agent-core` (`packages/agent`) | Рантайм агента: tool calling, state management, durable Session API |
| `@earendil-works/pi-coding-agent` (`packages/coding-agent`) | CLI-агент + SDK + RPC/JSON-режимы |
| `@earendil-works/pi-tui` (`packages/tui`) | Библиотека терминального UI с дифф-рендерингом |
| `@earendil-works/pi-protocol` (`packages/protocol`) | Транспортно-нейтральные конверты, CBOR, фрейминг «экспериментального протокола Pi» |
| `@earendil-works/pi-server` (`packages/server`) | Экспериментальный локальный сервер durable Session/Agent Harness интерфейсов |
| `@earendil-works/pi-session-backend-sqlite-node` (`packages/session-backends/sqlite-node`) | SQLite-бэкенд сессий для pi-agent-core |
| `@earendil-works/chord` (`packages/chord`) | Runtime композиции приложений: facets, services, replicated state, RPC-граница |
| `@earendil-works/pi-telemetry` (`packages/telemetry`) | Вендор-нейтральные телеметрические контракты и схемы |

Отдельный репозиторий `earendil-works/pi-chat` — Slack/chat-автоматизация и workflows
(в монорепо не входит). Источник: корневой `README.md`.

---

## 1. Архитектура

**Клиент/сервер.** Основной продукт — локальный однопроцессный CLI (Node/Bun, есть
сборка standalone-бинарников: `scripts/build-binaries.sh`). Клиент = TUI в том же
процессе, что и агент. Серверной части в OSS-продакшене нет; есть два «зачатка»:

1. **RPC-режим CLI** (`pi --mode rpc`) — headless-процесс, JSONL поверх stdin/stdout,
   для встраивания в IDE/приложения на любом языке. Источник: `packages/coding-agent/docs/rpc.md`.
2. **`@earendil-works/pi-server`** — экспериментальный локальный сервер для нового
   durable Session/Agent Harness интерфейса: Unix-socket-транспорт, CBOR-фрейминг из
   `pi-protocol`, роутинг facet-сервисов через `chord`, несколько «презентаций»
   (attachments) на одну сессию. Ключевые механизмы:
   - `RoutedServerServiceHost.attachClient()` — connection-scoped endpoint с узкими
     правами управления attachment'ами; `RoutedSessionHandle.attachClient()` —
     presentation-scoped capability сессии;
   - повторный `attach` идемпотентен; каждая привязка получает серверный `attachmentId`;
     запросы сессии несут `{ serverId, sessionId, attachmentId }`, устаревшие роуты
     отклоняются;
   - «настоящие» `Session` и `AgentHarness` не покидают процесс воркера — через
     границу идут только opaque-конверты; хост-приложение владеет `SessionDirectory`
     (каталог) и `SessionManagement` (создание/удаление/attach/detach);
   - жизненный цикл воркеров вне публичного протокола: хост решает, когда «ноль презентаций +
     нет активности» позволяет списать воркер.
   Источник: `packages/server/README.md`.

**Где хранится состояние.** Локально: сессии — JSONL-файлы (см. §2), настройки —
`~/.pi/agent/settings.json` + `.pi/settings.json`, креды — `~/.pi/agent/auth.json`.
В новом durable-слое pi-agent-core состояние сессии — это записи (entries),
коммутируемые через абстракцию `Storage`/`SessionRepo` (см. §2).

**Транспорт.** CLI — stdin/stdout (RPC: JSONL, строгий LF-фрейминг; в доке отдельно
предупреждают не использовать Node `readline`, т.к. он режет по U+2028/U+2029).
Новый протокол (`pi-protocol`, версия 8): 4 байта big-endian длины + CBOR; handshake
с `serverId`; строгий JSON внутри opaque payload'ов; лимиты 16 MiB/фрейм, 1e6
элементов, 64 уровня вложенности. Аутентификация пиров **не реализована** — «application
policy». Источники: `packages/coding-agent/docs/rpc.md`, `packages/protocol/README.md`.

**Изоляция процессов.** Встроенной permission-системы нет — «Pi does not include a
built-in permission system»; рекомендованные паттерны изоляции: Gondolin-расширение
(Linux micro-VM для инструментов при хостовом `pi`), Docker целиком, OpenShell.
Источник: корневой `README.md`, `packages/coding-agent/docs/containerization.md`.

**Chord** — отдельный рантайм (не зависит от Pi-пакетов): плагины-фасеты, типизированные
сервисы (singleton/keyed), реплицируемое состояние с delta-трекингом (path-codec,
строковые аппенды сохраняются), Go-подобный context для отмены, esbuild-бандлер фасетов,
загрузка через `node:vm` с SHA-256 и hot-reload без разрыва сервисных хэндлов.
Источник: `packages/chord/README.md`.

---

## 2. Модель сессий

### 2.1 Хранение: JSONL-дерево (текущий слой coding-agent)

- Файлы `~/.pi/agent/sessions/--<path>--/<timestamp>_<session-id>.jsonl`; каждая строка —
  JSON-запись с `type`. Записи образуют **дерево** через `id`/`parentId` — ветвление
  без создания новых файлов. Версия формата 3 (миграция v1/v2 при загрузке).
  Источник: `packages/coding-agent/docs/session-format.md`.
- Типы записей: `session` (заголовок), `message` (AgentMessage), `model_change`,
  `thinking_level_change`, `compaction` (summary + `firstKeptEntryId` + `tokensBefore`),
  `branch_summary` (резюме брошенной ветки), `custom` (состояние расширения, в LLM-контекст
  не попадает), `custom_message` (сообщение расширения, попадает в контекст), `label`
  (закладка), `session_info` (имя сессии). Источник: там же.
- Контекст для LLM строится проходом от листа к корню с учётом последней compaction
  записи. Источник: там же, раздел Context Building.
- Ветки/форки: `/tree` (навигация по дереву на месте), `/fork` (новая сессия от
  предыдущего user-сообщения), `/clone` (копия активной ветки), `--fork <path|id>` из CLI,
  `createBranchedSession(leafId)`, `branchWithSummary()`. Компакция — ручная и
  автоматическая (по порогу и по переполнению, с recovery-retry). Источники:
  `packages/coding-agent/README.md` (Sessions), `packages/coding-agent/docs/session-format.md`.
- `SessionManager.inMemory(cwd, { id }, entries)` — официальный способ «resume сессии,
  хранящейся вне файловой системы, например в БД»: сессия восстанавливается из
  переданного массива entries. Источник: `packages/coding-agent/docs/sdk.md` (Session Management).
- Экспорт/импорт: `/export` в HTML/JSONL, `/import` из JSONL, `/share` — приватный
  GitHub Gist с HTML-ссылкой. Источник: `packages/coding-agent/README.md`.

### 2.2 Durable Session API (новый слой pi-agent-core)

В `packages/agent/src/harness/session/types.ts` определены транспорт- и хранилище-нейтральные
интерфейсы — это самый интересный для нас паттерн:

- `SessionRepo<TMetadata>`: `create / open / list / delete / fork(source, options)`.
- `Session` (расширяет `SessionReader`): метаданные, ветки (`branch(name)`,
  `createBranch(name, at)`), `appendMessage`, **эксклюзивный мутационный барьер**
  `beginMutation()` → `SessionMutation` (ровно один `commit`, второй отклоняется) и
  callback `mutate(cb)`; key-value (`setValue/deleteValue/getValue/scanValues`) и
  списки (`appendList/readList/scanList`); `setName/setLabel/close`.
- `Storage`: батчевый `commit(writes)` (entry/usage/value/list), `getEntries`,
  сканы `scanBranch / scanBranchStructure / scanEntries / scanUsage`, `getStats`, `close`.
- `ForkOptions`: `scope: "branch"` (копия одного пути) или `scope: "tree"` (всё дерево
  без operation-состояния).
- Durable state machine операции: плоский union из **13 листьев** (`assistant.ready`,
  `assistant.effect_pending`, `assistant.retry_wait`, `tools`, `deferred.suspended`,
  `deferred.effect_pending`, `summary.*`, `navigation.ready_to_commit`, checkpoint, starting),
  плюс `LaneState.inbox` с видами элементов `steer | followUp | nextRun | write`,
  `CheckpointData { continuation, triggerEntryId }`, `OperationResultRecord` со
  статусами `completed/declined/aborted/failed`. Т.е. ход агента — восстановимая
  операция с чекпоинтами, а не живой цикл в памяти.
- Конкурентность: «хост-лайфцикл, а не бэкенд, гарантирует одного writable-owner на
  сессию»; в SQLite-бэкенде **нет** cross-process lease/lock/fence/heartbeat/takeover.
  Источник: `packages/session-backends/sqlite-node/README.md`.

### 2.3 SQLite-бэкенд

`@earendil-works/pi-session-backend-sqlite-node`: один файл на сессию в каталоге
(или общий контейнер через `databasePath`); фабрика БД (`databaseFactory`) — точка
расширения для других бэкендов («allowing other session backends to ship as their own
packages»); open()/delete() никогда не создают отсутствующую БД; форк живого источника —
read-only снапшот в отложенной WAL-транзакции, форк из того же репозитория встаёт в
очередь коммитов источника; поиск/FTS **не входит** (отдельная S3-проекция).
Источник: `packages/session-backends/sqlite-node/README.md`.

### 2.4 Удалённый доступ / роуминг / совместный доступ

- Встроенного remote-attach (аналог `opencode attach`) в OSS **не найдено**. Роуминг
  достигается только внешними средствами: sessions как файлы (можно синхронизировать),
  `/share`-gist, pi-chat для Slack. Внутренние дизайнерские документы
  `packages/agent/docs/mobile-handoff/` (delta/scopes/execenv/tool-output/plugins)
  указывают на планы мобильного/удалённого хэндовера через chord-фасеты, но это
  internal work-in-progress, не публичный API.
- Совместный доступ к одной сессии: только в экспериментальном pi-server через
  несколько presentation-attachments одной сессии (идемпотентный attach, attachmentId,
  отсечение устаревших роутов). Источник: `packages/server/README.md`.
- Multi-tenancy/авторизация поверх сессий: не найдено.

---

## 3. Модель агента

- **Агент = состояние + цикл.** `AgentState`: `systemPrompt`, `model`, `thinkingLevel`
  (off…max), `tools`, `messages` (+ вычисляемые `isStreaming`, `streamingMessage`,
  `pendingToolCalls`, `errorMessage`). Присваивание `tools/messages` копирует массив
  верхнего уровня. Источники: `packages/agent/README.md`, `packages/agent/src/agent.ts`.
- **Ролей/субагентов в ядре нет** (Philosophy: «No sub-agents»); их строят расширениями
  или запуском нескольких `pi` (tmux). Источник: `packages/coding-agent/README.md`.
- **Навыки**: стандарт [Agent Skills](https://agentskills.io) — `SKILL.md` в
  `~/.pi/agent/skills/`, `~/.agents/skills/`, `.pi/skills/`, `.agents/skills/` (от cwd
  вверх по дереву) или в pi-package; вызов `/skill:name` или автозагрузка агентом.
  Источник: `packages/coding-agent/README.md` (Skills).
- **Промпт-структура**: `AGENTS.md`/`CLAUDE.md` (global → родители → cwd, конкатенация;
  `AGENTS.override.md` перекрывает), замена системного промпта через `.pi/SYSTEM.md`
  или `~/.pi/agent/SYSTEM.md`, дозаполнение `APPEND_SYSTEM.md`; prompt templates
  (`/name` → раскрытие markdown-шаблона с `{{параметрами}}`). Источники:
  `packages/coding-agent/README.md` (Context Files, Prompt Templates).
- **Кастомные типы сообщений**: расширение `AgentMessage` через declaration merging
  (`CustomAgentMessages`) + обязательный `convertToLlm` для трансляции в LLM-формат;
  опциональный `transformContext` для pruning/compaction. Источник: `packages/agent/README.md`.
- **Оркестраторские механизмы очередей**: steering (вставка после текущего хода
  ассистента, до следующего LLM-вызова) и follow-up (после полной остановки), режимы
  `one-at-a-time | all`. На уровне ядра — `agent.steer()/followUp()/clearAllQueues()`.
  Источник: `packages/agent/README.md`, `packages/coding-agent/docs/rpc.md`.

---

## 4. Инструменты

**Встроенные**: `read`, `bash`, `powershell` (Windows), `edit`, `write`, `grep`, `find`,
`ls`; по умолчанию включены первые четыре. Allowlist/denylist через `--tools`/`--exclude-tools`,
`--no-builtin-tools`, `--no-tools`. Источник: `packages/coding-agent/README.md` (CLI Reference).

**Контракт инструмента** (`AgentTool`, `packages/agent/README.md`):
- параметры — TypeBox-схема; `execute(toolCallId, params, signal, onUpdate, ctx)`;
- стриминг прогресса — `onUpdate(partialResult)` → событие `tool_execution_update`;
- ошибки — throw (агент сам превращает в `isError: true` toolResult), а не возврат текста ошибки;
- `details` в результате — место для machine-readable метаданных (используется и для
  восстановления состояния расширений при ветвлении);
- `terminate: true` — хинт пропустить автоматический следующий LLM-вызов (срабатывает,
  если все результаты батча завершающие);
- исполнение: глобально `toolExecution: parallel|sequential`, пер-инструмент
  `executionMode`; preflight-хук `beforeToolCall` (может блокировать, мутировать `event.input`
  — на уровне расширений) и `afterToolCall` (может переопределять результат).

**Асинхронные инструменты / отложенный возврат результата.**
- Важно и честно: методов `agent.injectMessage` / `agent.injectSystemMessage` в текущем
  коде pi-agent-core **не найдено** (проверены `packages/agent/src/agent.ts`,
  `src/index.ts`, README). Искомый паттерн реализован другими именами:
  - `Agent.steer(message)` / `Agent.followUp(message)` — вставка произвольного
    `AgentMessage` (в т.ч. кастомного типа) в очереди;
  - на уровне coding-agent: `pi.sendMessage({ customType, content, display, details },
    { deliverAs: "steer" | "followUp" | "nextTurn", triggerTurn: true })` — канонический
    способ «инструмент вернулся сразу, результат придёт позже»: расширение регистрирует
    инструмент, немедленно возвращает «запущено», а по завершении внешней работы
    инжектирует `custom_message` с `triggerTurn: true`, что будит простаивающего агента;
    `pi.sendUserMessage(...)` — то же, но от лица пользователя (всегда триггерит ход).
    Источник: `packages/coding-agent/docs/extensions.md` (pi.sendMessage / pi.sendUserMessage).
  - Записи `custom_message` участвуют в LLM-контексте; `custom`-записи — нет (только
    состояние/TUI). Источник: `packages/coding-agent/docs/session-format.md`.
- **Provider-deferred ответы**: у `AssistantMessage.stopReason` есть значение `"deferred"`
  с полем `deferred: DeferredHandle` — «терминальная причина для ответа провайдера,
  который завершится позже»; в durable-слое этому соответствуют листья
  `deferred.suspended` / `deferred.effect_pending` (resume наружной генерации).
  Источник: `packages/coding-agent/docs/session-format.md`,
  `packages/agent/src/harness/session/types.ts`.
- Динамическая догрузка инструментов: расширения поддерживают «native deferred loading»
  (модели с нативной отложенной загрузкой схем) и fallback через search-tool.
  Источник: `packages/coding-agent/docs/extensions.md` (Dynamic Tool Loading).
- RPC-команда `bash`: исполняется сразу, вывод стримится `bash_execution_update`,
  а в LLM-контекст попадает при **следующем** prompt (через `BashExecutionMessage`
  → конвертацию в user-message). Источник: `packages/coding-agent/docs/rpc.md`.

---

## 5. Оркестрация / воркфлоу

- Встроенного workflow-движка, plan mode, todos, найма/распределения задач **нет** —
  осознанная философия («write plans to files, or build it with extensions»).
  Источник: `packages/coding-agent/README.md`.
- Планировщик: нет серверного планировщика; «планирование» в ядре — очереди
  steering/follow-up и автоповтор транзиентных ошибок (`auto_retry`, события
  `auto_retry_start/end`, отдельный retry-цикл для summarization). Источник:
  `packages/coding-agent/docs/rpc.md`.
- Durable harness (новый слой pi-agent-core, экспериментальный): сессия живёт в
  «полосах» (lane per конфигурация model/thinking/tools), операция (run/compaction/
  navigation) — восстанавливаемый конечный автомат (13 листьев, см. §2.2), чекпоинт
  `resume_checkpoint`, inbox-элементы `steer|followUp|nextRun|write`, `shouldStopAfterTurn`
  для graceful-остановки между ходами. Источники:
  `packages/agent/src/harness/session/types.ts`, `packages/agent/docs/harness.md`,
  work-packages в `packages/agent/docs/work-packages/`.
- Durable turns через внешние движки: официальный паттерн для Absurd (durable execution
  от Earendil): каждое `message_end` аппендится в durable step-log, контекст
  восстанавливается из лога, продолжение — `runAgentLoopContinue(...)`; если последнее
  сообщение `assistant` — задача уже завершена. Прямой аналог нашего «сессия
  возобновляется по новым сообщениям».
  Источник: https://earendil-works.github.io/absurd/patterns/pi-ai-agent/
- Многоагентные сценарии: только самоделки (расширения, порождение `pi -p`-процессов,
  pi-packages от сообщества). Готового оркестратора не найдено.

---

## 6. API-поверхность

**CLI-режимы** (`packages/coding-agent/README.md`): интерактивный TUI; `-p/--print`;
`--mode json` (все события JSON-строками, `docs/json.md`); `--mode rpc`; SDK.

**RPC-протокол** (`pi --mode rpc`, JSONL поверх stdin/stdout;
`packages/coding-agent/docs/rpc.md`):
- Команды (все с опциональным `id` для корреляции): `prompt` (+`images`,
  `streamingBehavior: steer|followUp`), `steer`, `follow_up`, `abort`, `clear_queue`,
  `new_session` (+`parentSession`), `get_state`, `get_messages`, `set_model`,
  `cycle_model`, `get_available_models`, `set_thinking_level`, `cycle_thinking_level`,
  `get_available_thinking_levels`, `set_steering_mode`, `set_follow_up_mode`, `compact`
  (+`customInstructions`), `set_auto_compaction`, `set_auto_retry`, `abort_retry`,
  `bash` (+стриминг `bash_execution_update`), `abort_bash`, `get_session_stats`,
  `export_html`, `switch_session`, `fork`, `clone`, `get_fork_messages`,
  `get_entries` (+ курсор `since`: entry-id как durable-курсор, `leafId` в ответе),
  `get_tree`, `get_last_assistant_text`, `set_session_name`, `get_commands`.
- События stdout: `agent_start/agent_end/agent_settled`, `turn_start/end`,
  `message_start/update/end` (дельты `text_*`, `thinking_*`, `toolcall_*`),
  `tool_execution_*`, `bash_execution_update`, `queue_update`, `compaction_start/end`,
  `auto_retry_*`, `summarization_retry_*`, `extension_error`.
- Sub-протокол Extension UI: диалоги `select/confirm/input/editor` (запрос
  `extension_ui_request` → ответ `extension_ui_response`, опциональный таймаут с
  авто-резолвом на стороне агента) + fire-and-forget `notify/setStatus/setWidget/
  setTitle/set_editor_text`.
- Авторизация: отсутствует (локальный подпроцесс).

**SDK** (`@earendil-works/pi-coding-agent`, `docs/sdk.md`): `createAgentSession()`,
`AgentSessionRuntime` (замена активной сессии: newSession/switchSession/fork/clone/
importFromJsonl), `session.prompt/steer/followUp/subscribe/navigateTree/compact/abort`,
`SessionManager` (create/open/continueRecent/inMemory/list/listAll/forkFrom),
`SettingsManager`, `ModelRuntime` (каталоги моделей, креды, runtime API-keys),
`defineTool`, фабрики инструментов, `DefaultResourceLoader`.

**HTTP/REST/SSE/WebSocket**: в OSS-ядре REST API **не найдено**. Web-транспорты есть
только на уровне провайдеров LLM (`transport: "sse" | "websocket" | "auto"` в настройках
и в `pi-ai`), не для клиентов агента. Стриминг к клиенту — события RPC/SDK.
Новый `pi-protocol` — бинарный CBOR поверх произвольного транспорта (сейчас Unix-socket),
без SSE/WebSocket и без аутентификации.

---

## 7. MCP

- Поддержки MCP **нет из коробки**, и это явная философия: «**No MCP.** Build CLI tools
  with READMEs, or build an extension that adds MCP support» со ссылкой на пост
  «What if you don't need MCP» (https://mariozechner.at/posts/2025-11-02-what-if-you-dont-need-mcp/).
  Источник: `packages/coding-agent/README.md` (Philosophy).
- MCP-клиент/сервер добавляется расширением (в списке возможностей extensions прямо
  указано «MCP server integration»). OAuth для MCP — только руками в своём расширении;
  встроенного не найдено.
- Для сравнения: нативная альтернатива Pi — «skill = папка с README + CLI-утилиты».

---

## 8. Расширения / плагины

**Extensions** (TypeScript-модули; `packages/coding-agent/docs/extensions.md`):
- Расположение: `~/.pi/agent/extensions/`, `.pi/extensions/`, `-e ./ext.ts`, pi-packages;
  hot-reload через `/reload`; project trust решает, грузить ли проектные расширения
  (`~/.pi/agent/trust.json`, `defaultProjectTrust: ask|always|never`, `-a/-na`).
- API `ExtensionAPI`: `pi.on(event, handler)`, `registerTool` (в т.ч. полная замена
  встроенных), `registerCommand`, `registerShortcut`, `registerFlag`,
  `registerProvider/unregisterProvider` (кастомные LLM-провайдеры, включая OAuth),
  `registerMessageRenderer/registerEntryRenderer/registerMarkdownTransformer`,
  `sendMessage/sendUserMessage/appendEntry/setSessionName/setLabel`,
  `getActiveTools/setActiveTools`, `setModel/setThinkingLevel`, `pi.exec`, `pi.events`
  (шина событий между расширениями).
- **Хуки жизненного цикла** (события `pi.on`): стартап `project_trust`, ресурсы
  `resources_discover`; сессия `session_start`, `session_info_changed`,
  `session_before_switch/fork/compact/tree` (отменяемые), `session_compact(_failed)`,
  `session_shutdown`; агент `before_agent_start`, `agent_start/end/settled`,
  `ui_prompt_start/end`, `turn_start/end`, `message_*`; провайдер `context`,
  `before_provider_headers`, `before_provider_request`, `after_provider_response`;
  модели `model_select`, `thinking_level_select`; инструменты `tool_call` (**блокирующий**,
  мутация `event.input`, `{block, reason, terminate}`), `tool_result`; ввод `user_bash`,
  `input`. Асинхронные фабрики расширений поддерживаются (pi ждёт их при старте).
- Контекст `ctx`: `ctx.ui` (диалоги, виджеты, статус, автокомплит, кастомные компоненты),
  `ctx.sessionManager`, `ctx.modelRegistry/model/thinkingLevel/scopedModels` (+ стриминг
  модельных вызовов из расширения), `ctx.isIdle/abort/hasPendingMessages`, `ctx.compact()`,
  `ctx.getContextUsage()`, `ctx.newSession/fork/navigateTree/switchSession/reload/shutdown`.
- Состояние расширения: рекомендация хранить в `details` toolResult и восстанавливать
  на `session_start` проходом по ветке — так состояние корректно переживает ветвление.
- **Pi Packages**: bundle расширений/скиллов/промптов/тем, установка из npm/git/URL/SSH,
  пин по тегу/коммиту, `pi install/remove/update/config`, манифест `pi` в package.json
  или автодисковери по каталогам. Предупреждение о безопасности: пакеты исполняют
  произвольный код. Источник: `packages/coding-agent/README.md` (Pi Packages).
- **Chord-плагины** — отдельный, более новый механизм для сервисной композиции нового
  рантайма (фасеты worker/presentation, бандл `node:vm`, reload без простоя сервисов).
  Это не то же самое, что pi-packages. Источник: `packages/chord/README.md`.

---

## 9. Сильные и слабые стороны (относительно целей spring-harness)

### Сильные

1. **`SessionRepo`/`Session`/`Storage` — готовый контракт «сессии в своей БД»**.
   Ровно наш кейс: `create/open/list/delete/fork`, ветки как первозданное понятие,
   батчевые коммиты записей (entry/usage/value/list), exclusive mutation barrier.
   SQLite-бэкенд — эталонный пример реализации поверх произвольного SQL-хранилища;
   фабрика БД — точка подмены (у нас — Postgres/JDBC). Источник:
   `packages/agent/src/harness/session/types.ts`, `packages/session-backends/sqlite-node/README.md`.
2. **Durable-состояние хода агента**: operation как восстанавливаемый автомат
   (13 листьев), чекпоинты, inbox (`steer|followUp|nextRun|write`), retry-политика в
   состоянии. Прямо ложится на наш ShedLock-планировщик: «сессия стартует, если есть
   новые сообщения и лок свободен» == пробуждение операции по inbox-элементу.
   Паттерн Absurd подтверждает жизнеспособность: append-only лог сообщений +
   `runAgentLoopContinue` при рестарте. Источники: `packages/agent/src/harness/session/types.ts`,
   https://earendil-works.github.io/absurd/patterns/pi-ai-agent/
3. **Асинхронные инструменты с отложенным результатом** решены инжектом сообщений:
   `pi.sendMessage(..., {deliverAs, triggerTurn})` / `Agent.steer()/followUp()`,
   плюс `stopReason: "deferred"` + `DeferredHandle` и листья `deferred.*` в state machine.
   Хороший референс для наших «инструмент вернулся сразу, вебхук придёт позже».
4. **Дерево сессий с ветвлением на месте** (id/parentId) и compaction/branch-summary
   как обычные записи — дёшево, прозрачно, курсор-ориентированно (`get_entries?since=`).
5. **RPC-протокол** очень полный (очереди, статистика, дерево, форки, extension-UI),
   с продуманными мелочами: строгий LF-фрейминг, корреляция по `id`, durable-курсор.
6. **Экосистема расширений без форков**: полный доступ к состоянию агента, блокирующие
   хуки `tool_call`, замена встроенных инструментов, кастомные провайдеры (наш
   LiteLLM-подобный SSO-провайдер делается именно `registerProvider`).
7. Инженерная культура: supply-chain hardening (пинованные зависимости, shrinkwrap,
   `--ignore-scripts`), телеметрия со схемами, конформанс-тесты бэкендов
   (`repo-conformance.test.ts`, `storage-conformance.test.ts`).

### Слабые / риски

1. **Нет продакшен-сервера**: pi-server экспериментальный (Unix-socket, без peer-auth,
   «no compatibility guarantees», protocol v8). Для нашей клиент-серверной платформы
   брать нечего, кроме идей.
2. **Нет REST/OpenAPI** и контракт-first: JSONL/CBOR/SDK. Наше требование
   REST+OpenAPI придётся закрывать полностью своими силами.
3. **Конкурентность между процессами не решена**: SQLite-бэкенд сознательно не делает
   lease/lock/heartbeat — «host гарантирует одного writable owner». Нам нужен
   распределённый лок (ShedLock) — Pi этот слой не даёт, только подтверждает, что он
   нужен и живёт выше хранилища.
4. **Нет permission-системы** — политики разрешений (наши «агент = роль + разрешения»)
   придётся строить самим; у Pi есть только хук `beforeToolCall`/`tool_call` как точка
   входа для гейтов.
5. **Нет MCP** — при наших 12+ MCP-серверах философия Pi прямо противоположна нашему
   требованию; поддержка была бы только самописным расширением.
6. **Два поколения API одновременно** (SessionManager-JSONL в coding-agent vs durable
   Session/Storage в pi-agent-core; chord-facets vs pi-packages) — миграция в разгаре,
   части помечены experimental, API нестабильны. Перенимать контракты нужно с осторожностью.
7. **Смена владельца и коммерциализация**: проект ушёл под Earendil Inc.; в сообществе
   обсуждается закрытие enterprise-фич (https://www.reddit.com/r/LocalLLaMA/comments/1sg37af/
   — не подтверждено официально; официально: «mostly a naming and ownership change»,
   https://pi.dev/news/2026/5/7/pi-has-a-new-home). Риск расхождения OSS-ядра и
   будущих проприетарных надстроек — учитывать, не строить критичный путь на их roadmap.

---

## 10. Выводы: что перенять и чего избегать

**Перенять:**

1. Контрактную triple «Repo → Session → Storage» с батчевыми коммитами и mutation
   barrier как основу нашего Postgres-хранилища сессий; ветки (`createBranch/at`) и
   fork (branch|tree scope) — прямо в схему БД (`entries(session_id, id, parent_id, seq, type, ...)`).
2. Модель «ход агента = durable операция с чекпоинтом и inbox» для планировщика:
   новые сообщения (пользователь/оркестратор/асинхронный инструмент) = inbox-элемент;
   лок = exclusive mutation barrier; рестарт = resume с checkpoint'а.
3. Паттерн отложенных инструментов: мгновенный toolResult + инжект custom-сообщения с
   `triggerTurn` по готовности (аналог наших «сессия просыпается от результата
   асинхронного инструмента»), и отдельный терминальный статус `deferred` для
   «ответ придёт позже».
4. Типы записей сессии: compaction (`firstKeptEntryId` + tokensBefore) и branch-summary
   как записи; custom-записи (состояние) vs custom-сообщения (контекст LLM) — чёткое
   разделение, которое мы можем повторить в схеме.
5. Курсорную синхронизацию клиентов: `get_entries?since=<entryId>` + `leafId` — готовый
   паттерн для attach-клиентов с любого ПК.
6. Хуки жизненного цикла как плоский список событий с блокирующими pre/post-фазами
   (`tool_call` c {block, reason, terminate}; `before_provider_request` для SSO-заголовков).
7. Учебное: RPC-фрейминг (строгий LF, корреляция id, авто-таймауты диалогов) и
   расширение системного промпта (replace/append + override-файлы).

**Избегать:**

1. Экспериментальных бинарных протоколов без версионирования совместимости и без
   аутентификации как основы клиент-серверного API — у нас контракт-first OpenAPI
   с SSO (Pi сам показывает, что auth — «application policy», т.е. боль).
2. Хранения сессий только в файлах как единственного источника правды для
   многопользовательского сервера — без БД нет ни конкурентного доступа, ни планировщика,
   ни аудита (Pi это признаёт, вводя Storage-абстракцию и S3-проекцию поиска).
3. Отсутствия permission-слоя в ядре: не повторять « containerization вместо разрешений» —
   для корпоративной платформы разрешения (роль + набор инструментов) должны быть
   первоклассными.
4. Дублирования API-поколений: не запускать два несовместимых слоя сессий параллельно,
   как Pi (SessionManager vs durable Session) — дорого в поддержке и документации.
5. Отказа от MCP как принципа: для нашей экосистемы (12+ MCP-серверов за OAuth-прокси)
   MCP — требование, а не опция; Pi-подход «CLI-утилиты + README» хорош как дополнение
   для локальных инструментов.

---

## Приложение: карта источников

- Корневой README (пакеты, философия, безопасность цепочки поставок):
  https://github.com/earendil-works/pi/blob/master/README.md
- pi-agent-core README (Agent, события, инструменты, steering):
  https://github.com/earendil-works/pi/blob/master/packages/agent/README.md
- agent.ts (класс Agent, очереди):
  https://github.com/earendil-works/pi/blob/master/packages/agent/src/agent.ts
- Durable-интерфейсы сессий:
  https://github.com/earendil-works/pi/blob/master/packages/agent/src/harness/session/types.ts
- coding-agent README (режимы, сессии, skills, packages):
  https://github.com/earendil-works/pi/blob/master/packages/coding-agent/README.md
- RPC-протокол:
  https://github.com/earendil-works/pi/blob/master/packages/coding-agent/docs/rpc.md
- SDK:
  https://github.com/earendil-works/pi/blob/master/packages/coding-agent/docs/sdk.md
- Формат сессии:
  https://github.com/earendil-works/pi/blob/master/packages/coding-agent/docs/session-format.md
- Расширения (события, sendMessage, хуки):
  https://github.com/earendil-works/pi/blob/master/packages/coding-agent/docs/extensions.md
- pi-server (экспериментальный сервер):
  https://github.com/earendil-works/pi/blob/master/packages/server/README.md
- pi-protocol (CBOR-протокол):
  https://github.com/earendil-works/pi/blob/master/packages/protocol/README.md
- SQLite session backend:
  https://github.com/earendil-works/pi/blob/master/packages/session-backends/sqlite-node/README.md
- Chord (facets/services/replicated state):
  https://github.com/earendil-works/pi/blob/master/packages/chord/README.md
- Переезд под Earendil:
  https://pi.dev/news/2026/5/7/pi-has-a-new-home
- Durable turns pattern (Absurd):
  https://earendil-works.github.io/absurd/patterns/pi-ai-agent/
- «Why no MCP»:
  https://mariozechner.at/posts/2025-11-02-what-if-you-dont-need-mcp/
