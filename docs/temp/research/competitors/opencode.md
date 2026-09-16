# Конкурентный анализ: OpenCode

> Объект: OpenCode (Anomaly). Репозиторий: https://github.com/anomalyco/opencode , документация: https://opencode.ai/docs/
> Дата анализа: 2026-09-15. Источники — официальная документация (opencode.ai/docs) и исходники ветки `dev` (packages/*). Нумерация вопросов — по единому списку из BRIEF.md.
> Принцип: каждый факт со ссылкой. Где данных нет — «не найдено/не подтверждено».

---

## 1. Архитектура

- Терминальный AI-кодинг-агент с истинной клиент-серверной архитектурой внутри одного процесса: «When you run `opencode` it starts a TUI and a server. Where the TUI is the client that talks to the server». Сервер публикует OpenAPI 3.1-спеку (`GET /doc`), по которой генерируется SDK. https://opencode.ai/docs/server/
- Это позволяет несколько клиентов одновременно: TUI, web UI, IDE-плагины, SDK-программы. «This architecture lets opencode support multiple clients». https://opencode.ai/docs/server/
- Монорепозиторий (Bun + turbo; https://github.com/anomalyco/opencode/tree/dev/packages). Ключевые пакеты:
  - `packages/opencode` — ядро приложения: session, agent, tool, server (HTTP-роуты), storage, mcp, plugin, share, worktree, control-plane;
  - `packages/core` — общая база: схемы, database (SQLite+Drizzle, миграции), git, filesystem, permissions, background-job, системные промпты;
  - `packages/tui` — терминальный UI (SolidJS/OpenTUI), отдельная линия TUI-плагинов;
  - `packages/plugin` — публичный пакет `@opencode-ai/plugin` (типы Plugin/Hooks/tool для авторов);
  - `packages/sdk` — клиент `@opencode-ai/sdk` (генерируется из OpenAPI); `packages/sdk-next`, `packages/client`, `packages/protocol` — клиентско-протокольные слои;
  - `packages/web` (доки + web-клиент), `packages/desktop` (Electron), `packages/cli`, `packages/enterprise`, `packages/function`, `packages/slack`, `packages/console`, `packages/session-ui`;
  - `packages/effect-drizzle-sqlite`, `packages/effect-sqlite-node` — Effect-native SQLite/Drizzle-адаптеры.
- Ядро написано на TypeScript поверх **Effect-TS** (слои/сервисы, fibers, типизированные ошибки; синглтоны через `makeGlobalNode`/`LayerNode`); событийная шина — bus-сервис с проекцией событий в БД (EventV2/projector). https://github.com/anomalyco/opencode/tree/dev/packages/opencode/src , https://github.com/anomalyco/opencode/tree/dev/packages/effect-drizzle-sqlite
- Хранилище — **локальное, машина-ориентированное** (local-first). Пути по XDG (https://github.com/anomalyco/opencode/blob/dev/packages/core/src/global.ts):
  - данные: `~/.local/share/opencode` (`data`; внутри — `log/`, `repos/`, `storage/`, `auth.json`, `mcp-auth.json`, `opencode.db`);
  - конфиги: `~/.config/opencode` (`config`; override `OPENCODE_CONFIG_DIR`);
  - state: `~/.local/state/opencode` (файловые локи), cache: `~/.cache/opencode` (bin, npm-кеш плагинов);
  - все каталоги создаются при старте модуля `global.ts`.
- Двойной ярус персистентности, идёт миграция «файлы → SQLite»:
  - Унаследованный **JSON-файл storage**: интерфейс `remove/read/update/write/list` с ключами-путями, файл `<key>.json` в `~/.local/share/opencode/storage/`, per-file реентерабельные локи (`TxReentrantLock` через `RcMap`), нумерованные миграции с маркером `storage/migration`. `packages/opencode/src/storage/storage.ts`: https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/storage/storage.ts
  - **SQLite** `~/.local/share/opencode/opencode.db` (для каналов latest/beta/prod; нестабильные каналы получают `opencode-<channel>.db`; override через `OPENCODE_DB`): PRAGMA `journal_mode=WAL`, `synchronous=NORMAL`, `busy_timeout=5000`, `foreign_keys=ON`, `cache_size=-64000`; миграции Drizzle применяются при старте. `packages/core/src/database/database.ts`: https://github.com/anomalyco/opencode/blob/dev/packages/core/src/database/database.ts
  - Спека вычистки легаси-обёртки БД перечисляет 22 файла-потребителя `Database.*` и фиксирует инварианты: вложенные чтения внутри транзакции видят активную транзакцию; «immediate»-транзакции для аллокации sequence; post-commit эффекты публикуются только после коммита; владение схемой остаётся в `packages/core/src/**/*.sql.ts`. `specs/storage/remove-opencode-db.md`: https://github.com/anomalyco/opencode/blob/dev/specs/storage/remove-opencode-db.md
- Транспорты: HTTP REST + SSE (событийная шина `/event`, глобальный поток `/global/event`); ACP (Agent Client Protocol) через stdin/stdout nd-JSON для IDE (`opencode acp`); mDNS-обнаружение для LAN. https://opencode.ai/docs/server/ , https://opencode.ai/docs/cli/
- Изоляция процессов: MCP-серверы — дочерние процессы (stdio) или удалённые HTTP-эндпоинты; плагины — **in-process** динамический import в рантайме сервера (Bun); TUI-плагины — отдельная параллельная линия загрузки (entrypoint `tui`). https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/plugin/loader.ts
- Desktop-приложение — Electron: main-процесс поднимает sidecar с OpenCode-сервером (Utility Process; вариант V2 — фоновый CLI при `OPENCODE_SIDECAR_V2=1`) и поллит health-эндпоинт. https://github.com/anomalyco/opencode/tree/dev/packages/desktop
- Аутентификация провайдеров LLM: `~/.local/share/opencode/auth.json` (`opencode auth login/list/logout`); каталог провайдеров — models.dev. https://opencode.ai/docs/cli/
- Enterprise-разворот: central config (единый конфиг организации), интеграция SSO для получения креденшелов внутреннего AI-шлюза, запрет «чужих» провайдеров, self-hosting share-страниц «в планах». Per-seat-модель, при своём LLM-шлюзе токены не тарифицируются. https://opencode.ai/docs/enterprise/
- Почему сессии локальные: продукт спроектирован как персональный local-first инструмент — сервер по умолчанию биндится на `127.0.0.1`, состояние (SQLite + JSON + auth) лежит в профиле пользователя машины; никакого центрального мультипользовательского хранилища в OSS-части нет (см. §2, §9). https://opencode.ai/docs/server/ , https://opencode.ai/docs/web/

---

## 2. Модель сессий

- Сессии — event-sourced агрегаты над SQLite (`packages/core/src/session/sql.ts`: https://github.com/anomalyco/opencode/blob/dev/packages/core/src/session/sql.ts). Таблицы:
  - `session`: id, `project_id` FK (cascade), `workspace_id`, `parent_id`, slug, directory, path, title, version, `share_url`, summary-поля, metadata, cost, tokens_*, `revert`, `permission`, agent, model;
  - `message`/`part`: JSON-данные (`data`) + timestamps, FK на сессию/сообщение с cascade;
  - `todo`: PK (session_id, position);
  - `session_message`: event-log c `type`+`seq`, unique `(session_id, seq)` — проекция событий;
  - `session_input`: входящий инбокс — `prompt`, `delivery`, `admitted_seq`, `promoted_seq`;
  - `session_context_epoch`: baseline-снапшот системного контекста (для инвалидации промпт-кеша).
- **Инбокс входящих сообщений** — прямая аналогия нашего «сессия запускается только при новых сообщениях»: `session_input` с monotonic `admitted_seq`/`promoted_seq` (миграции `20260603141458_session_input_inbox`, `20260604172448_event_sourced_session_input`, `20260622202450_simplify_session_input`). https://github.com/anomalyco/opencode/tree/dev/packages/core/src/database/migration
- Выполнение сериализуется **RunCoordinator**-ом (per-key = per-session; `packages/core/src/session/run-coordinator.ts`: https://github.com/anomalyco/opencode/blob/dev/packages/core/src/session/run-coordinator.ts):
  - контракт: «Serializes execution for each key while allowing different keys to run concurrently»;
  - `run(key)` — стартовать или присоединиться к текущему выполнению;
  - `wake(key)` — «Registers one coalesced follow-up after newly recorded work»: если сессия активна, ставится один флаг pendingWake; после завершения drain-а запуск повторяется без гонок;
  - `interrupt(key)` — остановить активное выполнение и дождаться cleanup (abort);
  - `active` — снапшот занятых ключей.
- Легаси-файловая раскладка (из миграций `storage.ts`): `storage/session/<projectID>/<sessionID>.json`, `storage/message/<sessionID>/<messageID>.json`, `storage/part/<messageID>/<partID>.json`, `storage/session_diff/...`; projectID для git-проектов = первый commit id (`git rev-list --max-parents=0 --all`, отсортированный). https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/storage/storage.ts
- Персистентность: всё на диске локальной машины; переживает рестарты; API-сущности доступны после старта (`opencode -c` продолжает последнюю сессию, `-s <id>` — конкретную). https://opencode.ai/docs/cli/
- **Attach/роуминг** (https://opencode.ai/docs/cli/ , https://opencode.ai/docs/web/):
  - `opencode attach [url]` подключает TUI к уже запущенному backend-серверу (`serve`/`web`) — «This allows using the TUI with a remote OpenCode backend»;
  - пример из доков: `opencode web --port 4096 --hostname 0.0.0.0` на одной машине + `opencode attach http://10.20.30.40:4096` с другой; basic-auth флаги `--username/--password`;
  - `opencode run --attach <url>` — то же для неинтерактивного режима (в т.ч. чтобы избежать MCP cold-boot на каждый run);
  - `--dir` при attach — рабочий каталог на удалённом сервере;
  - роуминг офис/дом = подключение к удалённо запущенному серверу; собственного relay/синхронизации между машинами в документации не найдено;
  - обнаружение серверов в LAN — mDNS (`--mdns`, домен `opencode.local` по умолчанию, кастомный `--mdns-domain`).
- Совместный доступ: web UI и TUI могут работать одновременно с одним сервером — «sharing the same sessions and state». Плюс SSE-поток `/event` для всех подключённых клиентов. https://opencode.ai/docs/web/ , https://opencode.ai/docs/server/
- Форки/ветвления: `POST /session/:id/fork` c `{ messageID? }` — форк сессии от конкретного сообщения; CLI-флаг `--fork` (с `--continue`/`--session`); создание сессии принимает `parentID` ( child-сессии субагентов, `GET /session/:id/children`); revert/unrevert по messageID+partID. https://opencode.ai/docs/server/ , https://opencode.ai/docs/cli/
- Удаление: `DELETE /session/:id` — сессия и все данные; `opencode session delete <id>`. Экспорт/импорт: `opencode export [sessionID] --sanitize`, `opencode import <file|share-url>`. https://opencode.ai/docs/server/ , https://opencode.ai/docs/cli/
- Шаринг наружу: `POST /session/:id/share` — публичная ссылка `opncd.ai/s/<share-id>` (синхронизация истории на серверы Anomaly, доступна любому со ссылкой); режимы manual/auto/disabled, `OPENCODE_AUTO_SHARE`; enterprise: отключение/ограничение SSO/self-host «on roadmap». https://opencode.ai/docs/server/ , https://opencode.ai/docs/share/
- Контекст-менеджмент сессии: autocompact (`OPENCODE_DISABLE_AUTOCOMPACT`), скрытый агент compaction, `POST /session/:id/summarize`, summarize/title-агенты; context epoch — снапшот системного контекста для инвалидации промпт-кеша. https://opencode.ai/docs/cli/ , https://github.com/anomalyco/opencode/blob/dev/packages/core/src/session/sql.ts
- Разрешения «always» персистятся в SQLite (таблица PermissionSaved, per project; каскадное удаление с проектом). https://github.com/anomalyco/opencode/blob/dev/specs/storage/remove-opencode-db.md (упоминание `permission/index.ts`, `PermissionTable`)
- Есть событийный лог с sequence-номерами и HTTP sync-эндпоинты (`server/routes/.../handlers/sync.ts` читает event rows) — судя по спеке, для синхронизации клиентов; публичной документации sync-протокола не найдено. https://github.com/anomalyco/opencode/blob/dev/specs/storage/remove-opencode-db.md

---

## 3. Модель агента

- Агент = конфиг (JSON в `opencode.json` или Markdown с YAML-frontmatter в `~/.config/opencode/agents/` / `.opencode/agent[s]/`; имя = имя файла). Роли как таковой нет — есть «режим использования». https://opencode.ai/docs/agents/
- Типы: `primary` (главные, переключение Tab) и `subagent` (вызываются главными или @-mention; `mode: all` по умолчанию). https://opencode.ai/docs/agents/
- Опции агента (https://opencode.ai/docs/agents/):
  - `description` — обязательна (по ней происходит делегирование);
  - `mode` — primary/subagent/all (по умолчанию all);
  - `prompt` — в т.ч. `{file:./prompts/x.txt}` (путь относительно конфига);
  - `model` — `provider/model-id`; субагенты наследуют модель вызвавшего, если не задана;
  - `temperature`, `top_p`, `steps` (лимит агентных итераций; после лимита — принудительный текстовый ответ с саммари; легаси `maxSteps` deprecated);
  - `permission`, `disable`, `hidden` (только subagent: скрыть из @-автокомплита, Task-инструментом звать можно), `color`;
  - произвольные дополнительные опции → passthrough в провайдер (`reasoningEffort`, `textVerbosity`, ...).
- Встроенные агенты: **build** (primary, по умолчанию, все инструменты), **plan** (primary, file edits и bash в `ask`), **general** (subagent, полный доступ кроме todo), **explore** (subagent, read-only поиск), **scout** (subagent, read-only внешние доки/зависимости, клонирует репозитории в кеш), скрытые системные **compaction**, **title**, **summary**. https://opencode.ai/docs/agents/
- Субагенты: вызываются автоматически по description, вручную `@name`, или Task-инструментом (`subagent_type`); промпт Task-инструмента поощряет параллельный запуск нескольких агентов и описывает паттерн «result not visible to user — суммируй», resume сессии субагента по `task_id`. `packages/opencode/src/tool/task.txt`: https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/tool/task.txt
- Ограничение найма: `permission.task` — glob-паттерны на имена субагентов (`"*": "deny", "orchestrator-*": "allow"`); `deny` убирает субагента из описания Task-инструмента; последнее совпавшее правило побеждает; пользователь через @-mention может позвать любого. https://opencode.ai/docs/agents/
- Навигация по дочерним сессиям в TUI: `session_child_first`/`session_child_cycle`/`session_parent` (Leader+Down/Right/Left/Up). https://opencode.ai/docs/agents/
- **Skills** (https://opencode.ai/docs/skills/):
  - `SKILL.md` c frontmatter: обязательные `name`, `description`; опциональные `license`, `compatibility`, `metadata`; неизвестные поля игнорируются;
  - локаации поиска: `.opencode/skills/`, `~/.config/opencode/skills/`, совместимость с Claude (`.claude/skills/`) и agents (`.agents/skills/`); для проекта — walk-up от CWD до git worktree; глобальные — из домашнего профиля;
  - ленивая загрузка через инструмент `skill`: в описание инструмента подставляется `<available_skills>` (name+description), агент вызывает `skill({ name })` для загрузки полного содержимого;
  - валидация имени: `^[a-z0-9]+(-[a-z0-9]+)*$`, 1–64 символа, совпадает с именем каталога; description 1–1024 символа;
  - права: `permission.skill` с wildcard (`internal-*: deny`, `experimental-*: ask`); `deny` скрывает скилл от агента; per-agent override в frontmatter/конфиге; полное отключение — `tools: { skill: false }`.
- Промпт-структура: набор системных промптов по провайдерам/моделям в репо — `default.txt`, `anthropic.txt`, `gpt.txt`, `gemini.txt`, `kimi.txt`, `meta.txt`, `beast.txt`, `codex.txt`, `copilot-gpt-5.txt`, `trinity.txt` + `plan.txt`/`plan-mode.txt`/`plan-reminder-anthropic.txt` + `build-switch.txt`; правила проекта — `AGENTS.md` (и `~/.claude/CLAUDE.md` совместимость, отключается env). https://github.com/anomalyco/opencode/tree/dev/packages/opencode/src/session/prompt , https://opencode.ai/docs/rules/ , https://opencode.ai/docs/cli/
- Permissions-модель: ключи `read`, `edit` (покрывает write/edit/apply_patch), `glob`, `grep`, `list`, `bash`, `task`, `external_directory`, `todowrite`, `webfetch`, `websearch`, `lsp`, `skill`, `question`, `doom_loop`; значения `ask|allow|deny`; для большинства ключей — объект glob→action (например `bash: {"git *": "ask", "grep *": "allow"}`); ключи матчятся как wildcard по имени инструмента, поэтому один синтаксис работает для builtin/custom/MCP (`"mymcp_*": "deny"`); **последнее совпавшее правило побеждает**. https://opencode.ai/docs/agents/ , https://opencode.ai/docs/tools/
- Создание агентов CLI: `opencode agent create` (интерактивно или флагами `--path/--description/--mode/--permissions/--model`). https://opencode.ai/docs/cli/

---

## 4. Инструменты

- Встроенные: `bash`, `edit`, `write`, `read`, `grep`, `glob`, `list`, `apply_patch`, `lsp` (эксперимент., `OPENCODE_EXPERIMENTAL_LSP_TOOL`: goToDefinition/findReferences/hover/documentSymbol/workspaceSymbol/goToImplementation/call hierarchy), `skill`, `todowrite`/`todoread` (у субагентов todowrite отключён по умолчанию), `webfetch`, `websearch` (через Exa/Parallel при `OPENCODE_ENABLE_EXA`/`OPENCODE_ENABLE_PARALLEL`, без API-ключа), `question` (вопросы пользователю с опциями), `task` (субагенты), `plan` (вход/выход из plan mode). https://opencode.ai/docs/tools/ , https://opencode.ai/docs/agents/
- Внутри grep/glob — ripgrep (уважает `.gitignore`, override через `.ignore`). https://opencode.ai/docs/tools/
- Паттерны выполнения: синхронный tool-call в agent loop; стриминг частных обновлений — события `message.part.updated` и SSE `/event`. Асинхронный ввод: `POST /session/:id/prompt_async` — «Send a message asynchronously (no wait)», ответ `204 No Content`, результат наблюдается через SSE. https://opencode.ai/docs/server/
- Фоновые задачи с отложенным результатом: эксперимент `OPENCODE_EXPERIMENTAL_BACKGROUND_SUBAGENTS` (https://opencode.ai/docs/cli/). Механика — **BackgroundJob** реестр (`packages/core/src/background-job.ts`: https://github.com/anomalyco/opencode/blob/dev/packages/core/src/background-job.ts):
  - API: `start/extend/wait/waitForPromotion/promote/cancel/list/get`; job = id, type, title, status (running/completed/error/cancelled), output, metadata;
  - `extend` добавляет continuation к живому job (последовательная нумерация вывода, `pending`-счётчик);
  - `promote` переводит job в «background» (metadata.background=true) с onPromote-колбэком; `wait` — с опциональным timeout;
  - в app-слое реестр скоупится на instance (`packages/opencode/src/background/job.ts`: https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/background/job.ts);
  - ключевое ограничение задокументировано в коде: «Entries are intentionally not durable: process restart or owner-scope closure loses status and interrupts live work. Persisted observation, restart recovery, and remote workers need a separate durable ownership slice».
- Промпт Task-инструмента: «For background tasks, you will be notified automatically when the result is ready» + `task_id` для продолжения той же сессии субагента. https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/tool/task.txt
- Кастомные инструменты: через плагины (Zod-схема + `execute(args, context)`, хелпер `tool(...)`; коллизия имён — плагинный инструмент перебивает builtin) и через config-инструменты (`/docs/custom-tools`). https://opencode.ai/docs/plugins/ , https://opencode.ai/docs/tools/
- MCP-инструменты регистрируются с префиксом имени сервера (`mymcp_search`), управляются wildcard-паттернами. https://opencode.ai/docs/mcp-servers/

---

## 5. Оркестрация/воркфлоу

- Workflow-движка (кастомные состояния, граф переходов, терминальные состояния) **нет** — не найдено ни в доках, ни в коде. Ближайшее: plan mode (агент plan + `plan.txt`/`plan-mode.txt` промпты + `OPENCODE_EXPERIMENTAL_PLAN_MODE`) и todo-листы внутри сессии. https://opencode.ai/docs/agents/ , https://github.com/anomalyco/opencode/tree/dev/packages/opencode/src/session/prompt
- Планировщика задач тоже нет; вместо него — описанный в §2 RunCoordinator: событийно-коалесцированный запуск drain-обработки сессии (run/wake/interrupt per key). Это функциональный аналог нашего вытесняющего сканера таблицы сессий, но без таймера — чисто event-driven wake. https://github.com/anomalyco/opencode/blob/dev/packages/core/src/session/run-coordinator.ts
- Многоагентность: делегирование через Task-инструмент (каждый вызов = свежая дочерняя сессия с собственным контекстом; параллельные вызовы encouraged; возврат одним сообщением); @-mention ручной вызов. Агента-«оркестратора», создающего workflow/назначающего задачи/триггеры, нет. https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/tool/task.txt , https://opencode.ai/docs/agents/
- Внешние триггеры вынесены в GitHub Actions — GitHub-агент (https://opencode.ai/docs/github/; GitLab-аналог: https://opencode.ai/docs/gitlab/). Поддерживаемые события:

  | Событие | Триггер | Примечание |
  |---|---|---|
  | `issue_comment` | `/opencode` или `/oc` в комментарии issue/PR | контекст треда, может создавать ветки и PR |
  | `pull_request_review_comment` | `/oc` на строках кода в ревью | передаётся файл, строки, diff-контекст |
  | `issues` | создание/редактирование issue | требуется `prompt` input |
  | `pull_request` | opened/synchronize/reopened | автоворевью; без `prompt` — дефолтный ревью-промпт |
  | `schedule` | **cron** | требуется `prompt`; вывод в логи/PR |
  | `workflow_dispatch` | вручную из UI Actions | требуется `prompt` |

  Аутентификация: OIDC-token exchange на токен OpenCode App (по умолчанию) либо `use_github_token: true` с caller `GITHUB_TOKEN`. Конфигурация: `model` (обязателен), `agent`, `share`, `prompt`, `mentions`, `variant`.
- Т.е. cron-оркестрация — на стороне CI, не продукта; «постоянных сессий, питающихся вебхуками», в ядре не найдено. Идею заменяют: `opencode serve` + внешний скрипт, дергающий `POST /session/:id/message|prompt_async` + SSE. https://opencode.ai/docs/server/
- Системные состояния без агента (webhook-wait с таймаутом, bash-скрипт с разбором ошибок) — аналогов не найдено.

---

## 6. API-поверхность

- `opencode serve [--port 4096] [--hostname 127.0.0.1] [--cors ...] [--mdns]` — headless HTTP-сервер с OpenAPI 3.1 на `GET /doc`. Если TUI уже запущен, `opencode serve` поднимет **новый** сервер (TUI занимает случайный порт; `--port/--hostname` задаются явно для соединения). https://opencode.ai/docs/server/
- Авторизация: HTTP Basic (`OPENCODE_SERVER_PASSWORD`, юзер `OPENCODE_SERVER_USERNAME`, по умолчанию `opencode`). Без пароля сервер открыт — «fine for local use». Per-user auth/RBAC/токены — не найдено. https://opencode.ai/docs/server/ , https://opencode.ai/docs/web/
- Стриминг: SSE `GET /event` («first event is `server.connected`, then bus events») и `GET /global/event`. WebSocket — не найдено. https://opencode.ai/docs/server/
- Основные группы эндпоинтов (все — REST/JSON; https://opencode.ai/docs/server/):
  - Global: `GET /global/health` (healthy+version), `GET /global/event` (SSE).
  - Project/Path/VCS: `GET /project`, `GET /project/current`, `GET /path`, `GET /vcs`.
  - Instance: `POST /instance/dispose`.
  - Config: `GET/PATCH /config`, `GET /config/providers`.
  - Provider/Auth: `GET /provider`, `GET /provider/auth`, `POST /provider/{id}/oauth/authorize`, `POST /provider/{id}/oauth/callback`, `PUT /auth/:id`.
  - Sessions: `GET/POST /session`, `GET /session/status`, `GET/DELETE/PATCH /session/:id`, `GET /session/:id/children`, `GET /session/:id/todo`, `POST /session/:id/init` (генерация AGENTS.md), `POST /session/:id/fork`, `POST /session/:id/abort`, `POST/DELETE /session/:id/share`, `GET /session/:id/diff`, `POST /session/:id/summarize`, `POST /session/:id/revert|unrevert`, `POST /session/:id/permissions/:permissionID`.
  - Messages: `GET/POST /session/:id/message` (POST ждёт ответ), `GET /session/:id/message/:messageID`, `POST /session/:id/prompt_async` (204), `POST /session/:id/command` (slash-команда), `POST /session/:id/shell` (shell-команда как сообщение).
  - Files/поиск: `GET /find?pattern=`, `GET /find/file?query=`, `GET /find/symbol?query=`, `GET /file?path=`, `GET /file/content?path=`, `GET /file/status`.
  - Tools: `GET /experimental/tool/ids`, `GET /experimental/tool?provider=&model=`.
  - LSP/Formatters/MCP: `GET /lsp`, `GET /formatter`, `GET /mcp`, `POST /mcp` (динамическое добавление MCP-сервера).
  - Agents: `GET /agent`. Logging: `POST /log`.
  - TUI-управление: `POST /tui/append-prompt|submit-prompt|clear-prompt|open-help|open-sessions|open-themes|open-models|execute-command|show-toast`, `GET /tui/control/next`, `POST /tui/control/response` — «This setup is used by the OpenCode IDE plugins» (драйв TUI через сервер).
  https://opencode.ai/docs/server/
- SDK (`@opencode-ai/sdk`, https://opencode.ai/docs/sdk/):
  - `createOpencode()` поднимает сервер+клиент (опции: hostname, port, signal, timeout 5000ms, config-overrides поверх `opencode.json`);
  - `createOpencodeClient({ baseUrl })` — клиент к уже запущенному серверу (опции: fetch, parseAs, responseStyle `data|fields`, throwOnError);
  - типы сгенерированы из OpenAPI — `packages/sdk/js/src/gen/types.gen.ts`;
  - structured output: `format: { type: "json_schema", schema, retryCount }` → модель отвечает через инструмент `StructuredOutput`; при провале валидаций после ретраев — ошибка `StructuredOutputError`;
  - события: `client.event.subscribe()` — async-итератор SSE-потока.
- CLI-поверхность (https://opencode.ai/docs/cli/):
  - `opencode run [msg]` — неинтерактивный режим: `--attach <url>` (к запущенному серверу, экономит cold-boot MCP), `--format json` (сырые JSON-события), `-c/-s/--fork`, `--share`, `--auto` (авто-аппрув не-denied разрешений), `--variant`, `--title`;
  - `opencode attach [url]` — TUI к удалённому backend (`--username/--password`);
  - `opencode serve` / `opencode web` — headless-сервер / сервер с web-UI;
  - `opencode acp` — ACP-сервер (stdin/stdout nd-JSON);
  - `opencode session list|delete`, `export [--sanitize]`, `import <file|share-url>`, `stats` (токены/стоимость; фильтры `--days/--models/--tools/--project`);
  - `opencode plugin <module>` (alias `plug`; `--global`, `--force`), `opencode pr <number>`, `opencode db [query]` / `db path` (прямой SQLite-доступ), `opencode agent create|list`, `opencode mcp add|list|auth|logout|debug`, `opencode github install|run`, `opencode debug`, `opencode uninstall`, `opencode upgrade`.
- Глобальные флаги: `--pure` (без внешних плагинов), `--print-logs`, `--log-level` и ~50 env-переменных (`OPENCODE_CONFIG`, `OPENCODE_PERMISSION`, `OPENCODE_DISABLE_DEFAULT_PLUGINS`, `OPENCODE_DISABLE_AUTOUPDATE`, ...). https://opencode.ai/docs/cli/

---

## 7. MCP

- OpenCode — **MCP-клиент** (режима MCP-сервера у продукта нет; наружу отдаётся собственный OpenAPI). Типы подключений (https://opencode.ai/docs/mcp-servers/):
  - local (stdio): `command: ["npx","-y",...]`, `environment`, `cwd`, `timeout` (5000ms по умолчанию);
  - remote (HTTP): `url`, `headers`, `oauth`, `timeout`.
- OAuth для remote MCP (https://opencode.ai/docs/mcp-servers/):
  - автоматическое обнаружение 401 → OAuth-флоу с **Dynamic Client Registration (RFC 7591)**, если сервер поддерживает;
  - опционально pre-registered `clientId`/`clientSecret`/`scope` с `{env:VAR}`-подстановками;
  - `oauth: false` отключает авто-детект (для API-key серверов — только headers);
  - токены хранятся в `~/.local/share/opencode/mcp-auth.json`;
  - CLI: `opencode mcp auth [name]`, `mcp auth list`, `mcp logout`, `mcp debug <name>` (статус, HTTP-коннективность, OAuth discovery).
- Динамическое управление: `POST /mcp` добавляет сервер в рантайме (body `{ name, config }`). https://opencode.ai/docs/server/
- Enterprise: дефолтные MCP-серверы организации через `.well-known/opencode` endpoint (могут быть `enabled: false` — пользователь opt-in локальным конфигом; локальные значения переопределяют удалённые дефолты). https://opencode.ai/docs/mcp-servers/
- Управление инструментами: глобально и per-agent (`tools: {"my-mcp*": false}` + в агенте `{"my-mcp*": true}`), wildcard `*`/`?`. https://opencode.ai/docs/mcp-servers/
- Известные проблемы, признанные в доках: MCP-инструменты раздувают контекст («can quickly add up», «GitHub MCP server ... can easily exceed the context limit»); рекомендация — аккуратно выбирать серверы. https://opencode.ai/docs/mcp-servers/
- В репо есть patch на `@modelcontextprotocol/sdk@1.29.0` — признаки доработок SDK под себя. https://github.com/anomalyco/opencode/tree/dev/patches
- Для нашего сценария (12+ MCP за OAuth2-прокси с токенами на 24ч): pre-registered client credentials + `{env:...}` подстановки поддерживаются, refresh подразумевается автоматикой; подтверждения refresh-политики в доках не найдено.

---

## 8. Расширения/плагины

- Плагин — JS/TS-модуль, экспортирующий функции `(input) => hooks`. Источники: локальные файлы (`.opencode/plugins/` — проект, `~/.config/opencode/plugins/` — глобально; грузятся автоматически на старте) и npm-пакеты из конфига (`"plugin": ["opencode-helicone-session", ...]`; ставятся Bun-ом на старте в `~/.cache/opencode/node_modules/`; утилита `opencode plugin <module>` добавляет в конфиг). https://opencode.ai/docs/plugins/ , https://opencode.ai/docs/cli/
- Порядок загрузки: global config → project config → global plugin dir → project plugin dir; npm-дубликаты (имя+версия) грузятся один раз; локальный + npm с похожими именами — оба. https://opencode.ai/docs/plugins/
- **PluginInput**: `client` (SDK-клиент для своего сервера), `project`, `directory`, `worktree`, `serverUrl`, `$` (Bun shell), `experimental_workspace.register` (адаптеры workspace local/remote). Прямого доступа к Storage/Database **нет** — только через HTTP SDK-клиент. `packages/plugin/src/index.ts`: https://github.com/anomalyco/opencode/blob/dev/packages/plugin/src/index.ts
- Hooks (полный список из `@opencode-ai/plugin`): `dispose`, `event` (все события шины), `config`, `tool` (кастомные инструменты), `auth` (кастомные методы аутентификации провайдеров: oauth/api, многошаговые prompts), `provider` (модели провайдера), `chat.message`, `chat.params` (temperature/topP/maxOutputTokens/options перед LLM), `chat.headers`, `permission.ask`, `command.execute.before`, `tool.execute.before/after` (мутация args/output), `shell.env`, `tool.definition` (правка описаний/схем инструментов), экспериментальные: `experimental.chat.messages.transform`, `experimental.chat.system.transform`, `experimental.provider.small_model`, `experimental.session.compacting` (инъекция контекста/замена промпта компакции), `experimental.compaction.autocontinue`, `experimental.text.complete`. https://github.com/anomalyco/opencode/blob/dev/packages/plugin/src/index.ts , https://opencode.ai/docs/plugins/
- События шины для плагинов: command.executed; file.edited/file.watcher.updated; installation.updated; lsp.*; message.* (part.removed/updated, removed, updated); permission.asked/replied; server.connected; session.* (created, compacted, deleted, diff, error, idle, status, updated); todo.updated; shell.env; tool.execute.after/before; tui.*. https://opencode.ai/docs/plugins/
- Зависимости плагина: `package.json` в config-каталоге, `bun install` на старте. https://opencode.ai/docs/plugins/
- **Ограничения механики** (подтверждены исходниками):
  - Загрузка только на старте: «Files in these directories are automatically loaded at startup», «npm plugins are installed automatically using Bun at startup». Хот-релоада нет — смена набора/версий плагинов требует рестарта процесса. https://opencode.ai/docs/plugins/
  - Кэш модулей Bun делает неудачную загрузку фатальной до конца жизни процесса: «Once dynamic import runs, failures are treated as permanent for this process because Bun caches failed module resolution»; файловые плагины ретраятся один раз — только до импорта и только при retryable pre-import ошибках (после `bun install`). `packages/opencode/src/plugin/loader.ts`: https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/plugin/loader.ts
  - Отсюда практический «двойной перезапуск» при обновлении плагина: `opencode plugin` обновляет конфиг (нужен рестарт №1), новая npm-версия ставится на старте и подхватывается только следующим запуском (рестарт №2 в ряде сценариев). Сам механизм в коде подтверждён (установка на старте + permanence of import failures); термин «double restart» в доках не встречается — помечаем как вывод из устройства, а не как документированный факт.
  - npm-плагины проходят compatibility-gate по версии opencode (файловые — нет: «local development code»). `loader.ts`: https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/plugin/loader.ts
  - «Статические конфиги»: хук `config` получает снимок конфига на старте; runtime-изменение конфига есть (`PATCH /config`), но перечитывание набора плагинов/агентов в живом процессе не задокументировано. https://opencode.ai/docs/plugins/ , https://opencode.ai/docs/server/
  - Нет доступа к Storage Layer: подтверждено типом `PluginInput` (только SDK HTTP-клиент) — плагин не может читать/писать сессии напрямую, минуя API. https://github.com/anomalyco/opencode/blob/dev/packages/plugin/src/index.ts
- TUI-плагины: отдельный entrypoint `tui` (темы, UI-расширения), грузятся той же цепочкой loader-а (`PluginKind` server/tui). https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/plugin/loader.ts

---

## 9. Сильные и слабые стороны (с точки зрения целей spring-harness)

### Сильные
- Настоящая клиент-серверная развязка: TUI/web/IDE/SDK — равноправные клиенты одного OpenAPI-сервера; спека `GET /doc` как единственный источник правды, SDK генерируется из неё (наш contract-first подход подтверждается практикой). https://opencode.ai/docs/server/ , https://opencode.ai/docs/sdk/
- Attach-модель решает наш сценарий роуминга: `opencode attach http://host:port` + basic auth; одновременно web и TUI разделяют сессии. https://opencode.ai/docs/cli/ , https://opencode.ai/docs/web/
- SSE-шина событий (`/event`, `server.connected` первым) — простая и рабочая модель стриминга для всех клиентов. https://opencode.ai/docs/server/
- Session input inbox + RunCoordinator: событийное «разбуди сессию при новых сообщениях» с коалесцированием — более экономичный аналог нашего 1–5-секундного сканера таблицы. https://github.com/anomalyco/opencode/blob/dev/packages/core/src/session/run-coordinator.ts
- Зрелый MCP-клиент: stdio+remote, OAuth с Dynamic Client Registration, pre-registered credentials, `{env:}`-подстановки, debug-утилита, динамический `POST /mcp`, org-дефолты через `.well-known/opencode`. https://opencode.ai/docs/mcp-servers/
- Продуманная permissions-модель: ask/allow/deny + glob-паттерны на bash-команды и имена инструментов (включая `mcp_*`), last-match-wins, персистентные «always». https://opencode.ai/docs/agents/
- Per-model системные промпты (файлы prompt/*.txt) + plan-mode промпты отдельно — удобная инженерия промптов. https://github.com/anomalyco/opencode/tree/dev/packages/opencode/src/session/prompt
- Форки сессии от произвольного messageID, revert/unrevert, event-log с seq — хорошая семантика ветвления диалога. https://opencode.ai/docs/server/
- Skills — совместимы с Claude-стандартом (`.claude/skills`), ленивая загрузка через инструмент. https://opencode.ai/docs/skills/

### Слабые
- Однопользовательский local-first: сессии в SQLite/JSON на конкретной машине; центрального хранилища, мульти-тенантности, распределённых локов нет — наш сценарий «PostgreSQL + ShedLock» OpenCode не покрывает. https://opencode.ai/docs/server/ , https://github.com/anomalyco/opencode/blob/dev/packages/core/src/database/database.ts
- Auth только HTTP Basic на весь сервер; без пароля — открытый сервер; per-user/RBAC не найдено. https://opencode.ai/docs/server/
- Фоновые задания не переживают рестарт процесса и намеренно так спроектированы (см. цитату в §4) — никаких durable tasks. https://github.com/anomalyco/opencode/blob/dev/packages/core/src/background-job.ts
- Плагины in-process без изоляции: краш плагина валит сервер; обновление = рестарты; Bun-кэш импортов делает сбои перманентными; нет доступа к Storage кроме SDK. https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/plugin/loader.ts
- Workflow-движка, планировщика, webhook-триггеров в ядре нет — оркестрация вынесена в GitHub Actions/cron вне продукта. https://opencode.ai/docs/github/
- Share = публичная ссылка с данными на серверах вендора (CDN-edge кеш) — для корпоративного использования предлагается просто отключать. https://opencode.ai/docs/share/ , https://opencode.ai/docs/enterprise/
- Миграционная нестабильность хранилища: двойной ярус JSON+SQLite, недавние миграции формата (storage-миграции, session_input переписывался трижды за июнь 2026) — формат не является стабильным контрактом. https://github.com/anomalyco/opencode/tree/dev/packages/core/src/database/migration
- Windows — второсортная платформа: «For the best experience, run `opencode web` from WSL rather than PowerShell». https://opencode.ai/docs/web/

---

## 10. Выводы: что перенять, чего избегать

### Перять
1. **OpenAPI 3.1 как единственный контракт**: живой `/doc`-эндпоинт, SDK и типы клиентов генерируются из спеки; клиент (TUI/web/IDE) никогда не ходит в ядро напрямую. Для нас: OpenAPI contract-first + генерация клиентов из той же спеки. https://opencode.ai/docs/server/ , https://opencode.ai/docs/sdk/
2. **Attach-протокол**: тонкий клиент = URL сервера + basic auth + SSE; никакого состояния на клиенте. Наш REST API должен позволять «подключиться с любого ПК» именно так (но с token/OIDC-auth вместо basic). https://opencode.ai/docs/cli/
3. **Событийный будильник сессий вместо поллинга**: `session_input` (admitted/promoted seq) + RunCoordinator `wake()` c коалесцированием. Поверх нашего PostgreSQL: триггер/NOTIFY на вставку входящего сообщения + коалесцирующий wake экономит 1–5-секундный скан; ShedLock остаётся для распределённой гарантии single-runner. https://github.com/anomalyco/opencode/blob/dev/packages/core/src/session/run-coordinator.ts
4. **SSE `/event` с первым событием `server.connected`** — дешёвая замена WebSocket для стриминга статусов/частей сообщений всем клиентам. https://opencode.ai/docs/server/
5. **Permissions: glob-паттерны на имена инструментов и bash-команды, last-match-wins, персистентные «always» per project** — готовая семантика для наших разрешений агентов. https://opencode.ai/docs/agents/
6. **MCP OAuth**: auto-detect 401 + DCR RFC 7591 + pre-registered creds + `{env:}`-подстановки + debug CLI — прямо ложится на наши 12+ MCP за OAuth2-прокси. https://opencode.ai/docs/mcp-servers/
7. **Per-model промпт-файлы и отдельные plan-промпты**; скрытые системные агенты (compaction/title/summary) как обычные конфиги агентов. https://github.com/anomalyco/opencode/tree/dev/packages/opencode/src/session/prompt
8. **Форк сессии от messageID + revert/unrevert** — семантика ветвления для наших сессий в PostgreSQL. https://opencode.ai/docs/server/
9. **Управление TUI через серверные эндпоинты** (`/tui/*`) — паттерн для нашего веб-клиента и IDE-интеграций. https://opencode.ai/docs/server/

### Избегать
1. **In-process плагины**: нет изоляции, рестарт-only обновления, «двойной перезапуск», перманентность неудачных импортов, отсутствие доступа к хранилищу. Для нас: расширения как изолированные процессы/контейнеры с own-lifecycle, версионируемые без рестарта ядра, с доступом к данным через API/БД-слой по правам. https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/plugin/loader.ts
2. **Недолговечные фоновые задания**: process-local registry теряет всё при рестарте («intentionally not durable»). Для нас: durable job table в PostgreSQL + recovery при старте сервера. https://github.com/anomalyco/opencode/blob/dev/packages/core/src/background-job.ts
3. **Двойное хранилище (JSON + SQLite) с живыми миграциями формата**: один источник правды (PostgreSQL), версионированные миграции, стабильный экспортный формат. https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/storage/storage.ts
4. **HTTP Basic без пользователей**: multi-user auth (SSO/OIDC), scoped токены, RBAC на сессии/проекты с первого дня. https://opencode.ai/docs/server/
5. **Публичный share без enterprise-стороны**: любые ссылки шаринга — с авторизацией и аудитом; self-hosted вариант обязателен. https://opencode.ai/docs/share/
6. **Вынос оркестрации наружу (GitHub Actions)**: у нас триггеры (webhooks, cron, системные состояния) должны быть частью сервера с сохранением состояния переходов в БД, а не скриптами в CI. https://opencode.ai/docs/github/

---

## Приложение: ключевые файлы репозитория для справки

| Область | Файл |
|---|---|
| JSON-storage (легаси) | `packages/opencode/src/storage/storage.ts` |
| SQLite (путь, прагмы, миграции) | `packages/core/src/database/database.ts` |
| Схемы таблиц сессий/сообщений/инбокса | `packages/core/src/session/sql.ts` |
| Координация выполнения сессий | `packages/core/src/session/run-coordinator.ts` |
| Фоновые задания | `packages/core/src/background-job.ts`, `packages/opencode/src/background/job.ts` |
| Загрузчик плагинов | `packages/opencode/src/plugin/loader.ts` |
| Публичный API плагинов | `packages/plugin/src/index.ts` |
| Системные промпты | `packages/opencode/src/session/prompt/*.txt` |
| Промпт Task-инструмента | `packages/opencode/src/tool/task.txt` |
| Спека вычистки легаси-БД | `specs/storage/remove-opencode-db.md` |
| XDG-пути | `packages/core/src/global.ts` |

Все пути — ветка `dev` репозитория https://github.com/anomalyco/opencode .
