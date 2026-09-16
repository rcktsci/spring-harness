# Конкурентный анализ: Codex (OpenAI)

Объект исследования — CLI-агент **Codex** от OpenAI: open-source репозиторий [openai/codex](https://github.com/openai/codex), документация [developers.openai.com/codex](https://developers.openai.com/codex/). Дополнительно учитывались Codex App, IDE extension, Codex Web (cloud) и SDK.

Источники помечены ссылками. Для GitHub-репозитория указываются конкретные crates/файлы (workspace `codex-rs`). Дата сбора: 2026-09-15.

> Примечание об инструментах: HTML-страницы `developers.openai.com` не читаются `webfetch` (HTTP 403). Использовался инструмент `z_ai_web_reader`, а также markdown-варианты страниц (`.md`) и чтение исходников из репозитория. Часть вызовов MCP-инструментов периодически отваливалась по таймауту — это отражено в «Ограничениях» в конце.

---

## 1. Архитектура

**Язык и структура.** Codex CLI — это Rust-workspace [`codex-rs`](https://github.com/openai/codex/tree/master/codex-rs) (плюс npm-обёртка `codex-cli/` для @openai/codex). Workspace состоит из десятков crates (см. [структуру репозитория](https://github.com/openai/codex)):

- `cli` — точка входа CLI (`codex-rs/cli`),
- `tui` — интерактивный терминальный клиент (`codex-rs/tui`),
- `app-server`, `app-server-protocol`, `app-server-transport`, `app-server-daemon`, `app-server-client` — серверная подсистема для «rich clients» (VS Code extension, TUI, SDK),
- `core` — движок агента (`codex-rs/core/src`, в т.ч. `core/src/tools/`, `core/src/session/`),
- `tools` — общие определения инструментов (`codex-rs/tools/src`),
- `sandboxing`, `linux-sandbox`, `windows-sandbox-rs`, `windows-sandbox-service`, `bwrap`, `execpolicy`, `process-hardening` — изоляция и политики,
- `cloud-tasks`, `cloud-tasks-client`, `cloud-tasks-mock-client` — клиент OpenAI-облачных задач,
- `codex-mcp`, `rmcp-client` — MCP-клиент,
- `rollout`, `state`, `thread-store`, `history`, `message-history`, `keyring-store`, `codex-home` — состояние/история/хранилища,
- `exec-server`, `exec-server-protocol`, `network-proxy`, `http-client`, `code-mode*`, `skills`, `plugin`, `hooks`, `memories`, `otel` и др.

**Клиент/сервер.** Продукт local-first: одна локальная бинарка запускает агента и пишет состояние в `~/.codex` (Codex home). Для интеграций выделен **app-server** — это «бэкенд» для TUI/IDE/SDK ([app-server docs](https://developers.openai.com/codex/app-server)). Облачные задачи вынесены в **Codex Web** — отдельный OpenAI-хостинговый сервис, к которому CLI обращается по `https://chatgpt.com/backend-api` (см. `codex-rs/cloud-tasks/src/lib.rs`, base_url по умолчанию).

**Транспорт app-server** ([app-server](https://developers.openai.com/codex/app-server)):
- `stdio` (`--listen stdio://`, по умолчанию) — newline-delimited JSON (JSONL);
- `websocket` (`--listen ws://IP:PORT`) — экспериментально и не поддерживается; один JSON-RPC-месседж на текстовый фрейм, есть `GET /readyz` и `GET /healthz`, запросы с заголовком `Origin` отбиваются `403`;
- Unix socket (`--listen unix://[PATH]`) — WebSocket-handshake поверх UDS;
- `off` — без локального транспорта.

**Изоляция процессов.** Команды агента исполняются в sandbox уровне ОС, а не только через встроенные файловые операции ([Sandbox docs](https://developers.openai.com/codex/sandboxing)):
- **macOS** — Seatbelt;
- **Linux/WSL2** — `bubblewrap` (`bwrap`) + unprivileged user namespaces (при отсутствии — bundled helper, с предупреждением);
- **Windows** — нативный sandbox (restricted token / elevated-пайплайн, см. `codex-rs/windows-sandbox-rs`, `codex-rs/core/src/windows_sandbox.rs`, настройки `windows.sandbox = unelevated|elevated`).

Дополнительно: `execpolicy`/`.rules` (Starlark-правила префиксов команд, `codex-rs/core/src/exec_policy/`), `shell_environment_policy` (фильтрация env для субпроцессов), `network-proxy`/managed network policy. MCP-stdio-серверы запускаются как отдельные дочерние процессы.

**Дистрибуция.** install.sh/install.ps1 (`https://chatgpt.com/codex/install...`), npm `@openai/codex`, Homebrew cask, GitHub Releases с платформенными бинарниками ([README](https://github.com/openai/codex/blob/master/README.md)).

---

## 2. Модель сессий

**Единица работы — «thread» (тред), исторически «session».** Тред = диалог + история turn'ов и item'ов; turn = один пользовательский ввод и работа агента до завершения ([app-server](https://developers.openai.com/codex/app-server)).

**Хранение.** Локальное, в `~/.codex`:
- **rollout-файлы** — транскрипты сессий на диске (README `codex exec --ephemeral` явно говорит «не сохранять session rollout files»; `thread/archive` «move a thread's log file into the archived directory»; `thread/read` читает «stored thread data»); сущность фигурирует в `codex-rs/rollout/` и `codex-rs/core/src/rollout.rs`;
- **`history.jsonl`** — `history.persistence = save-all | none`, лимит `history.max_bytes` ([config-reference](https://developers.openai.com/codex/config-reference.md));
- **SQLite** — `sqlite_home` (каталог SQLite-backed DB для agent jobs и другого resumable-состояния); `thread/metadata/update` «patch SQLite-backed stored thread metadata» (сейчас — `gitInfo`);
- **сессии приложения** хранят историю тредов, но API чтения/листинга идёт через app-server.

**Resume / чтение.** `thread/start`, `thread/resume`, `thread/read` (без загрузки в память), `thread/list` (курсорная пагинация, фильтры), `thread/turns/list`; в CLI — интерактивный session picker, `codex exec resume --last|<session-id>` ([non-interactive](https://developers.openai.com/codex/noninteractive.md)); `codex archive`/`codex unarchive`/`codex delete <SESSION>` (по id или имени) ([developer commands](https://developers.openai.com/codex/developer-commands.md)).

**Форки/ветвления.** `thread/fork` копирует сохранённую историю в новый thread id (возвращает `forkedFromId`); для субагентов есть `fork_context`/`fork_turns=none|all|N` ([subagents](https://developers.openai.com/codex/subagents)).

**Удалённый доступ / роуминг.** Несколько механизмов ([remote connections](https://developers.openai.com/codex/remote-connections)):
- **attach-аналог**: `codex --remote ws://host:port|unix://[PATH]` подключает TUI к уже запущенному app-server; опционально `--remote-auth-token-env` ([developer commands](https://developers.openai.com/codex/developer-commands.md));
- **remote-control**: `codex remote-control [start|stop|pair]` поднимает локальный app-server daemon с remote control и печатает короткоживущий pairing-код; ChatGPT mobile/другие Codex App устройства подключаются к хосту через «secure relay layer» (не публичный интернет);
- **SSH**: Codex App по SSH-алиасу из `~/.ssh/config` запускает удалённый Codex app server через login shell и работает с удалённым проектом;
- **handoff треда** между локальным и удалённым хостом с переносом git-состояния (через worktree; в cloud — не поддерживается);
- **Codex Web (cloud)**: серверные задачи в OpenAI cloud, привязанные к GitHub; CLI — `codex cloud list|status|diff|apply|exec`, `codex apply`.

**Совместный доступ.** Один загруженный тред может иметь несколько подписчиков app-server (есть `thread/unsubscribe`; при отсутствии подписчиков сервер выгружает тред после grace-периода и эмитит `thread/closed`). Это модель «несколько клиентов смотрят одну сессию», а не многопользовательское редактирование. Состояние треда транслируется уведомлением `thread/status/changed`.

---

## 3. Модель агента

**Ролей как первоклассной сущности нет.** Вместо этого — «агент» (модель + инструкции + конфиг) и **субагенты**.

**Субагенты** ([subagents](https://developers.openai.com/codex/subagents)):
- включены по умолчанию; Codex спавнит их только при явной просьбе;
- встроенные агенты: `default` (общий), `worker` (исполнение/фиксы), `explorer` (read-heavy исследование);
- **кастомные агенты** — отдельные TOML-файлы в `~/.codex/agents/` или `.codex/agents/`; обязательные поля `name`, `description`, `developer_instructions`; опционально `nickname_candidates`, `model`, `model_reasoning_effort`, `sandbox_mode`, `mcp_servers`, `skills.config`;
- глобально: `[agents] max_threads` (по умолчанию 6), `max_depth` (по умолчанию 1 — прямой ребёнок, без глубокой рекурсии), `job_max_runtime_seconds` (для CSV-fan-out, дефолт 1800 c);
- субагент наследует sandbox-политику родителя; при спавне переприменяются live-runtime override'ы родителя (`/permissions`, `--yolo`).

**Навыки (skills)** ([skills](https://developers.openai.com/codex/skills)):
- формат — открытый agent skills standard: каталог с `SKILL.md` (обязательны `name`, `description`) + `scripts/`, `references/`, `assets/`, `agents/openai.yaml`;
- **progressive disclosure**: в контекст сначала идёт только name+description+path; полный `SKILL.md` читается при выборе; список ограничен ~2% окна (или 8000 символов);
- области: `REPO` (`.agents/skills` от cwd вверх до root), `USER` (`~/.agents/skills`), `ADMIN` (`/etc/codex/skills`), `SYSTEM` (встроенные); поддерживаются symlink-каталоги; дубликаты по `name` не мержатся;
- явный вызов `$skill` / `/skills`, неявный — по `description`; `[[skills.config]] path=..., enabled=false`; `agents/openai.yaml` задаёт `policy.allow_implicit_invocation` и `dependencies.tools` (MCP).

**Разрешения и промпт:**
- `approval_policy = untrusted | on-request | never | { granular = {...} }`; `approvals_reviewer = user | auto_review` (reviewer-субагент, «guardian»); устаревший `on-failure` ([config-reference](https://developers.openai.com/codex/config-reference.md));
- `sandbox_mode = read-only | workspace-write | danger-full-access`, `sandbox_workspace_write.{writable_roots,network_access,...}`;
- именованные `permissions.*` профили (filesystem/network, deny-read glob, managed proxy);
- `requirements.toml` — admin-enforced ограничения (allow-lists approval/sandbox/web-search, allowlist MCP, pinned features);
- промпт-структура: системные/developer-инструкции + `AGENTS.md` (или `model_instructions_file`), список skills, tool-спеки, plan mode. Управляемые hooks могут менять `tool_input`.
- Profile (`[profiles.<name>]`, `--profile`) группируют модель/approval/sandbox и т.п.

**Оркестрация субагентов** описана в промпте spawn-инструмента (делегировать только bounded sidecar-задачи, не блокирующий critical path, непересекающиеся write-scope).

---

## 4. Инструменты

**Встроенные инструменты** (реестр `codex-rs/core/src/tools/`, сборка — `codex-rs/core/src/tools/spec_plan.rs`, определения — `codex-rs/core/src/tools/handlers/*_spec.rs`):

| Инструмент | Назначение | Источник |
|---|---|---|
| `exec_command` + `write_stdin` | Unified PTY-exec: запуск команды и запись в stdin живого процесса (feature `unified_exec`); при выключенном — `exec_command` one-shot | `handlers/unified_exec/{exec_command,write_stdin}.rs`, `spec_plan.rs` |
| `apply_patch` | Правки файлов патчем | `handlers/apply_patch*.rs` |
| `update_plan` | План: список `{step,status}`, статусы `pending|in_progress|completed`, не более одного `in_progress` | `handlers/plan_spec.rs` |
| `view_image` | Просмотр локального изображения | `handlers/view_image_spec.rs` |
| `web_search` | Веб-поиск (hosted или standalone namespace `web.run`; режим `disabled|cached|live`) | `handlers/hosted_spec.rs`, `core/src/web_search.rs` |
| `list_mcp_resources`, `list_mcp_resource_templates`, `read_mcp_resource` | MCP resources | `handlers/mcp_resource*.rs` |
| `request_user_input`, `request_user_input_async` | Запрос у пользователя 1–3 вопросов (experimental) | `handlers/request_user_input*.rs` |
| `send_message_to_user_async` | Асинхронное сообщение пользователю | `handlers/send_message_to_user_async.rs` |
| `request_permissions` | Запрос доп. прав (sandbox escalation) | `handlers/request_permissions.rs` |
| `new_context_window`, `get_context_remaining` | Управление бюджетом контекста | `handlers/new_context_window*.rs`, `get_context_remaining*.rs` |
| `current_time` (clock), `sleep` | Время/пауза | `handlers/current_time.rs`, `handlers/sleep.rs` |
| `list_available_plugins_to_install`, `request_plugin_install` | Поиск/установка plugins | `handlers/list_available_plugins_to_install*.rs`, `request_plugin_install*.rs` |
| `tool_search` | Поиск инструментов (deferred tools) | `codex-rs/tools/src/tool_discovery.rs` |
| `code_mode` + wait | Code Mode (исполнение кода, зовущего инструменты) | `handlers/code_mode/*.rs`, `codex-rs/tools/src/code_mode.rs` |
| `test_sync_tool` | Модель-специфичный тест-инструмент | `handlers/test_sync*.rs` |
| multi-agent v1 (namespace `multi_agent_v1`): `spawn_agent`, `send_input`, `wait_agent`, `resume_agent`, `close_agent` | Управление субагентами | `handlers/multi_agents_spec.rs` |
| multi-agent v2: `spawn_agent(task_name)`, `send_message`, `followup_task`, `interrupt_agent`, `list_agents`, `wait_agent` | Управление субагентами (новый контракт) | `handlers/multi_agents_spec.rs`, `spec_plan.rs` |
| `spawn_agents_on_csv`, `report_agent_job_result` | Batch fan-out: один worker на строку CSV, экспорт результатов | [subagents](https://developers.openai.com/codex/subagents) |
| dynamic tools | Client-executed инструменты, объявляемые через app-server `dynamicTools` | [app-server](https://developers.openai.com/codex/app-server) |
| MCP-инструменты (`mcp_tool_call`) | Инструменты внешних MCP-серверов | `core/src/mcp_tool_call*.rs` |

Особые режимы: `image_gen.imagegen` (генерация изображений), `web.run` (standalone web search), namespaces. Инструменты скрываются/переэкспонируются через `ToolExposure` (`direct`, `deferred`, `code_mode_only`, `hidden`) в зависимости от модели, feature-флагов и `supports_search_tool`.

**Синхронные/асинхронные паттерны:**
- обычный tool call внутри turn'а — синхронный (результат возвращается в turn);
- **фоновые терминалы** — `exec_command` создаёт PTY-сессию, дальше `write_stdin` опрашивает; `background_terminal_max_timeout` (по умолчанию 300000 мс) ограничивает пустой poll; app-server имеет `thread/backgroundTerminals/{list,clean,terminate}`, `process/{spawn,outputDelta,exited}` ([app-server](https://developers.openai.com/codex/app-server));
- **субагенты** — долгоживущие треды; родитель делает `wait_agent` с таймаутом (колл возвращается по таймауту/первому финалу), после завершения приходит нотификация с финальным статусом;
- **csv-джобы** — асинхронный fan-out с `max_concurrency`, `max_runtime_seconds`, экспортом CSV;
- **cloud tasks** — полностью асинхронные задачи с отложенным результатом (diff/PR).

**Одобрения как отдельный «канал».** App-server инициирует server→client запросы `item/commandExecution/requestApproval` и `item/fileChange/requestApproval`; решения: `accept`, `acceptForSession`, `decline`, `cancel`, `acceptWithExecpolicyAmendment`. Есть `tool/requestUserInput` с `autoResolutionMs` (пользователь может не отвечать). Это близко к нашим «асинхронным инструментам с отложенным возвратом».

---

## 5. Оркестрация / воркфлоу

**Workflow-движка (состояния + переходы) нет.** Состояние задачи не хранится как граф; есть несколько более слабых механизмов:

- **Plan mode**: инструмент `update_plan` ведёт список шагов (`pending/in_progress/completed`), один `in_progress`; `plan_mode_reasoning_effort`; app-server item `plan` и `item/plan/delta`. План — рекомендательный, не enforcing.
- **Multi-agent orchestration**: родитель спавнит субагентов, маршрутизирует follow-up, ждёт и закрывает треды; лимиты `max_threads`/`max_depth`; v2 ведёт «task paths» (`/root/task1/task3`) ([subagents](https://developers.openai.com/codex/subagents)).
- **Goals**: `thread/goal/{set,get,clear}` — цель треда как атрибут ([app-server](https://developers.openai.com/codex/app-server)).
- **Collaboration modes**: `collaborationMode/list` — пресеты режима совместной работы (experimental).
- **Cloud tasks**: асинхронные фоновые задачи в облаке, параллельные, с созданием PR через GitHub; environments (devcontainer-подобные настройки) ([Codex web](https://developers.openai.com/codex/cloud)).
- **Интеграции/триггеры**: GitHub (`@codex` на issue/PR), GitHub Action, интеграции Slack/Linear, GitLab (Beta); в Codex App — «Automations»/scheduled tasks (вне CLI-документации, см. навигацию docs).
- **Найма/распределения работ нет**: выбор «роли» делает модель по описаниям кастомных агентов, без серверного планировщика/пула задач.

В терминах нашей платформы: у Codex отсутствует персистентная серверная оркестрация — всё держится на промпте и локальном процессе.

---

## 6. API-поверхность

**Локальный app-server — JSON-RPC 2.0** (поле `"jsonrpc":"2.0"` на проводе опущено; см. `codex-rs/app-server-protocol/src/rpc.rs` — `JSONRPCRequest/Notification/Response/Error`, `RequestId` string|int). Схемы можно сгенерировать: `codex app-server generate-ts --out ./schemas` и `generate-json-schema` ([app-server](https://developers.openai.com/codex/app-server)).

**Основные методы** (неполный, но репрезентативный список из [app-server](https://developers.openai.com/codex/app-server)):
- lifecycle: `initialize` + нотификация `initialized` (обязательны до любого запроса);
- threads: `thread/start`, `thread/resume`, `thread/fork`, `thread/read`, `thread/list`, `thread/turns/list`, `thread/loaded/list`, `thread/name/set`, `thread/metadata/update`, `thread/archive`, `thread/delete`, `thread/unsubscribe`, `thread/unarchive`, `thread/compact/start`, `thread/shellCommand`, `thread/backgroundTerminals/*`, `thread/rollback`, `thread/inject_items`, `thread/goal/{set,get,clear}`;
- turns: `turn/start`, `turn/steer`, `turn/interrupt`;
- review: `review/start`;
- exec/process: `command/exec`, `command/exec/{write,resize,terminate}`, `command/exec/outputDelta` (notify), `process/{spawn,writeStdin,resizePty,kill}`, `process/{outputDelta,exited}`;
- models/features: `model/list`, `modelProvider/capabilities/read`, `experimentalFeature/list`, `experimentalFeature/enablement/set`, `collaborationMode/list`;
- skills/plugins/apps: `skills/list`, `skills/changed`, `skills/config/write`, `marketplace/{add,upgrade}`, `plugin/{list,read,install,uninstall}`, `app/list`, `tool/requestUserInput`;
- MCP: `mcpServer/oauth/login`, `config/mcpServer/reload`, `mcpServerStatus/list`, `mcpServer/resource/read`, `mcpServer/tool/call`, `mcpServer/startupStatus/updated`;
- config/fs: `config/read`, `config/value/write`, `config/batchWrite`, `configRequirements/read`, `fs/{readFile,writeFile,createDirectory,getMetadata,readDirectory,remove,copy,watch,unwatch,changed}`;
- прочее: `windowsSandbox/setupStart`, `feedback/upload`, `externalAgentConfig/{detect,import}`.

**Поток событий** — серверные нотификации: `thread/*`, `turn/*` (`turn/started`, `turn/diff/updated`, `turn/plan/updated`, ...), `item/started`/`item/completed`, дельты `item/agentMessage/delta`, `item/plan/delta`, `item/reasoning/*`, `item/commandExecution/outputDelta`, `serverRequest/resolved`, `error`. `ThreadItem` — tagged union: `userMessage`, `agentMessage`, `plan`, `reasoning`, `commandExecution`, `fileChange`, `mcpToolCall`, `dynamicToolCall`, `collabToolCall`, `webSearch`, `imageView`, `entered/exitedReviewMode`, `contextCompaction`. Есть `codexErrorInfo` (`ContextWindowExceeded`, `UsageLimitExceeded`, `HttpConnectionFailed`, `SandboxError`, ...).

**Транспорт и авторизация:** stdio/JSONL, WebSocket (experimental; `--ws-auth capability-token|signed-bearer-token`, `--ws-token-file`, `--ws-shared-secret-file`), Unix socket. При перегрузке — JSON-RPC `-32001 "Server overloaded; retry later."`. В `initialize.params.capabilities` — `experimentalApi` и `optOutNotificationMethods`.

**SDK:**
- **TypeScript** `@openai/codex-sdk` (Node ≥18): `new Codex()`, `startThread()`, `thread.run(prompt)`, `resumeThread(id)` — [SDK](https://developers.openai.com/codex/sdk);
- **Python** `openai-codex` (≥3.10, beta): управляет локальным Codex app-server по JSON-RPC; `Codex`/`AsyncCodex`, `thread_start(model=..., sandbox=...)`, `run()`, presets `Sandbox.read_only|workspace_write|full_access`.

**CLI/headless:**
- `codex exec` (`codex e`): промпт в stdout, прогресс в stderr; `--json` → JSONL-события (`thread.started`, `turn.started`, `turn.completed`, `turn.failed`, `item.*`, `error`); `--output-schema` (JSON Schema финального ответа), `-o/--output-last-message`, `--ephemeral`, `resume --last|<id>`, `--ignore-user-config`, `--ignore-rules`, `--sandbox`, `--full-auto` (deprecated) ([non-interactive](https://developers.openai.com/codex/noninteractive.md));
- `codex review`, `codex apply`, `codex cloud ...`, `codex doctor`, `codex features`, `codex execpolicy check`, `codex mcp`, `codex login/logout`, `codex archive/unarchive/delete`, `codex app-server`, `codex remote-control`, `codex apply` ([developer commands](https://developers.openai.com/codex/developer-commands.md)).

**MCP-сервер: удалён.** `codex mcp-server` и бинарник `codex-mcp-server` больше не существуют; интеграторам предлагается app-server. «This isn't an MCP server or a drop-in replacement for an MCP client» — [MCP server removal](https://developers.openai.com/codex/mcp-server). Внешние MCP-серверы как клиент — поддерживаются.

**Auth:** ChatGPT OAuth (browser/device-code/externally managed токены), API key (`CODEX_API_KEY`, `OPENAI_API_KEY`), workload identity federation для CI; хранение — `auth.json` или OS keyring (`cli_auth_credentials_store`). Публичного REST/OpenAPI для локального агента нет; cloud backend (`chatgpt.com/backend-api`) — внутренний, не документирован как публичный контракт.

---

## 7. MCP

**Роль Codex:** только **клиент** MCP (после удаления MCP-сервера). Документация: [MCP](https://developers.openai.com/codex/mcp).

- Транспорты: **STDIO** (локальный процесс) и **Streamable HTTP** (`url`).
- Аутентификация HTTP: `bearer_token_env_var`, `http_headers`, `env_http_headers`; **OAuth** — `codex mcp login <server>`; настройки `mcp_oauth_callback_port`, `mcp_oauth_callback_url`, `mcp_oauth_credentials_store = auto|file|keyring`; если сервер рекламирует `scopes_supported`, Codex предпочитает их (иначе `mcp_servers.<s>.scopes`), `oauth_resource` (RFC 8707).
- `instructions` сервера учитываются как server-wide guidance.
- Политики: `startup_timeout_sec` (10), `tool_timeout_sec` (60), `enabled`, `required`, `enabled_tools`/`disabled_tools`, `default_tools_approval_mode = auto|prompt|approve`, `tools.<tool>.approval_mode`.
- `env_vars` с `source = local|remote`; экспериментальный `experimental_environment = remote` (stdio через remote executor).
- Конфиг общий для CLI и IDE: `~/.codex/config.toml`, проектный `.codex/config.toml` (доверенные проекты); управление `codex mcp add/...`, просмотр `/mcp`.
- Плагины могут бандлить MCP-серверы; политика задаётся под `plugins.<plugin>.mcp_servers.<server>`.

**Известные проблемы (issue trackers/сообщества):**
- после re-auth OAuth-сервера активная сессия может продолжать использовать stale refresh token — [issue #14144](https://github.com/openai/codex/issues/14144);
- ненадёжный resume сессии — [issue #37719](https://github.com/openai/codex/issues/37719);
- проблемы MCP в VS Code extension — [issue #6465](https://github.com/openai/codex/issues/6465);
- MCP tool calls отменяются под managed sandbox (read-only/workspace-write) — [community](https://community.openai.com/t/codex-cli-0-125-0-alpha-3-cancels-mcp-tool-calls-under-read-only-workspace-write-sandbox/1379772);
- падение инициализации stdio MCP (в т.ч. в desktop) — [issue #18769](https://github.com/openai/codex/issues/18769), [issues](https://github.com/openai/codex/issues) («codex_apps failed to start»).

Админ-контроль: `requirements.toml` может задавать allowlist MCP-серверов по identity (command/url) ([config-reference](https://developers.openai.com/codex/config-reference.md)).

---

## 8. Расширения / плагины

**Skills** — формат авторства переиспользуемых воркфлоу (см. §3).

**Plugins** ([plugins docs](https://developers.openai.com/codex/plugins), [skills](https://developers.openai.com/codex/skills)) — устанавливаемая единица дистрибуции: может включать одну/несколько skills, app-маппинги (connectors), конфиг MCP-серверов и презентационные ассеты. Реестры — marketplaces (local/git/remote); app-server: `marketplace/add`, `marketplace/upgrade`, `plugin/{list,read,install,uninstall}`; пользовательский конфиг может только включать/выключать состояние и задавать политику инструментов.

**Hooks** ([config-reference](https://developers.openai.com/codex/config-reference.md), `codex-rs/core/src/hook_runtime.rs`, `hook_mcp_executor.rs`):
- `hooks.json` или inline `[hooks]`; `features.codex_hooks`;
- события: `PreToolUse`, `PostToolUse`, `PermissionRequest`, `SessionStart`, `UserPromptSubmit`, `Stop`;
- поддерживаются **command hooks** (prompt/agent handlers парсятся, но пропускаются);
- PreToolUse может блокировать вызов или переписать `tool_input`; PostToolUse может блокировать результат или подменить его;
- managed hooks из `requirements.toml` (`hooks.managed_dir`, `hooks.windows_managed_dir`), `allow_managed_hooks_only` — только через requirements.toml.

**Прочие механизмы расширения:**
- `AGENTS.md` (инструкции проекта) / `model_instructions_file`;
- Rules — `.rules` (Starlark `prefix_rule`) для pre-approval/блокировки команд; `codex execpolicy check`;
- Apps/Connectors (ChatGPT apps) + `tool_suggest`;
- Memories (`features.memories`) — долгоживущая память, генерация/консолидация по rollout'ам;
- Code Mode, dynamic tools, image generation;
- slash commands, `notify` (внешний процесс по событиям), OTEL-экспорт.

**Ограничения:** hooks — экспериментальны; plugin/skill/connector-экосистема завязана на ChatGPT-авторизацию и marketplaces; MCP-сервер удалён (миграция на app-server); много feature-флагов в статусе experimental/dev (`tui2`, `experimental_windows_sandbox`, `apply_patch_freeform`, ...).

---

## 9. Сильные и слабые стороны (с точки зрения наших целей)

### Сильные
- **Зрелая изоляция**: per-OS sandbox (Seatbelt/bwrap/Windows), execpolicy-правила, approvals с granular-режимом и auto-review, managed network proxy, `requirements.toml` для админов. Это готовый reference для нашей модели «агент = роль + инструменты + разрешения».
- **Чистый local-first + отдельный app-server**: протокол JSON-RPC с генерацией TS/JSON-schema, богатый набор методов и нотификаций, `initialize/capabilities`, approvals как server→client запросы. Хорошо ложится на наш REST/сессионный дизайн (идеи, не буквальный протокол).
- **Богатая модель сессий**: threads, resume/read/fork, archive/unarchive/delete, `sqlite_home`, rollout/history-файлы, status-changed, компакция контекста, rollback.
- **Субагенты и skills**: контракты `spawn_agent/wait_agent/send_input/close_agent`, лимиты threads/depth, кастомные агенты как конфиг, batch `spawn_agents_on_csv`, progressive-disclosure skills.
- **Инструменты**: unified PTY exec + background terminals + write_stdin; plan; view_image; MCP resources; request_user_input/async; tool_search/code mode.
- **MCP-клиент**: stdio+HTTP, OAuth с callback-настройками и keyring, тонкие политики инструментов.
- **Мульти-поверхностность**: CLI, IDE, desktop app, mobile remote, cloud tasks, SDK, GitHub Action — единая модель тредов.

### Слабые / риски
- **Нет серверного состояния**: Postgres/мульти-пользовательской персистентности нет; всё локальные файлы/SQLite на одной машине. Наш вытесняющий планировщик на ShedLock и распределённый лок не имеют прямого аналога.
- **Облако не self-hostable**: cloud tasks идут на `chatgpt.com/backend-api`, авторизация привязана к ChatGPT/аккаунту; внутренний API не документирован.
- **Нет workflow-движка** (состояния+переходы, терминальные состояния, вебхук-состояния), нет понятия «назначенный агент у состояния»; оркестрация промптовая и хрупкая.
- **Custom protocol вместо contract-first REST/OpenAPI** для локальной поверхности (у нас клиент↔сервер должен быть OpenAPI/contract-first).
- **Удалённый доступ proprietary-ish**: secure relay, pairing через ChatGPT, remote-control daemon; WebSocket-транспорт явно experimental и по умолчанию может принимать неаутентифицированные подключения.
- **MCP-сервер удалён** — если нам нужно выставлять агента как MCP-сервер наружу, это не пример для подражания.
- **Надёжность сессий**: публичные issue про resume; stale OAuth-токены MCP; отмена MCP-вызовов под sandbox.
- **Vendor lock-in**: auth, модели, plugins/apps, cloud, memories, model catalog — вокруг OpenAI/ChatGPT.
- **Много экспериментальных флагов**, обещающих нестабильные API.

---

## 10. Выводы: что перенять / чего избегать

### Перенять (идеи)
1. **Session/thread-модель**: thread с turn'ами/items, `resume`/`read`/`fork`/`archive`/`rollback`, статусы, компакция контекста и «goal». У нас сессия в Postgres + attach-клиенты — стоит держать такой же набор операций в API.
2. **Явные item/notification-события** для стриминга (`item/started|completed|delta`, `turn/*`, `serverRequest/resolved`) и **одобрения как server→client запросы** — прямой аналог наших асинхронных инструментов и внешних триггеров.
3. **Schema-first для протокола**: генерация типов/JSON-schema из описания (`generate-ts`, `generate-json-schema`) — переносим на OpenAPI генерацию клиента.
4. **Многоуровневые разрешения**: `approval_policy` (в т.ч. granular) + `approvals_reviewer` (auto-review субагентом) + sandbox-режимы + правила префиксов команд. Полезно для инструментов роли и bash-состояний.
5. **Progressive-disclosure skills** и кастомные агенты как **конфиг-файлы** (name/description/instructions + переопределения модели/sandbox/mcp) — хорошая основа для нашей «Агент = роль + инструменты + разрешения + навыки».
6. **Контракты субагентов** (`spawn/wait/send/close`, лимиты `max_threads/max_depth`, batch по CSV) и разделение «blocking vs sidecar» — применимо к оркестратору-«CEO» и параллельным задачам.
7. **Unified exec + background terminals** (`exec_command`/`write_stdin`, таймауты, список/терминирование фоновых процессов) — паттерн для bash-состояний и долгих команд.
8. **Cloud-паттерн async задачи с diff/apply** (`codex cloud list/status/diff/apply`) — как UI/CLI для отложенных результатов.
9. **MCP-клиент**: stdio+HTTP, OAuth с настраиваемым callback/port/keyring, per-server/per-tool policy — напрямую релевантно 12+ корпоративным MCP за OAuth2-прокси.
10. **Managed requirements** (`requirements.toml`) — админские ограничения, allowlists (MCP по identity, sandbox/approval values, features).

### Избегать
1. **Хранить состояние только в файлах на одной машине** — нам нужен Postgres и серверный источник истины.
2. **Привязки auth к одному вендору** — у нас корпоративный OAuth2/SSO и LiteLLM-совместимый провайдер; модель провайдера должна быть подключаемой (хотя у Codex есть custom providers, `wire_api` фактически сведён к `responses`).
3. **Промптовой оркестрации вместо workflow-движка** — у нас состояния/переходы/терминальные статусы должны быть детерминированными и серверными.
4. **Собственного нестандартного протокола** там, где нужен contract-first OpenAPI/REST (даже если app-server JSON-RPC удобен, для нашего клиента фиксируем OpenAPI).
5. **Удаления MCP-сервера** — если платформа должна выставлять агентов/MCP наружу, оставляем серверную роль (или явно отдельно поддерживаем app-server-протокол и MCP).
6. **Экспериментальных транспортов без auth по умолчанию** (Codex прямо предупреждает о non-loopback WebSocket).
7. **Много «experimental/dev» флагов** в пользовательском конфиге как основного способа включать функциональность — лучше стабильные контракты и явные фиче-гейты на уровне ролей/тенантов.

### Прямое соответствие нашей архитектуре
- Наш «сервер сессий + ShedLock планировщик» — то, чего у Codex нет; берём их thread/item/resume-модель как UX-контракт, но реализуем на Postgres.
- Наш workflow (состояния/переходы/терминалы/вебхук-ожидание) — прямое расширение; у Codex только plan/goal/subagents.
- Наш клиент attach — аналог `codex --remote` + `remote-control` + handoff; стоит повторить UX (подключение к сессии, статус, продолжение с другого устройства), но на нашем REST/OpenAPI и без vendor-relay.
- Наши вебхук-триггеры и постоянные сессии — у Codex ближайшее: GitHub/Slack/Linear-интеграции и `@codex`, GitHub Action, cloud tasks; перенять паттерны «событие → задача → PR», но с нашей воркфлоу-маршрутизацией.

---

## Приложение: ключевые файлы репозитория (для дальнейшего чтения)

- `codex-rs/core/src/tools/spec_plan.rs` — сборка реестра инструментов и их экспозиции.
- `codex-rs/core/src/tools/handlers/` — реализации и спеки инструментов (включая `plan_spec.rs`, `multi_agents_spec.rs`, `unified_exec/`, `mcp_resource*`, `view_image_spec.rs`).
- `codex-rs/tools/src/` — общие модели tool-спеков, `tool_discovery.rs` (tool_search, plugin install), `code_mode.rs`.
- `codex-rs/app-server-protocol/src/rpc.rs` — JSON-RPC-типы (без поля `jsonrpc`).
- `codex-rs/app-server-protocol/src/protocol/v2/` — схемы v2 (thread, turn, item, mcp, plugin, config, fs, ...); `protocol/v1.rs`.
- `codex-rs/cloud-tasks/src/lib.rs` — `codex cloud`-команды и backend (`chatgpt.com/backend-api`).
- `codex-rs/core/src/tools/registry.rs` — реестр tool-runtime'ов, hooks Pre/PostToolUse.
- `codex-rs/core/src/{rollout.rs, session/, state_db_bridge.rs, windows_sandbox.rs}` — сессии/состояние/sandbox.

## Ограничения исследования

- HTML-страницы `developers.openai.com` недоступны через `webfetch` (403); использованы `z_ai_web_reader` и `.md`-варианты.
- MCP-инструменты (`z_ai_zread-*`) периодически отвечали таймаутом при параллельных вызовах — часть чтений репозитория выполнялась напрямую через raw.githubusercontent.com.
- Публичной документации по внутреннему HTTP API Codex Web нет — только CLI-поведение (`codex cloud`) и код `cloud-tasks-client`.
- Точные имена cloud-эндпоинтов OpenAI не подтверждены (не документированы).
