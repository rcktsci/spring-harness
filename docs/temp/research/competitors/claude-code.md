# Конкурентный анализ: Claude Code

> Объект: Claude Code (Anthropic). Документация: https://code.claude.com/docs/ , Agent SDK: https://code.claude.com/docs/en/agent-sdk/overview
> Дата анализа: 2026-09-15. Источники — официальная документация (Mintlify `.md` страницы). Версия продукта в доке — ветка 2.1.x.
> Принцип: каждый факт со ссылкой. Где данных нет — «не найдено/не подтверждено».

---

## 1. Архитектура

- Claude Code — агентский CLI-инструмент, который «читает кодовую базу, редактирует файлы, запускает команды и интегрируется с инструментами разработчика». Работает в терминале, IDE (VS Code, JetBrains), desktop-приложении и в браузере (web). https://code.claude.com/docs/en/overview
- Все поверхности подключаются к «одному и тому же underlying Claude Code engine», поэтому CLAUDE.md, settings и MCP-серверы репозитория работают одинаково на всех поверхностях. https://code.claude.com/docs/en/overview
- Это локальный агент, а не клиент-серверная БД-платформа: состояние сессий хранится в файлах на машине пользователя (см. §2). Серверная/облачная часть — отдельные продукты (Claude Code on the web, self-hosted environments, Managed Agents), а не ядро CLI. https://code.claude.com/docs/en/sessions
- Agent SDK даёт тот же «agent loop», инструменты и управление контекстом «as a library» на Python и TypeScript. Для другого языка нужно запускать CLI как subprocess: `claude -p ... --output-format json`. https://code.claude.com/docs/en/agent-sdk/overview
- В доке выделены четыре разных продукта: Agent SDK (встраиваемая библиотека), Claude Code CLI (интерактив), Client SDK (прямой доступ к Anthropic API, свой tool loop) и Managed Agents (хостинговый REST API; Anthropic сам запускает агента и sandbox). https://code.claude.com/docs/en/agent-sdk/overview
- Ядро — итеративный agentic loop: модель планирует, вызывает инструменты, получает результаты, повторяет. Описан в «How Claude Code works». https://code.claude.com/docs/en/how-claude-code-works
- Фоновые сессии выполняются отдельным supervisor-процессом, так что можно закрыть терминал/шелл, а работа продолжится; состояние сессий сохраняется на диск и переживает авто-обновления и рестарт supervisor; после сна машины процессы возобновляются, а supervisor переподключается к ним. https://code.claude.com/docs/en/agent-view
- Изоляция процессов/окружений: git worktrees (`--worktree`, `isolation: worktree` у subagent), sandboxed Bash tool (filesystem/network isolation), dev containers/Docker/VM как отдельные варианты. https://code.claude.com/docs/en/sub-agents , https://code.claude.com/docs/en/sandboxing , https://code.claude.com/docs/en/sandbox-environments
- Конфигурационное состояние лежит в: `~/.claude/` (проекты, skills, agents, workflows, settings), `~/.claude.json` (local/user MCP, disabled-списки), `.claude/settings.json` и `.claude/settings.local.json` в проекте, `.mcp.json` в корне проекта. Каталог конфигурации можно сместить через `CLAUDE_CONFIG_DIR`. https://code.claude.com/docs/en/sessions , https://code.claude.com/docs/en/mcp , https://code.claude.com/docs/en/settings
- Мультиагентность внутри одного процесса/сессии (subagents) и вне него (background sessions, agent teams, workflows) различается: subagents работают «within a single session». https://code.claude.com/docs/en/sub-agents
- Agent loop: модель итерирует plan → tool call → result → repeat; контекстное окно наполняется автоматически (CLAUDE.md, правила, hooks), а при заполнении срабатывает compaction. Отдельная документация — «Explore the context window» и «How Claude Code uses prompt caching». https://code.claude.com/docs/en/how-claude-code-works , https://code.claude.com/docs/en/context-window , https://code.claude.com/docs/en/prompt-caching
- Поверхности (terminal/VS Code/JetBrains/Desktop/web) — не независимые реализации, а оболочки над одним engine; каждая ведёт собственную историю сессий. https://code.claude.com/docs/en/overview , https://code.claude.com/docs/en/sessions
- Провайдеры моделей: Anthropic API, Amazon Bedrock, Claude Platform on AWS, Google Cloud Agent Platform, Microsoft Foundry, а также self-hosted LLM gateway и Claude apps gateway (SSO, per-group model access, OTLP). https://code.claude.com/docs/en/third-party-integrations , https://code.claude.com/docs/en/llm-gateway
- Аутентификация: интерактивный login (claude.ai/Console), `ANTHROPIC_API_KEY`, `apiKeyHelper`, provider-specific credentials; в bare-режиме OAuth/системный keychain не читаются. https://code.claude.com/docs/en/authentication , https://code.claude.com/docs/en/headless
- Сеть в enterprise: proxy, custom CA, mTLS; отдельно — corporate launcher для процессов Claude Code. https://code.claude.com/docs/en/network-config , https://code.claude.com/docs/en/corporate-launcher
- Мониторинг и стоимость: OpenTelemetry-экспорт метрик/трейсов, `total_cost_usd` в JSON-выводе `-p`, дашборд аналитики и limits. https://code.claude.com/docs/en/monitoring-usage , https://code.claude.com/docs/en/costs , https://code.claude.com/docs/en/analytics

---

## 2. Модель сессий

- Сессия — сохранённый разговор, привязанный к каталогу проекта. Транскрипты по умолчанию: JSONL в `~/.claude/projects/<project>/<session-id>.jsonl`, где `<project>` — путь рабочего каталога с заменой не-алфанумерических символов на `-`; при длине >200 символов имя обрезается и добавляется хэш полного пути. https://code.claude.com/docs/en/sessions
- Формат строк JSONL — «internal to Claude Code и меняется между версиями»; парсить файлы напрямую не рекомендуется (для этого есть `/export` и SDK). https://code.claude.com/docs/en/sessions
- Персистентность и настройки: `CLAUDE_CONFIG_DIR` переносит хранилище из `~/.claude`; `CLAUDE_CODE_PROJECT_DIR_NAME` задаёт имя project-каталога; `cleanupPeriodDays` меняет 30-дневное хранение; `CLAUDE_CODE_SKIP_PROMPT_HISTORY` подавляет запись; `--no-session-persistence` отключает запись для одного `-p` запуска. https://code.claude.com/docs/en/sessions
- Resume/continue: `claude --continue`, `claude --resume [<name>|<session-id>|<transcript-path>]`, `claude --from-pr <number>`, `/resume`. Сессия ищется по ID сначала в текущем проекте и его worktrees, затем во всех проектах машины. https://code.claude.com/docs/en/sessions
- Что восстанавливается при resume: история (включая tool calls/results), модель, агент (`--agent`), permission mode (с оговорками по способу запуска), активная goal, неистёкшие scheduled tasks. НЕ восстанавливаются: background Bash/Monitor задачи; флаги `--mcp-config`, `--settings`, `--plugin-dir`, `--fallback-model`, `--add-dir` нужно передавать снова. https://code.claude.com/docs/en/sessions
- Ветвление/форки: `/branch [name]` копирует транскрипт и переключает процесс на новую копию; из CLI — `--continue`/`--resume` + `--fork-session`; `--fork-session` в отдельном процессе не переносит «Allow for this session» grants. https://code.claude.com/docs/en/sessions
- `/fork` (v2.1.212+) копирует текущий разговор в новую фоновую сессию, оригинал продолжает работу; копия стартует с отдельным worktree, если не задано «edit in place». https://code.claude.com/docs/en/agent-view
- Совместный доступ: если возобновить одну сессию в двух терминалах без форка, сообщения обоих интерливаются в один транскрипт. https://code.claude.com/docs/en/sessions
- Удалённый доступ/роуминг: Remote Control — продолжение локальной сессии с телефона/планшета/браузера; cloud sessions (Claude Code on the web) с переносом `--cloud`/`--teleport`; agent view управляет фоновыми сессиями (attach/peek). https://code.claude.com/docs/en/remote-control , https://code.claude.com/docs/en/claude-code-on-the-web , https://code.claude.com/docs/en/agent-view
- Сессия-пикер: группировка по worktree (`Ctrl+W`) и по всем проектам (`Ctrl+A`), фильтр по git-ветке (`Ctrl+B`), поиск по PR/merge-request URL. https://code.claude.com/docs/en/sessions
- Session storage в Agent SDK: транскрипты можно зеркалировать во внешнее хранилище (S3, Redis, свой backend), чтобы другие хосты могли resume-ить сессии — страница «Persist sessions to external storage». https://code.claude.com/docs/en/agent-sdk/session-storage
- Итог: это local-first файловая модель (per-project каталоги), а не единая БД; серверная мультипользовательская модель в ядре CLI не найдена.
- SDK-сессии: continue/resume/fork по session_id; SDK может читать и продолжать сессию, начатую другим хостом, если транскрипт доступен; внешнее хранилище позволяет мульти-хост resume. https://code.claude.com/docs/en/agent-sdk/sessions , https://code.claude.com/docs/en/agent-sdk/session-storage
- Cross-session messaging: Claude может перечислять и писать другим локальным сессиям (`ListAgents`, `SendMessage`), а при Remote Control — и сессиям на других машинах/в web; есть уведомление, когда другая сессия уходит в idle. https://code.claude.com/docs/en/cross-session-messaging , https://code.claude.com/docs/en/tools-reference
- Checkpointing: отслеживание и rewind правок файлов и разговора (в т.ч. откат к состоянию до `/clear`). https://code.claude.com/docs/en/checkpointing
- `/export` даёт человекочитаемый транскрипт; программные интерфейсы (`-p --output-format json`, `transcript_path` в hooks/statusline, Agent SDK) — структурированные данные. https://code.claude.com/docs/en/sessions
- Управление контекстом в сессии: `/clear`, `/compact [instructions]`, `/context`; на resume «тяжёлой» сессии (>100k токенов, простой > ~1 ч) на Pro/Max предлагается Resume from summary / as-is / не спрашивать. https://code.claude.com/docs/en/sessions
- Именование сессий: `-n`, `/rename`, генерация заголовка Haiku-класса моделью, имя как resume-handle; дефолтные display names не являются handle. https://code.claude.com/docs/en/sessions
- Удаление данных: `claude project purge`, `claude rm <id>` (транскрипт остаётся на диске), авто-очистка по retention. https://code.claude.com/docs/en/sessions

---

## 3. Модель агента

### Subagents
- Subagent — специализированный агент «в своём context window с собственным system prompt, доступом к инструментам и независимыми permissions». Делегирование происходит по `description`; возвращается только summary, что экономит контекст главного разговора. https://code.claude.com/docs/en/sub-agents
- Встроенные subagents: `Explore` (read-only, наследует модель, ограниченную Opus; quick/medium/very thorough), `Plan` (read-only, исследование для plan mode), `general-purpose` (все инструменты), а также `claude`, `statusline-setup`, `claude-code-guide`. Explore и Plan пропускают CLAUDE.md и git status. https://code.claude.com/docs/en/sub-agents
- Frontmatter subagent (обязательны только `name`, `description`): `tools`, `disallowedTools`, `model` (sonnet/opus/haiku/fable/ID/inherit), `permissionMode` (default/acceptEdits/auto/dontAsk/bypassPermissions/plan/manual), `maxTurns`, `skills` (preload полного содержимого), `mcpServers` (inline/scoped), `hooks`, `memory` (user/project/local), `background`, `omitClaudeMd`, `effort`, `isolation: worktree`, `color`, `initialPrompt`, `experimental.cacheTtl`. https://code.claude.com/docs/en/sub-agents
- Скоупы и приоритет: managed settings (1) > `--agents` CLI JSON (2) > `.claude/agents/` проекта (3) > `~/.claude/agents/` (4) > plugin `agents/` (5). Каталоги сканируются рекурсивно; имя берётся из frontmatter `name`. https://code.claude.com/docs/en/sub-agents
- Фильтры инструментов subagent: всегда удаляются `Agent` (на лимите глубины), `AskUserQuestion`, `EndConversation`, `EnterPlanMode`, `ExitPlanMode` (кроме permissionMode `plan`), `ScheduleWakeup`, `TaskOutput`, `WaitForMcpServers`, `Workflow`. Фоновые subagents дополнительно ограничены набором (Read, Grep, Glob, Bash, PowerShell, Edit, Write, NotebookEdit, WebFetch, WebSearch, TodoWrite, Skill, ToolSearch, EnterWorktree, ExitWorktree, Monitor, TaskStop, SendMessage, Artifact). https://code.claude.com/docs/en/sub-agents
- Запрет/ограничение: `Agent(agent_type)` — allowlist типов, которые может спавнить главный агент; вложенность subagents поддерживается (depth limit). https://code.claude.com/docs/en/sub-agents
- Вызов: инструмент `Agent` (ранее Task; `Task(...)` работает как алиас), @-mention, resume по agent ID/name (`SendMessage`). https://code.claude.com/docs/en/sub-agents , https://code.claude.com/docs/en/tools-reference

### Skills
- Skill — каталог с `SKILL.md` (YAML frontmatter + markdown). Следует открытому стандарту Agent Skills (agentskills.io) с расширениями Claude Code. Тело грузится только при использовании (в отличие от CLAUDE.md). https://code.claude.com/docs/en/skills
- Кастомные команды слились со skills: `.claude/commands/deploy.md` и `.claude/skills/deploy/SKILL.md` оба создают `/deploy`. https://code.claude.com/docs/en/skills
- Где живут: Enterprise (managed dir), Personal (`~/.claude/skills/`), Project (`.claude/skills/`), Nested, Additional dir (`--add-dir`), Plugin (`<plugin>/skills/`), claude.ai-аккаунт (synced). https://code.claude.com/docs/en/skills
- Frontmatter: `name`, `description` (рекомендован), `when_to_use`, `argument-hint`, `arguments`, `disable-model-invocation`, `user-invocable`, `allowed-tools`, `disallowed-tools`, `model`, `effort`, `context: fork`, `agent`, `background`, `hooks`, `paths`, `shell`. https://code.claude.com/docs/en/skills
- Управление вызовом: `disable-model-invocation: true` — только вручную; `user-invocable: false` — только модель; `context: fork` запускает skill в форкнутом subagent (возможен свой `agent` и `model`). https://code.claude.com/docs/en/skills
- Динамическая инъекция контекста: строка `` !`git diff HEAD` `` выполняется и подставляется до того, как Claude увидит skill. Есть подстановки `$name`, `${CLAUDE_PROJECT_DIR}`, `${CLAUDE_SESSION_ID}`. https://code.claude.com/docs/en/skills
- Bundled skills: `/doctor`, `/code-review`, `/batch`, `/debug`, `/loop`, `/claude-api`, `/run`, `/verify`, `/run-skill-generator`, `/deep-research` (workflow). https://code.claude.com/docs/en/skills , https://code.claude.com/docs/en/workflows

### Разрешения (permission gates)
- Режимы: `default`/Manual, `acceptEdits`, `plan`, `auto` (classifier вместо человека), `dontAsk` (авто-deny всего, что требует промпта), `bypassPermissions`. Задаётся `defaultMode`; `permissions.disableBypassPermissionsMode`/`disableAutoMode` могут запрещать режимы. https://code.claude.com/docs/en/permissions

  | Режим | Поведение |
  |---|---|
  | `default` (Manual) | Промпт при первом использовании каждого инструмента |
  | `acceptEdits` | Авто-принятие правок файлов и common FS-команд (`mkdir`, `touch`, `mv`, `cp`) для путей в рабочем каталоге/`additionalDirectories` |
  | `plan` | Чтение файлов и read-only shell-команды; правки исходников запрещены |
  | `auto` | Авто-approve с фоновыми проверками classifier'а |
  | `dontAsk` | Авто-deny всего, что иначе спросило бы; read-only и явные allow всё равно исполняются |
  | `bypassPermissions` | Пропуск промптов, кроме действий, которые не авто-approve'ится ни одним режимом |

  https://code.claude.com/docs/en/permissions , https://code.claude.com/docs/en/permission-modes
- Действия, которые не авто-approve'ится ни в одном режиме, и защищённые пути (`.git`, `.claude`) описаны отдельно; `bypassPermissions` их тоже затрагивает. https://code.claude.com/docs/en/permission-modes
- Параметрические deny/ask-правила: `Tool(param:value)`, напр. `Agent(model:opus)`, `Bash(run_in_background:true)`, с `*`-wildcard в значении; основной content-параметр (`command`, `file_path`, `url`) так матчить нельзя. https://code.claude.com/docs/en/permissions
- Синтаксис путей для Read/Edit: gitignore-подобный (`//abs`, `~/home`, `/relative-to-settings-source`, `./relative`), разное поведение allow vs deny/ask для односегментных паттернов, симлинки проверяются по двум путям. https://code.claude.com/docs/en/permissions
- Pre-approved домены WebFetch хранятся в `.claude/settings.local.json` в корне git-репозитория (через worktrees к main checkout). https://code.claude.com/docs/en/permissions
- Разрешения можно задавать в `permissions.allow`/`deny`/`ask` в settings, `--allowedTools`/`--disallowedTools`, `allowedTools`/`disallowedTools` в SDK, `allowed-tools` у skill, `if` у hook. https://code.claude.com/docs/en/tools-reference
- Управление через `/permissions`; правила можно менять на лету — применяются со следующего tool call в том же turn (v2.1.234+). https://code.claude.com/docs/en/permissions
- Формат правил: `Tool` или `Tool(specifier)`, напр. `Bash(npm run *)`, `Read(./.env)`, `WebFetch(domain:example.com)`, `mcp__puppeteer__*`. Порядок вычисления: deny → ask → allow; первое совпадение решает; специфичность не меняет порядок. https://code.claude.com/docs/en/permissions
- Deny по голому имени инструмента удаляет инструмент из контекста модели целиком (кроме `EndConversation`); scoped deny блокирует конкретные вызовы. https://code.claude.com/docs/en/permissions
- Разрешения применяет Claude Code, а не модель: инструкции в промпте/CLAUDE.md не меняют то, что разрешено. https://code.claude.com/docs/en/permissions
- Read-only инструменты (Read, Grep) не спрашивают внутри working directory; Bash не спрашивает для встроенного набора read-only команд (`ls`, `cat`, `grep`, `find`, `wc`, `diff`, `stat`, `du`, `cd`, read-only git и др.). https://code.claude.com/docs/en/permissions
- Working directories: доступ ограничен рабочим каталогом + `--add-dir`/`additionalDirectories`; файлы вне них вызывают промпт. https://code.claude.com/docs/en/permissions
- `bypassPermissions` пропускает промпты, включая защищённые пути `.git`/`.claude`; предупреждают использовать только в изолированных средах. https://code.claude.com/docs/en/permissions
- Sandboxing даёт filesystem/network изоляцию, не зависящую от текста команды; Bash-правила по тексту названы «fragile» и «не security boundary» (можно обойти через `/bin/rm`, `sh -c`, `git -C`). https://code.claude.com/docs/en/permissions , https://code.claude.com/docs/en/sandboxing

### Промпт-структура и память
- CLAUDE.md (memory) — persistent-инструкции; есть auto memory (накопление знаний), уровни загрузки и nested-файлы. https://code.claude.com/docs/en/memory
- Subagent получает только свой system prompt + базовые данные окружения (working directory), а не системный промпт Claude Code. https://code.claude.com/docs/en/sub-agents
- Output styles позволяют адаптировать поведение за пределами software engineering. https://code.claude.com/docs/en/output-styles
- В SDK: выбор между `claude_code` preset и полностью кастомным system prompt; CLAUDE.md/skills/commands/memory загружаются из `.claude/` и `~/.claude/`. https://code.claude.com/docs/en/agent-sdk/overview , https://code.claude.com/docs/en/agent-sdk/modifying-system-prompts

---

## 4. Инструменты

### Полный список встроенных инструментов (canonical names)
Колонка «Разрешение» — «спрашивает ли в Manual-режиме для путей внутри рабочего каталога». https://code.claude.com/docs/en/tools-reference

| Инструмент | Назначение | Разрешение |
|---|---|---|
| `Agent` | Спавнит subagent с отдельным context window (с `name` — teammate при agent teams) | No |
| `Artifact` | Публикует HTML/Markdown как артефакт на claude.ai | Yes |
| `AskUserQuestion` | Multiple-choice вопросы к пользователю (таймаут `askUserQuestionTimeout`) | No |
| `Bash` | Выполнение shell-команд | Yes |
| `CronCreate` | Планирует повторяющийся/разовый промпт в сессии | No |
| `CronDelete` | Отменяет scheduled task по ID | No |
| `CronList` | Список scheduled tasks сессии | No |
| `Edit` | Точечная замена строк в файле | Yes |
| `EndConversation` | Завершает сессию (редко; защита от abuse) | No |
| `EnterPlanMode` | Переход в plan mode | No |
| `EnterWorktree` | Создаёт/переключает изолированный git worktree | Yes |
| `ExitPlanMode` | Презентует план на утверждение и выходит из plan mode | Yes |
| `ExitWorktree` | Выход из worktree обратно в исходный каталог | No |
| `Glob` | Поиск файлов по паттерну (по умолчанию отсутствует на macOS/Linux/WSL) | No |
| `Grep` | Поиск по содержимому (ripgrep; по умолчанию отсутствует на macOS/Linux/WSL) | No |
| `ListAgents` | Список агентов, которым можно писать через `SendMessage` | No |
| `ListMcpResourcesTool` | Список ресурсов подключённых MCP-серверов | No |
| `LSP` | Code intelligence через language server (переходы, references, диагностика) | No |
| `Monitor` | Фоновая команда/WebSocket, каждая строка вывода → событие в сессию | Yes |
| `NotebookEdit` | Изменение ячеек Jupyter (cell_id, replace/insert/delete) | Yes |
| `PowerShell` | Нативный PowerShell-инструмент (Windows) | Yes |
| `PushNotification` | Desktop/phone-уведомление о завершении долгой задачи | No |
| `Read` | Чтение файлов | No |
| `ReadMcpResourceTool` | Чтение MCP-ресурса по URI | No |
| `RemoteTrigger` | Создание/обновление/запуск Routines на claude.ai (`/schedule`) | No |
| `ReportFindings` | Структурированный список находок code-review | No |
| `ScheduleWakeup` | Перепланирование следующей итерации self-paced `/loop` | No |
| `SendFeedback` | Черновик отчёта о проблеме Claude Code | No |
| `SendMessage` | Сообщение другому агенту/сессии (cross-session messaging) | No |
| `SendUserFile` | Отправка файлов пользователю (Remote Control / cloud) | No |
| `ShareOnboardingGuide` | Публикация ONBOARDING.md | Yes |
| `Skill` | Выполнение skill в основном разговоре | Yes |
| `TaskCreate` | Создание задачи в списке задач | No |
| `TaskGet` | Детали задачи | No |
| `TaskList` | Список задач со статусами | No |
| `TaskOutput` | Получение вывода фоновой задачи (deprecated в пользу Read по output-файлу) | No |
| `TaskStop` | Остановка фоновой задачи/teammate/named agent | No |
| `TaskUpdate` | Обновление статуса/зависимостей/удаление задач | No |
| `TodoWrite` | Чеклист сессии (отключён по умолчанию в пользу Task*; `CLAUDE_CODE_ENABLE_TASKS=0` включает обратно) | No |
| `ToolSearch` | Поиск и загрузка отложенных инструментов при MCP tool search | No |
| `WaitForMcpServers` | Ожидание ещё подключающихся MCP-серверов (когда tool search выключен) | No |
| `WebFetch` | Загрузка содержимого URL | Yes |
| `WebSearch` | Веб-поиск | Yes |
| `Workflow` | Запуск dynamic workflow (скрипт, оркестрирующий много subagents) | Yes |
| `Write` | Создание/перезапись файлов | Yes |

### Синхронные vs асинхронные паттерны
- Read-only инструменты (Read, Grep, Glob) могут исполняться параллельно — harness знает об их read-only природе из деклорации инструмента. https://code.claude.com/docs/en/how-claude-code-works
- `TodoWrite`/`Task*` — плоский чеклист/список задач сессии, не граф состояний. https://code.claude.com/docs/en/tools-reference

### Фоновые задачи и отложенный возврат результата
- Bash: фоновый запуск через `run_in_background: true`; список/остановка — `/tasks`; результат пишется в файл, Claude читает его позже. https://code.claude.com/docs/en/tools-reference
- При достижении timeout команда НЕ убивается, а переносится в фон (кроме команд, начинающихся с `sleep`); в результате явно указывается `Command did not complete within its 120s timeout and was moved to the background` + task ID + путь к output-файлу. https://code.claude.com/docs/en/tools-reference
- Lifetimes: команда, запущенная foreground-subagent, останавливается при его финальном ответе; запущенная main-разговором или background-subagent — продолжает работать. В `-p` режиме фоновые shell-задачи завершаются примерно через 5 секунд после финального результата и закрытия stdin. https://code.claude.com/docs/en/tools-reference , https://code.claude.com/docs/en/headless
- `-p` с фоновым subagent/workflow ждёт завершения работы (по умолчанию до 10 минут простоя, `CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS`). https://code.claude.com/docs/en/headless
- MCP: вызов инструмента в main-разговоре, всё ещё идущий через 2 минуты, автоматически переносится в фоновую задачу; Claude сразу получает task ID, а результат приходит как task notification. Порог настраивается `CLAUDE_CODE_MCP_AUTO_BACKGROUND_MS` (0 — выключить). Не переносятся вызовы из subagents, к IDE-серверам, и в `-p` (если не `CLAUDE_AUTO_BACKGROUND_TASKS=1`). https://code.claude.com/docs/en/mcp
- `Monitor`: фоновая слежка (tail логов, polling PR/CI, watch каталога, WebSocket-фид); каждая строка вывода → событие в сессию, не прерывая разговор; deadline 5 мин по умолчанию, максимум 30 мин (10 мин в `-p`); использует permission-правила Bash. https://code.claude.com/docs/en/tools-reference
- `TaskOutput` (результат фоновой задачи) — deprecated в пользу `Read` по output-файлу; `TaskStop` останавливает задачу. https://code.claude.com/docs/en/tools-reference

### Поведение отдельных инструментов
- Bash: каждая команда — отдельный процесс; `cd` сохраняется между командами только в main-сессии и только внутри разрешённых каталогов (иначе `Shell cwd was reset`); env-переменные не сохраняются; алиасы/функции из стартового файла шелла подхватываются. Timeout: дефолт 2 мин (`BASH_DEFAULT_TIMEOUT_MS`), потолок 10 мин (`BASH_MAX_TIMEOUT_MS`). Вывод стримится в рабочий файл (убийство >5 ГБ); inline до ~30k символов, иначе путь к файлу + превью; при ошибке inline до ~10k. https://code.claude.com/docs/en/tools-reference
- Read: партиальный просмотр (`PARTIAL view`) не считается полноценным read для правила read-before-edit. Edit: точная замена `old_string`→`new_string` (без regex/fuzzy), три проверки — read-before-edit, точное совпадение, уникальность (иначе `replace_all`). Файл, изменённый на диске после чтения, может редактироваться, если `old_string` уникально совпадает с текущим содержимым. https://code.claude.com/docs/en/tools-reference
- Grep: построен на ripgrep (синтаксис ripgrep, не POSIX), режимы `files_with_matches` (default), `content`, `count`; параметры `glob`, `type`, `multiline`; уважает `.gitignore`. Glob: сортировка по mtime, cap 100 файлов, по умолчанию НЕ уважает `.gitignore` (`CLAUDE_CODE_GLOB_NO_IGNORE=false` включает). https://code.claude.com/docs/en/tools-reference
- LSP: активируется только при установленном code-intelligence плагине для языка; даёт go-to-definition, references, types, symbols, implementations, call hierarchy и авто-диагностику после правок. https://code.claude.com/docs/en/tools-reference
- NotebookEdit: правка ячейки по `cell_id` (replace/insert/delete), permission через `Edit(path)`-правила. https://code.claude.com/docs/en/tools-reference

---

## 5. Оркестрация и воркфлоу

- Task-прослеживание: `TaskCreate`/`TaskGet`/`TaskList`/`TaskUpdate` (доступны по умолчанию только на части моделей); `TodoWrite` как legacy-чеклист. https://code.claude.com/docs/en/tools-reference
- Plan mode: `EnterPlanMode`/`ExitPlanMode`; для исследования используется read-only subagent `Plan`; план презентуется на утверждение (`ExitPlanMode` требует permission). https://code.claude.com/docs/en/tools-reference , https://code.claude.com/docs/en/sub-agents , https://code.claude.com/docs/en/permission-modes
- Subagents vs agent teams vs workflows — четыре способа параллельной работы (плюс agent view для фонов):
  - Subagents: worker, которого спавнит Claude; решение turn-by-turn; результаты в context window главного агента.
  - Skills: инструкции, которым следует Claude.
  - Agent teams: lead-агент надзирает за peer-сессиями; общий shared task list; меж-агентный messaging.
  - Workflows: скрипт, который исполняет runtime; промежуточные результаты в переменных скрипта; масштаб «dozens to hundreds of agents per run»; повторяемая оркестрация. https://code.claude.com/docs/en/workflows , https://code.claude.com/docs/en/agent-teams
- Dynamic workflow: Claude пишет JavaScript-скрипт (`agent()`, `pipeline()`, `parallel()`, `phase()`, `log()`, глобальный `args`), runtime исполняет в изолированной среде, session остаётся отзывчивой. Лимиты: до 16 конкурентных агентов, до 4096 элементов в `parallel()`/`pipeline()`, до 1000 агентов на запуск, нет mid-run user input, у самого скрипта нет прямого доступа к FS/shell (только агенты), `import()` запрещён. https://code.claude.com/docs/en/workflows
- Сохранение workflow как команды: `/workflows` → `s` → `.claude/workflows/` (шарится через git) или `~/.claude/workflows/` (личное); запуск как `/<name>`; plugin-workflows неймспейсятся `/<plugin>:<name>`. https://code.claude.com/docs/en/workflows
- Agent view (`claude agents`): экран управления множеством фоновых сессий — dispatch, peek, attach, состояния (Working/Needs input/Idle/Completed/Failed/Stopped), PR-status, фильтры (`a:`, `s:`, `#`, URL), `/bg`, `/fork`. https://code.claude.com/docs/en/agent-view
- Scheduled tasks: `/loop`, инструменты `CronCreate`/`CronDelete`/`CronList`, `ScheduleWakeup` (self-paced loop), разовые напоминания; задачи session-scoped и восстанавливаются при resume, если не истекли. https://code.claude.com/docs/en/scheduled-tasks
- Goal: `/goal` ставит условие завершения, и Claude продолжает работу до его выполнения, до признания модели «невозможно» или до ошибки. https://code.claude.com/docs/en/goal
- Каналы (channels): MCP-сервер с capability `claude/channel`, включается флагом `--channels`, пушит события/алерты/вебхуки прямо в сессию для реакции без пользователя. https://code.claude.com/docs/en/channels , https://code.claude.com/docs/en/mcp
- Изоляция параллельной работы через git worktrees (`--worktree`, `isolation: worktree`, `.worktreeinclude`, cleanup). https://code.claude.com/docs/en/worktrees
- Checkpointing: track/rewind edits и conversation, восстановление файлов к предыдущему состоянию. https://code.claude.com/docs/en/checkpointing
- «Найм»/распределение работ как отдельная сущность (роли, ставки, бюджеты) — не найдено; ближайшие аналоги: subagent definitions, agent teams lead, workflow size guideline (small/medium/large/unrestricted). https://code.claude.com/docs/en/workflows
- Agent teams: координация нескольких Claude Code инстансов как команды — общий task list, меж-агентный messaging, централизованное управление, display modes (in-process teammate / split pane). Teammates дополнительно сохраняют task- и cron-инструменты. https://code.claude.com/docs/en/agent-teams , https://code.claude.com/docs/en/sub-agents
- Cross-session messaging: один Claude может писать другим сессиям (`SendMessage`, `ListAgents`); сообщение несёт optional `summary` (5–10 слов). https://code.claude.com/docs/en/cross-session-messaging
- Scheduled tasks детальнее: `/loop` повторяет промпт; `ScheduleWakeup` — self-paced loop (1 мин–1 час), `stop: true` завершает; pending wakeup виден в `session_crons` (Stop hook input). https://code.claude.com/docs/en/scheduled-tasks , https://code.claude.com/docs/en/tools-reference
- Goal: активная goal переносится при resume, но её turn count, таймер и token-baseline сбрасываются. https://code.claude.com/docs/en/sessions , https://code.claude.com/docs/en/goal
- Ultracode: `/effort ultracode` или `--effort ultracode` включает xhigh reasoning + автоматическую workflow-оркестрацию для каждой существенной задачи; ключевое слово `ultracode` в промпте запускает workflow для одной задачи. Работает только для «human» origin промптов (не для `-p`, scheduled, webhook). https://code.claude.com/docs/en/workflows
- Bundled workflow `/deep-research`: fan-out веб-поиска, перекрёстная проверка источников и цитируемый отчёт. https://code.claude.com/docs/en/workflows

---

## 6. API-поверхность

### CLI / headless
- Основной программный вход — CLI с флагом `-p`/`--print`: `claude -p "..." --allowedTools "Read,Edit,Bash"`. Не все флаги совместимы с `-p` (`--bg` отвергается; `--cloud` с описанием задачи отвергается). https://code.claude.com/docs/en/headless
- `--bare` ускоряет старт, пропуская авто-обнаружение hooks/skills/commands/subagents/plugins/MCP/auto memory/CLAUDE.md; рекомендуется для CI/SDK и станет дефолтом для `-p`. В bare-режиме доступны Bash, чтение и редактирование файлов; аутентификация — `ANTHROPIC_API_KEY` (не подписка). https://code.claude.com/docs/en/headless
- `--output-format`: `text` (default), `json` (result, session ID, usage, cost), `stream-json` (NDJSON real-time). `--json-schema` возвращает структурированный `structured_output`. https://code.claude.com/docs/en/headless
- `--input-format`, stdin piping (cap 10 МБ), авто-approve через `--allowedTools`, permission mode через `--permission-mode`, unattended через `--permission-prompts none`. https://code.claude.com/docs/en/headless
- Exit codes: 0 при успехе, non-zero при ошибке; SIGTERM → код 143. https://code.claude.com/docs/en/headless
- События `stream-json`: `system/init` (model, tools, mcp_servers, mcp_server_errors, plugins, plugin_errors, capabilities), `system/api_retry` (attempt, max_retries, retry_delay_ms, error_status, error), `system/plugin_install`, `stream_event` (`text_delta`), `assistant`/`user` сообщения (с `parent_tool_use_id` для subagents), финальный `result` (текст, cost, session metadata, `permission_denials`). https://code.claude.com/docs/en/headless
- Сообщения subagents и форкнутых skills появляются в потоке с `parent_tool_use_id` = ID вызвавшего их Agent-инструмента; включение `--forward-subagent-text` добавляет текстовые/thinking-блоки на всех уровнях вложенности. https://code.claude.com/docs/en/headless
- Поток не использует SSE/WebSocket: это NDJSON по stdout процесса (stream-json). Отдельные сетевые SSE/WS — это транспорт MCP, а не протокол сессии.
- Часто используемые флаги `-p`-режима: `--continue`, `--resume <id|path>`, `--allowedTools`, `--disallowedTools`, `--output-format`, `--json-schema`, `--input-format`, `--append-system-prompt(-file)`, `--system-prompt`, `--settings`, `--mcp-config`, `--agents`, `--plugin-dir`, `--plugin-url`, `--add-dir`, `--permission-mode`, `--permission-prompts`, `--permission-prompt-tool`, `--forward-subagent-text`, `--no-session-persistence`, `--strict-mcp-config`, `--setting-sources`, `--restricted`, `--agents`. https://code.claude.com/docs/en/headless , https://code.claude.com/docs/en/cli-reference
- User-invoked skills/команды работают в `-p` (включаются в промпт как `/skill-name`); терминальные команды (`/login`) недоступны. https://code.claude.com/docs/en/headless
- SDK streaming: сообщения приходят как типизированные объекты (`SDKSystemMessage`, `SDKHookStartedMessage` и т.п.), есть control requests (`register_repo_root`), `interrupt()` с receipts, capability-флаги в `system/init` (`interrupt_receipt_v1` и др.). https://code.claude.com/docs/en/agent-sdk/typescript , https://code.claude.com/docs/en/headless
- SDK observability/cost: OpenTelemetry (traces/metrics/events), cost tracking, todo tracking, file checkpointing. https://code.claude.com/docs/en/agent-sdk/observability , https://code.claude.com/docs/en/agent-sdk/cost-tracking , https://code.claude.com/docs/en/agent-sdk/todo-tracking , https://code.claude.com/docs/en/agent-sdk/file-checkpointing

### Agent SDK
- Библиотека для Python и TypeScript; предоставляет tools, hooks, subagents, MCP, permissions, sessions, skills/commands/memory, plugins. Вход/выход: streaming input vs single message; стриминг ответов и tool call'ов; structured outputs (JSON Schema/Zod/Pydantic). https://code.claude.com/docs/en/agent-sdk/overview , https://code.claude.com/docs/en/agent-sdk/streaming-vs-single-mode , https://code.claude.com/docs/en/agent-sdk/streaming-output , https://code.claude.com/docs/en/agent-sdk/structured-outputs
- Сессии в SDK: continue/resume/fork, session_id; персистентность; зеркалирование во внешнее хранилище (S3/Redis/свой backend) для resume с других хостов. https://code.claude.com/docs/en/agent-sdk/sessions , https://code.claude.com/docs/en/agent-sdk/session-storage
- Управление: approval/`canUseTool` callback, `PermissionRequest` hooks, `interrupt()`; динамическое обновление MCP через `setMcpServers()`; `additionalDirectories`/`add_dirs`. https://code.claude.com/docs/en/agent-sdk/permissions , https://code.claude.com/docs/en/agent-sdk/user-input , https://code.claude.com/docs/en/agent-sdk/typescript
- Кастомные инструменты: in-process MCP server (`createSdkMcpServer`, `tool()`), tool search для тысяч инструментов. https://code.claude.com/docs/en/agent-sdk/custom-tools , https://code.claude.com/docs/en/agent-sdk/tool-search
- Хостинг SDK: subprocess-архитектура, session persistence, масштабирование, observability, multi-tenant isolation для Docker/K8s/sandbox-провайдеров. https://code.claude.com/docs/en/agent-sdk/hosting
- Другие языки: только через запуск CLI subprocess с `-p --output-format json`. Официального Java SDK не найдено.
- Авторизация: API key (для сторонних продуктов claude.ai login/rate limits не разрешены); также Amazon Bedrock / Google Cloud Agent Platform / Microsoft Foundry / LLM-gateway. https://code.claude.com/docs/en/agent-sdk/overview , https://code.claude.com/docs/en/headless
- Отдельный продукт Managed Agents — hosted REST API (Anthropic запускает агента и sandbox); это НЕ Agent SDK. https://code.claude.com/docs/en/agent-sdk/overview

### MCP как транспорт (не наш REST)
- `claude mcp add/add-json/list/get/remove`, `claude mcp login` (OAuth), `/mcp` панель. https://code.claude.com/docs/en/mcp

---

## 7. MCP

- Поддерживаемые транспорты: `stdio` (локальный процесс), `http` (рекомендуемый для remote; `streamable-http` — алиас), `sse` (deprecated, но поддерживается), `ws` (WebSocket; только header-аутентификация, без OAuth и без `--transport`). https://code.claude.com/docs/en/mcp
- Скоупы хранения: `local` (`~/.claude.json`, только текущий проект, дефолт), `project` (`.mcp.json` в корне, шарится через VCS), `user` (`~/.claude.json`, все проекты). Приоритет при дублях: local > project > user > plugin > claude.ai connector; `managedMcpServers` выше всех. https://code.claude.com/docs/en/mcp
- Проектные серверы из `.mcp.json` требуют approve в интерактивной сессии (workspace trust); в `-p`, SDK и cloud промпт не показывается — серверы загружаются без подтверждения. https://code.claude.com/docs/en/mcp
- OAuth: remote-серверы авторизуются через `/mcp` (OAuth 2.0); при удалении remote-сервера удаляются сохранённые OAuth-токены и client registration; переаутентификация доступна через `/mcp`. Есть `claude mcp login` для авторизации из shell. https://code.claude.com/docs/en/mcp
- Ресурсы/промпты: `ListMcpResourcesTool`/`ReadMcpResourceTool`; MCP prompts доступны как команды (`/mcp__server__prompt`); elicitation (MCP-сервер запрашивает ввод у пользователя) с hooks `Elicitation`/`ElicitationResult`. https://code.claude.com/docs/en/mcp
- Tool search (по умолчанию включён): MCP-инструменты загружаются отложенно, Claude ищет их через `ToolSearch`, что позволяет масштабироваться до тысяч инструментов; при выключенном tool search используется `WaitForMcpServers`. https://code.claude.com/docs/en/mcp
- Лимиты вывода: предупреждение при превышении 10 000 токенов, лимит 25 000 по умолчанию, поднимается `MAX_MCP_OUTPUT_TOKENS`. https://code.claude.com/docs/en/mcp
- Таймауты: startup `MCP_TIMEOUT` (30 с), per-server `timeout` (мс, ≥1000), `MCP_TOOL_TIMEOUT` (дефолт ~28 часов), idle timeout (5 мин HTTP/SSE/WS, 30 мин stdio; `CLAUDE_CODE_MCP_TOOL_IDLE_TIMEOUT`). https://code.claude.com/docs/en/mcp
- Переподключение: remote-сервер после mid-session обрыва — до 5 попыток с экспоненциальным backoff; первое подключение HTTP/SSE — до 3 попыток; stdio не переподключаются автоматически. https://code.claude.com/docs/en/mcp
- Динамические обновления: поддержка MCP `list_changed` (обновление tools/prompts/resources без reconnect). https://code.claude.com/docs/en/mcp
- Организационный контроль: managed MCP (allow/deny, `managedMcpServers`, `disableCommandPluginSources`), managed-mcp.json для эксклюзивного контроля. https://code.claude.com/docs/en/managed-mcp
- Расширение конфигурации: `${VAR}` и `${VAR:-default}` в `command`/`args`/`env`/`url`/`headers`; `headersHelper` для динамических заголовков/токенов; per-server `timeout`; `--header` при `claude mcp add`. https://code.claude.com/docs/en/mcp
- claude.ai connectors: Claude Code может сам подтягивать коннекторы аккаунта; они участвуют в приоритетах и могут отключаться per-project. https://code.claude.com/docs/en/mcp
- Plugin-provided MCP-серверы: автоматический lifecycle, `${CLAUDE_PLUGIN_ROOT}`/`${CLAUDE_PLUGIN_DATA}`/`${CLAUDE_PROJECT_DIR}`, scoped имена. https://code.claude.com/docs/en/mcp
- Секреты/безопасность: verify trust перед подключением; серверы, тянущие внешний контент, дают риск prompt injection; credential-like текст редактируется из сообщений об ошибках, расширенный URL не показывается. https://code.claude.com/docs/en/mcp , https://code.claude.com/docs/en/security
- Известные проблемы/ограничения: SSE deprecated; `url` без `type` — ошибка конфигурации; зарезервированные имена серверов (`workspace`, `claude-in-chrome`, `computer-use`, `Claude Preview`, `Claude Browser`); предупреждения о hidden whitespace в значениях конфига; два клиентских runtime (v1 на MCP TS SDK 1.x, v2 на 2.0 с protocol revision 2026-07-28) и связанные различия (v2 не регистрирует channel-сервер на новой ревизии, OAuth с неожиданным issuer падает); WebSocket без OAuth; размер вывода ограничен. https://code.claude.com/docs/en/mcp

---

## 8. Расширения и плагины

- Plugin упаковывает skills, agents, hooks, MCP-серверы, команды, workflows, monitors; ставится из marketplace или по локальному пути/URL (`--plugin-dir`, `--plugin-url`). https://code.claude.com/docs/en/plugins , https://code.claude.com/docs/en/plugins-reference
- Плагинные subagents не поддерживают frontmatter `hooks`, `mcpServers`, `permissionMode` (игнорируются по соображениям безопасности). https://code.claude.com/docs/en/sub-agents
- Плагинные MCP-инструменты называются `mcp__plugin_<plugin>_<server>__<tool>`; сервер регистрируется как `plugin:<plugin>:<server>`; `${CLAUDE_PLUGIN_ROOT}`/`${CLAUDE_PLUGIN_DATA}`/`${CLAUDE_PROJECT_DIR}` подставляются. https://code.claude.com/docs/en/mcp
- Marketplace, версионирование зависимостей (`plugin-dependencies`), plugin evals, рекомендации плагинов. https://code.claude.com/docs/en/plugin-marketplaces , https://code.claude.com/docs/en/plugin-dependencies , https://code.claude.com/docs/en/plugin-evals
- Компоненты плагина: `skills/`, `agents/`, `hooks/hooks.json`, `.mcp.json`, commands, `workflows/`, monitors, output-styles; манифест `plugin.json`; `claude plugin validate` проверяет каталог; изменения hooks/MCP/agents требуют `/reload-plugins`. https://code.claude.com/docs/en/plugins-reference , https://code.claude.com/docs/en/skills
- Plugin monitors: плагин может объявлять мониторы, стартующие автоматически при активации плагина. https://code.claude.com/docs/en/plugins-reference
- `.claude` directory: где что лежит (CLAUDE.md, settings.json, hooks, skills, commands, agents, workflows, rules, auto memory) — полезная карта для нашей раскладки. https://code.claude.com/docs/en/claude-directory
- Расширения для обзора: «Extend Claude Code» сводит когда использовать CLAUDE.md, Skills, subagents, hooks, MCP и plugins. https://code.claude.com/docs/en/features-overview
- Artifacts (публикация результата сессии как интерактивной страницы) — отдельный канал вывода, не расширение кода. https://code.claude.com/docs/en/artifacts
- Хуки (hooks) как механизм расширения и контроля жизненного цикла (см. ниже). Skill frontmatter тоже умеет регистрировать hooks и подключать MCP. https://code.claude.com/docs/en/hooks , https://code.claude.com/docs/en/skills
- Enterprise-ограничения: `allowManagedHooksOnly`, managed settings, `strictPluginOnlyCustomization`, managed MCP. https://code.claude.com/docs/en/hooks , https://code.claude.com/docs/en/managed-mcp

### Hooks: события и управление
- Типы handler'ов: `command` (stdin JSON, результат через exit code/stdout), `http` (POST JSON), `mcp_tool` (вызов MCP-инструмента), `prompt` (single-turn оценка моделью), `agent` (subagent-верификатор, experimental). Все совпавшие hooks идут параллельно. https://code.claude.com/docs/en/hooks
- События (по доке): `SessionStart`, `Setup`, `UserPromptSubmit`, `UserPromptExpansion`, `PreToolUse`, `PermissionRequest`, `PermissionDenied`, `PostToolUse`, `PostToolUseFailure`, `PostToolBatch`, `Notification`, `MessageDisplay`, `SubagentStart`, `SubagentStop`, `TaskCreated`, `TaskCompleted`, `Stop`, `StopFailure`, `TeammateIdle`, `InstructionsLoaded`, `ConfigChange`, `CwdChanged`, `DirectoryAdded`, `FileChanged`, `WorktreeCreate`, `WorktreeRemove`, `PreCompact`, `PostCompact`, `PreModelSwitch`, `PostModelSwitch`, `Elicitation`, `ElicitationResult`, `SessionEnd`. https://code.claude.com/docs/en/hooks
- Что можно менять/блокировать: `PreToolUse` возвращает `permissionDecision` (`allow`/`deny`/`ask`) и блокирует вызов; `UserPromptSubmit`/`Stop`/`PreCompact`/`PreModelSwitch` и др. умеют влиять на ход; формат `hookSpecificOutput` с `additionalContext`, `continue`, `stopReason`, `systemMessage`, `retry` (для `PermissionDenied`). Exit code 0 — нет решения, 2 — блок, прочие — ошибка. https://code.claude.com/docs/en/hooks
- Async hooks: `async: true` + `asyncTimeout` для «запустить и забыть»; отдельные события (Notification, FileChanged, ConfigChange и т.п.) — standalone async. https://code.claude.com/docs/en/hooks
- Фильтрация: `matcher` (точное имя/список/регулярка; `*` — все) и `if`-условие в синтаксисе permission-правил (`Bash(rm *)`, `Edit(*.ts)`). MCP-инструменты матчатся как `mcp__<server>__<tool>`. https://code.claude.com/docs/en/hooks
- Где задаются: user/project/local settings, managed policy, plugin `hooks/hooks.json`, frontmatter skill, frontmatter subagent. https://code.claude.com/docs/en/hooks
- Hooks из settings/plugins работают и внутри subagents; tool-события несут `agent_id`/`agent_type`. https://code.claude.com/docs/en/hooks
- Практический разрыв: PreToolUse hooks описаны как способ реализовать проверку, которую нельзя надёжно выразить Bash-паттернами. https://code.claude.com/docs/en/permissions

---

## 9. Сильные и слабые стороны (в контексте нашей платформы)

### Сильные
- Зрелая, детальная модель разрешений: `Tool(specifier)`, порядок deny→ask→allow, режимы (включая `plan`, `dontAsk`, `bypassPermissions`), read-only fast-path, working directories. Это почти готовый шаблон permission gates для агентов. https://code.claude.com/docs/en/permissions
- Богатый жизненный цикл hooks с возможностью блокировать/менять действия и async-вариантами — прямой аналог наших «точек расширения сервера». https://code.claude.com/docs/en/hooks
- Изоляция контекста subagents + собственные tool allow/deny + model routing (`inherit`, `CLAUDE_CODE_SUBAGENT_MODEL`, `_FORCE`) — хорошо ложится на наши роли агентов. https://code.claude.com/docs/en/sub-agents
- Фоновые задачи и отложенный возврат результата сделаны системно: background Bash, Monitor, авто-backgrounding MCP >2 мин, agent view/supervisor, `TaskOutput`/`TaskStop`. Это близко к нашей идее «асинхронные инструменты, возвращающие результат в сессию». https://code.claude.com/docs/en/tools-reference , https://code.claude.com/docs/en/mcp , https://code.claude.com/docs/en/agent-view
- Dynamic workflows — скриптовая оркестрация «dozens to hundreds of agents» с возобновляемостью и prompt-cache fan-out; концептуально ближе всего к нашему workflow-движку. https://code.claude.com/docs/en/workflows
- Agent SDK даёт официальный способ встраивания агента (Python/TS, streaming, structured outputs, sessions, custom tools через in-process MCP). https://code.claude.com/docs/en/agent-sdk/overview
- MCP поддержан глубоко: 4 транспорта, 3 скоупа + managed, OAuth, tool search, resources/prompts/elicitation, list_changed. https://code.claude.com/docs/en/mcp
- Skills как lazy-loaded модули со стандартом Agent Skills и versionable в git — годится для нашей модели «навыки у роли». https://code.claude.com/docs/en/skills

### Слабые / риски
- Состояние сессий — локальные файлы, per-project каталоги, JSONL с «internal» форматом, меняющимся между версиями. Нет серверной БД и распределённого планировщика; multi-user/мульти-хост из коробки нет. Для нашего PostgreSQL+ShedLock это анти-паттерн. https://code.claude.com/docs/en/sessions
- Сессии привязаны к домашнему каталогу (`~/.claude`), каноническое состояние в конфиг-файлах, а не в БД. Облачные/удалённые доступы — отдельные продукты, а не свойство ядра. https://code.claude.com/docs/en/sessions
- Permission-границы на текстовых паттернах команд признаны «fragile» и не security boundary; настоящая изоляция только через sandbox. Это подтверждает, что permission gates нельзя делать единственной линией защиты. https://code.claude.com/docs/en/permissions , https://code.claude.com/docs/en/sandboxing
- Жёсткая привязка к вендору: аутентификация через claude.ai/API key, Agent SDK только Python/TS, отдельного Java SDK нет; для другого языка — subprocess CLI. https://code.claude.com/docs/en/agent-sdk/overview , https://code.claude.com/docs/en/headless
- Нет официального «серверного REST API» самого Claude Code; протокол — subprocess + NDJSON. Хостинговый REST — это отдельный Managed Agents, не Agent SDK. https://code.claude.com/docs/en/agent-sdk/overview
- Совместный доступ к одной сессии слабый: параллельный resume в двух терминалах без форка интерливает сообщения; collaboration примитивы — agent teams / cross-session messaging, а не общий attach нескольких клиентов к одной сессии. https://code.claude.com/docs/en/sessions
- Workflow-движок ограничен: нет mid-run user input, скрипт не имеет прямого FS/shell, лимит 1000 агентов, до 16 параллельных, product-specific (ultracode, `/deep-research`). https://code.claude.com/docs/en/workflows
- Высокая сложность конфигурации и конфликтов: приоритеты sources, дубли имён, много env-переменных, различия версий (много «requires v2.1.x»), два MCP-runtime. https://code.claude.com/docs/en/hooks , https://code.claude.com/docs/en/mcp , https://code.claude.com/docs/en/sub-agents
- Зависимость от фонового supervisor/supervisor-сервиса для agent view; shutdown останавливает запущенные сессии (требуется восстановление). https://code.claude.com/docs/en/agent-view

---

## 10. Выводы: что перенять и чего избегать

### Перенять
- Формат правил разрешений `Tool(specifier)` с явным порядком deny→ask→allow и набором режимов (Manual/acceptEdits/plan/auto/dontAsk/bypass); отдельно — идею read-only fast-path и concept «working directories / additional directories». https://code.claude.com/docs/en/permissions
- Дизайн hooks как формального жизненного цикла с JSON-in/JSON-out, блокировкой на `Pre*`-событиях, async-вариантами и matcher'ами по инструменту и аргументам — прямой прообраз наших серверных перехватчиков. https://code.claude.com/docs/en/hooks
- Модель subagent: изоляция контекста, собственный system prompt, tool allow/deny, model routing, maxTurns, preload skills. https://code.claude.com/docs/en/sub-agents
- Асинхронные паттерны: `run_in_background`, авто-перенос по timeout, отложенная доставка результата task-notification, `TaskOutput`/`TaskStop`, Monitor-события — реализовать в нашем планировщике как первоклассные состояния сессии. https://code.claude.com/docs/en/tools-reference , https://code.claude.com/docs/en/mcp
- Session-семантика resume/fork/branch с отдельными session ID и переносом части состояния; для нашего БД-хранилища — хранить версии/ветки сессий и явно фиксировать, что восстанавливается, а что нет. https://code.claude.com/docs/en/sessions
- Dynamic workflows как способ зафиксировать оркестрацию в переиспользуемом артефакте (script as orchestration, результаты в переменных, а не в контексте) — концептуальный референс для нашего workflow-графа, хотя мы делаем граф состояний, а не JS-скрипт. https://code.claude.com/docs/en/workflows
- MCP-клиент: транспорты, скоупы, tool search для больших каталогов, managed allow/deny, OAuth, resources/prompts/elicitation. https://code.claude.com/docs/en/mcp
- Skills как лениво загружаемые версионируемые модули (SKILL.md + frontmatter, `allowed-tools`, `context: fork`, preload в subagent) — хорошо ложится в нашу сущность «навык агента». https://code.claude.com/docs/en/skills
- Использование git worktrees/sandbox для изоляции параллельной работы и как реальной (а не только правиловой) границы безопасности. https://code.claude.com/docs/en/worktrees , https://code.claude.com/docs/en/sandboxing

### Избегать
- Хранения канонического состояния сессий в локальных JSONL-файлах и `~/.claude`; нам нужен PostgreSQL и распределённый планировщик (ShedLock), а не файловая модель. https://code.claude.com/docs/en/sessions
- Опора на текстовый матчинг команд как на границу безопасности; вместо этого — изоляция процессов + явные allow/deny + sandbox. https://code.claude.com/docs/en/permissions
- Жёсткой привязки к одному вендору и не-Java SDK; закладывать провайдер-агностичный слой (наш LiteLLM-провайдер) и REST/OpenAPI контракт клиент↔сервер, которого у Claude Code по сути нет. https://code.claude.com/docs/en/agent-sdk/overview , https://code.claude.com/docs/en/headless
- Неявного «магического» auto-поведения (auto mode classifier, ultracode-ключевое слово, авто-backgrounding) без прозрачной трассируемости в БД; у нас всё должно быть явным состоянием workflow. https://code.claude.com/docs/en/permissions , https://code.claude.com/docs/en/workflows
- Раздувания количества конфликтующих scope/приоритетов и version-gated нюансов; в нашей модели «агент = роль + инструменты + разрешения + навыки» лучше держать один источник истины. https://code.claude.com/docs/en/sub-agents , https://code.claude.com/docs/en/mcp
- Зависимости от локального supervisor-процесса для фоновых сессий; у нас фоновость = состояние сессии в БД + вытесняющий планировщик. https://code.claude.com/docs/en/agent-view

### Прямые соответствия нашей архитектуре
- Наши системные состояния «ожидание вебхука / вызов bash-скрипта» ↔ hooks + channels + scheduled tasks Claude Code; но у нас переход состояния выполняет workflow-движок, а не hook/human. https://code.claude.com/docs/en/channels , https://code.claude.com/docs/en/scheduled-tasks , https://code.claude.com/docs/en/hooks
- Наш вытесняющий планировщик сессий ↔ supervisor + agent view; отличие — мы планируем по таблице сессий и локам, Claude Code держит локальный supervisor. https://code.claude.com/docs/en/agent-view
- Наши «постоянные сессии с входящими событиями» ↔ background sessions + channels. https://code.claude.com/docs/en/channels , https://code.claude.com/docs/en/agent-view
- Терминальные состояния Done/Cancelled/Error ↔ состояния сессий агента в agent view (Completed/Failed/Stopped) — можно переиспользовать как UX-референс. https://code.claude.com/docs/en/agent-view
- Переход задачи инструментом (tool call) ↔ `ExitPlanMode`/`Workflow`/task-tools как примеры инструментов, меняющих ход работы. https://code.claude.com/docs/en/tools-reference

---

## Источники (основные страницы)
- Overview: https://code.claude.com/docs/en/overview
- How Claude Code works: https://code.claude.com/docs/en/how-claude-code-works
- Sessions: https://code.claude.com/docs/en/sessions
- Tools reference: https://code.claude.com/docs/en/tools-reference
- Hooks: https://code.claude.com/docs/en/hooks
- Subagents: https://code.claude.com/docs/en/sub-agents
- Skills: https://code.claude.com/docs/en/skills
- Permissions: https://code.claude.com/docs/en/permissions
- Permission modes: https://code.claude.com/docs/en/permission-modes
- Sandboxing: https://code.claude.com/docs/en/sandboxing
- MCP: https://code.claude.com/docs/en/mcp
- Plugins: https://code.claude.com/docs/en/plugins
- Headless: https://code.claude.com/docs/en/headless
- Agent SDK overview: https://code.claude.com/docs/en/agent-sdk/overview
- Agent SDK sessions: https://code.claude.com/docs/en/agent-sdk/sessions
- Agent SDK streaming: https://code.claude.com/docs/en/agent-sdk/streaming-output
- Agent SDK hosting: https://code.claude.com/docs/en/agent-sdk/hosting
- Agent view: https://code.claude.com/docs/en/agent-view
- Workflows: https://code.claude.com/docs/en/workflows
- Scheduled tasks: https://code.claude.com/docs/en/scheduled-tasks
- Channels: https://code.claude.com/docs/en/channels
- Agent teams: https://code.claude.com/docs/en/agent-teams
- Worktrees: https://code.claude.com/docs/en/worktrees
- Checkpointing: https://code.claude.com/docs/en/checkpointing
- CI (llms.txt): https://code.claude.com/docs/llms.txt

## Не подтверждено / не найдено
- Единый серверный REST/OpenAPI-контракт самого Claude Code (не найдено; только CLI/subprocess и SDK).
- Официальный Java SDK (не найдено).
- Точный сетевой протокол между SDK и CLI-движком (control protocol) — детали не проверялись в этой итерации; частично видны control requests вроде `register_repo_root` (упоминается в hooks). https://code.claude.com/docs/en/hooks
- Точные детали «Persist sessions to external storage» (S3/Redis) — известен факт наличия страницы, содержание не вычитывалось. https://code.claude.com/docs/en/agent-sdk/session-storage
- Формат строк JSONL транскрипта — внутренний и не документирован (не подтверждено по замыслу).
- «Найм/распределение работ» как отдельная сущность с бюджетами — не найдено.
