# Конкурентный анализ: OpenClaw (ex-Clawdbot / ex-Moltbot)

> Исследование по общему брифу `docs/research/competitors/BRIEF.md`.
> Объект: OpenClaw — open-source персональный AI-ассистент, работающий 24/7 на машине/VPS
> пользователя и подключённый к каналам сообщений (WhatsApp, Telegram, Discord, Slack и др.).
> Репозиторий: https://github.com/openclaw/openclaw, сайт https://openclaw.ai, доки https://docs.openclaw.ai.
> Ссылки на файлы — относительно корня репозитория (ветка master), если не указано иное.

---

## 0. Общая справка

Создан Питером Штайнбергером как «weekend hack», опубликован в ноябре 2025 как **Clawdbot**
(каламбур на Claude; переименован в **Moltbot** в конце января 2026 после претензий Anthropic,
через дни — в **OpenClaw**). Взрывной рост: >180 000 GitHub-звёзд за считанные недели,
тысячи сторонних skills. Источник: https://www.immersivelabs.com/resources/c7-blog/openclaw-what-you-need-to-know-before-it-claws-its-way-into-your-organization

Суть: self-hosted агент-демон («Gateway»), который слушает входящие сообщения из мессенджеров,
маршрутизирует их в персистентные сессии и исполняет LLM-ходы с инструментами на хосте
(файлы, shell, браузер, календарь). TypeScript/Node, монорепо с pnpm-workspace
(`src/`, `packages/`, `extensions/`, `skills/`, `apps/`, `ui/`).

---

## 1. Архитектура

**Центральный компонент — Gateway**: «local control plane for sessions, tools, events,
and channel connections». Один Gateway на хост, он единственный держит сессии мессенджеров
(например, WhatsApp-сессию Baileys). Источник: `docs/concepts/architecture.md`.

Состав:

- **Gateway-демон**: поддерживает соединения с провайдерами сообщений; типизированный
  WebSocket-API (requests/responses/server-push events); валидация входящих фреймов по
  JSON Schema (схемы генерируются из TypeBox; из JSON Schema генерируются Swift-модели
  для macOS-приложения); события `agent`, `chat`, `presence`, `health`, `heartbeat`, `cron`.
- **Клиенты управления**: macOS-приложение, CLI, TUI, web Control UI — по одному
  WS-соединению на клиента; дефолтный бинд `127.0.0.1:18789`.
- **Nodes** — компаньон-устройства (macOS/iOS/Android/headless): тот же WS-сервер, но
  `role: node` с явными caps/commands (`camera.*`, `screen.record`, `location.get`,
  `canvas.*`); device-based pairing, device-токены, подпись `connect.challenge` nonce
  (v3 биндит platform/deviceFamily).
- **Channels**: WhatsApp (Baileys), Telegram (grammY), Slack, Discord, Signal, iMessage,
  Google Chat, WebChat. Виджеты Canvas/A2UI хостятся HTTP-сервером Gateway на том же порту
  (`/__openclaw__/canvas/`, `/__openclaw__/a2ui/`).

**Wire-протокол** (WS, text-фреймы JSON): первый фрейм обязан быть `connect`; далее
`{type:"req", id, method, params}` → `{type:"res", id, ok, payload|error}` и события
`{type:"event", event, payload, seq?, stateVersion?}`. Для сайд-эффектных методов
(`send`, `agent`) обязательны idempotency keys (короткий dedupe-кэш на сервере).
**События не реплеятся** — клиент обязан перезапрашивать состояние при гэпах.
Источник: `docs/concepts/architecture.md`.

**Аутентификация клиентов**: device identity на `connect` + pairing-апрув новых устройств
(loopback может автоподтверждаться; tailnet/LAN — явный апрув); режимы `gateway.auth.*`:
token / password / trusted-proxy (identity-заголовки) / none (только приватный ingress);
operator scopes (`operator.admin`, `operator.approvals`, `operator.pairing`,
`operator.read`, `operator.write`, `operator.talk.secrets`).
Источники: `docs/concepts/architecture.md`, `docs/gateway/openai-http-api.md`.

**Удалённый доступ**: рекомендуемые варианты — Tailscale/VPN или SSH-туннель
(`ssh -N -L 18789:127.0.0.1:18789`); TLS + опциональный pinning; Cloudflare Access,
trusted-proxy-auth, stable HTTPS URL — отдельные гайды в `docs/gateway/`.

**Агентные рантаймы** — 4-слойная модель: Provider (anthropic/openai/…) → Model →
**Agent runtime** (исполнитель хода) → Channel. Рантаймы:
- `openclaw` — встроенный embedded-рантайм (свой цикл, свои инструменты, своя компакция);
- `codex` — Codex app-server (подписочный ChatGPT/Codex путь), native `/codex` команды;
- `copilot` — внешний плагин на GitHub Copilot CLI;
- `claude-cli` — CLI-бэкенд (запуск локального CLI-процесса);
- ACP-адаптер для внешних харнессов (Claude Code, Gemini CLI, OpenCode, Cursor, Droid).
  В доке Copilot-плагина среди вариантов упоминается и **Pi** как агентный рантайм.
  Источник: `docs/concepts/agent-runtimes.md`.

**Изоляция исполнения**: инструменты по умолчанию исполняются **на хосте**; песочница —
опция (Docker sandbox, `docs/gateway/sandboxing.md`; сравнение подходов —
`docs/gateway/sandbox-vs-tool-policy-vs-elevated.md`; exec-approvals, permission modes).
Источник: корневой `README.md` (Security).

**Хостинг**: ноутбук/homelab/VPS (`docs/vps.md`), Docker (`Dockerfile`,
`docker-compose.yml`), Fly.io (`fly.toml`), Render (`render.yaml`); есть документы
`docs/gateway/cloud-workers.md`, `docs/gateway/cloud-sessions.md`,
`docs/gateway/multi-tenant-hosting.md` (мультиарендный хостинг), `docs/gateway/multiple-gateways.md`.
Supervision — launchd/systemd. Источник: `docs/concepts/architecture.md`, корень репо.

---

## 2. Модель сессий

Источник (если не указано иное): `docs/concepts/session.md`.

**Владелец состояния — Gateway**; UI-клиенты только запрашивают данные у него.

**Маршрутизация входящего сообщения в сессию по источнику:**

| Источник | Поведение по умолчанию |
|---|---|
| Личные сообщения (DM) | Общая сессия (main session) |
| Групповые чаты | Изолированная сессия на группу |
| Rooms/channels | Изолированная на комнату |
| Cron-задачи | Свежая сессия на запуск |
| Webhooks | Изолированная на хук |

- `session.dmScope`: `main` (все DM в одну сессию) | `per-peer` | `per-channel-peer`
  (рекомендовано) | `per-account-channel-peer`. `session.identityLinks` — склейка
  личностей одного человека из разных каналов в один canonical peer id (та же сессия).
- `session.groupScope`: `per-group` (по умолчанию) | `main`; переопределяется
  binding'ами (`bindings: [{agentId, match:{channel, peer}, session:{groupScope}}]`).
- **Main session** — «одна роллинговая беседа, разделяемая всеми DM-каналами», в которую
  могут стекаться групповая активность и фоновые события (`docs/concepts/main-session.md`).
  Это и есть «несколько каналов на одну сессию»: Telegram-DM и WhatsApp-DM ведут одну
  беседу; та же сессия продолжается в Control UI/терминале/coding-харнессе через
  session attachment (`docs/concepts/session-attachment.md`).

**Хранение:**
- Runtime-строки сессий и транскрипты: `~/.openclaw/agents/<agentId>/agent/openclaw-agent.sqlite`
  (per-agent SQLite).
- Архивные транскрипты: `~/.openclaw/agents/<agentId>/sessions/`.
- Миграция с legacy `sessions.json` + JSONL — через `openclaw doctor --fix`.
- Раздельные метки жизни: `sessionStartedAt` (для daily-reset), `lastInteractionAt`
  (для idle-reset; только реальные пользовательские/канальные взаимодействия, не
  heartbeat/cron/exec), `updatedAt` (книжkeeping).

**Жизненный цикл**: сброс вручную (`/new`, `/reset`) или политикой:
`mode: none` (дефолт) | `daily` (`atHour`, по умолчанию 4) | `idle` (`idleMinutes`), плюс
`resetByType` (direct/group/thread) и `resetByChannel`. При ролловере сессии отложенные
system-уведомления старой сессии отбрасываются. Контекст длинных бесед управляется
компакцией (`docs/concepts/compaction.md`), обрезкой tool-результатов (session pruning)
и full-text поиском по прошлым транскриптам (session search).

**Восстановление после рестарта Gateway**: прерванный ход автоматически продолжается;
бюджет — 3 неудачные попытки старта backend-хода (обновляется после реального старта);
если исчерпан — «Resume in new session» в WebChat или `/new`.

**Обслуживание/limits**: `session.maintenance` — `pruneAfter: 30d`,
`archiveDashboardAfter: 7d`, `maxEntries: 500`, `preserveRecent: 7d`; архивные/pinned
сессии защищены от evictions; dry-run через `openclaw sessions cleanup --dry-run`.

**Incognito-сессии**: сессия только в памяти процесса (не пишется на диск, исчезает при
рестарте); видна только admin-scope подключениям.

---

## 3. Очередь и планирование обработки сообщений

Источник (если не указано иное): `docs/concepts/queue.md`. Это самый релевантный нам блок.

**Сериализация**: все inbound auto-reply прогоны (все каналы) проходят через маленькую
in-process lane-aware FIFO-очередь («pure TypeScript + promises», без внешних зависимостей
и воркер-тредов):
- пер-сессионная полоса `session:<key>` — **только один активный прогон на сессию**;
- глобальная полоса `main` — общий параллелизм `agents.defaults.maxConcurrent`
  (дефолт `min(16, max(8, CPU))`); `subagent` — 8;
- фоновые полосы (`cron`, `cron-nested`, `nested`, `subagent`) не блокируют входящие ответы.

**Что происходит с сообщением, пришедшим во время активного прогона** — режимы `/queue`
(дефолт `steer`, дебаунс 500 мс, `cap: 20`, `drop: summarize`):

| Режим | Поведение |
|---|---|
| `steer` (default) | Инжект в активный рантайм на границе (законченный инструмент виден до запуска следующего); идущий инструмент не прерывается; незапущенные sequential-вызовы пропускаются с синтетическими error-результатами (транскрипт остаётся валидным) |
| `followup` | Не вмешиваться: каждое сообщение — в очередь на отдельный ход после текущего прогона |
| `collect` | Слить накопленное в **один** followup-ход после «тихого окна» (дебаунс); сообщения в разные каналы/треды сливаются раздельно |
| `interrupt` | Прервать активный прогон сессии и выполнить новое сообщение |

- Настройка: глобально `messages.queue`, по каналам `byChannel`/`debounceMsByChannel`,
  per-session `/queue <mode> debounce: cap: drop:`; приоритезация: session override →
  byChannel → global mode → `steer`.
- Переполнение очереди: `drop: summarize` (дропнуть старейшие, вжать в синтетический
  followup-промпт) | `old` | `new` (отклонить новое).
- **Отмена ожидания**: Gateway хранит cancel-identity клиентского `runId` для
  взведённых в очередь ходов; `chat.abort` c runId отменяет queued-ход; без runId —
  сначала queued, потом активные прогоны.
- **Долговечность ввода**: обычный ввод Control UI пишется в per-agent БД **до** ack;
  но in-memory очередь **не реплеится** после остановки Gateway — недоставленное до
  транскрипта ввода помечается как interrupted и требует явной пересылки.
- Диагностика зависаний: классификация `session.long_running` / `session.stalled` /
  `session.stuck` с автореанимацией (release lane / active-abort) на heartbeat-тиках.

---

## 4. Модель агента

- **Мультиагентность**: несколько агентов (`agents.entries.*`), у каждого свой workspace,
  скиллы, модель; binding'и маршрутизируют каналы/пиров на агентов; `session.scope: global`
  не смешивает разных агентов — команды/навыки/ответы остаются у выбранного агента.
  Источник: `docs/concepts/session.md`, `docs/concepts/multi-agent.md`.
- **Субагенты**: спавн через инструмент `sessions_spawn` (child session), трекинг как
  background task; есть параллельные specialist lanes и delegate-архитектура
  (`docs/concepts/parallel-specialist-lanes.md`, `docs/concepts/delegate-architecture.md`,
  `docs/tools/subagents.md`, `docs/tools/swarm.md`).
- **Промпт-структура**: системный промпт собирается из workspace-файлов и памяти
  (`MEMORY.md`, `memory/*.md`, user model, soul — `docs/concepts/soul.md`,
  `docs/concepts/user-model.md`, `docs/concepts/memory.md`), eligible skills компилируются
  в компактный XML-блок `<available_skills>` в системном промпте (~24 токена/скилл).
  Источник: `docs/tools/skills.md`.
- **Heartbeat** — периодические «пустые» ходы main-сессии для проактивности (проверка
  задач, уведомлений); heartbeat-прогоны идут в ограниченной полосе `cron-nested`
  и не создают task-записей. Источник: `docs/gateway/heartbeat.md`, `docs/concepts/queue.md`.
- **Память**: провайдеры памяти (builtin / Honcho), active memory, recall между
  разговорами (`memory.search.rememberAcrossConversations`), «dreaming» — фоновая
  консолидация (`docs/concepts/dreaming.md`).

### Skills (формат и механика)

Источник: `docs/tools/skills.md`.

- **Формат**: каталог с `SKILL.md` (YAML frontmatter + markdown body); спецификация —
  [AgentSkills](https://agentskills.io) (та же, что у Pi). Обязательны `name` и
  `description`; `{baseDir}` в теле — путь к папке навыка.
- **Источники и приоритет** (выше → ниже): workspace `skills/` → project
  `.agents/skills` → personal `~/.agents/skills` → managed `<state-dir>/skills` →
  bundled (+ Custodian) → extra dirs + plugin skills. Имя/слэш-команда берутся из
  frontmatter `name`, а не из пути.
- **Gating** (`metadata.openclaw`): `requires.bins/anyBins/env/config`, `os`-фильтр,
  `always`, `primaryEnv`, installer specs (brew/node/go/uv/download c sha256).
- **Per-agent allowlists**: `agents.defaults.skills` + `agents.entries.*.skills`
  (непустой список — финальный набор, не мержится); применяется к промпту, слэш-командам,
  sandbox sync, снапшотам. Явно указано: это **не** shell-авторизация — `exec`
  ограничивается отдельно (sandbox/OS-user/deny-list).
- **Секреты**: `skills.entries.<name>.env` / `apiKey` инжектятся в host-процесс только
  на время хода агента (в sandbox не попадают).
- **Снапшоты**: eligible-список фиксируется на старте сессии; mid-session refresh —
  watcher'ом по изменению `SKILL.md` (дебаунс 250 мс) или подключением ноды.
- **Вызов**: `$skill-name` в промпте (до 8 ссылок на сообщение; `disable-model-invocation`
  прячет от модели), `/name` — слэш-команда (возможен `command-dispatch: tool` —
  прямой вызов инструмента без модели).
- **ClawHub** — публичный реестр (`https://clawhub.ai`): `openclaw skills install
  @owner/<slug> | git:… | ./local`, trust envelope `clawhub.skill.verify.v1`,
  сканирование VirusTotal/ClawScan/static analysis, `openclaw skills verify`.
- **Skill Workshop**: агент предлагает новые навыки как proposals в очередь — оператор
  ревьюит и применяет (`openclaw skills workshop …`) — агент не пишет в SKILL.md напрямую.
- **Node-hosted skills**: подключённая нода публикует свои skills (исполнение через
  `exec host=node`).

---

## 5. Инструменты

- **Встроенные** (каталог `docs/tools/`): `exec` (shell, в т.ч. `host=node`),
  apply-patch, browser control (CDP, отдельные гайды по логину/WSL2/удалённому CDP),
  web_fetch, веб-поиск (много провайдеров: Brave/Perplexity/Tavily/Exa/…), message
  (отправка в каналы), sessions/sessions_spawn, session_search, memory, cron,
  image/music/video_generate, tts, pdf, code execution / code-mode, canvas, tasks,
  tool-search и др. Полный список — `docs/tools/index.md`.
- **Синхронные vs фоновые**: обычные инструменты синхронны в ходе агента. Фоновые —
  субагенты, ACP-прогоны, automation jobs, медиагенерация — возвращаются сразу как
  **background task**, а результат доставляется потом (см. §6). Для session-backed
  медиагенерации действует guardrail от дублей: повтор того же промпта возвращает статус
  активной задачи, а не запускает новую. Источник: `docs/automation/tasks.md`.
- **Политики инструментов**: tool profiles, per-tool policy, permission modes,
  exec-approvals (подтверждение shell-команд), elevated-режим —
  `docs/tools/permission-modes.md`, `docs/tools/exec-approvals.md`,
  `docs/gateway/sandbox-vs-tool-policy-vs-elevated.md`.

---

## 6. Оркестрация / воркфлоу

- **Automations (cron)**: `docs/automation/cron-jobs.md` — задачи по расписанию; запуск
  в main-сессии или изолированной сессии; каждый запуск создаёт task-запись.
- **Hooks**: `docs/automation/hooks.md` — hooks на события; **webhooks** — входящие
  HTTP-хуки создают изолированные сессии (по хуку).
- **Standing orders** (`docs/automation/standing-orders.md`) и **imap** (`docs/automation/imap.md`,
  email как входной канал задач).
- **Background tasks** (`docs/automation/tasks.md`) — «activity ledger, не планировщик»:
  lifecycle `queued → running → succeeded|failed|timed_out|cancelled|lost`; терминальные
  записи хранятся 7 дней (`lost` — 24 ч), sweeper каждые 60 сек (reconciliation по
  живости рантайма, cleanup stamping, pruning). Delivery: прямая отправка в канал
  (с `Inspect`-ссылкой) либо queue-в сессию запросчика + **немедленный heartbeat wake** —
  push-driven модель «запустил и жди уведомления», опрос — только для дебага.
  Недоставленные завершения субагентов ретраятся до 30 минут с capped backoff;
  невоставленное — терминальный статус `blocked` c `tasks retry/dismiss`.
- **TaskFlow** (`docs/automation/taskflow.md`) — слой оркестрации флоу над tasks
  (managed/mirrored sync modes; состояния waiting/blocked; `openclaw tasks flow …`).
  Это ближайший аналог нашего workflow-движка, но без явного графа состояний задачи:
  флоу координирует несколько tasks, а не «задача лежит в состоянии с назначенным агентом».
- **Планировщика с распределённым лока нет** — один Gateway на хост, in-memory полосы,
  SQLite-состояние. Для нескольких инстансов — `multiple-gateways.md` /
  `multi-tenant-hosting.md`, но это не наш «планировщик по таблице сессий».

---

## 7. API-поверхность

- **WebSocket Gateway-протокол** (основной): см. §1; детали — `docs/gateway/protocol.md`.
  Типизация через TypeBox → JSON Schema → кодогенерация клиентов.
- **HTTP на том же порту (WS+HTTP mux), дефолтно выключено**:
  - OpenAI-совместимый сёрфейс (`docs/gateway/openai-http-api.md`): `POST /v1/chat/completions`,
    `GET /v1/models`, `GET /v1/models/{id}`, `POST /v1/embeddings`; `POST /v1/responses`
    (OpenResponses) включается отдельно. Стриминг — SSE (`stream: true`, `data: [DONE]`).
    Ключевая идея — **agent-first контракт**: поле `model` = целевой агент
    (`openclaw`, `openclaw/default`, `openclaw/<agentId>`), заголовки `x-openclaw-model`
    (переопределение модели), `x-openclaw-session-key` (явный роутинг сессии; reserved
    namespaces `subagent:`, `cron:`, `acp:` запрещены), `x-openclaw-message-channel`.
    Сессии: по умолчанию stateless (новый ключ на вызов), стабильная сессия — через поле
    `user`. Лимиты: 20 MB body, 8 image_url, allowlist домейнов картинок.
    Важно: bearer-токен этого эндпоинта = **полные operator-права**, не узкий scope.
  - Tools invoke HTTP API: `docs/gateway/tools-invoke-http-api.md`.
- **CLI**: `openclaw gateway|status|sessions|tasks|skills|mcp|doctor|security audit|…`;
  one-shot прогон — `openclaw agent exec`.
- **Канальные вебхуки**: интеграция внешних систем (изолированная сессия на хук).
- **Авторизация**: см. §1 (bearer token/password, trusted-proxy, none; rate limit 429 c
  `Retry-After`; operator scopes; pairing устройств для WS).
- **SDK/контракт-first (OpenAPI)**: не найдено. Протокол описан документами и JSON Schema,
  кодогенерация только для внутренних клиентов (Swift).

---

## 8. MCP

Источник: `docs/tools/mcp.md`.

- **MCP-клиент**: серверы в `mcp.servers` конфига; транспорты **stdio / SSE / Streamable HTTP**;
  добавление через Control UI (Settings → MCP), CLI (`openclaw mcp add …`) или конфиг;
  фильтры инструментов `toolFilter.include/exclude`; таймауты; диагностика
  `openclaw mcp doctor <name> --probe`, `mcp status/probe`; **OAuth** для HTTP-серверов:
  `auth: "oauth"` + `openclaw mcp login <name>` (loopback redirect перехватывается,
  креды сохраняются). Инструменты MCP проходят те же tool-profile/policy контролы,
  что и встроенные (подключение сервера не расширяет политику). Per-session включение
  и per-tool deny через Control UI («+ → Connectors → Tool access»).
- **MCP-сервер (обратный режим)**: `openclaw mcp serve` — exposes OpenClaw channel
  conversations внешнему MCP-клиенту.
- Известные проблемы: из doc-раздела Troubleshooting — сервер виден в Settings, но
  инструменты не появляются (нужен probe/фильтры), stdio-сервер не стартует (окружение/cwd),
  изменения требуют `mcp reload`/рестарта Gateway. Массовых репортов по OAuth не найдено.

---

## 9. Безопасность: встроенная модель, инциденты, критика

**Штатная модель** (`docs/gateway/security/index.md`, `docs/gateway/security/exposure-runbook.md`,
корневой `README.md`): «Treat inbound messages as untrusted input»; pairing неизвестных
отправителей по умолчанию (`openclaw pairing approve <channel> <code>`); sandboxing
(Docker); tool policy; exec-approvals; `openclaw security audit`; rate limiting
(`docs/gateway/security/rate-limiting.md`); secrets через SecretRef/1Password;
device pairing + challenge-подписи; trusted-proxy auth.

**Инциденты и критика** (февраль 2026, волна disclosures):

1. **CVE-2026-25253** — one-click RCE, CVSS 8.8: Control UI принимала `gatewayURL` из
   query string и автоматически подключалась по WebSocket, передавая сохранённый
   auth-токен; вредоносная страница вытягивала токен и получала полный контроль над
   Gateway (включая отключение sandbox/policy и исполнение команд), даже при бинде на
   loopback (браузер сам делает outbound-соединение). Запатчено в 2026.1.29.
   Источники: https://security.utoronto.ca/advisories/openclaw-vulnerability-notification/,
   https://nvd.nist.gov/vuln/detail/CVE-2026-25253.
2. **Серия advisory**: три high-severity за неделю с 31.01.2026 (включая command
   injection CVE-2026-25157 и CVE-2026-24763), ещё два 04.02.2026 — 5 advisory менее
   чем за неделю. Источник: Immersive Labs (ссылка выше).
3. **Supply chain ClawHub**: аудит Koi Security 2 857 skills → **341 malicious**
   (кампания «ClawHavoc» — 335 шт.: Atomic Stealer, credential harvesters, reverse shell
   под видом Polymarket-инструмента); Snyk — **283 skills, утекавших API-ключи**; суммарно
   ~900 вредоносных/опасных skills. После — VirusTotal/ClawScan сканирование и репорты.
4. **Массовая экспозиция**: >40 000 открытых инстансов (позднее SecurityScorecard —
   >135 000 internet-exposed, >12 800 эксплуатируемых через уже запатченный RCE);
   утечки API-ключей, истории чатов, кредов. Корневая причина: CLI ставится на
   `127.0.0.1:18789`, но **официальный docker-setup.sh биндит `0.0.0.0:18789`**;
   ~80% экспонированных — устаревшие сборки до auth-hardening.
5. **Поведенческие инциденты**: задокументирован случай «загулявшего» агента — 500+
   спам-сообщений в iMessage пользователю и его контактам.
6. **Энтерпрайз-критика** (Immersive Labs, Sophos, IBM X-Force): нет централизованного
   управления, RBAC, интеграции с корпоративным IdP, аудит-логов и комплаенс-инструментов;
   prompt injection при обработке внешних сообщений — постоянный класс атак; феномен
   «Shadow AI» (сотрудники ставят агента на рабочие машины). Вердикт Immersive — «не
   запускать в организациях до security-first переработки архитектуры».

---

## 10. Сильные и слабые стороны (относительно целей spring-harness)

### Сильные (что изучить и перенять)

1. **Семантика очереди входящих — лучшая в классе**: `steer/followup/collect/interrupt`
   + debounce + cap/drop(summarize) + пер-канальные переопределения + cancel-identity
   для queued-ходов. Это точный ответ на наш вопрос «что делать с сообщением, пока агент
   занят» — у нас в брифе этот слой вообще не спроектирован.
2. **Роутинг источник→сессия** как декларативная таблица (dmScope/groupScope/bindings +
   identityLinks): готовая модель для наших вебхук-триггеров и «постоянных сессий,
   получающих события через вебхуки».
3. **Per-session serialization + глобальные лимиты полос** — концептуально совпадает с
   нашим ShedLock-подходом (один прогон на сессию), но у нас это будет в БД, а не в памяти.
4. **Push-driven фоновые задачи**: task lifecycle (queued/running/succeeded/failed/
   timed_out/cancelled/**lost**) + delivery-статус отдельно от исполнения + retry/dismiss
   для недоставленного + heartbeat wake. Отличный референс для наших асинхронных
   инструментов и «сессия-разборщик ошибки».
5. **Жизненный цикл сессий**: политики сброса (daily/idle с защитой от продления
   системными событиями), maintenance/pruning/archive, recovery после рестарта с
   ограниченным бюджетом попыток, отдельные `sessionStartedAt`/`lastInteractionAt`.
6. **Agent-first OpenAI-совместимый HTTP эндпоинт**: интеграция любым OpenAI-клиентом,
   где `model` = агент, а сессия выводится из `user` — дёшево даёт внешний API без SDK.
7. **Skills**: AgentSkills-формат (совместим с Pi), gating по окружению, per-agent
   allowlists, env-инжект на ход, снапшоты на сессию, $-ссылки в промпте, Skill Workshop
   (агент предлагает — человек утверждает).
8. **MCP из коробки**: stdio/SSE/HTTP + OAuth-login + tool-фильтры + обратный режим
   (`mcp serve`).

### Слабые / чего избегать

1. **Инструменты на хосте по умолчанию** и Docker-дефолт `0.0.0.0` — прямая причина
   массовой компрометации. Наш сервер: security-by-default (loopback/авторизованный
   ingress, изоляция исполнения, запрет публичного бинда без явного opt-in).
2. **Токен в браузерном UI + автоподключение по query-параметру** (класс CVE-2026-25253):
   токены не должны жить в Control UI и передаваться в URL; у нас — короткоживущие
   токены SSO, никакого автоподключения по ссылке.
3. **In-memory очередь без реплея**: при остановке Gateway взведённые в очередь вводы
   теряются (требуют resend). У нас всё состояние — в Postgres, планировщик обязан
   переживать рестарт.
4. **Однопользовательская ДНК**: общая main-сессия по умолчанию (риск утечки между
   пользователями — официальный Warning в доках), мультиюзер прикручен позже
   (`dmScope`, incognito). Нам multi-tenancy и изоляция контекстов нужны с первого дня.
5. **Непроверенный маркетплейс навыков**: 341 malicious skill / утечки ключей. Наши
   навыки должны проходить верификацию/подпись до публикации, а не после инцидента.
6. **Нет распределённого планировщика и аудит-слоя энтерпрайз-уровня**: один Gateway
   на хост, состояние в локальном SQLite, «no enterprise governance tooling» (критика
   Immerive Labs/Sophos). Наша серверная модель (Postgres + ShedLock + аудит) — именно
   то, чего у OpenClaw нет.
7. **События WS не реплеятся** (клиент должен сам детектить гэпы) — для надёжных
   удалённых клиентов нужен replay/cursor (как `get_entries?since=` у Pi).

---

## 11. Выводы

**Перенять в spring-harness:**

1. Декларативный роутинг входящих (источник/пир/канал → ключ сессии, identity-склейка)
   — в основу наших вебхук-триггеров и постоянных сессий.
2. Режимы очереди `steer/followup/collect/interrupt` с debounce/cap/drop — как
   спецификация поведения «сообщение пришло во время хода»; реализовать поверх
   Postgres-очереди (у нас это получится durable по умолчанию, в отличие от OpenClaw).
3. Модель фоновых задач: lifecycle + отдельный delivery-статус + retry/dismiss +
   wake-механика для пробуждения сессии результатом (наш планировщик = их sweeper+wake,
   но с распределённым локом).
4. Политики сброса/архивации долгоживущих сессий и разделение меток
   started/last-interaction/updated — сразу в схему БД.
5. Agent-first OpenAI-совместимый read/write эндпоинт как минимальный внешний API
   поверх наших сессий (в дополнение к контракт-first REST).
6. Формат skills (AgentSkills) с gating и per-agent allowlists — унификация с экосистемой
   (тот же формат у Pi/OpenClaw), skill-воркшоп «агент предлагает — человек утверждает».
7. Security-by-default: pairing/подтверждение внешних отправителей, `security audit`
   как команду, exposure-runbook как часть эксплуатации.

**Избегать:**

1. Host-exec без песочницы по умолчанию и сетевых дефолтов «на все интерфейсы».
2. Браузерных UI с долгоживущими токенами и автоподключением по параметрам ссылки.
3. In-memory очередей/состояний без реплея; событий без replay-курсора.
4. Непроверенных реестров расширений; публикация навыков без верификации.
5. Эволюции «single-user → multi-user постфактум»: изоляция сессий/контекстов и
   авторизация — фундамент схемы, а не конфиг-опция.

---

## Приложение: карта источников

- README (обзор, security): https://github.com/openclaw/openclaw/blob/master/README.md
- Gateway architecture: `docs/concepts/architecture.md`
- Gateway protocol: `docs/gateway/protocol.md`
- Command queue: `docs/concepts/queue.md`
- Session management: `docs/concepts/session.md`
- Agent runtimes: `docs/concepts/agent-runtimes.md`
- Skills: `docs/tools/skills.md`
- MCP: `docs/tools/mcp.md`
- OpenAI-compatible HTTP API: `docs/gateway/openai-http-api.md`
- Background tasks: `docs/automation/tasks.md`
- Heartbeat: `docs/gateway/heartbeat.md`
- CVE-2026-25253: https://nvd.nist.gov/vuln/detail/CVE-2026-25253 ,
  https://security.utoronto.ca/advisories/openclaw-vulnerability-notification/
- Анализ безопасности и истории проекта (Immersive Labs, 19.02.2026):
  https://www.immersivelabs.com/resources/c7-blog/openclaw-what-you-need-to-know-before-it-claws-its-way-into-your-organization
- Доки онлайн: https://docs.openclaw.ai
