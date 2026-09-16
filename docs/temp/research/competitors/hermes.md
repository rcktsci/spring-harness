# Конкурентный анализ: Hermes Agent (Nous Research)

> Объект: **Hermes Agent** — открытый агентский рантайм от [Nous Research](https://nousresearch.com). Репозиторий: https://github.com/NousResearch/hermes-agent , сайт/доки: https://hermes-agent.nousresearch.com/ , https://hermes-agent.nousresearch.com/docs/ .
> Дата анализа: 2026-09-15. Источники: README.md и `docs/*` репозитория, официальная документация (Docusaurus), производные обзорные страницы Zread; структура пакетов.
> Нумерация вопросов — по единому списку из BRIEF.md. Принцип: каждый факт со ссылкой; где данных нет — «не найдено/не подтверждено».

---

## 0. Что это за продукт (важное уточнение)

- **Hermes — это прежде всего серия LLM-моделей Nous Research** (Hermes 3 и т.п.), но у того же вендора есть **agent-рантайм «Hermes Agent»** — именно он и является объектом анализа. README однозначно описывает его как «**The self-improving AI agent built by Nous Research**», а не как модель. https://github.com/NousResearch/hermes-agent/blob/master/README.md
- **Лицензия:** MIT. **Модель:** open-source, self-hosted, без обязательного облачного аккаунта (хотя есть опциональный Nous Portal). https://github.com/NousResearch/hermes-agent/blob/master/README.md
- **Формулировка продукта:** «It's the only agent with a built-in learning loop — it creates skills from experience, improves them during use, nudges itself to persist knowledge, searches its own past conversations, and builds a deepening model of who you are across sessions. Run it on a $5 VPS, a GPU cluster, or serverless infrastructure that costs nearly nothing when idle.» https://github.com/NousResearch/hermes-agent/blob/master/README.md
- **Позиционирование против OpenClaw/Claude Code:** «Hermes Agent is an open-source AI agent framework … it runs from your terminal, messaging apps, and automation workflows»; есть встроенный импорт из OpenClaw (`hermes claw migrate`) и Claude Code (`hermes import-agent claude-code`). https://github.com/NousResearch/hermes-agent/blob/master/README.md , https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp
- **Технологический стек:** Python 3.11 (пакетный менеджер `uv`), Node.js для некоторых мостов, ripgrep, ffmpeg. Установка — `curl … install.sh | bash`, PowerShell-инсталлятор для нативного Windows, Termux-путь для Android. https://github.com/NousResearch/hermes-agent/blob/master/README.md
- **Три способа «жить»:** локально на ноутбуке, на $5 VPS/GPU-кластере, либо serverless (Daytona/Modal — окружение «засыпает» между сессиями и просыпается по требованию). https://github.com/NousResearch/hermes-agent/blob/master/README.md
- Примечание: сторонний блог заявляет «175K stars in under 4 months» — **не подтверждено** первоисточником; не использую как факт. https://www.aibuilderclub.com/blog/hermes-nous-research-self-improving-agent

---

## 1. Архитектура

- **Точки входа** (https://hermes-agent.nousresearch.com/docs/developer-guide/architecture):
  - **CLI** (`cli.py`) — терминальный TUI;
  - **Gateway** (`gateway/run.py`) — долгоживущий процесс мессенджер-шлюза;
  - **ACP** (`acp_adapter/`) — интеграция с редакторами (VS Code, Zed, JetBrains) по stdio/JSON-RPC;
  - **Batch Runner** (`batch_runner.py`) — генерация траекторий;
  - **API Server** — OpenAI-совместимый HTTP;
  - **Python Library** — встраивание.
- **Ядро — один класс `AIAgent`** (`run_agent.py` как фасад, цикл в `agent/conversation_loop.py` и `agent/turn_*.py`). Один и тот же класс обслуживает CLI, gateway, ACP, batch и API server — «Platform differences live in the entry point, not the agent». https://hermes-agent.nousresearch.com/docs/developer-guide/architecture
- **Три режима API-провайдеров:** `chat_completions`, `codex_responses`, `anthropic_messages`; выбор через transport registry. https://hermes-agent.nousresearch.com/docs/developer-guide/architecture , https://zread.ai/NousResearch/hermes-agent/23-model-specific-transport-layer
- **Provider resolution:** общий резолвер `(provider, model) → (api_mode, api_key, base_url)`; 18+ провайдеров, OAuth-флоу, credential pools, alias-резолюция. Провайдеры — плагины (`plugins/model-providers/*`, 40+ записей: anthropic, openai-codex, gemini, bedrock, openrouter, deepseek, minimax, xai, vertex, ollama-cloud и др.). https://hermes-agent.nousresearch.com/docs/developer-guide/architecture , https://github.com/NousResearch/hermes-agent/tree/master/plugins/model-providers
- **Инструменты:** центральный реестр (`tools/registry.py`), **70+ инструментов в ~28 toolsets**; каждый файл `tools/*.py` саморегистрируется при импорте (`registry.register()`), поэтому ручной список импортов не нужен. https://hermes-agent.nousresearch.com/docs/developer-guide/architecture
- **Терминальные бэкенды (7):** local, Docker, SSH, Singularity, Modal, Daytona, Vercel Sandbox. **Браузерных (5)**, **web (4)**; MCP — динамически. https://hermes-agent.nousresearch.com/docs/developer-guide/architecture
- **Хранение состояния — SQLite + FTS5** (`hermes_state.py` — фасад, собранный из ~15 mixin-модулей: `hermes_state_schema.py`, `_messages.py`, `_sessions.py`, `_fts.py`, `_gateway.py`, `_portability.py`, `_repair.py` и т.д.). https://github.com/NousResearch/hermes-agent/blob/master/hermes_state.py , https://zread.ai/NousResearch/hermes-agent/7-architecture-overview
- **Слой сессий шлюза:** `gateway/session.py` (~1200 строк + `session_*.py`), `gateway/run.py` (фасад + `run_*.py`-фазы). https://github.com/NousResearch/hermes-agent/blob/master/docs/session-lifecycle.md
- **Промпт-система:** трёхуровневая сборка `stable → context → volatile` (`system_prompt.py` + `prompt_builder.py`), Anthropic prompt caching (`prompt_caching.py`), компрессия контекста (`context_compressor.py`, lossy summarization). https://hermes-agent.nousresearch.com/docs/developer-guide/architecture
- **Плагинная архитектура:** три источника обнаружения — `~/.hermes/plugins/` (user), `.hermes/plugins/` (project), pip entry points; один `PluginManager` управляет загрузкой и хуками. https://hermes-agent.nousresearch.com/docs/developer-guide/architecture
- **Cron:** «first-class agent tasks (not shell tasks)»; задания в JSON, несколько форматов расписания, вложения skills/scripts, доставка на любую платформу. https://hermes-agent.nousresearch.com/docs/developer-guide/architecture
- **Изоляция:** каждая **профиль** (`hermes -p <name>`) получает собственный `HERMES_HOME`, config, memory, sessions и gateway PID; профили работают параллельно. https://hermes-agent.nousresearch.com/docs/developer-guide/architecture
- **Multiplexing gateway (opt-in):** один процесс обслуживает все профили (`gateway.multiplex_profiles`, default false), разделяя один event loop, один HTTP-listener, один process lock и один status surface; изоляция профилей — через **contextvars**, без мутаций `os.environ`. https://github.com/NousResearch/hermes-agent/blob/master/docs/design/multiplexing-gateway.md
- **Design principles:** prompt stability (системный промпт не меняется мид-сессией), observable execution (каждый tool call виден), interruptible, platform-agnostic core, loose coupling (registry + `check_fn`-gating), profile isolation. https://hermes-agent.nousresearch.com/docs/developer-guide/architecture
- **Эксплуатация:** Docker/Docker Compose, systemd/launchd-сервисы (`hermes gateway install`), опциональный systemd-watchdog (Type=notify) для event-loop stall. https://hermes-agent.nousresearch.com/docs/user-guide/messaging
- **Траектории для обучения:** `trajectory_compressor.py`, `batch_runner.py`, ShareGPT-формат — «trajectory generation … for training the next generation of tool-calling models». https://hermes-agent.nousresearch.com/docs/developer-guide/architecture , https://github.com/NousResearch/hermes-agent/blob/master/README.md
- **Структура репозитория** (`hermes-agent/`, https://hermes-agent.nousresearch.com/docs/developer-guide/architecture):
  - корень: `run_agent.py`, `cli.py`, `model_tools.py`, `toolsets.py`, `hermes_state.py` (+ `hermes_state_*.py`), `hermes_constants.py`, `batch_runner.py`, `mcp_serve.py`, `mini_swe_runner.py`;
  - `agent/` — `prompt_builder.py`, `conversation_loop.py`, `context_engine.py`, `context_compressor.py`, `prompt_caching.py`, `memory_manager.py`, `provider_registry.py`, `transports/`;
  - `hermes_cli/` — `main.py`, `config.py`, `commands.py`, `auth.py`, `runtime_provider.py`, `models.py`, `setup.py`, `plugins.py`, `gateway.py`;
  - `tools/` — по файлу на инструмент + `registry.py`, `approval.py`, `process_registry.py`, `delegate_tool.py`, `mcp_tool.py`, `environments/`;
  - `gateway/` — `run.py` (+ `run_*.py`), `session*.py`, `delivery*.py`, `pairing.py`, `hooks.py`, `mirror.py`, `platforms/` (webhook, api_server, signal, whatsapp_cloud, …);
  - `plugins/` — `platforms/`, `model-providers/`, `memory/`, `context_engine/`, `web/`, `image_gen/`, `video_gen/`, `browser/`, `dashboard_auth/`, `cron_providers/`, `observability/`;
  - `acp_adapter/`, `cron/`, `skills/`, `optional-skills/`, `optional-mcps/`, `web/`, `website/`, `apps/`, `tests/` (~25 000 тестов в ~1250 файлах).
- **Данные-flow (по докам):** CLI `process_input()` → `AIAgent.run_conversation()` → `build_system_prompt()` → resolve provider → API call → tool_calls loop → final response → `SessionDB`; Gateway: `adapter.on_message()` → `MessageEvent` → `_handle_message()` → authorize → session key → `AIAgent` с историей → delivery; Cron: tick → due jobs → fresh `AIAgent` (без истории) → inject skills → run → deliver → update `next_run`. https://hermes-agent.nousresearch.com/docs/developer-guide/architecture

---

## 2. Модель сессий (ключевой раздел для нашего заказчика)

### 2.1 Базовая модель

- **Каждая беседа = сессия, сохраняется автоматически.** «Hermes Agent automatically saves every conversation as a session. Sessions enable conversation resume, cross-session search, and full conversation history management.» https://hermes-agent.nousresearch.com/docs/user-guide/sessions
- **Сессии persist across messages** — «The agent remembers your conversation context»; в messaging-канале сессия намеренно **одна непрерывная**, переживающая рестарты, крэши gateway и перезагрузку машины: «Shutting the machine down overnight does *not* end the session». https://hermes-agent.nousresearch.com/docs/user-guide/messaging , https://hermes-agent.nousresearch.com/docs/user-guide/features/memory
- **Двойное хранилище** (https://github.com/NousResearch/hermes-agent/blob/master/docs/session-lifecycle.md):
  - **SQLite `~/.hermes/state.db`** — канонический store метаданных сессий и транскриптов (`SessionDB` из `hermes_state`); таблицы `sessions`, `messages` + производные `messages_fts*` с sync-триггерами;
  - `sessions.json` в `{sessions_dir}` — in-memory-словарь `session_key → SessionEntry` (метаданные, флаги, timestamps, token-счётчики);
  - легаси-JSONL (`{session_id}.jsonl`) — «degradation path» при недоступности SQLite (в спецификации 002 помечен как удаляемый).
- **Поля сессии** (`SessionEntry`): `session_key`, `session_id` (формат `YYYYMMDD_HHMMSS_<8hex>`), `created_at`/`updated_at`, `origin` (`SessionSource`), `display_name`, `platform`, `chat_type`, token-счётчики (`input_tokens`, `output_tokens`, `cache_read/write_tokens`, `total_tokens`), `estimated_cost_usd`, `last_prompt_tokens`. https://github.com/NousResearch/hermes-agent/blob/master/docs/session-lifecycle.md
- **Поля в SQLite** (Sessions doc): ID, source-платформа, user ID, **уникальный человекочитаемый title**, model name/config, **snapshot системного промпта**, полная история сообщений (role, content, tool calls, tool results), input/output tokens, `started_at`/`ended_at`, **`parent session id`** (для lineage при компрессии). https://hermes-agent.nousresearch.com/docs/user-guide/sessions
- **Источники сессий (source):** `cli`, `telegram`, `discord`, `slack`, `whatsapp`, `signal`, `matrix`, `mattermost`, `email`, `sms`, `dingtalk`, `feishu`, `wecom`, `weixin`, `bluebubbles`, `qqbot`, `homeassistant`, **`webhook`**, **`api-server`**, `acp`, `cron`, `batch`. https://hermes-agent.nousresearch.com/docs/user-guide/sessions

### 2.2 SessionSource и ключи сессий

- **`SessionSource`** — замороженный дескриптор «откуда пришло сообщение»: `platform`, `chat_id`, `chat_name`, `chat_type` (`dm|group|channel|thread`), `user_id`, `user_name`, `thread_id`, `chat_topic`, `user_id_alt`, `chat_id_alt`, `is_bot`, `guild_id`, `parent_chat_id`, `message_id`, `role_authorized`. https://github.com/NousResearch/hermes-agent/blob/master/docs/session-lifecycle.md
- **Ключ сессии:** `agent:main:{platform}:{chat_type}[:{chat_id}][:{thread_id}][:{participant_id}]`; детерминированный, строится `build_session_key(...)`. https://github.com/NousResearch/hermes-agent/blob/master/docs/session-lifecycle.md
- **Мульти-пользовательская изоляция:** DM всегда приватны; группы/каналы — **per-user по умолчанию** (`group_sessions_per_user: true`); треды — **общие по умолчанию** (`thread_sessions_per_user: false`). WhatsApp-идентификаторы канонизируются (JID/LID alias flips). https://github.com/NousResearch/hermes-agent/blob/master/docs/session-lifecycle.md
- **Multiplexing:** ключи namespaced по профилю (`agent:main` для default, `agent:<name>` для именованных); DB-handles резолвятся лениво через активный `HERMES_HOME` override — «one cached handle per resolved `profiles/<name>/state.db`». https://github.com/NousResearch/hermes-agent/blob/master/docs/design/multiplexing-gateway.md

### 2.3 Жизненный цикл, сброс и state machine

- **Флаги-состояния `SessionEntry`:** `was_auto_reset`, `auto_reset_reason` (`idle`/`daily`), `reset_had_activity`, `is_fresh_reset` (явный `/new`/`/reset`), `expiry_finalized`, `suspended` (жёсткий force-wipe по `/stop` или после 3+ подряд рестартов), `resume_pending` (мягкое восстановление), `resume_reason` (`restart_timeout`, `shutdown_timeout`, `restart_interrupted`), `last_resume_marked_at`. https://github.com/NousResearch/hermes-agent/blob/master/docs/session-lifecycle.md
- **Приоритет `get_or_create_session()`:** (1) `suspended` → всегда force-reset; (2) `resume_pending` → сохранить `session_id`; (3) policy expiry (`idle`/`daily`) → auto-reset; (4) иначе — вернуть существующую запись. https://github.com/NousResearch/hermes-agent/blob/master/docs/session-lifecycle.md
- **Reset policy** (`session_reset`): `none` | `idle` | `daily` | `both`; в внутреннем дизайн-доке default — `both` (`idle_minutes: 1440`, `at_hour: 4`), настраивается per-platform/per-type. https://github.com/NousResearch/hermes-agent/blob/master/docs/session-lifecycle.md
- **Расхождение в актуальной пользовательской доке:** в messaging-канале «Gateway conversations do **not** reset after inactivity or at a daily boundary… Legacy `session_reset` settings, reset-policy overrides and reset-timer environment variables are **ignored**». Это указывает на то, что продуктовый default сместился к непрерывным сессиям, а legacy-reset оставлен для совместимости. https://hermes-agent.nousresearch.com/docs/user-guide/messaging
- **Операции `SessionStore`:** `get_or_create_session`, `update_session`, `reset_session`, `switch_session` (из `/resume`), `suspend_session`, `mark_resume_pending`, `clear_resume_pending`, `suspend_recently_active(max_age_seconds=120)`, `prune_old_entries`, `list_sessions`, `lookup_by_session_id`, `append_to_transcript`, `rewrite_transcript` (`/retry`, `/undo`, `/compress`), `load_transcript`, `rewind_session` (soft-delete с аудит-трейлом). https://github.com/NousResearch/hermes-agent/blob/master/docs/session-lifecycle.md

### 2.4 Восстановление после сбоев (restart recovery)

- **Startup-последовательность** (https://github.com/NousResearch/hermes-agent/blob/master/docs/session-lifecycle.md):
  1. проверка маркера `.clean_shutdown` — если есть, `suspend_recently_active()` пропускается;
  2. иначе `suspend_recently_active(120s)` — сессии, обновлённые за последние 120 с, помечаются `resume_pending` c `resume_reason="restart_interrupted"`;
  3. `_suspend_stuck_loop_sessions()` — сессии, активные через 3+ подряд рестарта, авто-суспендятся (счётчик в `{HERMES_HOME}/restart_counts.json`);
  4. входящие сообщения очередируются, пока идёт startup restore;
  5. по каждому адаптеру `resume_pending`-сессии синтезируются в `MessageEvent` и обрабатываются для авто-продолжения.
- **Мягкий vs жёсткий resume:** `resume_pending` сохраняет `session_id` и транскрипт (сбрасывается только после успешного хода); `suspended` форсирует новую сессию. При повторном прерывании флаг остаётся, следующий рестарт повторит; счётчик залипаний даёт терминальную эскалацию. https://github.com/NousResearch/hermes-agent/blob/master/docs/session-lifecycle.md
- **Drain timeout:** `mark_resume_pending()` с причинами `restart_timeout`/`shutdown_timeout` (все в `_AUTO_RESUME_REASONS`). https://github.com/NousResearch/hermes-agent/blob/master/docs/session-lifecycle.md
- **Delivery ledger (at-least-once):** финальные ответы записываются в durable ledger в `state.db`; при крэше между генерацией и подтверждением платформы следующий boot **пере-доставляет** ответ, а не теряет его и не пере-выполняет ход. Ответ, чей send не начинался, — пере-доставляется как есть; ответ, бывший в середине send, — пере-доставляется с видимым префиксом «♻️ Recovered reply — … may be a duplicate». Ретраи ограничены: 3 попытки, 24 ч freshness, затем строка abandoned; доставленные строки чистятся через 7 дней. Отключается `gateway.delivery_ledger: false`. https://hermes-agent.nousresearch.com/docs/user-guide/messaging

### 2.5 Очереди, конкурентность, кеш агентов

- **Очередь сообщений:** single-slot `_pending_messages` (burst collapse — повторные send перезаписывают слот) + overflow FIFO `_queued_events` (для `/queue`, промотируется по одному после drain). Инвариант: каждый `/queue` даёт ровно один полный ход, в FIFO, без merge. Очищается на `/new`/`/reset`. https://github.com/NousResearch/hermes-agent/blob/master/docs/session-lifecycle.md
- **busy_input_mode:** `interrupt` (default), `queue`, `steer` (инъекция в текущий run после следующего tool call); `busy_ack_enabled`. https://hermes-agent.nousresearch.com/docs/user-guide/messaging
- **Agent cache:** LRU по `session_key`, max 128, idle TTL 1 ч, memory-pressure eviction (`agent.agent_cache.memory_high_mb`); никогда не вытесняются: агенты mid-turn, `protect_recent` (8) последних и сессии с не записанным до конца транскриптом. https://github.com/NousResearch/hermes-agent/blob/master/docs/session-lifecycle.md
- **Background expiry watcher:** каждые 300 с финализирует истёкшие сессии (hooks `on_session_finalize`, очистка tool-ресурсов, eviction кеша, `promote_to_session_reset()`), чистит idle-кеш, сбрасывает LRU под memory pressure, `prune_old_entries()` раз в час. https://github.com/NousResearch/hermes-agent/blob/master/docs/session-lifecycle.md
- **Форки/ветвления:** `POST /api/sessions/{id}/fork` — «Branch the session via `SessionDB` lineage (matches CLI `/branch` semantics)»; lineage хранится через `parent session id` (в т.ч. при compression-splitting). https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server , https://hermes-agent.nousresearch.com/docs/user-guide/sessions

### 2.6 Память и поиск по сессиям

- **Built-in memory:** `MEMORY.md` (2200 chars ≈ 800 токенов) и `USER.md` (1375 chars ≈ 500 токенов) в `~/.hermes/memories/`, инжектятся в системный промпт как **frozen snapshot** на старте сессии; управление через инструмент `memory` (`add`/`replace`/`remove`); защита от prompt-injection и exfiltration; лимиты не auto-compact — при переполнении инструмент возвращает ошибку и просит консолидацию. https://hermes-agent.nousresearch.com/docs/user-guide/features/memory
- **Внешние memory-провайдеры (8):** Honcho, OpenViking, Mem0, Hindsight, Holographic, RetainDB, ByteRover, Supermemory — работают **рядом** со встроенной памятью (не заменяют её). https://hermes-agent.nousresearch.com/docs/user-guide/features/memory
- **Session search (FTS5):** все CLI- и messaging-сессии лежат в `~/.hermes/state.db` с full-text поиском; `session_search` возвращает реальные сообщения из БД «no LLM summarization, no truncation»; поддерживает discovery/scroll/browse. https://hermes-agent.nousresearch.com/docs/user-guide/features/memory
- **Session DB recovery** — отдельный документ: при corruption FTS5 снимаются sync-триггеры, ставится durable `fts_stale`, canonical writes продолжают работать, поиск падает в `LIKE`-fallback; при повреждении самого файла `SessionDB` «карантинит» handle (`StateDbCorruptError`), pending-транскрипты уходят в `sessions/<id>.jsonl`/spool, и есть явный ремонт `hermes sessions repair` + снапшоты `state-snapshots/`. https://github.com/NousResearch/hermes-agent/blob/master/docs/state-db-recovery.md

### 2.7 Resume (CLI)

- `hermes --continue`/`-c`, `hermes --resume <id|title|latest>`, `hermes chat --continue`; **per-terminal breadcrumbs** в `~/.hermes/terminal-sessions/` (tty/tmux/kitty/wezterm/Zellij/Windows Terminal) — «two panes side by side each continue their own conversation»; `--in <dir>` выбирает сессию workspace-а; resume **восстанавливает рабочий каталог** (`--no-restore-cwd` для отключения). https://hermes-agent.nousresearch.com/docs/user-guide/sessions

---

## 2bis. Входящие события: webhooks и API Server (референс «постоянных сессий с вебхуками»)

Это ровно тот сценарий, который упоминал заказчик, и он реализован в Hermes двумя независимыми каналами.

### Webhook-адаптер

- **HTTP-сервер** на `WEBHOOK_PORT` (default **8644**), принимает `POST /webhooks/<route-name>`, валидирует HMAC, трансформирует payload в промпт агента, маршрутизирует ответ обратно к источнику или на другую платформу. `GET /health` → `{"status":"ok","platform":"webhook"}`. https://hermes-agent.nousresearch.com/docs/user-guide/messaging/webhooks
- **Способы настройки:** `hermes gateway setup` или env (`WEBHOOK_ENABLED`, `WEBHOOK_PORT`, `WEBHOOK_SECRET`); маршруты — статически в `config.yaml` (`platforms.webhook.extra.routes`) **или динамически** через `hermes webhook subscribe`. https://hermes-agent.nousresearch.com/docs/user-guide/messaging/webhooks
- **Свойства маршрута:** `events` (список типов; читается из `X-GitHub-Event`/`X-GitLab-Event`/`event_type`), `secret` (обязателен, fallback на глобальный), `profile` (для multiplexing; биндит секрет к `/p/<profile>/webhooks/<route>`), `prompt` (dot-notation-шаблон `{pull_request.title}`, спец-токен `{__raw__}`), `filters` (декларативный DSL: `exists`, `missing`, `equals`, `not_equals`, `contains`, `in`, `in_file`, `regex`, `all`/`any`/`not`), `script` (фильтр/трансформ на stdin JSON из `~/.hermes/scripts/`), `skills`, `toolsets`, `deliver`, `deliver_extra`, `deliver_only`. https://hermes-agent.nousresearch.com/docs/user-guide/messaging/webhooks
- **Direct Delivery Mode (`deliver_only: true`)** — payload-шаблон доставляется буквально, **без запуска агента**: «Zero LLM tokens», «Sub-second delivery»; при этом HMAC, rate limit, idempotency и body-size limit всё равно применяются; POST возвращает `200` при успехе доставки или `502` при отказе target-а (чтобы upstream мог умно ретраить). https://hermes-agent.nousresearch.com/docs/user-guide/messaging/webhooks
- **Цели доставки:** `log` (default), `github_comment`, `telegram`, `discord`, `slack`, `signal`, `sms`, `whatsapp`, `matrix`, `mattermost`, `homeassistant`, `email`, `dingtalk`, `feishu`, `wecom`, `weixin`, `bluebubbles`. https://hermes-agent.nousresearch.com/docs/user-guide/messaging/webhooks
- **Ограничение инструментов webhook-агента:** по умолчанию набор сужен до `web_search`, `web_extract`, `vision_analyze`, `clarify`, т.к. payload — недоверенный контент третьих лиц. Расширение (`toolsets`) — только ручной правкой `config.yaml`; `hermes webhook subscribe` **намеренно не принимает** флаг `toolsets`, чтобы агент не мог сам выдать себе `terminal`. https://hermes-agent.nousresearch.com/docs/user-guide/messaging/webhooks , https://zread.ai/NousResearch/hermes-agent/14-toolset-system-and-core-tools
- **Безопасность:** HMAC (GitHub `X-Hub-Signature-256`, GitLab plain `X-Gitlab-Token`, Standard Webhooks, Generic V2 с timestamp ±300 c против replay, Generic V1 legacy без replay-защиты); secret обязателен, иначе адаптер не стартует; `INSECURE_NO_AUTH` разрешён только на loopback; rate limit 30 req/min (настраивается); idempotency по delivery-ID (`X-GitHub-Delivery`, `svix-id`, `webhook-id`, `X-Request-ID`) — кеш 1 ч; body ≤ 1 МБ (настраивается). Ключевой тезис: «**Authenticated does not mean trusted**» — подпись подтверждает отправителя, но не контент бизнес-полей. https://hermes-agent.nousresearch.com/docs/user-guide/messaging/webhooks
- **Динамические подписки:** `hermes webhook subscribe/list/remove/test`; хранятся в `~/.hermes/webhook_subscriptions.json`; адаптер hot-reload-ит файл на каждом запросе (mtime-gated); статические маршруты из `config.yaml` имеют приоритет. Есть skill `webhook-subscriptions` для agent-driven подписок. https://hermes-agent.nousresearch.com/docs/user-guide/messaging/webhooks
- **Response codes:** `200 delivered`, `200 status=duplicate`, `401` (bad HMAC), `400` (malformed JSON), `404` (unknown route), `413` (payload too large), `429` (rate limit), `502` (target rejected). https://hermes-agent.nousresearch.com/docs/user-guide/messaging/webhooks

### API Server (OpenAI-совместимый + native Runs/Sessions/Jobs)

- **OpenAI-совместимый HTTP** (aiohttp), default `127.0.0.1:8642`, bearer `API_SERVER_KEY` (обязателен), CORS выключен по умолчанию. https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server
- **Эндпоинты:** `POST /v1/chat/completions` (stateless, полная история в `messages`), `POST /v1/responses` (+ `previous_response_id` — серверное состояние), `GET/DELETE /v1/responses/{id}`, `GET /v1/models`, `GET /api/model/options`, `GET /v1/capabilities`, `GET /health`, `GET /health/detailed`. https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server
- **Runs API (для long-form):** `POST /v1/runs` (с `session_id`, `instructions`, `conversation_history`, `previous_response_id`; поддержка **`Idempotency-Key`** с durable reservation, HTTP 202 + `Idempotency-Replayed: true`), `GET /v1/runs/{id}`, `GET /v1/runs/{id}/events` (**SSE**: tool-call progress, token deltas, lifecycle; плюс события `subagent.start`/`subagent.complete`), `POST /v1/runs/{id}/stop`, `POST /v1/runs/{id}/approval`. https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server
- **Sessions API:** `GET/POST /api/sessions`, `GET/PATCH/DELETE /api/sessions/{id}`, `GET /api/sessions/{id}/messages`, `POST /api/sessions/{id}/fork`, `POST /api/sessions/{id}/chat`, `POST /api/sessions/{id}/chat/stream` (SSE: `assistant.delta`, `tool.started`, `tool.completed`, `run.completed`). https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server
- **Jobs API:** `/api/jobs` CRUD + `/pause`, `/resume`, `/run` — удалённое управление cron-заданиями. https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server
- **Сессионные заголовки:** `X-Hermes-Session-Id` (транскрипт-скоуп, ротируется на `/new`) и `X-Hermes-Session-Key` (stable per-channel id для long-term memory, ≤256 chars). https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server
- **Стриминг:** SSE; кастомный `hermes.tool.progress` для Chat Completions; в Responses — spec-native `function_call`/`function_call_output`. https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server
- **Конкурентность:** `max_concurrent_runs` (default 10; 0 = без лимита) → `429 Too many concurrent runs`. Security-заголовки `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`. https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server
- **Multi-profile routing:** `/p/<profile>/v1/...` — auth привязан к профилю (его собственный `API_SERVER_KEY`); run-id другого профиля → `404`, не `403`. https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server
- **Proxy mode:** gateway с `GATEWAY_PROXY_URL` форвардит сообщения на другой API server (split deployment, напр. Matrix E2EE в контейнере → агент на хосте). https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server
- **Runs API известные дефекты** (issue #104341): нет `POST /v1/runs/{id}/queue` (нельзя отложить follow-up без прерывания busy-run), молчаливый маппинг неизвестной платформы в `LOCAL` (`delivery.py`), CORS не разрешает `X-Hermes-Session-Id`. https://zread.ai/NousResearch/hermes-agent/5-issues-and-feedbacks

---

## 3. Модель агента

- **Один `AIAgent` на все точки входа** — платформо-агностичное ядро. https://hermes-agent.nousresearch.com/docs/developer-guide/architecture
- **Инструменты/навыки/разрешения:**
  - **Toolsets** — группировки инструментов (~28), платформенные пресеты: `hermes-cli` (полный доступ), `hermes-telegram`, `hermes-discord`, …, `hermes-api-server` (минус `clarify`, `tts`), `hermes-webhook` (только безопасное подмножество), `hermes-acp`, `hermes-raft`. https://hermes-agent.nousresearch.com/docs/user-guide/messaging , https://zread.ai/NousResearch/hermes-agent/14-toolset-system-and-core-tools
  - **Skills** — прогрессивное раскрытие знаний; каждый skill — директория с `SKILL.md`; bundled (`skills/`) + optional (`optional-skills/`); совместимы с открытым стандартом **agentskills.io**; `/learn` создаёт новый skill из контекста; есть Skills Hub. https://zread.ai/NousResearch/hermes-agent/17-skills-system , https://github.com/NousResearch/hermes-agent/blob/master/README.md
  - **Разрешения:** allowlist-пользователи per-platform, DM pairing (код, TTL 1 ч), admin/user tiers (admin — все slash-команды, `user` — только явно разрешённые; floor `/help`, `/whoami`). https://hermes-agent.nousresearch.com/docs/user-guide/messaging
  - **Dangerous command approval** (`/approve`, `/deny`); блокировка записи в `config.yaml`, в защищённые instruction-файлы (`AGENTS.md`, `CLAUDE.md`, `SOUL.md`, `.cursorrules`, `.hermes/`) и разрушительных git-операций над собственным репо. https://zread.ai/NousResearch/hermes-agent/15-tool-execution-and-guardrails
- **`check_fn`-gating:** инструменты с недоступными зависимостями (Docker, `HASS_TOKEN`, OAuth…) тихо исключаются из payload; результат кешируется на 30 с. https://zread.ai/NousResearch/hermes-agent/14-toolset-system-and-core-tools
- **Субагенты / делегирование:** первоклассная архитектура — родитель порождает изолированные дочерние `AIAgent` (своя сессия, terminal-сессия, toolset, системный промпт), возвращается **только финальная сводка**; worktrees в `<repo>/.worktrees/subagent-<id>` на ветке `hermes-subagent/<id>`; `DaemonThreadPoolExecutor`; в SSE родителя — `subagent.start`/`subagent.complete` (status, summary, duration, tokens/cost, `child_session_id`, `delegation_id`; per-tool детские события намеренно не форвардятся). https://zread.ai/NousResearch/hermes-agent/25-subagent-delegation , https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server
- **Профили = отдельные агенты:** `hermes -p <name>`, каждый со своим `HERMES_HOME`, config, memory, sessions, gateway PID. https://hermes-agent.nousresearch.com/docs/developer-guide/architecture
- **Персоналии/`SOUL.md`, контекстные файлы (`AGENTS.md`, project `.hermes/`)**, `/personality`, `/model`. https://github.com/NousResearch/hermes-agent/blob/master/README.md
- **Фоновые/параллельные режимы:** `/bg <prompt>` — отдельный агент с изолированной сессией и собственной историей, неблокирующий, результат возвращается в тот же чат; `/btw` — боковой вопрос без прерывания. https://hermes-agent.nousresearch.com/docs/user-guide/messaging
- **Импорт из других агентов:** `hermes claw migrate` (OpenClaw: SOUL.md, memories, skills, allowlist, API keys, workspace), `hermes import-agent claude-code` (в т.ч. `mcpServers` из `~/.claude.json`). https://github.com/NousResearch/hermes-agent/blob/master/README.md , https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp

---

## 4. Инструменты

- **~70 инструментов, ~28 toolsets**, само-регистрация при импорте (`tools/*.py` → `registry.register()`). Категории: terminal (7 бэкендов), file (`read_file`, `write_file`, `patch`, `search_files`), web (`web_search`, `web_extract`; 4 бэкенда), browser (5 бэкендов), code execution (`execute_code`), delegation (`delegate_tool`), MCP (`mcp_tool`), vision, TTS, clarify и др. https://hermes-agent.nousresearch.com/docs/developer-guide/architecture
- **Синхронные/асинхронные:** tool-executor обрабатывает и **последовательные**, и **конкурентные батчи** (когда модель выдаёт несколько независимых вызовов в одном ходе); единый pipeline «observe → commit → project». https://zread.ai/NousResearch/hermes-agent/15-tool-execution-and-guardrails
- **Фоновые задачи с отложенным результатом:** `/bg` (отдельный агент, fire-and-forget, доставка в тот же чат), `terminal(background=true)` с настраиваемыми уведомлениями (`display.background_process_notifications`: concise/all/result/error/off), cron-задания, webhook `deliver_only`. https://hermes-agent.nousresearch.com/docs/user-guide/messaging
- **MCP-инструменты** регистрируются с префиксом `mcp_<server>_<tool>`; per-server include/exclude (+glob); динамический discovery по `notifications/tools/list_changed`. https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp
- **Изоляция webhook-tools по умолчанию** (`web_search`, `web_extract`, `vision_analyze`, `clarify`) — см. §2bis. https://hermes-agent.nousresearch.com/docs/user-guide/messaging/webhooks

---

## 5. Оркестрация / воркфлоу

- **Модели «компании», org-chart, целей, бюджетов, найма и governance-гейтов у Hermes НЕТ** — это принципиально иной продукт, чем Paperclip. Оркестрация строится на: conversation loop + subagents + cron + webhook/API-триггеры + опциональный Kanban. (Не найдено признаков org-chart/goals/budgets/approvals в смысле Paperclip.)
- **Conversation loop** — синхронный движок (`agent/conversation_loop.py`, `agent/turn_*.py`): выбор провайдера, сборка промпта, исполнение инструментов, retry, fallback, callbacks, компрессия, persistence. https://hermes-agent.nousresearch.com/docs/developer-guide/architecture
- **Cron (scheduled automations):** «first-class agent tasks (not shell tasks)»; natural-language задания, доставка на любую платформу; gateway тикает каждые 60 секунд; CRUD через `hermes cron` и `/api/jobs`. https://hermes-agent.nousresearch.com/docs/developer-guide/architecture , https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server
- **Subagent delegation** — многоагентный сценарий: параллельные workstreams в изолированных worktrees, возврат только сводки. https://zread.ai/NousResearch/hermes-agent/25-subagent-delegation
- **Kanban (экспериментальный):** task-board с dispatcher, watchers, subscriptions (`notify`/`notify+wake`/`wake`), multi-gateway режим (single-dispatcher posture: только один gateway владеет dispatcher-ом; остальные ставят `kanban.dispatch_in_gateway: false`). https://github.com/NousResearch/hermes-agent/blob/master/docs/kanban/multi-gateway.md
- **Goals:** `run_goals.py` в gateway (цели на уровне сессии/чата). https://github.com/NousResearch/hermes-agent/tree/master/gateway
- **A2A (Agent-to-Agent):** отдельный platform-адаптер (`plugins/platforms/a2a/`) — интеграция «агент↔агент». https://github.com/NousResearch/hermes-agent/tree/master/plugins/platforms
- **Hosted rooms:** `gateway/hosted_room_*.py` — совместные комнаты/политики/реплики (экспериментально). https://github.com/NousResearch/hermes-agent/tree/master/gateway
- **Планирование:** явного plan-mode/decomposition как у Paperclip не найдено; есть «goals», «checkpoints & rollback» (`/rollback`), `run_goals` и «trajectories» для обучения.

---

## 6. API-поверхность

- **CLI (`hermes <cmd>`):** `hermes` (chat), `model`, `tools`, `config set/get`, `gateway` (setup/install/start/stop/status), `setup` (+ `--portal`), `claw migrate`, `import-agent`, `update`, `doctor`, `mcp` (add/install/login/serve/configure/catalog), `sessions` (list/recover/repair/optimize/prune), `skills`, `plugins`, `profile`, `webhook` (subscribe/list/remove/test), `cron`, `pairing`, `memory` (setup/status), `journey`/`learning`/`memory-graph`, `send`, `batch`. https://github.com/NousResearch/hermes-agent/blob/master/README.md , https://hermes-agent.nousresearch.com/docs/user-guide/messaging
- **Slash-команды** (общие CLI + мессенджеры): `/new`, `/reset`, `/model`, `/personality`, `/retry`, `/undo`, `/status`, `/stop`, `/approve`, `/deny`, `/sethome`, `/compress`, `/title`, `/resume`, `/sessions`, `/usage`, `/insights`, `/reasoning`, `/voice`, `/rollback`, `/bg`, `/btw`, `/reload-mcp`, `/update`, `/help`, `/<skill-name>`; `/platform` (list/pause/resume). https://hermes-agent.nousresearch.com/docs/user-guide/messaging
- **HTTP (API Server):** см. §2bis — OpenAI-совместимый (`/v1/chat/completions`, `/v1/responses`, `/v1/models`), native Runs API (`/v1/runs`, SSE events, stop, approval), Sessions API (`/api/sessions/*`), Jobs API (`/api/jobs/*`), `/v1/capabilities`, health. https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server
- **Webhooks:** отдельный HTTP-сервер на 8644 (см. §2bis). https://hermes-agent.nousresearch.com/docs/user-guide/messaging/webhooks
- **MCP:** клиент (stdio + HTTP/SSE + OAuth) и сервер (`hermes mcp serve`, stdio, 10 tools). https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp
- **ACP:** stdio/JSON-RPC для VS Code/Zed/JetBrains. https://hermes-agent.nousresearch.com/docs/developer-guide/architecture
- **TUI gateway:** ws JSON-RPC для Desktop (`profiles.list/create/describe/configure/...`, `model.options`). https://github.com/NousResearch/hermes-agent/blob/master/docs/design/multiplexing-gateway.md
- **Web dashboard:** браузерный UI для управления установкой, профилями, MCP, моделями. https://hermes-agent.nousresearch.com/docs/user-guide/features/web-dashboard
- **Авторизация:** API server — bearer `API_SERVER_KEY`; мессенджеры — allowlists + DM pairing + admin/user tiers; multi-profile — per-profile key. Нет OAuth-сервера для внешних клиентов, нет RBAC в смысле Paperclip. https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server , https://hermes-agent.nousresearch.com/docs/user-guide/messaging
- **Стриминг:** SSE (Runs events, chat/stream), live tool progress; WebSocket — для Desktop TUI gateway и browser-control. https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server

### 6.1 Безопасность, развёртывание, наблюдаемость

- **Модель безопасности мессенджеров:** по умолчанию gateway **отклоняет всех**, кого нет в allowlist или кто не спарен; allowlist per-platform (`TELEGRAM_ALLOWED_USERS`, `GATEWAY_ALLOWED_USERS`, …) или `GATEWAY_ALLOW_ALL_USERS=true` (не рекомендовано); DM pairing с одноразовым кодом (TTL 1 ч, rate limit, криптослучайность), `hermes pairing approve/list/revoke`; admin/user tiers per scope. https://hermes-agent.nousresearch.com/docs/user-guide/messaging
- **Guardrails инструментов:** подтверждение опасных команд (`/approve`, `/deny`); блокировка записи в `~/.hermes/config.yaml`; защита instruction-файлов (`AGENTS.md`, `CLAUDE.md`, `SOUL.md`, `.cursorrules`, `.hermes/`) от prompt-injection-персистенции; блокировка `git checkout/rebase/reset --hard/stash/bisect/worktree` над собственным исходником агента. https://zread.ai/NousResearch/hermes-agent/15-tool-execution-and-guardrails
- **Секреты:** `.env` per profile, `hermes auth`, фильтрация env для stdio-MCP; есть документ об egress-proxy и network egress isolation. https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp , https://github.com/NousResearch/hermes-agent/blob/master/docs/security/network-egress-isolation.md
- **Развёртывание:** Docker/Docker Compose (`docker-compose.yml`, `docker-compose.windows.yml`), systemd user/system сервисы и launchd (macOS), опциональный `systemd_watchdog_seconds` (Type=notify); managed venv под `$HERMES_HOME/hermes-agent`, `hermes update` с авто-рестартом сервиса. «Multiple installations» — каждый `HERMES_HOME` получает свой сервис (`hermes-gateway-<hash>`). https://hermes-agent.nousresearch.com/docs/user-guide/messaging , https://github.com/NousResearch/hermes-agent/blob/master/README.md
- **Наблюдаемость:** логи gateway (`~/.hermes/logs/gateway.log`) с secret-redacting formatter; `display.tool_progress: log` пишет tool-calls в ротируемый `~/.hermes/logs/tool_calls.log`; плагин `observability/langfuse`; `/usage`, `/insights`; `session_model_usage` (в т.ч. `task='background_review'`). https://hermes-agent.nousresearch.com/docs/user-guide/messaging , https://github.com/NousResearch/hermes-agent/tree/master/plugins/observability
- **Egress/сеть:** `gateway.trust_env` (игнорировать унаследованный HTTP(S)_PROXY), per-platform proxy (`DISCORD_PROXY` и др.), автоматический **circuit breaker** на адаптер (не auto-resume — ручной `/platform resume`). https://hermes-agent.nousresearch.com/docs/user-guide/messaging
- **Desktop и dashboard:** Hermes Desktop (нативное приложение) + web dashboard (браузерный UI для управления установкой/профилями/MCP/моделями); Desktop хостит OAuth-callback на машине пользователя и релеит его в gateway для remote-backend. https://hermes-agent.nousresearch.com/docs/user-guide/features/web-dashboard , https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp

---

## 7. MCP

- **Клиент:** stdio (subprocess) и HTTP/SSE (remote), автоматический discovery и регистрация при старте; префикс имён `mcp_<server>_<tool>`. https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp
- **OAuth 2.1:** `auth: oauth` — discovery, DCR (fallback), Client ID Metadata Document, PKCE, token exchange/refresh, step-up auth; токены кешируются в `~/.hermes/mcp-tokens/<server>.json` (0o600); refresh-токен привязан к issuer (при смене authorization server дропается). Поддержка device-code, paste-back, SSH-туннеля, proxied callback. https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp
- **mTLS:** `client_cert`/`client_key` (combined PEM, `[cert,key]`, `[cert,key,password]`); `identity_header` (static/profile) для per-user маршрутизации. https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp
- **Каталог MCP (`optional-mcps/`):** «presence in that directory means Nous approval»; `hermes mcp catalog/install`; при установке — checklist выбора инструментов (pre-checked из прошлого выбора/manifest default); поддержка больших поверхностей через `tools.default_excluded` + globs (напр. Cloudflare ~3300 tools). GitHub намеренно не в каталоге. https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp
- **Sampling** (`sampling/createMessage`) включён по умолчанию: rate limiter, per-request timeout, max_tool_rounds, `allowed_models`; **Elicitation** (`elicitation/create`, form-mode) роутится через approval-поверхность. https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp
- **Безопасность результатов:** стриппинг invisible Unicode TAG-символов (U+E0000–U+E007F — канал prompt-injection); пропуск protocol-reserved `_meta`-ключей; env-фильтрация для stdio-серверов (только явный `env` + safe baseline). https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp
- **Hermes как MCP-сервер:** `hermes mcp serve` (stdio) выставляет 10 инструментов для моста к мессенджерам: `conversations_list`, `conversation_get`, `messages_read`, `attachments_fetch`, `events_poll`, `events_wait` (long-poll до 5 мин), `messages_send`, `channels_list`, `permissions_list_open`, `permissions_respond`; читает `~/.hermes/state.db`; send требует запущенного gateway. https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp
- **Известные ограничения:** серверный режим — только stdio (HTTP-сервер надо запускать отдельно); text-only send; event-polling ~200 мс; нет push-протокола; parallel tool calls — opt-in (`supports_parallel_tool_calls`). https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp
- **Multi-profile limitation:** MCP discovery и tool registration **process-global** — «the first profile to build an agent wins the discovery slot» (tracking issue `#67605`). https://github.com/NousResearch/hermes-agent/blob/master/docs/design/multiplexing-gateway.md

---

## 8. Расширения / плагины

- **Плагины:** три источника обнаружения (`~/.hermes/plugins/`, `.hermes/plugins/`, pip entry points); `PluginManager` (`hermes_cli/plugins.py`) — discovery, loading, hooks; `plugin.yaml`-манифест, `plugin_loader.py`, `plugin_storage.py`. Плагин расширяет: tools, hooks, CLI-команды. https://hermes-agent.nousresearch.com/docs/developer-guide/architecture , https://github.com/NousResearch/hermes-agent/tree/master/plugins
- **Два специализированных типа (single-select, по одному активному):** **memory providers** (`plugins/memory/`) и **context engines** (`plugins/context_engine/`). https://hermes-agent.nousresearch.com/docs/developer-guide/architecture
- **Bundled-плагины (крупные семейства):** platform-адаптеры (`plugins/platforms/`: telegram, discord, slack, whatsapp, matrix, mattermost, email, sms, dingtalk, feishu, wecom, homeassistant, irc, line, teams, google_chat, buzz, ntfy, photon, raft, simplex, **a2a**); model-providers (40+); web-поиск (brave_free, ddgs, exa, firecrawl, keenable, parallel, perplexity, searxng, tavily, xai); image_gen (deepinfra, fal, krea, meta-ai, openai, openrouter, xai); video_gen (deepinfra, fal, xai); browser (browser_use, browserbase, firecrawl); dashboard_auth (basic, drain, nous, self_hosted); cron_providers (chronos); observability (langfuse); kanban; google_meet; spotify; security-guidance; hermes-achievements; disk-cleanup; teams_pipeline. https://github.com/NousResearch/hermes-agent/tree/master/plugins
- **Расширяемость форматов:** skills как `SKILL.md` (agentskills.io), контекстные файлы, персоны `SOUL.md`, `AGENTS.md`. https://github.com/NousResearch/hermes-agent/blob/master/README.md
- **Портативность:** «Profile Distributions: Share a Whole Agent» (экспорт/импорт целого профиля); состояние памяти — `hermes_state_portability.py`. https://hermes-agent.nousresearch.com/docs/user-guide/sessions , https://github.com/NousResearch/hermes-agent/blob/master/hermes_state_portability.py
- **Relay (экспериментальный):** внешний connector-система, бронирующая платформы (Discord/Telegram/Slack/WhatsApp) с negotiated capabilities (media, approval, reactions, threads, typing, streaming); контракт `docs/relay-connector-contract.md`. https://hermes-agent.nousresearch.com/docs/user-guide/messaging

---

## 9. Сильные и слабые стороны (с точки зрения наших целей)

### Сильные стороны

- **Поистине persistent-сессии** с полной историей, lineage (parent/child при компрессии), FTS5-поиском по всем прошлым диалогам и человекочитаемыми title — прямое попадание в наш интерес. https://hermes-agent.nousresearch.com/docs/user-guide/sessions
- **Webhooks как first-class канал входящих событий** — именованные маршруты, HMAC per-route, декларативные фильтры + script-трансформ, prompt-шаблоны, per-route toolsets, **direct-delivery (zero-LLM)**, идемпотентность, rate limit, body limit. Это ровно референс «постоянных сессий с вебхуками». https://hermes-agent.nousresearch.com/docs/user-guide/messaging/webhooks
- **REST/SSE-поверхность для внешних клиентов** (OpenAI-compat + native Runs/Sessions/Jobs API) с `Idempotency-Key`, session-fork, approval-endpoint, `X-Hermes-Session-Id/Key` — хороший образец для нашего REST-клиента. https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server
- **At-least-once delivery ledger** с честной маркировкой возможного дубля и bounded-retry — зрелый паттерн для исходящих интеграций. https://hermes-agent.nousresearch.com/docs/user-guide/messaging
- **Продуманное восстановление:** soft `resume_pending` vs hard `suspended`, `.clean_shutdown`, `suspend_recently_active(120s)`, stuck-loop escalation (3×), drain-timeout marking. https://github.com/NousResearch/hermes-agent/blob/master/docs/session-lifecycle.md
- **Очереди и режимы занятости:** burst-collapse + FIFO overflow, `interrupt/queue/steer` — аккуратная модель конкурентных входящих. https://github.com/NousResearch/hermes-agent/blob/master/docs/session-lifecycle.md
- **Двусторонний MCP** с OAuth 2.1 (DCR/PKCE/device-code/mTLS), sampling, elicitation и per-server filtering/globs — одна из самых полных реализаций, что мы видели. https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp
- **Профильная изоляция через contextvars** без мутаций `os.environ` + multiplexing одного процесса под много профилей — образец безопасной мультиарендности на одном хосте. https://github.com/NousResearch/hermes-agent/blob/master/docs/design/multiplexing-gateway.md
- **Subagents** с изолированными сессиями/worktrees и summary-only возвратом + lifecycle-события. https://zread.ai/NousResearch/hermes-agent/25-subagent-delegation
- **Self-improving loop** (memory + skills + background review на дешёвой модели) — уникальная фича, потенциально полезна для «обучения» агентов в нашей платформе. https://hermes-agent.nousresearch.com/docs/user-guide/features/memory
- **Open-source MIT, self-hosted, Python**, простой install, множество бэкендов исполнения, serverless-персистентность. https://github.com/NousResearch/hermes-agent/blob/master/README.md

### Слабые стороны / риски

- **Нет «компании»:** отсутствуют org-chart, цели-деревья, бюджеты, governance-гейты, approvals, multi-tenant роли — то есть значительная часть нашего дизайна не покрыта и не подсказывается Hermes. (Не найдено/подтверждено, что этого нет.)
- **Хранилище — SQLite на машину/профиль.** Это single-writer-модель, плохо масштабируется горизонтально; «one agent per Hermes home»; при двух писателях в один home память «compounds entries into state neither authored». Мы выбрали PostgreSQL — и это преимущество нужно удержать. https://hermes-agent.nousresearch.com/docs/user-guide/features/memory
- **Дублирование и легаси путей хранения:** `state.db` + `sessions.json` + JSONL-fallback + `pending_messages/` spool; отдельный `webhook_subscriptions.json` — усложняет восстановление. https://github.com/NousResearch/hermes-agent/blob/master/docs/session-lifecycle.md
- **Реальные инциденты с БД:** коррупция FTS5 и повреждение самого файла приводили к «карантину» и need for explicit repair; документ фиксирует, что handle продолжал писать ~50 минут после первой структурной ошибки и превратил читаемый файл в нечитаемый. https://github.com/NousResearch/hermes-agent/blob/master/docs/state-db-recovery.md
- **Webhooks — отдельный HTTP-listener (8644)**, не интегрированный с API server (8642) и его авторизацией; две независимые auth-модели (HMAC secret vs bearer key). https://hermes-agent.nousresearch.com/docs/user-guide/messaging/webhooks , https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server
- **Молодость Runs API:** нет очереди follow-up без прерывания, молчаливый fallback неизвестной платформы, CORS-проблема с session-header (issue #104341). https://zread.ai/NousResearch/hermes-agent/5-issues-and-feedbacks
- **Расхождение документации** (reset-policy default `both` в design-doc vs «reset settings ignored» в user-doc) — признак активной миграции поведения; для нас важно не копировать нестабильную семантику. https://github.com/NousResearch/hermes-agent/blob/master/docs/session-lifecycle.md , https://hermes-agent.nousresearch.com/docs/user-guide/messaging
- **MCP process-global в multiplex-режиме** — первый профиль «захватывает» discovery slot (issue #67605). https://github.com/NousResearch/hermes-agent/blob/master/docs/design/multiplexing-gateway.md
- **Производительность:** N+1 session search, отсутствие кеша БД (issue #104340); утечки памяти в некоторых путях (issue с «117MB leak»). https://zread.ai/NousResearch/hermes-agent/5-issues-and-feedbacks
- **Сложная конфигурация:** `config.yaml` с routes/filters/scripts/channel_overrides/platform-toolsets/reset-policy — высокий порог входа и риск ошибок.
- **Нет объяснимой модели workflow-графа:** переходы и терминальные состояния как first-class (наш Workflow-движок) не обнаруживаются; «goals» и Kanban — ближайшие, но иные абстракции.

---

## 10. Выводы

### Что перенять

1. **Webhook-маршруты как first-class сущность** с полным набором защит: HMAC per-route (включая timestamped V2 против replay), обязательный secret, rate limit, body limit, idempotency по delivery-ID, декларативные фильтры + script-трансформ, prompt-шаблоны с dot-notation и `{__raw__}`, per-route набор инструментов, **direct-delivery без вызова LLM**. Это самый близкий к нашему ТЗ референс. https://hermes-agent.nousresearch.com/docs/user-guide/messaging/webhooks
2. **Разделение «сессия-транскрипт» vs «канал/скоуп памяти»:** `X-Hermes-Session-Id` (ротируется при `/new`) и `X-Hermes-Session-Key` (stable per-channel для long-term memory) — изящное решение для мульти-пользовательских фронтендов. https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server
3. **Idempotency-Key с durable reservation** и HTTP 202 + `Idempotency-Replayed`, сохраняемыми через рестарт — готовый контракт для retryable-операций. https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server
4. **Delivery ledger (at-least-once)** с честной маркировкой возможного дубля и bounded-retry — паттерн для наших исходящих вебхуков/integrations. https://hermes-agent.nousresearch.com/docs/user-guide/messaging
5. **Восстановление сессии: soft vs hard** (`resume_pending` vs `suspended`), `.clean_shutdown`-маркер, восстановление недавно активных (120 с), stuck-loop escalation — прямо применимо к нашему планировщику на ShedLock. https://github.com/NousResearch/hermes-agent/blob/master/docs/session-lifecycle.md
6. **Очередь входящих:** single-slot с burst-collapse + FIFO overflow; режимы `interrupt/queue/steer` — хорошая модель для «сообщения от пользователя/оркестратора/асинхронных инструментов». https://github.com/NousResearch/hermes-agent/blob/master/docs/session-lifecycle.md
7. **MCP-клиент:** stdio+HTTP, OAuth 2.1 с DCR/PKCE/device-code/mTLS, sampling и elicitation с rate-limit, per-server include/exclude/globs, recycling stdio по idle/lifetime, стриппинг Unicode TAG-символов из tool-результатов. С учётом наших 12+ MCP за OAuth2-прокси — очень релевантно. https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp
8. **Профильная изоляция через contextvars** (без `os.environ`) — образец для мультиарендности в одном процессе. https://github.com/NousResearch/hermes-agent/blob/master/docs/design/multiplexing-gateway.md
9. **Skills как `SKILL.md` + progressive disclosure + runtime injection + agentskills.io** — согласуется с нашей моделью навыков. https://github.com/NousResearch/hermes-agent/blob/master/README.md
10. **Subagents с изолированными сессиями/worktrees и summary-only возвратом**, плюс lifecycle-события в стриме — паттерн для наших параллельных веток работы. https://zread.ai/NousResearch/hermes-agent/25-subagent-delegation

### Чего избегать

1. **SQLite как primary store.** Мы уже выбрали PostgreSQL — это преимущество; не тянуть в сторону per-machine БД с single-writer-ограничениями и «one agent per home». https://hermes-agent.nousresearch.com/docs/user-guide/features/memory
2. **Дублирование хранилищ и легаси-fallback-путей** (`state.db` + `sessions.json` + JSONL + spool). Один канонический store и явные миграции — понятнее и надёжнее. https://github.com/NousResearch/hermes-agent/blob/master/docs/state-db-recovery.md
3. **Отдельный неинтегрированный listener для вебхуков.** У нас вебхуки должны быть частью единого API и единой auth-модели, а не вторым сервером с собственным HMAC-контуром. https://hermes-agent.nousresearch.com/docs/user-guide/messaging/webhooks
4. **Молчаливые fallback-и и слабые error-surfaces** в API (неизвестная платформа → `LOCAL`; отсутствие queue-эндпоинта) — у нас ошибки должны быть явными и типизированными. https://zread.ai/NousResearch/hermes-agent/5-issues-and-feedbacks
5. **Token-heavy self-improvement через background review на каждом ходу** — держать под контролем или выносить на дешёвую модель/по расписанию. https://hermes-agent.nousresearch.com/docs/user-guide/features/memory
6. **Нестабильную/расходящуюся семантику reset-политик** — у нас должна быть одна явно версионированная модель жизненного цикла сессии.
7. **Сложный YAML-конфиг с вложенными routes/filters/scripts/overrides** — предпочесть типизированный конфиг/БД-сущности.
8. **Отсутствие workflow-графа** — наш Workflow (кастомные состояния + переходы + терминальные состояния + сессия/агент на состояние) остаётся нашим дифференциатором; Hermes его не подтверждает как необходимость и не реализует.

### Как соотносится с нашей архитектурой

| Наш элемент (spring-harness) | Аналог у Hermes | Комментарий |
|---|---|---|
| Сессии агентов в PostgreSQL | `state.db` (SQLite) + `sessions.json` | У нас единый PostgreSQL; у Hermes — per-machine SQLite с дублями. |
| Вытесняющий планировщик (ShedLock, скан 1–5 c) | Cron-тикер gateway (60 c) + restart-recovery + wake-механика | Их планировщик — тикер и cron; наш — сканирование таблицы сессий. |
| Workflow (кастомные состояния + граф) | Нет прямого аналога (goals/Kanban иные) | Наш workflow-граф остаётся преимуществом. |
| Системные состояния (webhook-таймаут, bash-скрипт) | Webhook routes + direct delivery + cron | Их webhook-модель даёт много идей для триггеров. |
| Агент-оркестратор («CEO») | Subagents + delegation + `/bg`; A2A | У них нет org-chart/найма; есть делегирование подзадач. |
| Внешние триггеры: вебхуки (GitHub/GitLab) | Webhook-адаптер (HMAC, filters, templates) | Прямой референс — брать контракт маршрутов и защит. |
| Постоянные сессии, получающие события | Messaging gateway + API server + webhook | Ровно тот сценарий заказчика; подтверждён и зрел. |
| REST API клиент↔сервер, OpenAPI contract-first | API Server (OpenAI-compat + native) + SSE | У них OpenAI-compat JSON, не spec-first OpenAPI. |
| MCP через корпоративный OAuth2-прокси | MCP client (OAuth2.1, mTLS, DCR) | Брать OAuth/PKCE/device-code/mTLS и filtering. |

---

### Итоговая оценка

Hermes Agent — **не конкурент Paperclip’у по «компании агентов»**, а сильный **persistent-agent runtime** с явно выраженным фокусом на персонального ассистента, живущего на сервере и присутствующего во всех мессенджерах. Для нашей задачи он ценен именно тем, что подробно решает **модель постоянных сессий** и **входящие события (вебхуки/API/Runs/SSE)**, а также даёт зрелые паттерны восстановления, delivery-ledger, MCP-интеграции и профильной изоляции. Его главные уроки — **что** моделировать (session lineage, soft/hard resume, webhook route contract, idempotency, direct-delivery, MCP OAuth) и **чего** избегать (SQLite-центричность, дубли хранилищ, отдельный неинтегрированный webhook-listener, молчаливые fallback-и, нестабильная reset-семантика). Технологически (Python/`uv`/aiohttp/SQLite) он нам не переносим, но контракты и архитектурные приёмы — переносимы напрямую.
