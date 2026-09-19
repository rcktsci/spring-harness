# Ревью пачки I (движок состояний) — GLM-5.3-Flash

Дата: 2026-09-19. Объект: `execution/impl/`: TaskEngineImpl, BashStateExecutor, WaitWebhookStateExecutor, WaitTasksStateExecutor (+WaitTasksScope, TaskGraphReader), InProcessTaskWakeBus, TaskWakeDispatcher, TaskSchedulerJob, TaskTimeoutScannerJob, TaskProperties, task-namespace в WorkspaceContainerManager/ContainerWorkspaceTools, тесты `tests/execution/task/`. Сборки не запускались.

**Вердикт: REJECT** — 6 находок (4 minor / 2 nit). Движок по сути верен (CAS-семантика, идемпотентность, конфиг-числа, ArchUnit-границы), фиксируются хвосты: утечка task-контейнера на нормальном пути, полный payload в reason, дрейф описания TransitionDto с замороженной спекой и пустая apply-notes-секция I.

---

## (1) TaskEngineImpl (I.1) — ✅
Один CAS-UPDATE (гвард `current_state = expected AND suspended = false`; stop'овский CAS без гварда — в TaskRegistry, winner-семантика §7.2 соблюдена) + INSERT истории в одной транзакции; `task_event_seq = task_event_seq + 1` и `state_attempt` (инкремент только при входе в BASH_SCRIPT) — в том же UPDATE; дедлайн цели: явный `state.timeout` → kind-дефолт `harness.task.transition.kind-timeouts.*` → null (TERMINAL — null); CANCEL в движок не проходит (IllegalArgumentException — stop владеет `'$CANCELLED'`); CAS-промах → null (no-op, «один победитель»); wake/terminal — строго afterCommit. Проверка ребра графа до CAS. Замечание на пачку J: `IllegalArgumentException` при отсутствии ребра должен маппиться в `409 wrong-transition` на api-слое — проконтролировать в J/K (handler).

## (2) BashStateExecutor (I.2) — ✅
`WorkspaceTools.executeBash(taskId, …)` → контейнер `harness-task-<taskId>` (namespace `task-`, workspace `workspaces/task-<taskId>` — WorkspaceContainerManager/ContainerWorkspaceTools подтверждены); in-flight-гвард на taskId (двойной wake — no-op); classify: exit=0→NEXT, ≠0→ERROR, timedOut→TIMEOUT, LOST/ERROR→ERROR, CANCELLED→null (терминал уже записан stop'ом); таймаут состояния = явный `state.timeout` → kind-дефолт; идемпотентность скрипта — инвариант в Javadoc (D-50/R3); `state_attempt` передаётся из задачи (после инкремента движком при входе). Таймаут-скан пропускает in-flight (`isInFlight`) — двойного TIMEOUT нет.

## (3) WaitWebhookStateExecutor (I.3) — ✅ c M-1
Пассивный: NOT_WAITING при не-WAIT_WEBHOOK/терминале (HTTP-слой ответит 409); payloadSchema — ограниченный профиль D-58; провал → ERROR-переход с `reason.validationErrors`; успех → NEXT с source+summary+payload; идемпотентность по построению — повторная доставка после перехода = CAS-промах → NOT_WAITING (409). Semantics 202+ERROR — на HTTP-слое (L.3).

### M-1 (minor). Полный payload пишется в reason без ограничения размера
- **Цитата**: WaitWebhookStateExecutor:75–76 — `reason.put("payloadSummary", summary(payload)); reason.put("payload", payload);` — безусловно.
- **Проблема**: спека (inbound-triggers, «Threat-model и логирование») — `payloadSummary` «компактное summary (**без полного тела, если оно большое**)». Тело до 1 МБ (лимит §0.11) целиком ляжет в `reason_jsonb` каждой доставки — рост истории и export'ов непропорционален.
- **Предложение**: конфиг `harness.webhook.reason-payload-limit` (число — конфиг): payload включается в reason только если byteSize ≤ лимита, иначе — только summary; порог — на усмотрение владельца (дефолт предложить в apply-notes).

## (4) WaitTasksStateExecutor (I.4) — ✅
Scope-выборки по индексам H.1: ALL_CHILDREN (`parent_task_id`), BLOCKED_BY (`task_dependency.blocked_task_id` — обратный индекс), TAGGED (GIN `@>`), EXPLICIT (params, не-UUID — WARN+skip); условия — чистый `WaitTasksScope` (unit-тесты есть); закрытие → NEXT (`closedBy`) / ERROR при ALL_SUCCESS (`failed` — отдельный запрос FAILED/CANCELLED); переход — через TaskEngine CAS; пустой scope → ждём; suspended отсекается CAS-гвардией. `uuidArrayArg` (AbstractSqlTypeValue + `createArrayOf("uuid", …)`) — dev-фикс #1, ограничение pgjdbc задокументировано.

## (5) Шина/джобы (I.5) — ✅ c M-2
`InProcessTaskWakeBus` — event-only, «НЕ SSE» задокументировано (durable-курсор — TaskWakeBroadcaster, пачка J.4); потери после рестарта страхует POLL. Джобы: ShedLock-ключи `task-scheduler` и `task-timeout-scanner` (TTL/интервалы — конфиг); POLL: AGENT+RUNNING без STATE-сессии → повторный EVENT-wake (переходный механизм до J.3 — dev-заметка, `NOT EXISTS` по partial-unique сессий) + WAIT_TASKS переоценка; **WAIT_WEBHOOK в POLL не входит** — только таймаут-скан (согласовано с re-approval плана). Таймаут-скан: `deadline_at <= now() AND status IN (RUNNING,WAITING)`; in-flight bash — пропустить (доведёт executor); остатки после рестарта — контейнер добивается (`removeContainer(TASK_NAMESPACE, taskId)`) + TIMEOUT-переход. `bash-dispatch.enabled` — гейт (dev-заметка; в yml `enabled: true`, тесты зовут executor напрямую); универсальная переоценка барьеров на любой wake/terminal (dev-фикс #2) — идемпотентна, batch из конфига; виртуальные потоки + shutdown.

### M-2 (minor). task-контейнер не удаляется при нормальном выходе из BASH-состояния — утечка
- **Цитата**: единственный вызов `removeContainer(TASK_NAMESPACE, …)` вне менеджера — `TaskTimeoutScannerJob:79` (только TIMEOUT-путь); Javadoc RestartScan: «harness-task-* (M2) не трогаются».
- **Проблема**: спека workspace-tools (дельта): «Lifecycle контейнера — задача». Успешный/ошибочный bash → переход дальше — контейнер остаётся остановленным навсегда (task_event_seq/timeout его уже не касаются: у не-bash состояний дедлайн null, скан их не подбирает). Накопление: один мёртвый контейнер на каждую bash-задачу.
- **Предложение**: удалять task-контейнер после применённого исхода bash (в `dispatchBash` после `applyOutcome`, или по терминалу задачи в диспетчере); рестарт-хвосты уже покрыты таймаут-сканом. Альтернатива — расширить рестарт-скан на `harness-task-*` без активного bash. Одна из двух — на выбор dev, зафиксировать в apply-notes.

## (6) ArchUnit — ✅
`execution.impl` импортирует из task только контракты/модели (`Task`, `TaskRegistry`, `TaskStateKind`, `TaskStatus`, `Transition(Kind)`, `TaskWakeListener`) — ни одного `task.impl.*`; шина реализует `task.TaskWakeListener` (direction execution→task); графики/edges — собственный `TaskGraphReader` (SQL по ревизии, не workflow-классы) — граница выдержана.

## (7) Фиксы/заметки dev — частично, см. M-3/M-4
- Фикс #1 (UUID-массив) — подтверждён (uuidArrayArg). Фикс #2 (универсальная wake-переоценка) — подтверждён (dispatcher: reevaluateBarriers на каждый wake/terminal). Фикс #3 (детерминизм тестов) — тесты на месте (TaskSchedulerJobsTest/TaskWakeBusTest/BashStateExecutorClassifyTest/WaitTasksScopeTest + 3 интеграции), «jobs test-tick» — заметка #4.
- Заметки #2 (bash-dispatch gate), #3 (AGENT-bootstrap переходный) — подтверждены кодом.

### M-3 (minor). Дрейф с замороженной спекой: TransitionDto описывает `stdout/stderr`, реализация пишет `output`
- **Цитата**: openapi.yaml:1896 — «bash — {exitCode, **stdout, stderr**, durationMs, attempt}»; BashStateExecutor.reason — `reason.put("output", result.output())` («stdout и stderr объединены» — контракт workspace-tools).
- **Проблема**: поле reason — свободный JSONB, но его описание в замороженной спеке теперь врёт (парсер/клиент поля stdout/stderr не найдут). Плюс apply-notes: **секции «Пачка I» нет** — 3 фикса и 4 заметки девиаций не документированы (тот же паттерн, что DS H-9 в пачке H; тогда судья потребовал документировать).

### M-4 (minor). apply-notes: отсутствует секция «Пачка I»
- **Проверено**: заголовки файла — D.2/D/H (+round 2); упоминания пачки I — только ссылка из H («появятся в пачке I»). Сюита/решения/девиации пачки I (UUID-массив, универсальная переоценка, output-vs-stdout, bash-dispatch gate, переходный AGENT-bootstrap, jobs test-tick, M-1/M-2/M-3 из этого ревью) не зафиксированы.
- **Предложение**: дописать секцию I (решения + результаты ревью-цикла) до коммита пачки; в неё же — строку про обновление описания TransitionDto (M-3).

## Nits

- **N-1**: `resolveCwd` подставляет только `${task.id}` (остальные `${…}`-выражения молча останутся в пути) — MVP-профиль задокументирован в Javadoc; при появлении `${task.params.*}` в workspace.path — добавить WARN.
- **N-2**: `reevaluateBarriers` — полный проход WAIT_TASKS на каждый wake (cap batch) — масштаб MVP задокументирован в Javadoc диспетчера; адресную сверку scope не заводить без сценария.

---

## Позитив

- CAS-семантика всех переходов (движок/stop/вебхук/WAIT_TASKS/таймаут) сведена в один механизм — «один победитель, проигравшие no-op» выдержана везде.
- Все числа — конфиг (TaskProperties + yml: poll-interval, batch-size, ttl, scan-interval, kind-timeouts, bash-dispatch) — правило владельца соблюдено; биндинг-тест есть.
- afterCommit-публикации и in-flight-гварды закрывают двойные прогоны; WAIT_WEBHOOK корректно выведен из POLL.
- Границы модулей чистые; шина честно отделена от SSE (документировано в двух местах).

## Вердикт

**REJECT** — 6 находок (4 minor / 2 nit): M-1 лимит payload в reason, M-2 утечка task-контейнера на нормальном пути, M-3 дрейф описания TransitionDto, M-4 apply-notes I; nits N-1/N-2 — по желанию. Все фиксы локальные; после них — approve.

---

# Re-approval пачки I (2026-09-19)

## Статус моих находок

- **M-1 (полный payload в reason)** — **закрыто жёстче моего предложения**: в reason хранится только `payloadSummary` — `{topKeys, byteSize}` в пределах лимита, сверх `harness.webhook.payload-summary.byte-size-limit` — `{byteSize, truncated: true}` (WaitWebhookStateExecutor:109–129, WebhookProperties.payloadSummary().limitOrMax()); полное тело не хранится вовсе (D-29-совместимо). Спека-сценарий «без полного тела, если оно большое» выполнен с запасом; число — конфиг.
- **M-2 (утечка task-контейнера)** — **закрыто**: контейнер одноразовый — снимается на любом исходе bash (NEXT/ERROR/TIMEOUT/отмена/исключение) в BashStateExecutor:82–84; timeout-путь дублирует снятие для рестарт-хвостов. «Lifecycle = задача» теперь буквально: один контейнер на одно исполнение состояния, workspace на хосте переживает (следующий вход пересоздаёт — state_attempt растёт).
- **M-3 (дрейф TransitionDto)** — **закрыто**: openapi.yaml:1896–1897 — «bash — {exitCode, output, durationMs, attempt}, где output — stdout и stderr, объединённые в один» — спека и реализация совпадают.
- **M-4 (apply-notes I)** — **закрыто**: секция «Пачка I — движок состояний…» (line 202): зафиксированные решения, фиксы ревью (применённые до цикла), тесты пачки.
- **N-1/N-2 (nits)** — N-2 задокументирован в Javadoc диспетчера (MVP-масштаб); N-1 остаётся на будущее (не блокер, при появлении не-`${task.id}` плейсхолдеров).

## Попутно проверено (фиксы DS/судьи в моей зоне просмотра)

- **I-3 (self в scope)** — WAIT_TASKS-задача исключена из собственных scope: `AND blocker_task_id <> ?` (BLOCKED_BY), `AND id <> ?` (TAGGED/ALL_CHILDREN) — барьер не закрывается сам от себя.
- Контейнер-фикс заодно убирает мой рестарт-хвост (timeout-скан больше не единственный уборщик).

## Вердикт re-approval

**APPROVE** — незакрытых: 0. M-1…M-4 закрыты (M-1 — строже требуемого), фиксы DS в моей зоне подтверждены; движок готов к пачке J (STATE-сессии/мета-инструмент).
