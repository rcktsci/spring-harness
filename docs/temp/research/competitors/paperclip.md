# Конкурентный анализ: Paperclip

> Объект: **Paperclip** (Paperclip Labs, Inc). Репозиторий: https://github.com/paperclipai/paperclip , сайт: https://paperclip.ing/ , документация: https://docs.paperclip.ing .
> Лицензия: MIT. Дата анализа: 2026-09-15. На момент анализа последний релиз — v2026.831.1 (02.09.2026), сайт указывает «Latest release v2026.831.1».
> Источники: README.md, `doc/*` (PRODUCT, GOAL, TASKS, DATABASE, DEPLOYMENT-MODES, MCP-ACCESS-GOVERNANCE, execution-semantics, architecture/paperclip-runner, architecture/durable-continuation-scheduler), `server/src/*` (app.ts, index.ts, routes/, services/), структура пакетов и производные обзорные страницы Zread.
> Нумерация вопросов — по единому списку из BRIEF.md. Принцип: каждый факт со ссылкой; где данных нет — «не найдено/не подтверждено».

---

## 0. Паспорт объекта

- **Что это:** «open-source orchestration for teams of AI agents». «Paperclip is a Node.js server and React UI that orchestrates a team of AI agents to run a business. Bring your own agents, assign goals, and track work and costs from one dashboard.» https://github.com/paperclipai/paperclip/blob/master/README.md
- **Формула продукта:** «If OpenClaw is an _employee_, Paperclip is the _company_». https://github.com/paperclipai/paperclip/blob/master/README.md
- **Лицензия:** MIT © 2026 Paperclip Labs, Inc. https://github.com/paperclipai/paperclip/blob/master/LICENSE
- **Модель распространения:** open source, self-hosted, без обязательного аккаунта; есть managed-платформа `paperclip.inc` (упоминается в issue-репортах). https://github.com/paperclipai/paperclip/blob/master/README.md
- **Четыре продуктовых «столпа»** (https://github.com/paperclipai/paperclip/blob/master/README.md):
  1. *Agentic Task Manager* — задачи, approvals/review gates, auditable routines & workflows, verify из diff/screenshots/tests;
  2. *Org Chart for Agents* — mixed human+agent org chart, роли/пермишены/границы, governance, scoped secrets;
  3. *Agent Employee Training* — Skill Studio, shared org-wide skills, evals & saved test runs, performance reviews;
  4. *Agentic OS* — cross-provider runtime, sandboxing/integrations/MCP, SSO/GRC/RBAC/cost controls, trace collection.
- **Явное «чем НЕ является»** (важно для позиционирования; https://github.com/paperclipai/paperclip/blob/master/README.md):
  - не чат-бот («Agents have jobs, not chat windows»);
  - не agent-framework («We don't tell you how to build agents»);
  - не drag-and-drop workflow-builder («No drag-and-drop pipelines. Paperclip models companies…»);
  - не prompt manager;
  - не single-agent tool («This is for teams»);
  - не code-review tool («Paperclip orchestrates work, not pull requests»).
- **Подсистемы control-plane** (по README): Identity & Access, Org Chart & Agents, Work & Task System, Heartbeat Execution, Workspaces & Runtime, Governance & Approvals, Budget & Cost Control, Routines & Schedules, Plugins, Secrets & Storage, Activity & Events, Company Portability. https://github.com/paperclipai/paperclip/blob/master/README.md

---

## 1. Архитектура

- **Два слоя, жёстко разделённые по авторитету** (https://github.com/paperclipai/paperclip/blob/master/doc/GOAL.md):
  1. **Control Plane** (сам Paperclip): реестр агентов и org-chart, назначение и статусы задач, бюджеты и учёт токенов, комментарии/документы/work products/вложения, иерархия целей, мониторинг heartbeat; плюс execution-control семантика — single-assignee, atomic checkout, execution locks, блокеры, recovery issues, workspace/runtime-контроль.
  2. **Execution Services** (адаптеры): агенты работают **вне** сервера и «phone home». Адаптеры определяют, как heartbeat вызывается, наблюдается и отменяется.
- **Ключевой архитектурный принцип:** «Control plane, not execution plane. Paperclip orchestrates. Agents run wherever they run and phone home». https://github.com/paperclipai/paperclip/blob/master/doc/PRODUCT.md
- **Стек:** монорепозиторий pnpm (https://github.com/paperclipai/paperclip/blob/master/README.md, `pnpm-workspace.yaml`):
  - `server/` — Express-сервер + WebSocket-слой реального времени (TypeScript);
  - `ui/` — React 19 SPA, Vite, Tailwind v4, shadcn/ui;
  - `cli/` — самостоятельный CLI (собирается esbuild);
  - `packages/db` — схема/миграции на Drizzle ORM + PostgreSQL;
  - `packages/shared`, `packages/adapters` (12+ провайдеров), `packages/adapter-utils`, `packages/plugins`, `packages/mcp-server`, `packages/paperclip-runner` (Rust + TS), `packages/paperclip-eval-kernel`, `packages/skills-catalog`, `packages/teams-catalog`, `packages/tailscale-https-broker`, демо-MCP-серверы.
  - Требования: Node.js 24.11+, pnpm 9.15+. https://github.com/paperclipai/paperclip/blob/master/README.md
- **HTTP-слой:** построен на **Express**. `server/src/app.ts` создаёт `express()`, монтирует `Router` под `/api`, а HTTP-сервер поднимается через `node:http` (`createServer`). Есть глобальный `express.json()` с лимитами тела (`DEFAULT_JSON_BODY_LIMIT`, `PORTABLE_JSON_BODY_LIMIT`), сжатие (`apiCompression`), `httpLogger`, `errorHandler`. https://github.com/paperclipai/paperclip/blob/master/server/src/app.ts
- **Порядок старта** (`server/src/index.ts`): detection → Sentry → config → DB-миграции → bootstrap сервисов → HTTP-сервер → WebSocket-серверы → планировщики → systemd notify; `startServer()` дожидается `instrumentationReady` (OpenTelemetry) до открытия соединений с БД. https://github.com/paperclipai/paperclip/blob/master/server/src/index.ts
- **Хранилище состояния — PostgreSQL через Drizzle ORM** (https://github.com/paperclipai/paperclip/blob/master/doc/DATABASE.md):
  - режим «zero-config» — **встроенный PostgreSQL**, данные в `~/.paperclip/instances/default/db/`, миграции применяются автоматически для пустой БД;
  - локальный Docker-Postgres 17 (`docker-compose up -d`, `localhost:5432`);
  - hosted (пример Supabase; поддержка transaction-pooling через `DATABASE_PREPARED_STATEMENTS=false` и отдельного `DATABASE_MIGRATION_URL`).
  - В схеме десятки таблиц: `issues`, `issue_labels`, `issue_relations`, `comments`, `heartbeat_runs`, `heartbeat_run_events`, `agent_wakeup_requests`, `cost_events`, `budget_incidents`, `tool_call_events`, `company_secrets`, `user_secret_definitions`, `plugin_database_namespaces`, `plugin_migrations`, `project_memberships`, `agent_memberships`, `decision_queues`, `decision_training_examples` и др.
- **Режимы развёртывания (deployment modes)** (https://github.com/paperclipai/paperclip/blob/master/doc/DEPLOYMENT-MODES.md):
  - `local_trusted` (по умолчанию, loopback, без логина) и `authenticated` (логин обязателен) с exposure `private` / `public`;
  - **bind** — отдельная от auth ось: `loopback | lan | tailnet | custom`;
  - в `authenticated/public` локальные stdio-MCP-слоты «fail closed» без `PAPERCLIP_TRUSTED_MCP_RUNTIME_HOST`.
- **Изоляция процессов:**
  - агенты не запускаются в процессе сервера (control plane их лишь оркестрирует);
  - **плагины** — отдельные worker-процессы, общающиеся с хостом по **stdio JSON-RPC 2.0** (https://github.com/paperclipai/paperclip/blob/master/doc/plugins/PLUGIN_SPEC.md);
  - **Paperclip Runner** — отдельный процесс (`paperclip-runnerd`), общается с сервером по WebSocket PRP v1 (`/api/runner/v1/connect/:runId`); Rust-раннер владеет process-supervision провайдера, сервер остаётся авторитетом по identity/policy/workflow. https://github.com/paperclipai/paperclip/blob/master/doc/architecture/paperclip-runner.md
  - **Execution workspaces** (git worktrees, операторские ветки) и sandbox-провайдеры (e2b, Cloudflare, Daytona, Modal, Kubernetes) изолируют рабочее окружение агента. https://github.com/paperclipai/paperclip/blob/master/README.md
- **Наблюдаемость:** opt-in OpenTelemetry (traces, `OTEL_EXPORTER_OTLP_ENDPOINT`), opt-in Sentry (`SENTRY_DSN_BACKEND`/`FRONTEND`), телеметрия включена по умолчанию с opt-out (`PAPERCLIP_TELEMETRY_DISABLED=1`, `DO_NOT_TRACK=1`, `CI=true`). https://github.com/paperclipai/paperclip/blob/master/README.md
- **Транспорты:** HTTP REST (UI ↔ server), три WebSocket-сервера (`live-events-ws.ts` — UI-события; `runner-prp-ws.ts` — PRP; terminal-ws для custom-image environments), MCP (`/mcp`). UI «ходит» в REST API и слушает **live-events WebSocket** для realtime-обновлений. https://zread.ai/paperclipai/paperclip/7-architecture-overview
- **Масштабирование/производительность:** single-process-модель с embedded Postgres для local-first; известные проблемы при росте (issue #2553 — 280 issue, крэши по RAM, UI > 2 мин на страницу), решались курсорной пагинацией и ленивой загрузкой. https://zread.ai/paperclipai/paperclip/5-issues-and-feedbacks

---

## 2. Модель сессий

- **Главное отличие от «долгоживущего агента»:** Paperclip **не держит процесс агента живым между ходами**. «A heartbeat run is finite: it starts, performs work, records a terminal result, and exits.» https://github.com/paperclipai/paperclip/blob/master/doc/architecture/durable-continuation-scheduler.md
- **Heartbeat — это протокол, а не рантайм.** Paperclip определяет «как запустить цикл агента»; что агент делает внутри цикла — целиком на стороне агента. Агенты просыпаются по расписанию, проверяют работу и действуют. https://github.com/paperclipai/paperclip/blob/master/doc/PRODUCT.md
- **Persistent agent state:** заявлено, что «Agents resume the same task context across heartbeats instead of restarting from scratch»; конкретная форма session-state — **адаптер-специфичная** (Claude Code session, Codex, ACP и т.п.), а не универсальная таблица сессий. https://github.com/paperclipai/paperclip/blob/master/README.md
- **Планировщик heartbeat** (https://github.com/paperclipai/paperclip/blob/master/doc/architecture/durable-continuation-scheduler.md):
  - включён, если не `HEARTBEAT_SCHEDULER_ENABLED=false`;
  - интервал `HEARTBEAT_SCHEDULER_INTERVAL_MS`, по умолчанию **30 000 мс**, минимум клампится к **10 000 мс**;
  - «durable continuation scheduler» — зонтичный термин для двух механизмов:
    1. **explicit continuation effects** (пишутся native status arbitration);
    2. **stranded-issue reconciliation** — startup + периодическая страховка (`reconcileStrandedAssignedIssues()`).
- **Durable-модель:** намерение реконструируется из персистентных записей control plane, а не из in-memory таймера: статус/assignee issue, execution lock/run identity, статус heartbeat-run и его retry-ancestry, строки `agent_wakeup_requests`, native status decisions и materialized effects, pending interactions/approvals/monitors/blockers, scheduled retry timestamps. Поэтому рестарт процесса позволяет возобновить работу. https://github.com/paperclipai/paperclip/blob/master/doc/architecture/durable-continuation-scheduler.md
- **Типы continuation** (явные): `same_agent`, `retry`, `delegated_issue`, `response_wake`, `monitor`; материализуются как идемпотентные wake-request; monitor использует `monitor_due`, остальные — `issue_status_changed`. https://github.com/paperclipai/paperclip/blob/master/doc/architecture/durable-continuation-scheduler.md
- **Сессии/локи на уровне issue** (https://github.com/paperclipai/paperclip/blob/master/doc/execution-semantics.md):
  - `checkoutRunId` — «кто владеет правами исполнения» (issue-ownership lock);
  - `executionRunId` — «какой run реально жив прямо сейчас»;
  - checkout обязателен для перехода issue в agent-owned `in_progress`; `409` при checkout означает реального живого владельца/статусную нестыковку/блокер;
  - stale-lock recovery — именно crash recovery, не retry-loop; нельзя очищать/присваивать локи у non-terminal run.
- **Роуминг/удалённый доступ/attach:** отдельной команды «attach» (как `opencode attach`) **не найдено/не подтверждено**. Удалённый доступ обеспечивается REST + realtime-WS, mobile-раздачей UI (`pnpm dev:mobile`, прокси `/api`), managed-режимом `tailscale_https`. https://github.com/paperclipai/paperclip/blob/master/README.md , https://github.com/paperclipai/paperclip/blob/master/doc/DEPLOYMENT-MODES.md
- **Совместный доступ:** несколько «board users» (Better Auth), company memberships, resource-memberships (`project_memberships`, `agent_memberships`) для sidebar-visibility; multi-tenancy — каждый объект company-scoped. https://github.com/paperclipai/paperclip/blob/master/doc/DATABASE.md , https://github.com/paperclipai/paperclip/blob/master/doc/DEPLOYMENT-MODES.md
- **Форки/ветвления:** сессионных форков не найдено; есть ветвление/версионирование на уровне **skill** (`.../skills/{skillId}/fork`, `fork-precheck`), **документов issue** (`.../documents/{key}/revisions`, `restore`) и **config revisions агента** (`config-revisions`, `rollback`). https://github.com/paperclipai/paperclip/blob/master/server/src/routes/openapi.ts
- **Рабочие сессии (workspaces):** трёхслойная модель Project Workspace → Execution Workspace (checkout/cwd) → Work Product / Runtime Service; изоляция через git worktrees и sandbox-провайдеры. https://zread.ai/paperclipai/paperclip/15-no-remote-git-contract

---

## 3. Модель агента

- **Сотрудник = агент.** Каждый сотрудник имеет (https://github.com/paperclipai/paperclip/blob/master/doc/PRODUCT.md):
  - **adapter type + config** — как агент запускается и что определяет его identity/behavior. Формат — adapter-specific: OpenClaw — `SOUL.md`/`HEARTBEAT.md`, Claude Code — `CLAUDE.md`, «голый скрипт» — CLI-args. Paperclip не диктует формат;
  - **роль и reporting** — title, кому подчиняется, кто подчиняется ему;
  - **capabilities description** — короткий абзац, помогающий другим агентам понять, кто чем может помочь.
- **Минимальный контракт агента:** «be callable» / «If it can receive a heartbeat, it's hired». https://github.com/paperclipai/paperclip/blob/master/README.md
- **Орг-структура и делегация** (https://zread.ai/paperclipai/paperclip/9-org-chart-and-agent-management): директивы делегирования следуют дереву — CEO делегирует код CTO, CTO делегирует проверку QA; заблокированные агенты эскалируют вверх по тем же рёбрам. CEO эскалирует кросс-командные/бюджетные блокеры; QA — вопросы ownership CTO. **Найм:** когда агент видит capacity-gap («подчинённого нет»), он использует skill `paperclip-create-agent`, чтобы нанять нового агента, и затем делегирует. Организация растёт органически, инициируемая агентами.
- **Встроенный реестр агентов (built-in agents):** first-party, company-wide агенты, резолвятся по стабильному **key**, а не по DB id. API: `GET .../built-in-agents`, `POST .../built-in-agents/:key/provision`, `.../:key/reset`. https://zread.ai/paperclipai/paperclip/9-org-chart-and-agent-management , https://github.com/paperclipai/paperclip/blob/master/server/src/routes/openapi.ts
- **Bundled-шаблоны команд:** `packages/teams-catalog/catalog/bundled/company-defaults/core-exec-team/` — `TEAM.md` + `agents/ceo/AGENTS.md`, `agents/cto/AGENTS.md`, `agents/qa/AGENTS.md` с `reportsTo`, `skills` и YAML-frontmatter. CEO — `reportsTo: null`, skills `task-planning`, `issue-triage`. https://zread.ai/paperclipai/paperclip/9-org-chart-and-agent-management
- **Skill-модель:** каждый skill — директория с entrypoint `SKILL.md`; агенты ссылаются на skills по **shortname** с трёхуровневым резолвом: (1) локальный пакет `skills/<shortname>/SKILL.md`, (2) referenced/included skill по slug, (3) company skill library, управляемая инструментами. https://zread.ai/paperclipai/paperclip/20-skill-studio-and-training , https://zread.ai/paperclipai/paperclip/9-org-chart-and-agent-management
- **Разрешения/безопасность:** центральный «trust preset» (`standard`, `low_trust_review` и др.) влияет на права run: низкодоверительные агенты не могут читать/менять конфиги агентов, instruction bundles, company-skill config; run-scoped write authorization subtree-scoped. https://github.com/paperclipai/paperclip/blob/master/doc/execution-semantics.md , https://zread.ai/paperclipai/paperclip/18-approval-gates-and-board-control
- **Доступность агента:** отдельные сервисы `agent-assignability`, `agent-invokability`, `agent-start-lock`, `agent-permissions`, `agent-secret-bindings`. https://zread.ai/paperclipai/paperclip/7-architecture-overview
- **Конфиг-версионирование и rollback:** `.../agents/{id}/config-revisions`, `.../config-revisions/{revisionId}/rollback`, `.../instructions-path`, `.../instructions-bundle`, `.../skills/sync`. https://github.com/paperclipai/paperclip/blob/master/server/src/routes/openapi.ts
- **Промпт-структура:** явного «промпт-шаблонизатора» нет (Paperclip позиционирует себя как «Not a prompt manager» — агенты приносят свои промпты/модели/рантаймы). Управление — через adapter config + `AGENTS.md` + instructions bundle + skills. https://github.com/paperclipai/paperclip/blob/master/README.md , https://github.com/paperclipai/paperclip/blob/master/doc/PRODUCT.md

---

## 4. Инструменты

- **Paperclip не поставляет «инструменты агента» напрямую** — он даёт **MCP-шлюз** и **skills**. Инструменты приходят: (а) из upstream MCP-серверов через gateway, (б) из плагинов (`plugin-tool-dispatcher`), (в) встроенные «семантические действия» PRP (`create_task`, `set_dependencies`, etc.). https://github.com/paperclipai/paperclip/blob/master/doc/MCP-ACCESS-GOVERNANCE.md , https://github.com/paperclipai/paperclip/blob/master/doc/architecture/paperclip-runner.md
- **Встроенные операции/действия (из API и docs):**
  - issue-операции: `checkout`, `release`, `children`, `comments`, `documents`, `work-products`, `interactions`, `watchdog`, `recovery-actions`, `tree-control`, `tree-holds`, `queued-comments` (+ `steer`); https://github.com/paperclipai/paperclip/blob/master/server/src/routes/openapi.ts
  - агентские: `wakeup`, `heartbeat/invoke`, `pause`/`resume`/`terminate`, `keys`, `skills/sync`, `runtime-state/reset-session`, `task-sessions`; ibid.
  - агрегация задач: `create_task`, `set_dependencies` (capabilities `standard`-run). https://github.com/paperclipai/paperclip/blob/master/doc/execution-semantics.md
- **Синхронные vs асинхронные паттерны:**
  - синхронно — tool-call внутри heartbeat-run (через gateway, с policy/approval);
  - асинхронно/фоново — через **durable-модель**: continuations, `monitor` (one-shot `nextCheckAt`), `agent_wakeup_requests`, routines (cron/webhook/API-triggers), webhooks, ручные monitors (`.../issues/{id}/monitor/check-now`). https://github.com/paperclipai/paperclip/blob/master/doc/architecture/durable-continuation-scheduler.md
- **«Фоновые задачи с отложенным возвратом результата»** реализованы не как promise/background-tool, а как **durable action path**: one-shot monitor должен быть персистентным в control-plane state; «unmanaged local process» (nohup, detached PTY, PIDs, локальные polling-loop) **не считается** ни liveness, ни отложенным результатом. https://github.com/paperclipai/paperclip/blob/master/doc/execution-semantics.md
- **MCP-инструменты:** список формируется catalog-ом из аннотаций upstream MCP: risk `read`/`write`/`destructive`; write по умолчанию требует approval, destructive — quarantined при первом обнаружении. https://github.com/paperclipai/paperclip/blob/master/doc/MCP-ACCESS-GOVERNANCE.md
- **Tool-call tracing:** каждый вызов попадает в append-only `tool_call_events` с decision, matched policy ids, reason code, redaction plan, latency, outcome. https://github.com/paperclipai/paperclip/blob/master/doc/MCP-ACCESS-GOVERNANCE.md
- **Skills как «инструменты команд»:** Paperclip ship-ит core skills в корне `skills/`:
  - `paperclip` — базовый протокол работы агента (получение заданий, контекста, комментирование);
  - `paperclip-board` — интерфейс «board» (аппрувы, решения, бюджеты через API);
  - `paperclip-converting-plans-to-tasks` — декомпозиция принятого плана;
  - `paperclip-create-agent` — найм новых агентов;
  - `para-memory-files` — PARA-based persistent memory.
  Runtime инжектирует их в агентов («runtime skill injection» — агенты «учат» Paperclip-воркфлоу и контекст без переобучения). https://github.com/paperclipai/paperclip/blob/master/README.md , https://zread.ai/paperclipai/paperclip/20-skill-studio-and-training
- **Skills-catalog / Skill Studio:** `@paperclipai/skills-catalog` — реестр и дистрибуция skills; Skill Studio — редактирование, тест-ранны (`.../skills/{id}/test-runs`), версии, fork, star, install из каталога, scan-project, import. https://zread.ai/paperclipai/paperclip/20-skill-studio-and-training , https://github.com/paperclipai/paperclip/blob/master/server/src/routes/openapi.ts
- **Доступные адаптеры (bring-your-own):** `claude-local`, `codex-local`, `cursor-local`, `cursor-cloud`, `gemini-local`, `grok-local`, `kimi-local`, `opencode-local`, `pi-local`, `hermes`, `hermes-gateway`, `openclaw-gateway`, плюс `process` (shell/скрипт) и `http` (webhook/API). https://github.com/paperclipai/paperclip/tree/master/packages/adapters , https://github.com/paperclipai/paperclip/blob/master/doc/PRODUCT.md
- **Способы запуска агента (4 механизма)** (https://github.com/paperclipai/paperclip/blob/master/doc/PRODUCT.md):
  1. local CLI/session adapters (Claude Code, Codex, Gemini, OpenCode, Pi, Cursor);
  2. «run a command» — процесс (shell/python), heartbeat = «execute & monitor»;
  3. «fire and forget» — webhook/API к внешнему агенту (OpenClaw-style);
  4. external adapter plugins — динамически загружаемые рантаймы.

---

## 5. Оркестрация / воркфлоу

- **Иерархия целей и работ** (https://github.com/paperclipai/paperclip/blob/master/doc/TASKS.md):
  `Workspace → Initiatives → Projects → Milestones → Issues → Sub-issues`.
  Company goal — вершина; всё должно трассироваться к цели: «If you can't explain why a task matters to the company goal, it shouldn't exist» (https://github.com/paperclipai/paperclip/blob/master/doc/PRODUCT.md).
- **Модель задачи (Issue)** — центральная сущность (https://github.com/paperclipai/paperclip/blob/master/doc/TASKS.md):
  - human-readable `identifier` (`ENG-123`, team key + auto-increment);
  - `status` — не плоский enum, а **team-specific набор состояний** с фиксированными категориями: `triage`, `backlog`, `unstarted`, `started`, `completed`, `cancelled`; состояния внутри категории кастомизируемы, категории — нет;
  - `priority` — фиксированная шкала 0–4 (No/Urgent/High/Medium/Low);
  - **single assignee** (намеренно: «clear ownership prevents diffusion of responsibility»; для коллаборации — sub-issues);
  - `parentId` (structure) и `blockedByIssueIds` (dependency) — разные механизмы; `blocks`/`blocked_by`/`related`/`duplicate`; blocking не транзитивен; cancelled-блокер не удовлетворяет зависимость;
  - `goalId`, `projectId`, `milestoneId`, `teamId`, labels, estimates (Exponential 1,2,4,8,16,32,64), comments (threaded), documents, work products, attachments.
- **Assignees-инвариант:** `assigneeAgentId` XOR `assigneeUserId` — «hard invariant». Агентские issue участвуют в execution-loop, пользовательские — нет. https://github.com/paperclipai/paperclip/blob/master/doc/execution-semantics.md
- **Atomic checkout + execution locks:** checkout — мост от ownership к активному исполнению; `checkoutRunId`/`executionRunId`; stale-lock recovery crash-safe; `409` = реальный конфликт владения. Это прямо отвечает на заявленную боль «no double-work». https://github.com/paperclipai/paperclip/blob/master/doc/execution-semantics.md , https://github.com/paperclipai/paperclip/blob/master/README.md
- **Планирование (plan mode):** «Deep Planning» — planning-режим, revisioned plans, plan approvals; принятый план атомарно переводит `planning`-источник в `standard`; есть skill `paperclip-converting-plans-to-tasks`; decomposition — exact-once по fingerprint `(sourceIssueId, acceptedPlanRevisionId)`. https://github.com/paperclipai/paperclip/blob/master/README.md , https://github.com/paperclipai/paperclip/blob/master/doc/execution-semantics.md
- **Многоагентные сценарии / передача работы** (https://github.com/paperclipai/paperclip/blob/master/doc/execution-semantics.md):
  - **child → parent reporting**: 3 канонических канала — (1) completion signal `issue_blockers_resolved` (всегда), (2) direct-parent report comment (trust-gated), (3) stop-only relay для запрещённых к комментированию пресетов;
  - **The Courier Pattern** — легальный lateral-канал: создать новое issue, назначенное целевому агенту, с самодостаточным описанием; «wakes the target agent through normal assignment» и сохраняет аудит;
  - **review delegation**: вердикт ревью — сам deliverable; ревью с замечаниями = `done`, не `blocked`.
- **Найм / распределение работ:** CEO — реальный поставляемый шаблон `core-exec-team`; найм требует board-approval по умолчанию; API: `.../agent-hires`, `.../agents/{id}/approve`, entity-level approvals. https://github.com/paperclipai/paperclip/blob/master/server/src/routes/openapi.ts , https://zread.ai/paperclipai/paperclip/18-approval-gates-and-board-control
- **Routines & Schedules:** recurring-задачи с cron/webhook/API-триггерами, concurrency и catch-up политиками; каждый прогон создаёт tracked issue и будит назначенного агента. https://github.com/paperclipai/paperclip/blob/master/README.md
- **Watchdogs и recovery:** `task-watchdogs`, `task-watchdog-scope`, silent active-run watchdog, recovery-actions, stalled-review-decisions, issue-liveness. Классы восстановления: Auto-Recover, Explicit Recovery Action, Human Escalation. https://github.com/paperclipai/paperclip/blob/master/doc/execution-semantics.md
- **Liveness-инвариант:** агентская нетерминальная issue должна иметь живой путь (active run, queued wake/continuation, typed execution-policy participant, pending interaction/approval, monitor, human owner, healthy blocker-chain, recovery action). Prose-only «blocked» отклоняется и превращается в `needs_attention`. https://github.com/paperclipai/paperclip/blob/master/doc/execution-semantics.md
- **Реальный инцидент конвергенции (DOT-2 loop):** runner повторно выдавал успешный результат с `done`, но evidence-classifier не принимал ссылки → status arbitration сохранял `in_progress` → reconciler каждые ~30с ставил `issue_continuation_needed` → бесконечный цикл. Пофикшено добавлением redacted task prompt в native input и ослаблением требования к evidence для low-risk completion. Ценный кейс: «Fix the status or continuation decision; increasing the polling interval only hides the bug». https://github.com/paperclipai/paperclip/blob/master/doc/architecture/durable-continuation-scheduler.md
- **Inbox-модель:** агенты получают работу через inbox (`GET /api/agents/me/inbox/mine`, `inbox-lite`), плюс `inbox-dismissals`, `inbox-agent-policy`. https://github.com/paperclipai/paperclip/blob/master/server/src/routes/openapi.ts

---

## 6. API-поверхность

- **REST API** поверх Express, смонтирован под `/api`; домены роутов: identity (`auth`, `access`, `user-profiles`), org (`agents`, `companies`, `projects`, `folders`), tasks (`issues`, `issue-tree-control`, `goals`, `cases`), execution (`environments`, `execution-workspaces`, `routines`, `pipelines`), governance (`approvals`, `secrets`, `tool-access`, `decision-queues`), observability (`activity`, `costs`, `dashboard`, `status-cards`, `sidebar-badges`), extensibility (`plugins`, `adapters`, `company-skills`, `teams-catalog`). https://zread.ai/paperclipai/paperclip/7-architecture-overview
- **OpenAPI генерируется из Zod-схем** (`server/src/routes/openapi.ts`, endpoint `GET /api/openapi.json`), — то есть contract-first в терминах Zod, но не spec-first файлом. https://github.com/paperclipai/paperclip/blob/master/server/src/routes/openapi.ts
- **Поверхность очень большая** — только из `openapi.ts` извлечено **300+ уникальных путей** (`/api/companies/{companyId}/...`, `/api/issues/{id}/...`, `/api/agents/{id}/...`, `/api/tool-gateway/...`, `/api/plugins/...`). Бases включают: `issues`, `agents`, `approvals`, `budgets`, `secrets`, `skills`, `routines`, `runs`, `tools`, `goals`, `projects`, `environments`, `decision-queues`, `teams`, `instance`, `admin`, `invites`, `join-requests`, `cloud`, `llms`, `board-api-keys`. https://github.com/paperclipai/paperclip/blob/master/server/src/routes/openapi.ts
- **CLI (`paperclipai`)** — esbuild-bundled, payload в `~/.paperclip/cli/` с atomic switch через symlink `current`; состояние в `~/.paperclip/instances/`. Команды (из doc/CLI.md): `onboard`, `configure`, `doctor`, `run`, `service`, `test-drive`, `install`, `update`, `upgrade`, `uninstall`, `auth`, `whoami`, `token`, `admin`, `agent`, `agent-config`, `agent-prompt`, `approval`, `asset`, `board`, `board-claim`, `budget`, `company`, `connect`, `context`, `cost`, `dashboard`, `environment`, `env-lab`, `feedback`, `finance`, `goal`, `heartbeat`, `inbox`, `instance`, `invite`, `issue`, `join`, `llm`, `member`, `openapi`, `openclaw`, `org`, `plugin`, `profile`, `project`, `project-workspace`, `routine`, `routines`, `secrets`, `skill`, `skills`, `teams`, `workspace`, `activity`, `adapter`, `allowed-hostname`, `available-skill`, `sidebar`. https://github.com/paperclipai/paperclip/blob/master/doc/CLI.md
- **Авторизация** (https://zread.ai/paperclipai/paperclip/17-identity-auth-and-rbac , https://github.com/paperclipai/paperclip/blob/master/doc/DEPLOYMENT-MODES.md):
  - `local_trusted` → неявный actor `local-board` (`source=local_implicit`, `isInstanceAdmin=true`, все компании);
  - `authenticated` → Better Auth session cookie; board identity = реальная строка в `authUsers` + `instance_user_roles` + `company_memberships`;
  - **Board API key** (Bearer) — для CLI/автоматизации;
  - **Agent API key** (hash-match);
  - **Agent JWT** (HS256) — per-instance и per-company изоляция: ключ = `HMAC-SHA256(masterSecret, "jwt:{instanceId}:{companyId}")`; опционально `responsible_user_id` («agent acts on behalf of human») с проверкой активного membership;
  - Mutating-запросы атрибутируются актору; `agent-auth-jwt.ts`.
- **Стриминг:** `GET /api/board/chat/stream` (board-chat stream), live-events WebSocket (`live-events-ws.ts`) для UI-обновлений, plugin bridge stream `/api/plugins/{pluginId}/bridge/stream/{channel}`, PRP WebSocket для runner. SSE как универсальный транспорт чата — не подтверждено (кроме board-chat stream). https://github.com/paperclipai/paperclip/blob/master/server/src/app.ts
- **Инициация OAuth для инструментов:** `POST /api/tools/oauth/{connectionId}/start`, `GET /api/tools/oauth/callback`, cloud-connector enrollment-callback, vercel-connect callback. https://github.com/paperclipai/paperclip/blob/master/server/src/routes/openapi.ts
- **Идемпотентность:** явные idempotency keys на decomposition/continuation/company-transfer; PRP command-identity идемпотентна (reuse с тем же input → stored result, с другим → fail closed). https://github.com/paperclipai/paperclip/blob/master/doc/architecture/paperclip-runner.md

---

## 7. MCP

- **Две роли Paperclip в MCP-графе** (частая причина путаницы) (https://github.com/paperclipai/paperclip/blob/master/doc/MCP-ACCESS-GOVERNANCE.md):
  - **MCP endpoint** — внешние клиенты (Claude Code, IDE, скрипты) вызывают Paperclip через `/mcp`, управляя задачами/агентами; auth — обычная Paperclip auth, **не** policy-governed;
  - **MCP gateway** — Paperclip проксирует tool-calls агента к upstream MCP (GitHub, Linear, stdio-fixtures и т.п.) с profile/policy/approval/audit.
- **Четырёхслойная модель доступа:** Application → Connection → Catalog Entry; Profile → Binding; Policy → Gateway → Action Request. Мнемоника: «profile says _can this agent see the tool_; policy says _is this exact call allowed right now_». https://github.com/paperclipai/paperclip/blob/master/doc/MCP-ACCESS-GOVERNANCE.md
- **Транспорты:** `remote_http` (предпочтительно) и `local_stdio` (только на trusted host; cloud public — fail closed). Операторы **не** могут вставлять произвольные `command`/`args` — только approved stdio templates. https://github.com/paperclipai/paperclip/blob/master/doc/MCP-ACCESS-GOVERNANCE.md
- **Risk-классификация:** `read` / `write` / `destructive` из MCP-аннотаций; write по умолчанию требует approval, destructive — quarantine. **Changed-tool quarantine**: новый write/destructive из upstream → `quarantined` до ревью оператором. https://github.com/paperclipai/paperclip/blob/master/doc/MCP-ACCESS-GOVERNANCE.md
- **Policies:** `allow`, `block`, `require_approval`, `rate_limit`, `trust_rule`; порядок оценки: catalog status → profile → policies (block short-circuit) → default allow; «Deny always beats allow». Dry-run: `POST /api/companies/:id/tools/policy/test`. https://github.com/paperclipai/paperclip/blob/master/doc/MCP-ACCESS-GOVERNANCE.md
- **Человеческий approval-flow:** вызов → HTTP `409` + `reasonCode: approval_required` + `actionRequestId`; run **паузится** на этом вызове; после approve агент ретраит с `approvedActionRequestId`; проверяется совпадение canonical argument hash. Возможна промоция в **trust rule** (exact-arg-shape, optional threshold/expiry), с отзывом. https://github.com/paperclipai/paperclip/blob/master/doc/MCP-ACCESS-GOVERNANCE.md
- **Runtime slots:** локальные stdio — supervised child processes с lifecycle `stopped→starting→running→idle→(stopped|failed)`, eviction по idle, restart-suppression. https://github.com/paperclipai/paperclip/blob/master/doc/MCP-ACCESS-GOVERNANCE.md
- **Audit:** append-only `tool_call_events` (decision, matched policies, reason code, redaction, latency, outcome); API `GET /api/tool-gateway/audit`, `.../tools/runs/:runId/decisions`. https://github.com/paperclipai/paperclip/blob/master/doc/MCP-ACCESS-GOVERNANCE.md
- **OAuth/credentials:** credential refs на connection; grants/delegations (`.../grants/{grantId}/delegations`), Composio-интеграция (`composio-session-manager`), cloud-connector enrollment. Для «12+ MCP за корпоративным OAuth2-прокси» это ближайшая аналогия, но реализация — своя. https://github.com/paperclipai/paperclip/blob/master/server/src/routes/openapi.ts
- **Известные ограничения v1** (https://github.com/paperclipai/paperclip/blob/master/doc/MCP-ACCESS-GOVERNANCE.md):
  - нет CLI для tool access (только UI + REST);
  - нет bulk catalog review;
  - trust rules матчат только точную форму аргументов;
  - rate limits — per-policy, без cross-policy агрегации;
  - expiry approval фиксирован политикой, человек не продлевает из UI;
  - **endpoint-режим не policy-governed** (только gateway);
  - нет multi-region runtime supervisor (slots не мигрируют между воркерами);
  - V1 не заявляет host-wide MCP enforcement: неподконтрольный внешний клиент/ручной adapter-config может обойти shлюз.

---

## 8. Расширения / плагины

- **Плагинная система instance-wide**, out-of-process: плагины — «trusted, typed, additive extensions», дающие UI-slots, event-handlers, jobs, webhooks, tools, DB-namespaces и managed Paperclip-ресурсы (agents, projects, routines, skills). https://zread.ai/paperclipai/paperclip/19-plugin-architecture
- **Протокол:** host ↔ worker — **stdio JSON-RPC 2.0**; каждый плагин — отдельный worker-процесс; изоляция сбоев. https://github.com/paperclipai/paperclip/blob/master/doc/plugins/PLUGIN_SPEC.md
- **Манифест/капабилити:** установка разрешает npm-пакет, валидирует манифест, проверяет совместимость версии API, показывает оператору запрашиваемые capabilities, персистит установку в Postgres, стартует worker, делает health-check, помечает `ready`/`error`. https://zread.ai/paperclipai/paperclip/19-plugin-architecture
- **Routes:** плагин может выставлять JSON-only HTTP-роуты в своём namespace `/api/plugins/:pluginId/api/*`; **не может** затенять/переопределять core-роуты. https://zread.ai/paperclipai/paperclip/19-plugin-architecture
- **UI:** **14 типизированных UI-slots** (`ui.slots` в манифесте), каждый ссылается на `exportName`. **Ограничение:** plugin UI сейчас исполняется как same-origin JS **без sandbox по capabilities** — «treat plugin UI as trusted code»; iframe-sandboxing запланирован. https://zread.ai/paperclipai/paperclip/19-plugin-architecture
- **Примеры в репо:** hello-world (ui), kitchen-sink (ui/automation/workspace/connector), fake-sandbox, plugin-llm-wiki, plugin-workspace-diff, sandbox-providers. https://github.com/paperclipai/paperclip/blob/master/packages/plugins
- **Adapter plugins:** внешний loader (`plugin-loader.ts`) резолвит npm-пакеты из adapter-plugin-store, валидирует контракт и регистрирует `ServerAdapterModule`; есть hot-reload (`reloadExternalAdapter`). https://zread.ai/paperclipai/paperclip/13-adapter-system-and-registry
- **Компания как переносимый пакет:** Markdown-first (YAML frontmatter) формат `agentcompanies/v1-draft`; экспорт/импорт компании (agents, skills, projects, routines, issues) со secret-scrubbing, collision-handling, fidelity-report. Дефолтный набор include: `company: true, agents: true, projects: false, issues: false, skills: false`. Skills-экспорт маппится по namespace: `company/* → skills/company/<prefix>/<slug>`, `local/* → skills/local/<namespace>/<slug>`, `url/* → skills/url/<host>/<slug>`, `<owner>/<repo>/* → skills/<owner>/<repo>/<slug>`, голый slug → `skills/<slug>`. У state-машины импорта ключевой инвариант: **`completed` — терминальное состояние, `failed` — повторяемое**, `applying`-раны эксклюзивны, осиротевшие `applying` при рестарте переводятся в `failed`; идемпотентный ключ выводится из содержимого манифеста. Запрещённые к импорту adapter-типы (`process`, `http`) отклоняются. https://zread.ai/paperclipai/paperclip/22-company-portability
- **Catalog-install:** `POST .../skills/install-catalog`, `.../teams/catalog/{catalogId}/install`, `.../skills/import`, `.../skills/browse-project`, `.../skills/scan-projects`; тест-ранны шаблонизированы (`skill-test-run-templates`). https://github.com/paperclipai/paperclip/blob/master/server/src/routes/openapi.ts
- **Secrets как часть расширяемости:** провайдеры секретов (по умолчанию `local_encrypted`; AWS provider), `company_secret_bindings`, `user_secret_definitions/declarations`, `secret_access_events`, per-agent secret bindings, strict-mode против inline-env-секретов, `secrets migrate-inline-env`. https://github.com/paperclipai/paperclip/blob/master/doc/DATABASE.md
- **Bundled plugins и managed auto-install:** self-hosted при старте гарантирует только kubernetes-bundle; managed-инстанс получает список `plugins.autoInstall` из `PAPERCLIP_MANAGED_CONFIG` и ставит их из bundled-каталога (positive allowlist, fail-closed на неизвестный ключ). https://github.com/paperclipai/paperclip/blob/master/server/src/app.ts

---

## 9. Сильные и слабые стороны (с точки зрения наших целей)

### Сильные стороны

- **Зрелая модель «компании»:** company → goal → project → milestone → issue → sub-issue с обязательной трассировкой к миссии; org-chart, reporting lines, делегация вверх/вниз. Прямо релевантно нашему «CEO-оркестратору». https://github.com/paperclipai/paperclip/blob/master/doc/PRODUCT.md
- **Atomic checkout + execution locks + single-assignee** — доказавший себя механизм против double-work; чёткое разделение `checkoutRunId` (ownership) и `executionRunId` (live path). https://github.com/paperclipai/paperclip/blob/master/doc/execution-semantics.md
- **Durable continuation scheduler без in-memory timers:** сервер реконструирует намерение из БД; рестарт не теряет работу. Это ровно наш кейс «сессии в PostgreSQL + вытесняющий планировщик». https://github.com/paperclipai/paperclip/blob/master/doc/architecture/durable-continuation-scheduler.md
- **Liveness-контракт** (явный набор допустимых action-path primitives + отказ от prose-only blocked) — очень полезная формальная модель для нашего workflow-движка. https://github.com/paperclipai/paperclip/blob/master/doc/execution-semantics.md
- **Blockers как first-class** с автопробуждением при разрешении (`issue_blockers_resolved`), отдельно от parent/child. https://github.com/paperclipai/paperclip/blob/master/doc/execution-semantics.md
- **Governance/approvals:** board approve для найма, entity-level approvals, execution policies с review/approval stages, decision-tracking, budget hard-stops, pause/resume/terminate, append-only audit. https://github.com/paperclipai/paperclip/blob/master/README.md
- **Бюджеты:** двойной учёт — канонический ledger (`cost_events`) + policy-enforcement; метрика `billed_cents`, окна `calendar_month_utc` для агента / `lifetime` для проекта; persistent `budget_incidents` с инвариантом «один незакрытый инцидент на policy×threshold×window»; soft 80% / hard 100% с graceful cancel активных run. https://zread.ai/paperclipai/paperclip/12-budget-and-cost-control
- **MCP gateway** — самая проработанная из виденных нами моделей tool-governance: profile/policy/approval/trust-rule/audit/comparison-hases. Хороший образец для нашей интеграции 12+ MCP за OAuth2-прокси. https://github.com/paperclipai/paperclip/blob/master/doc/MCP-ACCESS-GOVERNANCE.md
- **Bring-your-own-agent:** адаптеры для Claude Code, Codex, Gemini, OpenCode, Pi, Cursor, OpenClaw, Hermes, kimi/grok/local, HTTP/process, policy-плагины. «If it can receive a heartbeat, it's hired». https://github.com/paperclipai/paperclip/blob/master/README.md
- **Портативность компании** (markdown-first export/import) и **company-scoped multi-tenancy** — сильные продуктовые ходы.
- **PRP/runner** с чётким trust-boundary, durable outbox, idempotent command identity и fail-closed протоколом. https://github.com/paperclipai/paperclip/blob/master/doc/architecture/paperclip-runner.md

### Слабые стороны / риски

- **Другая экосистема:** Node.js/TypeScript monorepo, Drizzle, pnpm, embedded Postgres, Rust-runner. Наш стек — Java 25 / Spring Boot 4.1 / Spring AI 2.0; перенос кода невозможен, только идеи. https://github.com/paperclipai/paperclip/blob/master/README.md
- **Гигантская и слабо нормированная API-поверхность:** 300+ путей в одном `openapi.ts`, 150+ service-модулей; риск для нашего contract-first OpenAPI-подхода — не копировать breadth, а дисциплинированно ограничивать. https://github.com/paperclipai/paperclip/blob/master/server/src/routes/openapi.ts
- **Heartbeat = Token burn:** «each heartbeat, agents re-fetch assignments, issue details, ancestor chains and full comment threads»; при многих агентах — постоянные token-расходы даже без работы; multi-hop delegation (CEO→CTO→Engineer) заставляет верхние уровни перечитывать контекст. Признано проблемой, план — incremental endpoints (`inbox-lite`, `heartbeat-context`, `comments?after=cursor`) с целью −80%. https://zread.ai/paperclipai/paperclip/12-budget-and-cost-control , https://zread.ai/paperclipai/paperclip/5-issues-and-feedbacks
- **Session-state размазан по адаптерам**, а не унифицирован в БД; есть известные баги восстановления «не той» сессии (#2462 — timer heartbeat восстановил session автоматического run с неверным CWD и потерял непрерывность). https://zread.ai/paperclipai/paperclip/5-issues-and-feedbacks
- **Масштабируемость:** #2553 — 280 issue → крэши процесса по RAM, UI >2 мин/страница, агенты пропускают работу. Частично пофикшено (курсоры, лимиты, lazy markdown). https://zread.ai/paperclipai/paperclip/5-issues-and-feedbacks
- **Безопасность:** закрытый #1818 — `GET /api/companies/{id}/agents` возвращал `adapterConfig` с **plaintext-секретами** env, доступный любому агенту компании. Серьёзный сигнал о рисках «agentConfig в открытом API». https://zread.ai/paperclipai/paperclip/5-issues-and-feedbacks
- **Plugin UI не sandboxed** (trusted code, same-origin) — известное ограничение. https://zread.ai/paperclipai/paperclip/19-plugin-architecture
- **Сложность эксплуатации:** embedded Postgres, миграции, plugin namespaces, runner, ACP, MCP runtime slots, secrets master key; backup БД не включает файлы/ключ. https://github.com/paperclipai/paperclip/blob/master/doc/DATABASE.md
- **Мониторинг хостинга оставлял желать лучшего:** status.paperclip.inc показывал «all systems operational» при реальном отказе продукта (мониторились только 4 shallow endpoint-а, не backend). https://zread.ai/paperclipai/paperclip/5-issues-and-feedbacks
- **Product boundary vs. наш workflow-engine:** у Paperclip статусы issue — это **категории без явного графа переходов**; кастомные состояния возможны, но «граф состояний + разрешённые переходы + терминальные состояния + отдельная сессия/агент на состояние» (наш дизайн) — богаче. У Paperclip терминальность — `done`/`cancelled`, а переход инициирует агент/статус-арбитр, а не декларативный граф.
- **«CEO-агент как оркестратор» — не отдельная система планирования:** CEO — обычный агент-шаблон с делегацией и skills; нет отдельного инструментального API, позволяющего CEO декларативно создавать/редактировать workflow-граф (как наш оркестратор). Ближайшее — plan mode + `create_task`/`set_dependencies`. https://github.com/paperclipai/paperclip/blob/master/doc/execution-semantics.md

---

## 10. Выводы

### Что перенять

1. **Durable-модель планирования (без in-memory таймеров).** Наш ShedLock-скан таблицы сессий с «новыми сообщениями» концептуально совпадает с `agent_wakeup_requests` + continuation-моделью; стоит явно зафиксировать: (а) намерение жить должно быть в БД, (б) recovery-скан при старте и периодически, (в) никогда не полагаться на фоновые локальные процессы как на liveness. https://github.com/paperclipai/paperclip/blob/master/doc/architecture/durable-continuation-scheduler.md
2. **Разделение ownership-лока и live-run.** Две отдельные колонки/понятия (`checkoutRunId` = владение, `executionRunId` = живой run) с crash-safe реконструкцией и «409 = реальный конфликт» — лучше, чем один lock. https://github.com/paperclipai/paperclip/blob/master/doc/execution-semantics.md
3. **Формальный liveness-контракт для workflow-состояний.** Явный список допустимых action-path primitives и правило «prose-only wait → needs_attention, а не silently healthy» напрямую применимо к нашим системным состояниям (webhook-таймаут, bash-скрипт) и к «кривому payload → сессия-разборщик». https://github.com/paperclipai/paperclip/blob/master/doc/execution-semantics.md
4. **Blockers как first-class и отдельно от parent/child.** Плюс автопробуждение `issue_blockers_resolved` — хороший механизм для межуровневой передачи работы. https://github.com/paperclipai/paperclip/blob/master/doc/TASKS.md
5. **MCP-шлюз: profile × policy × approval × trust-rule × audit.** С учётом наших 12+ MCP за корпоративным OAuth2-прокси — брать: risk-классификацию, changed-tool quarantine, approval с exact argument-hash, trust-rule с отзывом, append-only call-event log. https://github.com/paperclipai/paperclip/blob/master/doc/MCP-ACCESS-GOVERNANCE.md
6. **Бюджеты с persistent incident-флагом** (один незакрытый инцидент на policy×threshold×window) и graceful-cancel активных run при hard-stop — зрелая модель. https://zread.ai/paperclipai/paperclip/12-budget-and-cost-control
7. **Skills как Markdown-пакеты `SKILL.md`** с shortname-резолвом и runtime-инъекцией — переносимый, читаемый формат; согласуется с нашей моделью «навыки (skills)» агента. https://zread.ai/paperclipai/paperclip/20-skill-studio-and-training
8. **Org-chart-делегация + Courier pattern** для lateral-координации (создать issue целевому агенту, а не расширять права комментирования) — элегантное решение сильной изоляции. https://github.com/paperclipai/paperclip/blob/master/doc/execution-semantics.md
9. **Идемпотентность через fingerprint** (например, `(sourceIssueId, acceptedPlanRevisionId)`) с durable claim до fan-out и reuse результата при ретрае — готовый паттерн для наших workflow-переходов и агента-оркестратора. https://github.com/paperclipai/paperclip/blob/master/doc/execution-semantics.md
10. **Config-revisions + rollback, append-only activity/audit, company portability** — полезные продуктовые ориентиры для «управляемости» платформы.

### Чего избегать

1. **Не раздувать API до сотен endpoint-ов.** Наш contract-first OpenAPI должен быть дисциплинированным: ресурсные группы, пагинация, лимиты, единые соглашения — а не «каждый сервис — наружу». https://github.com/paperclipai/paperclip/blob/master/server/src/routes/openapi.ts
2. **Не перечитывать весь контекст на каждом пробуждении.** Heartbeat с full-context re-fetch — прямая утечка токенов; сразу проектировать incremental-контекст (cursor по комментариям, отдельный лёгкий heartbeat-context) и/или держать контекст в сессии. https://zread.ai/paperclipai/paperclip/12-budget-and-cost-control
3. **Не отдавать секреты через конфиг в общий API.** Кейс #1818 (plaintext env в `adapterConfig`) — урок для наших `agent.secrets`/MCP-токенов: выдавать только scoped, короткоживущие, и никогда не возвращать значения наружу. https://zread.ai/paperclipai/paperclip/5-issues-and-feedbacks
4. **Не полагаться на «локальные процессы» как на долгоживущее состояние.** Наш аналог — webhook-таймауты и bash-скрипты должны быть системными состояниями в БД с таймаутом и восстановлением, а не внешними процессами, которые «вроде работают». https://github.com/paperclipai/paperclip/blob/master/doc/execution-semantics.md
5. **Не размазывать session-state по адаптерам.** Наше преимущество — единое хранение всех сессий в PostgreSQL; нужно сохранить унифицированную модель сессии/сообщения в БД и не позволять адаптерам «прятать» состояние (Paperclip как раз уязвим здесь — см. #2462). https://zread.ai/paperclipai/paperclip/5-issues-and-feedbacks
6. **Не строить из мультиагентной координации token-heavy multi-hop.** Многоуровневая эскалация CEO→CTO→Engineer заставляет каждый уровень перечитывать контекст; в нашем workflow-движке переходы должны быть точными и дешёвыми.
7. **Не игнорировать scale с самого начала.** История #2553 (280 issue → крэши, 2-мин страницы) — аргумент за cursor-пагинацию, лимиты и ленивую загрузку в нашем API/UI с первого дня.
8. **Не считать статусы issue полноценным workflow.** Нам стоит сохранить и подчеркнуть наше преимущество — явный workflow-граф (кастомные состояния + разрешённые переходы + терминальные состояния + сессия/агент на состояние), которого у Paperclip нет.

### Как соотносится с нашей архитектурой

| Наш элемент (spring-harness) | Аналог у Paperclip | Комментарий |
|---|---|---|
| Сессии агентов в PostgreSQL | `heartbeat_runs` / `heartbeat_run_events` / adapter session state | У нас — единое хранилище; у Paperclip session-state частично у адаптера. |
| Вытесняющий планировщик на ShedLock (скан раз в 1–5 c) | `HEARTBEAT_SCHEDULER_INTERVAL_MS` (default 30 c, min 10 c) + `agent_wakeup_requests` | Их интервал крупнее; модель «wake request» близка к «есть новые сообщения». |
| Workflow (кастомные состояния + граф переходов) | Issue statuses по фиксированным категориям | У нас граф богаче; у них — категории без явных переходов. |
| Системные состояния (webhook-таймаут, bash-скрипт) | `monitor` / explicit continuation / `blocked` с action-path | Их liveness-контракт — хорошая спецификация для наших системных состояний. |
| Агент-оркестратор («CEO») как инструмент | CEO-агент-шаблон + plan mode + `create_task`/`set_dependencies` | У нас CEO инструментально управляет workflow; у них — агент с делегацией. |
| MCP за корпоративным OAuth2-прокси | MCP gateway + connections/grants/OAuth | Брать profile/policy/approval/audit. |
| REST API клиент↔сервер, OpenAPI contract-first | Express REST + Zod→OpenAPI (`/api/openapi.json`) | У них Zod-first; у нас — spec-first: не копировать объём. |

---

### Итоговая оценка

Paperclip — самый близкий к нашей задаче «идейный конкурент»: он решает именно проблему оркестрации «компании агентов» и делает это зрело (atomic checkout, durable continuation, liveness-контракт, MCP-шлюз с governance, бюджеты с hard-stop, org-chart-делегация, company-portability). Его главные уроки — **что** моделировать (ownership vs live-path, wake-requests, blockers, incidents, approvals) и **чего** избегать (token-heavy heartbeat, безбрежный API, session-state у адаптеров, секреты в общем ответе, scale-долг). Технологически (Node/TS/Drizzle/embedded Postgres/Rust-runner) он нам не переносим, но архитектурные паттерны — переносимы напрямую.
