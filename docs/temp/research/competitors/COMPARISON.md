# Сводный конкурентный анализ: OpenCode, Paperclip, Pi, Claude Code, Codex

> Синтез пяти отчётов (`docs/research/competitors/{opencode,paperclip,pi,claude-code,codex}.md`), выполненных по общему брифу (`BRIEF.md`) для проекта **spring-harness** (Java 25 / Spring Boot 4.1 / Spring AI 2.0; сервер сессий в PostgreSQL; ShedLock-планировщик с распределённым локом; workflow-движок с графом состояний; агент-оркестратор «CEO»; вебхук-триггеры; attach-клиенты; REST/OpenAPI contract-first).
> Дата: 2026-09-15. Ссылки в матрицах — на секции исходных отчётов вида `«отчёт §N»` (внутри них — первичные URL); в разделах 3–5 — прямые первичные ссылки. Ничего сверх отчётов не выдумано; расхождения отчётов помечены отдельно.

---

## 0. Паспорт участников (одной строкой)

| Продукт | Что это | Центральная метафора |
|---|---|---|
| **OpenCode** (Anomaly) | терминальный AI-кодинг-агент с встроенным OpenAPI-сервером | «TUI — клиент собственного HTTP-сервера» (opencode.md §1) |
| **Paperclip** (Paperclip Labs) | open-source оркестрация «команды агентов», Node.js+React+Postgres | «If OpenClaw is an employee, Paperclip is the company» (paperclip.md §0) |
| **Pi** (Earendil) | минималистичный модульный coding-harness | «ядро без MCP/субагентов/permissions — всё расширениями» (pi.md §0) |
| **Claude Code** (Anthropic) | флагманский терминальный агент + SDK + cloud | «один engine, много поверхностей» (claude-code.md §1) |
| **Codex** (OpenAI) | Rust-CLI + app-server + cloud tasks | local-first + JSON-RPC app-server для rich clients (codex.md §1) |

Ключевое разделение: **Paperclip — единственный «серверный оркестратор»** (Postgres, multi-tenant, governance); OpenCode — «персональный сервер»; Pi/Claude Code/Codex — локальные агенты с разными степенями remote/web-надстроек.

---

## 1. Функциональная матрица

Легенда ссылок: `О` = opencode.md, `P` = paperclip.md, `П` = pi.md, `CC` = claude-code.md, `X` = codex.md, номер — секция отчёта.

### 1.1 Модель сессий и хранение

| Продукт | Факт | Ссылка |
|---|---|---|
| OpenCode | Локально: SQLite `opencode.db` (WAL, foreign_keys) + легаси JSON-файлы, миграция «файлы→SQLite» в разгаре; сессии event-sourced (таблицы `session/message/part/session_message`); форк от messageID, revert/unrevert | О §1–2 |
| Paperclip | **PostgreSQL (Drizzle)**: `issues`, `heartbeat_runs(+events)`, `agent_wakeup_requests`, `cost_events` и десятки таблиц; heartbeat-run конечен («starts, performs work, records terminal result, exits»); session-state частично у адаптеров (признанная слабость) | P §1–2 |
| Pi | JSONL-дерево записей (`id`/`parentId`, ветвление без новых файлов); durable-контракт `SessionRepo/Session/Storage` с батчевыми коммитами и mutation barrier; SQLite-бэкенд как эталон замены | П §2 |
| Claude Code | JSONL в `~/.claude/projects/<project>/`; формат «internal, меняется между версиями»; resume/fork/branch; SDK умеет зеркалировать во внешнее хранилище (S3/Redis) | CC §2 |
| Codex | Треды/turns/items; rollout-файлы + `history.jsonl` + SQLite (`sqlite_home`) для resumable-состояния; `thread/fork`, archive, rollback | X §2 |
| **spring-harness (план)** | Все сессии в PostgreSQL, единая модель сессии/сообщения; REST по OpenAPI; конкурентность через распределённый лок | BRIEF.md |

### 1.2 Роуминг / attach / мульти-клиент

| Продукт | Факт | Ссылка |
|---|---|---|
| OpenCode | `opencode attach http://host:port` (TUI к чужому серверу), `opencode web/serve`, mDNS; web+TUI одновременно разделяют сессии; auth — только HTTP Basic | О §2, §6 |
| Paperclip | Команды attach нет; удалённый доступ — REST + live-events WebSocket, mobile-прокси, tailnet/tailscale-HTTPS; multi-tenancy company-scoped | P §2 |
| Pi | Remote-attach нет в OSS; pi-server экспериментальный (Unix-socket, CBOR, идемпотентный `attachClient`, `attachmentId`) | П §1, §2.4 |
| Claude Code | Remote Control (secure relay + pairing-код), cloud sessions `--cloud/--teleport`, agent view (attach/peek к фоновым) | CC §1, §2 |
| Codex | `codex --remote ws://…|unix://…` (attach к app-server), remote-control daemon + pairing через relay, SSH-режим, handoff треда между хостами | X §2 |
| **spring-harness (план)** | Удалённые attach-клиенты («аналог `opencode attach`»), подключение с любого ПК, роуминг офис/дом | BRIEF.md |

### 1.3 Workflow / оркестрация

| Продукт | Факт | Ссылка |
|---|---|---|
| OpenCode | Workflow-движка нет; plan mode + todo; оркестрация вынесена в GitHub Actions (включая cron-`schedule`) | О §5 |
| Paperclip | Иерархия Workspace→Initiative→Project→Milestone→Issue→sub-issue; статусы = team-specific наборы в **фиксированных категориях**, без явного графа переходов; atomic checkout, single-assignee XOR-инвариант, blockers first-class, Courier pattern, liveness-контракт, watchdogs/recovery | P §5 |
| Pi | Движка нет осознанно («write plans to files»); steering/followUp-очереди; durable-операция с чекпоинтами как заменитель | П §5 |
| Claude Code | Dynamic workflows (JS-скрипт `agent()/pipeline()/parallel()`; до 16 конкурентных/1000 агентов, без mid-run input), agent teams (shared task list + messaging), plan mode | CC §5 |
| Codex | Движка нет: `update_plan` (рекомендательный), goals, multi-agent v1/v2 (`spawn/wait/send/close`, task paths), cloud tasks | X §5 |
| **spring-harness (план)** | Workflow-движок: кастомные состояния + разрешённые переходы (граф), сессия+назначенный агент на состояние, терминальные Done/Cancelled/Error, системные состояния (вебхук-ожидание, bash) | BRIEF.md |
| **Вывод** | Явного графа состояний с декларативными переходами нет **ни у одного** — это наше дифференцирующее преимущество (подтверждает и P §9: «не считать статусы issue полноценным workflow») | |

### 1.4 Модель агента: роли / субагенты

| Продукт | Факт | Ссылка |
|---|---|---|
| OpenCode | Primary (build/plan) + subagent (general/explore/scout) + скрытые системные (compaction/title/summary); конфиг JSON/MD; `permission.task` glob-ограничение найма | О §3 |
| Paperclip | Агент = «сотрудник»: adapter config + роль + reporting line + capabilities; org-chart делегация CEO→CTO→QA; найм агентом через skill `paperclip-create-agent` + board approval; built-in реестр по ключу | P §3 |
| Pi | Ролей/субагентов в ядре нет («No sub-agents») — строятся расширениями | П §3 |
| Claude Code | Subagents с frontmatter (tools/model/permissionMode/maxTurns/skills/mcpServers), встроенные Explore/Plan/general-purpose; agent teams lead; cross-session messaging | CC §3 |
| Codex | Субагенты default/worker/explorer + кастомные TOML-агенты (`name/description/developer_instructions/model/sandbox_mode/mcp_servers`); лимиты `max_threads`=6, `max_depth`=1; `approvals_reviewer`-агент | X §3 |
| **spring-harness (план)** | Агент = роль (описание) + инструменты + разрешения + навыки; оркестратор «CEO» инструментально создаёт workflow/назначает задачи/триггеры | BRIEF.md |

### 1.5 Skills

| Продукт | Факт | Ссылка |
|---|---|---|
| OpenCode | `SKILL.md` (frontmatter name+description), лениво через инструмент `skill` со списком `<available_skills>`; совместимость `.claude/skills`, `.agents/skills`; права `permission.skill` с wildcard | О §3 |
| Paperclip | `SKILL.md` + shortname-резолв (локально → referenced → company library); Skill Studio (версии, fork, test-runs, каталог); runtime-инъекция core-skills | P §3–4 |
| Pi | Стандарт Agent Skills (agentskills.io): `SKILL.md` в `~/.pi/agent/skills/`, `.pi/skills/`, `.agents/skills/`; `/skill:name` или автозагрузка | П §3 |
| Claude Code | SKILL.md-стандарт + слияние с командами (`commands/deploy.md` ≡ `skills/deploy/SKILL.md`); frontmatter `allowed-tools`, `context: fork`, `background`, hooks | CC §3 |
| Codex | Agent skills standard + **progressive disclosure** (сначала name+description ~2% окна, тело при выборе); области REPO/USER/ADMIN/SYSTEM; `agents/openai.yaml` c policy | X §3 |
| **spring-harness (план)** | Навыки (skills) как часть определения агента | BRIEF.md |

### 1.6 Инструменты: синхронные / асинхронные / фоновые

| Продукт | Факт | Ссылка |
|---|---|---|
| OpenCode | Синхронные builtin (bash/edit/read/grep/…); асинхронный ввод `POST prompt_async` (204, результат через SSE); BackgroundJob-реестр **намеренно недолговечный** («process restart ... loses status») | О §4 |
| Paperclip | Своих инструментов не поставляет — MCP-шлюз + семантические действия (`create_task`, `set_dependencies`); фоновость = durable-модель: continuations, one-shot monitors (`nextCheckAt`), wake-запросы; «unmanaged local process не считается liveness» | P §4 |
| Pi | `read/bash/powershell/edit/write/grep/find/ls`; отложенный результат: `pi.sendMessage({deliverAs: steer|followUp|nextTurn, triggerTurn:true})` будит простаивающего агента; `stopReason:"deferred"` + `DeferredHandle`; `terminate:true`-хинт; parallel/sequential execution | П §4 |
| Claude Code | `run_in_background` у Bash; **авто-перенос в фон по timeout** («moved to the background» + task ID + output-файл); авто-фон MCP-вызовов >2 мин (`CLAUDE_CODE_MCP_AUTO_BACKGROUND_MS`) с task-notification; Monitor (строки вывода → события сессии); Cron*/ScheduleWakeup | CC §4 |
| Codex | Unified PTY-exec (`exec_command`+`write_stdin`), background terminals с `background_terminal_max_timeout`; субагенты как долгоживущие треды (`wait_agent` с таймаутом); `spawn_agents_on_csv` fan-out; approvals как server→client запросы с `acceptForSession` | X §4 |
| **spring-harness (план)** | Асинхронные инструменты с отложенным возвратом результата — явное требование брифа | BRIEF.md |
| **Вывод** | Зрелые паттерны отложенного результата: инжект сообщения с пробуждением (Pi), task-notification (CC), durable-monitor (P), server→client approval (X) | |

### 1.7 MCP

| Продукт | Факт | Ссылка |
|---|---|---|
| OpenCode | Только клиент; stdio+remote HTTP; OAuth с **Dynamic Client Registration (RFC 7591)** и pre-registered creds, `{env:}`-подстановки; токены в `mcp-auth.json`; динамический `POST /mcp`; признанная боль — раздувание контекста | О §7 |
| Paperclip | **Двойная роль**: MCP-endpoint (вход для внешних клиентов) + MCP-gateway (исходящие вызовы с governance); профиль×политика×approval×trust-rule×audit; risk-классы read/write/destructive; changed-tool quarantine; approval = `409` + retry с `approvedActionRequestId` + сверка argument-hash | P §7 |
| Pi | MCP нет из философии («CLI tools with READMEs»); добавляется расширением | П §7 |
| Claude Code | 4 транспорта (stdio/http/sse/ws); 3 скоупа + managed; OAuth через `/mcp`; **tool search** для тысяч инструментов; resources/prompts/elicitation; `list_changed` | CC §7 |
| Codex | Только клиент (MCP-сервер продукта **удалён**); stdio+Streamable HTTP; OAuth `codex mcp login` (callback port/url, keyring, `scopes_supported`, `oauth_resource` RFC 8707); per-tool `approval_mode`; известные issues: stale refresh token (#14144), отмена вызовов под sandbox | X §7 |
| **spring-harness (план)** | 12+ MCP-серверов за корпоративным OAuth2-прокси (SSO, токены 24 ч) — клиентская роль обязательна | BRIEF.md |

### 1.8 Расширения / плагины

| Продукт | Факт | Ссылка |
|---|---|---|
| OpenCode | In-process TS/JS (Bun-импорт); загрузка только на старте; Bun кэширует неудачные импорты → обновление = рестарты («двойной перезапуск»); доступ только через SDK-клиент, **Storage Layer недоступен**; широкие hooks (`tool.execute.*`, `chat.params`, компакция) | О §8 |
| Paperclip | Out-of-process worker-плагины по **stdio JSON-RPC 2.0**; манифест+capabilities+health-check; свои HTTP-роуты в namespace; 14 типизированных UI-слотов (UI не sandboxed — «trusted code»); adapter-плагины с hot-reload | P §8 |
| Pi | Extensions (TS) с hot-reload `/reload` и project-trust; `registerTool` (замена builtin), `registerProvider` (свой OAuth-провайдер), блокирующий хук `tool_call {block, reason, terminate}`; pi-packages из npm/git | П §8 |
| Claude Code | Plugins = marketplace-пакеты (skills+agents+hooks+MCP+commands+workflows+monitors); hooks: command/http/mcp_tool/prompt/agent-хендлеры, ~30 событий, блокировки на Pre-фазах; `/reload-plugins` для изменений hooks/MCP/agents | CC §8 |
| Codex | Plugins (marketplace, бандлят skills/MCP/apps) + hooks (`PreToolUse/PostToolUse/...`, могут блокировать/переписывать); `.rules` Starlark; `requirements.toml` для админ-контроля | X §8 |
| **spring-harness (план)** | Механизм расширений в брифе не специфицирован — открытый вопрос дизайна | — |

### 1.9 Governance / бюджеты / разрешения

| Продукт | Факт | Ссылка |
|---|---|---|
| OpenCode | Permissions `ask/allow/deny` + glob-паттерны (bash-команды, имена инструментов `mcp_*`), **last-match-wins**, персистентные «always» per project; бюджетов нет (только `stats`) | О §3, §6 |
| Paperclip | Полный контур: бюджеты (`cost_events` ledger + `budget_incidents`, soft 80%/hard 100% с graceful cancel активных run), approvals (board/entity-level), trust presets, RBAC (Better Auth, Agent JWT per instance+company), append-only audit tool_call_events | P §6–7, §9 |
| Pi | Permission-системы нет — только хук `tool_call` как точка входа для гейтов; изоляция контейнерами | П §1, §9 |
| Claude Code | `Tool(specifier)` правила, порядок **deny→ask→allow**, первое совпадение; режимы Manual/acceptEdits/plan/auto/dontAsk/bypass; sandboxing как реальная граница (текстовый матчинг «fragile»); managed settings/MCP | CC §3, §9 |
| Codex | `approval_policy` (untrusted/on-request/never/granular) + `approvals_reviewer` (auto-review субагент); `sandbox_mode` + OS-sandbox (Seatbelt/bwrap/Windows); execpolicy-префиксы; `requirements.toml` админ-allowlists | X §3, §7 |
| **spring-harness (план)** | Агент = роль + инструменты + **разрешения** + навыки; бюджеты в брифе не заявлены | BRIEF.md |
| **Противоречие** | Семантики правил расходятся: OpenCode — «последнее совпавшее правило побеждает», Claude Code — «первое совпавшее решает» при порядке deny→ask→allow. Для нас выбрать одну семантику и зафиксировать | О §3, CC §3 |

### 1.10 Триггеры / вебхуки

| Продукт | Факт | Ссылка |
|---|---|---|
| OpenCode | В ядре нет; GitHub-агент через Actions: `issue_comment`/`pull_request_review_comment`/`issues`/`pull_request`/**`schedule` (cron)**/`workflow_dispatch` | О §5 |
| Paperclip | **Routines** с cron/webhook/API-триггерами (каждый прогон — tracked issue + пробуждение агента); monitors с `check-now` | P §4–5 |
| Pi | Триггеров нет; внешний паттерн — Absurd durable turns (`message_end` → step-log → `runAgentLoopContinue`) | П §5 |
| Claude Code | **Channels** — MCP-сервер с capability `claude/channel` пушит события/алерты/вебхуки прямо в сессию; scheduled tasks (Cron*, `/loop`, ScheduleWakeup); GitHub Action | CC §5 |
| Codex | GitHub `@codex`, GitHub Action, Slack/Linear-интеграции, GitLab beta, cloud Automations (scheduled) | X §5 |
| **spring-harness (план)** | Вебхуки GitHub/GitLab создают задачи по workflow; отдельный тип — постоянные сессии с входящими событиями | BRIEF.md |

### 1.11 Планировщик / пробуждение сессий

| Продукт | Факт | Ссылка |
|---|---|---|
| OpenCode | `session_input`-инбокс (admitted/promoted seq) + **RunCoordinator**: per-session сериализация, `wake()` коалесцирует follow-up («one coalesced follow-up after newly recorded work»), `interrupt`; чисто event-driven, без таймера | О §2 |
| Paperclip | **Durable continuation scheduler**: интервал 30 с (мин 10 с); намерение реконструируется из БД (статусы, wake-запросы, мониторы, retry-таймстампы) — рестарт не теряет работу; stranded-issue reconciliation на старте; урок DOT-2: «fix the decision, don't increase the polling interval» | P §2, §5 |
| Pi | Серверного планировщика нет; inbox durable-операции (`steer|followUp|nextRun|write`) + чекпоинты; конкурентность: «host гарантирует одного writable-owner», БД локов не делает | П §2.2, §2.3 |
| Claude Code | Локальный supervisor для фоновых сессий (переживает закрытие терминала и сон машины); scheduled tasks восстанавливаются при resume | CC §1, §2 |
| Codex | Серверного планировщика нет; app-server выгружает тред без подписчиков после grace-периода | X §2 |
| **spring-harness (план)** | Вытесняющий планировщик на ShedLock: скан таблицы сессий раз в 1–5 с; лок по ключу сессии; запуск только при новых сообщениях и свободном локе | BRIEF.md |
| **Вывод** | Все трое «серверных» (O/P/П) сходятся к модели «входящий ящик + событийное пробуждение + восстановление из БД», а не к чистому поллингу | |

---

## 2. Сравнение API-поверхности

| Критерий | OpenCode | Paperclip | Pi | Claude Code | Codex |
|---|---|---|---|---|---|
| **Протокол** | HTTP REST + SSE (О §6) | HTTP REST (`/api`) + 3 WebSocket-канала (live-events, PRP-runner, terminal) (P §6) | JSONL-RPC по stdin/stdout; новый CBOR/Unix-socket (эксперим.) (П §6) | CLI subprocess + NDJSON `stream-json`; сетевых SSE/WS для сессии нет (CC §6) | JSON-RPC 2.0 app-server: stdio-JSONL / WS (эксперим.) / Unix-socket (X §6) |
| **Контракт-подход** | **OpenAPI 3.1 spec-first**: живой `GET /doc`, SDK генерируется из спеки (О §1, §6) | Code-first: Zod-схемы → `GET /api/openapi.json` (P §6) | Нет формального контракта; SDK-типы TS (П §6) | Нет публичного контракта; SDK-типы + NDJSON-схема событий (CC §6, §9) | Протокол-first: `generate-ts`, `generate-json-schema` из Rust-описаний (X §6) |
| **Стриминг** | SSE `/event` (первое событие `server.connected`) + `/global/event` (О §6) | live-events WS для UI; board-chat stream; PRP-WS для runner (P §6) | События RPC/SDK (`message_*`, `tool_execution_*`) (П §6) | `stream-json` NDJSON по stdout (CC §6) | Нотификации `thread/*`, `item/*/delta` в JSON-RPC (X §6) |
| **Auth** | HTTP Basic (username/password env); без пароля сервер открыт (О §6) | Better Auth cookie; Board API key (Bearer); Agent API key; **Agent JWT** HS256 c per-instance/per-company изоляцией (P §6) | Отсутствует («application policy») (П §1) | API key / OAuth claude.ai; для SDK — API key (CC §6) | ChatGPT OAuth / API key / workload identity; WS-токены (capability/signed-bearer) (X §6) |
| **SDK** | `@opencode-ai/sdk` из OpenAPI; `createOpencode()`/`createOpencodeClient()`; structured output json_schema (О §6) | CLI `paperclipai` (60+ команд); UI-клиент; SDK как продукт не выделен (P §6) | `@earendil-works/pi-coding-agent` SDK (SessionManager, ModelRuntime…) (П §6) | Agent SDK Python/TS; Java-SDK нет — только subprocess `-p` (CC §6) | `@openai/codex-sdk` (TS), `openai-codex` (Python) поверх app-server (X §6) |
| **Качество контракта (оценка отчётов)** | Эталон contract-first; но auth примитивен (О §9) | Функционально богатейший, но **300+ путей в одном файле** — антипаттерн объёма; идемпотентность-fingerprints есть (P §6, §9) | Протокол продуман (LF-фрейминг, id-корреляция, курсоры), но без версионных гарантий (П §9) | Контракт не публичный; «server REST API не найдено» (CC §9) | Чистый JSON-RPC с `initialize/capabilities`; много experimental-флагов (X §6, §9) |

Для spring-harness: ближе всего целевой образ — OpenCode (spec-first OpenAPI + SSE + генерация SDK), по auth — Paperclip (Scoped-токены/JWT per tenant), по объёму — дисциплина «не как Paperclip».

---

## 3. Паттерны к перенятию (приоритизировано)

1. **Durable-планировщик: намерение в БД + wake-запросы + recovery-скан.** У Paperclip — `agent_wakeup_requests` + реконструкция намерения из персистентных записей, рестарт не теряет работу (https://github.com/paperclipai/paperclip/blob/master/doc/architecture/durable-continuation-scheduler.md); у OpenCode — `session_input`-инбокс + коалесцирующий `wake()` (https://github.com/anomalyco/opencode/blob/dev/packages/core/src/session/run-coordinator.ts); у Pi — inbox durable-операции (`steer|followUp|nextRun|write`). Для нашего ShedLock-сканера: таблица wake-запросов/inbox как источник «есть новые сообщения», коалесцирование, recovery при старте.
2. **Разделение ownership-лока и живого выполнения.** Paperclip: `checkoutRunId` (владение правами исполнения) ≠ `executionRunId` (живой run); `409` = реальный конфликт; crash-safe реконструкция (https://github.com/paperclipai/paperclip/blob/master/doc/execution-semantics.md). Прямо ложится на наш «распределённый лок по ключу сессии».
3. **Liveness-контракт для системных состояний.** Paperclip: явный список допустимых action-path primitives; prose-only «blocked» → `needs_attention`, а не молчаливо-здоровое состояние (там же). Готовая спецификация для наших состояний «вебхук-ожидание с таймаутом» / «bash-скрипт» / «кривой payload → сессия-разборщик».
4. **Асинхронные инструменты с отложенным результатом — три совместимых механики:** мгновенный toolResult + инжект сообщения с `triggerTurn` по готовности (Pi, https://github.com/earendil-works/pi/blob/master/packages/coding-agent/docs/extensions.md); авто-перенос в фон + task-notification (Claude Code, https://code.claude.com/docs/en/mcp); approval как server→client запрос с `acceptForSession` (Codex, https://developers.openai.com/codex/app-server). Для нас: результат асинхронного инструмента = новое входящее сообщение сессии (будит планировщик).
5. **OpenAPI 3.1 как единственный контракт + генерация SDK.** OpenCode (https://opencode.ai/docs/server/); идея подтверждена Codex (`generate-ts`/`generate-json-schema`). Наш contract-first — верный курс; клиент никогда не ходит в ядро напрямую.
6. **SSE-шина `/event` с первым событием `server.connected`.** OpenCode (https://opencode.ai/docs/server/) — дешёвая альтернатива WebSocket для стриминга статусов/частей всем attach-клиентам.
7. **MCP-governance: профиль × политика × approval × trust-rule × audit.** Paperclip (https://github.com/paperclipai/paperclip/blob/master/doc/MCP-ACCESS-GOVERNANCE.md): risk-классы read/write/destructive, changed-tool quarantine, approval с exact argument-hash, append-only `tool_call_events`. Плюс OAuth DCR RFC 7591 (OpenCode, https://opencode.ai/docs/mcp-servers/), per-tool `approval_mode` + keyring + `oauth_resource` RFC 8707 (Codex, https://developers.openai.com/codex/mcp), tool search для больших каталогов (Claude Code, https://code.claude.com/docs/en/mcp). Наш кейс: 12+ MCP за OAuth2-прокси.
8. **Модель разрешений `Tool(specifier)` + режимы.** Claude Code: deny→ask→allow, режимы Manual/plan/dontAsk…, read-only fast-path, working directories (https://code.claude.com/docs/en/permissions); OpenCode: glob-паттерны на bash-команды и имена инструментов с last-match-wins (https://opencode.ai/docs/agents/). Для «агент = роль + разрешения».
9. **Курсорная синхронизация + идемпотентный attach.** Pi: `get_entries?since=<entryId>` + `leafId` (https://github.com/earendil-works/pi/blob/master/packages/coding-agent/docs/rpc.md); pi-server: повторный `attach` идемпотентен, `attachmentId` на привязку (https://github.com/earendil-works/pi/blob/master/packages/server/README.md). Готовый паттерн для attach-клиентов с любого ПК.
10. **Контракт сессий «Repo→Session→Storage» + ветвление.** Pi: `create/open/list/delete/fork`, ветки записей, батчевые коммиты, mutation barrier (https://github.com/earendil-works/pi/blob/master/packages/agent/src/harness/session/types.ts); OpenCode: fork от messageID + revert/unrevert (https://opencode.ai/docs/server/); Codex: archive/rollback/compact (https://developers.openai.com/codex/app-server). Основа схемы нашей таблицы сессий.
11. **Бюджеты: ledger + persistent incidents + hard-stop.** Paperclip: `cost_events` + «один незакрытый инцидент на policy×threshold×window», soft 80%/hard 100% с graceful cancel (https://zread.ai/paperclipai/paperclip/12-budget-and-cost-control). Единственный полный референс из пяти.
12. **Идемпотентность через fingerprint + durable claim.** Paperclip: decomposition exact-once по `(sourceIssueId, acceptedPlanRevisionId)`, PRP command-identity reuse/fail-closed (https://github.com/paperclipai/paperclip/blob/master/doc/execution-semantics.md). Для наших workflow-переходов и вебхук-пayload-ключей.
13. **Hooks как формальный жизненный цикл с блокирующими Pre-фазами.** Claude Code: ~30 событий, PreToolUse-блокировка, async-варианты (https://code.claude.com/docs/en/hooks); Pi: `tool_call {block, reason, terminate}`; Codex: managed hooks. Прототип серверных перехватчиков расширений.
14. **Субагент-контракты с лимитами + агенты как конфиг.** Codex: `spawn/wait/send/close`, `max_threads`/`max_depth`, TOML-агенты (https://developers.openai.com/codex/subagents); OpenCode: `permission.task` globs (https://opencode.ai/docs/agents/). Для «CEO» и параллельных задач.
15. **Org-chart делегация + Courier pattern.** Paperclip: делегация по дереву reporting, эскалация блокеров вверх; lateral-координация — создать issue целевому агенту, а не расширять права (https://github.com/paperclipai/paperclip/blob/master/doc/execution-semantics.md). Прямой референс для оркестратора «CEO» из Paperclip (заявлен в брифе как источник идеи).

---

## 4. Антипаттерны и риски (чего избегать)

1. **Каноническое состояние сессий в локальных файлах одного хоста.** Claude Code (JSONL «internal format»), Pi (JSONL/SQLite без локов), Codex (rollout+SQLite), OpenCode (SQLite в профиле): нет конкурентного доступа, распределённого планировщика, аудита (CC §9, П §9, X §9, О §9). Наш ответ: PostgreSQL как единственный источник правды.
2. **In-process плагины без изоляции.** OpenCode: краш плагина валит сервер, обновление = рестарты, Bun-кэш делает сбои перманентными, Storage недоступен (О §8). Paperclip частично решает (out-of-process stdio JSON-RPC), но plugin UI остаётся unsandboxed «trusted code» (P §8). Наш ответ: изолированные процессы/контейнеры с собственным lifecycle.
3. **Недолговечные фоновые задания.** OpenCode BackgroundJob «intentionally not durable» (О §4); Claude Code — зависимость фоновых сессий от локального supervisor (CC §9). Наш ответ: durable job/состояния в БД + восстановление при старте.
4. **Token-heavy heartbeat с полным re-fetch контекста.** Paperclip: каждое пробуждение перечитывает assignments+details+ancestor chains+комментарии; multi-hop CEO→CTO→Engineer умножает расход (P §9-слабости). Наш ответ: incremental-контекст (курсоры) и «контекст живёт в сессии», переходы — точные и дешёвые.
5. **Безбрежная API-поверхность.** Paperclip: 300+ путей в одном openapi.ts (P §6, §9). Наш ответ: дисциплинированные ресурсные группы, курсорная пагинация и лимиты с первого дня (урок Paperclip #2553: 280 issues → крэши и 2-минутные страницы).
6. **Секреты в API-ответах.** Paperclip #1818: `GET .../agents` отдавал `adapterConfig` с plaintext-секретами env (P §9-слабости). Наш ответ: scoped, короткоживущие токены; значения секретов никогда не возвращаются наружу.
7. **Текстовый матчинг команд как security boundary.** Claude Code сам признаёт Bash-правила «fragile», «не security boundary» (обход через `/bin/rm`, `sh -c`) (CC §9); Codex идёт дальше — OS-sandbox (X §3). Наш ответ: разрешения + реальная изоляция исполнения, не только паттерны.
8. **Vendor lock-in: auth/relay/облако у вендора.** Codex — ChatGPT OAuth, cloud на `chatgpt.com/backend-api` (X §9); Claude Code — claude.ai/relay (CC §9); OpenCode — публичный share через opncd.ai CDN (О §9); Pi — риск коммерциализации после смены владельца (П §9). Наш ответ: корпоративный SSO, self-hosted всё, подключаемый LLM-провайдер (LiteLLM-совместимый).
9. **Два поколения API/хранилища параллельно.** Pi: SessionManager-JSONL vs durable Session (П §9); OpenCode: JSON+SQLite с живыми миграциями формата (О §9). Наш ответ: одна модель сессии, версионированные миграции, стабильный экспортный формат.
10. **Промптовая/категорийная «оркестрация» вместо явного графа.** Codex: план рекомендательный, оркестрация в промпте spawn-инструмента (X §5); Paperclip: статусы-категории без декларативных переходов; терминальность есть, графа нет (P §9). Наш ответ: сохранить заявленный граф состояний + разрешённые переходы + терминальные состояния — это подтверждённая всеми пятью «дыра» рынка.
11. **Экспериментальные транспорты без auth по умолчанию.** Codex WS (предупреждение о non-loopback), Pi CBOR-протокол «application policy» (X §6, П §9). Наш ответ: auth на всех транспортам с первого дня.

---

## 5. Следствия для spring-harness

### 5.1 По компонентам

| Компонент | Следствия из находок | Источники |
|---|---|---|
| **Сервер сессий (PostgreSQL)** | Единая схема «сессия-сообщение-часть» (аналог OpenCode `session/message/part` + event-log `session_message`); операции fork/revert/unrevert/compact в API; Pi-контракт Repo→Session→Storage как образец границ слоя; НЕ размазывать state по адаптерам (урок Paperclip #2462); курсорная пагинация всех списков с первого дня | О §2, П §2, P §9 |
| **Планировщик (ShedLock)** | Гибрид: таблица wake-запросов/inbox (источник «новых сообщений») + коалесцирующее пробуждение (OpenCode RunCoordinator) + recovery-скан при старте (Paperclip stranded-reconciliation); разделить «владение» и «живое выполнение» (`checkout`/`execution`-семантика); урок DOT-2: конфликт статуса лечить фиксом решения, не увеличением интервала | О §2, P §2, §5 |
| **Workflow-движок** | Наш граф состояний + терминалы — уникальность подтверждена (нет ни у одного из пяти); добрать: liveness-контракт допустимых путей ожидания (Paperclip), идемпотентность переходов по fingerprint, «кривой payload → сессия-разборщик» оформить как явный error-path состояния, а не exception | P §5, BRIEF |
| **Модель агента** | Агент как конфиг (TOML/MD-фронтматтер — Codex/OpenCode); субагент-лимиты (`max_threads/max_depth`); `permission.task`-ограничение найма; skills с progressive disclosure (~2% окна — Codex); выбрать ОДНУ семантику правил (см. противоречие §1.9) | X §3, О §3, CC §3 |
| **Оркестратор «CEO»** | Референс — Paperclip: делегация по reporting-дереву, эскалация блокеров вверх, Courier pattern для lateral-связей, найм с board-approval, `create_task/set_dependencies` как инструменты; наш CEO сильнее — управляет workflow-графом декларативно, чего нет даже у Paperclip | P §3, §5 |
| **Вебхук-триггеры** | Routines с cron/webhook/API (Paperclip) + channels «пуш события в сессию» (Claude Code) — два образца для «постоянных сессий с входящими событиями»; идемпотентные ключи payload (fingerprint); ожидание вебхука = системное состояние с таймаутом и liveness-контрактом | P §4–5, CC §5 |
| **REST-контракт** | Spec-first OpenAPI 3.1 + живой `/doc` + генерация SDK (OpenCode); SSE `/event` c `server.connected`; auth по образцу Paperclip (scoped-токены, per-tenant JWT), не basic; объём дисциплинированный (не 300 путей) | О §6, P §6 |
| **Attach-клиент** | URL + токен + SSE + курсор `?since=` (Pi/OpenCode); идемпотентный attach с `attachmentId`; несколько клиентов на одну сессию — состояние разделяется через сервер, не через файлы | П §2.4, О §2 |
| **Расширения** | Out-of-process воркеры с манифестом и health-check (Paperclip), НЕ in-process (OpenCode); hooks как формальный список событий с блокирующими Pre-фазами (CC/Pi/Codex); доступ к данным — через API по правам | P §8, О §8, CC §8 |
| **MCP** | При 12+ серверах: OAuth DCR + pre-registered creds + `{env:}` (OpenCode), per-tool approval_mode + `oauth_resource` (Codex), а governance-слой (profile/policy/audit/quarantine) — по Paperclip, объём по потребности | О §7, X §7, P §7 |

### 5.2 Открытые вопросы дизайна

1. **Поллинг vs события.** Бриф фиксирует «скан раз в 1–5 с»; все три серверных референса (O/P/П) демонстрируют inbox + событийное пробуждение. Решить: гибрид (скан как страховка + `NOTIFY`/wake-запись как основной триггер)? Paperclip работает на 30-секундном интервале — наш 1–5-секундный скан агрессивнее всех рассмотренных.
2. **Где живут локи.** Paperclip — в БД (checkout/execution); Pi — «host гарантирует, БД не делает»; у нас ShedLock (внешний распределённый лок). Определить сочетание: ShedLock-лок = ownership; что является execution-идентичностью и как переживать рестарты.
3. **Формат сессии.** Плоские сообщения (OpenCode `message/part`) vs дерево записей с ветвлением на месте (Pi) vs чистый event-sourcing (`session_message`). Дерево даёт дешёвые форки; event-log — аудит и проекции; выбрать до миграций.
4. **Компакция/контекст-эпохи.** OpenCode — `session_context_epoch` против промпт-кеш-инвалидации; Pi — compaction-запись с `firstKeptEntryId`. Решить, как хранить длинные сессии в Postgres и что отдаётся агенту при пробуждении (и как не перечитывать контекст — урок heartbeat Paperclip).
5. **Доставка результата асинхронного инструмента.** Инжект-сообщение с пробуждением (Pi) vs task-notification канал (CC) vs отдельная подсессия. Бриф уже требует «от асинхронных инструментов» как источник новых сообщений — зафиксировать контракт записи-результата.
6. **MCP-governance: глубина.** Полный gateway Paperclip (quarantine, trust-rules) vs тонкий клиент (OpenCode/Codex). 12+ серверов за OAuth2-прокси с 24-часовыми токенами: нужен ли refresh-контур и аудит вызовов уровня `tool_call_events` — решить по требованиям безопасности.
7. **Семантика разрешений.** «Последнее правило побеждает» (OpenCode) vs «первое при deny→ask→allow» (Claude Code) — явное противоречие референсов; выбрать и документировать. Плюс: нужны ли режимы (plan/dontAsk) на уровне платформы.
8. **Мульти-тенантность и RBAC с первого дня?** Paperclip показывает полную модель (company-scoped всё, Agent JWT); но это фактор сложности API. Решить: single-tenant MVP с изолируемыми ключами или сразу per-tenant.
9. **Внешняя поверхность.** Только свой REST или также MCP-endpoint для внешних клиентов (двойная роль Paperclip) и/или SDK? Codex показал обратный путь (удаление MCP-сервера) — не обязательная фича.
10. **Версионирование конфигураций агентов/скиллов.** Paperclip: config-revisions + rollback для агентов, версии/fork для skills. Для «CEO редактирует workflow» нужны ревизии и откат — заложить в схему workflow.

---

## 6. Итоговая формула

- **Ниша пуста:** ни один из пяти не имеет «PostgreSQL-сервер сессий + распределённый планировщик + явный workflow-граф + вебхук-триггеры + attach-клиенты» одновременно. Paperclip ближе всех (оркестрация, Postgres, governance), но без графа переходов и с adapter-размазанным session-state; остальные — локальные инструменты с remote-надстройками.
- **Что собираем:** контракт и attach — у OpenCode; durable-планировщик, локи, liveness, MCP-governance, бюджеты, CEO-делегацию — у Paperclip; контракт сессий и отложенные результаты — у Pi; разрешения, hooks, фоновые задачи — у Claude Code; sandbox, субагент-лимиты, MCP-политики — у Codex.
- **Чего избегаем:** файловое каноническое состояние, in-process плагины, недолговечные фоны, token-heavy пробуждения, безбрежный API, секреты в ответах, текстовые «границы безопасности», vendor lock-in.
