# Ревью M5 PROPOSE — Web Desktop (qwen)

Объект: `openspec/changes/m5-web-desktop/` (proposal, 5 specs, design, tasks).
Контракты: `docs/design/api-contracts.md` §1–§8, M4-спеки (`client-relay`, `client-tool-bridge`, `workspace-download`, `session-api`), `openapi.yaml`.
Сборки не запускались.

## Итог

Архитектурно консистентно: WS-фреймы, close-коды, SSE-семантика, REST-пути — соответствуют §5/§3/§2/§8. D-86…D-91 обоснованы. Есть 2 BLOCKER (невозможные требования) и ряд HIGH.

---

## BLOCKER

### B-1. Артефакты: «браузер файлов» невозможен — нет каталог-эндпоинта
- **Файл**: `specs/desktop-artifacts/spec.md` (Requirement «Браузер файлов workspace»), `tasks.md` 5.3.
- **Что не так**: spec требует «список (имя, размер, изменение) с навигацией по подкаталогам; рефреш» и сценарий «подкаталог → показывается его содержимое». Но серверный контракт §8 — **только** `GET /sessions/{id}/workspace/files?path=<relative>`, отдающий **файл** (200 поток) или 422 `path-invalid` **для каталога**. Каталог-листинга нет (и в M4-спеке `workspace-download`, и в `openapi.yaml` operationId `downloadWorkspaceFile`). Фраза «для каталогов — клиентская навигация, сервер отдаёт только файлы» не объясняет, откуда клиент берёт список имён/размеров/дат — он их не может получить. Требование «список (имя, размер, изменение)» — мёртвое.
- **Предложение**: либо (а) сузить до «скачивание по известному пути» (пользователь вводит/выбирает путь, клиент не обещает листинг) и убрать «имя/размер/изменение» + сценарий «подкаталог»; либо (б) поднять мини-амендмент серверного контракта (новый `GET .../workspace/dirs?path=`) — но это против «M5 = чистый потребитель, серверных изменений нет» (proposal Non-goals, design Risks). Рекомендую (а) + явный non-goal «каталог-листинг — эволюция».

### B-2. Чат: `ASYNC_ACCEPTED` в MessageDto.kind — против контракта
- **Файл**: `specs/desktop-chat/spec.md` (Requirement «Лента сообщений»: «все MessageKind (…/COMPACT/ASYNC_ACCEPTED)»), `tasks.md` 4.2.
- **Что не так**: `MessageDto.kind` по контракту (§2, `openapi.yaml` MessageKind) = `USER|ASSISTANT|SYSTEM|TOOL_CALL|TOOL_RESULT|COMPACT` — **без ASYNC_ACCEPTED**. ASYNC_ACCEPTED — это **статус** в `payload.status` TOOL_RESULT-контракта (agent-tools §5: `status: OK|ERROR|ASYNC_ACCEPTED|CANCELLED|LOST`), а не отдельный kind сообщения. Лента не может рендерить «ASYNC_ACCEPTED» как kind — такого сообщения в журнале нет. Сценарий «async-плейсхолдер» описывает поведение, но привязан к несуществующему kind.
- **Предложение**: переформулировать: плейсхолдер «ожидает результат» рендерится из TOOL_RESULT с `payload.status=ASYNC_ACCEPTED` (или из TOOL_CALL без ещё пришедшего TOOL_RESULT); поздний TOOL_RESULT с `late=true` заменяет его. Убрать ASYNC_ACCEPTED из перечисления «MessageKind» в spec и tasks 4.2.

---

## HIGH

### H-1. Relay: `tool.result` после `tool.cancel` — неоднозначность
- **Файл**: `specs/desktop-relay-client/spec.md` (Scenario «отмена»).
- **Что не так**: «`tool.result` не отправляется (или отправляется после явного завершения — по контракту §5.3)». Контракт §5.3: «поздний `tool.result` игнорируется» (сервером). Клиенту надо **одно** поведение: после `tool.cancel` не отправлять `tool.result` (сервер всё равно игнорирует, но клиент не должен дублировать). Двойная формулировка — дефект спеки.
- **Предложение**: «после `tool.cancel` клиент не отправляет `tool.result` для этого `callId`; если процесс уже завершился до прихода cancel — отправляет (сервер игнорирует дубль по идемпотентности callId)».

### H-2. Relay: `workspace-occupied` — диалог takeover невалиден
- **Файл**: `specs/desktop-relay-client/spec.md` (Requirement «Регистрация…», Scenario «сессия занята»).
- **Что не так**: «`error workspace-occupied` — диалог «сессия занята другим подключением — забрать?» (повторный register → takeover)». По контракту §5.2/`client-relay`: `workspace-occupied` = сессия занята **другим principal** → повторный register **снова** `workspace-occupied` (takeover работает только для **того же** principal → `superseded`). Диалог «забрать?» для другого пользователя не приведёт к успеху — это не takeover, а конфликт.
- **Предложение**: `workspace-occupied` → показать «сессия занята другим пользователем», без кнопки «забрать». Takeover-диалог не нужен вовсе: тот же principal при reconnect автоматически получает `superseded` для старого соединения (клиент сам инициирует повторный register при reconnect — это штатный путь, не «диалог»).

### H-3. Chat: `PARKED_CLIENT` в runtimeStatus-бейджах — не присваивается
- **Файл**: `specs/desktop-chat/spec.md` (Requirement «Список сессий»: «runtimeStatus-бейдж (IDLE / TURN_RUNNING / PARKED_ASYNC)»).
- **Что не так**: перечислены 3 из 4 значений. `PARKED_CLIENT` — зарезервировано, **не присваивается** (D-84, §2 SessionDto). Это не ошибка (бейдж его не покажет), но spec должен явно сказать, что `PARKED_CLIENT` не ожидается, чтобы разработчик не писал обработку.
- **Предложение**: добавить «`PARKED_CLIENT` — зарезервировано, не присваивается (D-84); UI не обрабатывает».

### H-4. Session-tree: обновление дерева — «SSE-событие или poll» — не определено
- **Файл**: `specs/desktop-session-tree/spec.md` (Scenario «оркестратор spawn'ил субагента»).
- **Что не так**: «через обновление дерева по SSE-событию или poll». Контракт §3.1 не имеет события «sub-session created» — есть только `message.created` и `session.status`. Spawn субагента не эмитит отдельного SSE-события, которое клиент мог бы поймать для обновления дерева. «Или poll» — не определён интервал, триггер.
- **Предложение**: зафиксировать: дерево обновляется (а) при переключении на сессию, (б) по таймеру (конфиг, дефолт N с) пока сессия активна, (в) при `session.status` смене (признак активности). Убрать «SSE-событие» (его нет).

### H-5. Relay: reconnect — «takeover при необходимости» — не определено
- **Файл**: `specs/desktop-relay-client/spec.md` (Requirement «Heartbeat и переподключение»).
- **Что не так**: «повторной регистрацией (takeover при необходимости)». По контракту повторный register с **того же** соединения — idempotent success; с **нового** соединения того же principal — takeover (старое 4409 `superseded`). Но если старое соединение ещё живо (не закрыто по heartbeat-таймауту), новый register → `superseded` для старого, `registered` для нового. Клиенту не нужен «takeover при необходимости» — он всегда шлёт register, сервер сам решает. Фраза вводит ложную логику ветвления.
- **Предложение**: «при reconnect клиент шлёт `register` с теми же параметрами; сервер обрабатывает (idempotent / takeover / error) — клиент не ветвит».

### H-6. Shell: SSO — «скрытый BrowserWindow» vs «BrowserWindow» — противоречие
- **Файл**: `specs/desktop-shell/spec.md` (Requirement «SSO-логин»: «открытие скрытого `BrowserWindow`»), `proposal.md` («Keycloak OAuth redirect (BrowserWindow)»), `tasks.md` 2.1 («скрытый BrowserWindow, loopback redirect»).
- **Что не так**: proposal говорит «BrowserWindow» (видимое окно), spec и tasks — «скрытый BrowserWindow». Для OAuth redirect flow в desktop обычно **видимое** окно (пользователь видит Keycloak-страницу, вводит пароль). Скрытое окно — только для silent token exchange. Если окно скрыто, пользователь не видит форму логина.
- **Предложение**: зафиксировать: **видимое** BrowserWindow для авторизации (пользователь вводит креды), после redirect — окно закрывается, токен в safeStorage. Silent refresh — без окна (fetch code exchange).

### H-7. Artifacts: «фронтенд-валидация путей (доп. слой к серверному гварду D-72)» — не определено
- **Файл**: `proposal.md` (desktop-artifacts: «фронтенд-валидация путей (доп. слой к серверному гварду D-72)»), `specs/desktop-artifacts/spec.md` (нет такого требования).
- **Что не так**: proposal обещает «доп. слой» валидации путей на клиенте, но spec не содержит ни одного требования по клиентской валидации. AGENTS.md: «прежде чем писать «защиту», назвать угрозу и её реального потребителя». Угроза: пользователь введёт `../../etc/passwd` в поле пути → сервер ответит 422 (гвард D-72). Клиентская валидация — UX-удобство (не слать заведомо кривые запросы), не безопасность. Если это UX — ок, но надо зафиксировать в spec; если безопасность — это мёртвый слой (серверный гвард уже покрывает).
- **Предложение**: либо убрать из proposal (серверный гвард достаточен), либо добавить в spec: «клиент валидирует относительность пути (не абсолютный, без `..`) перед отправкой — UX, не безопасность».

---

## MEDIUM

### M-1. Chat: «подгрузка по курсору при скролле вверх» — направление
- **Файл**: `specs/desktop-chat/spec.md` (Requirement «Лента сообщений»).
- **Что не так**: `GET /sessions/{id}/messages?since=<seq>` — интервал `(since, …]`, т.е. **после** seq. Для подгрузки **более ранних** (скролл вверх) нужен `before`/`to`, а не `since`. Контракт не имеет `before`-параметра.
- **Предложение**: уточнить: подгрузка ранних — `GET /messages?since=0&limit=N` + клиентская фильтрация, либо поднять амендмент контракта (параметр `before`). Если амендмент — это серверное изменение (против M5 non-goals). Реалистично: клиент держит `lastSeq` и при скролле вверх запрашивает `?since=<lastSeq - N>` — но это не «раньше», а «после». Нужно решение.

### M-2. Relay: `basePath` — «создаётся при отсутствии» — кто создаёт
- **Файл**: `specs/desktop-relay-client/spec.md` (Requirement «Регистрация…»).
- **Что не так**: «`basePath` — … создаётся при отсутствии». Не указано, на каком этапе: при register (main-process, до отправки фрейма) или при первом `tool.call`. Если при register — main создаёт каталог синхронно. Если при tool.call — лениво.
- **Предложение**: зафиксировать: main-process создаёт `basePath` при обработке register (до отправки `register` фрейма серверу), `fs.mkdirSync(recursive)`.

### M-3. Chat: `/compact` и `/stop` — синтаксис команд
- **Файл**: `specs/desktop-chat/spec.md` (Requirement «Отправка и команды»).
- **Что не так**: «команды: `/compact` → `POST .../compact`, `/stop` → `POST .../stop`». Не определено: это slash-команды в поле ввода (пользователь печатает `/compact` и Enter) или отдельные кнопки? Если slash — надо парсить ввод. Если кнопки — не надо.
- **Предложение**: зафиксировать: кнопки «Compact» и «Stop» в UI (не slash-парсинг), либо slash-команды с парсингом. Рекомендую кнопки (проще, нет парсинга).

### M-4. Session-tree: «задачный статус если есть» — какой именно
- **Файл**: `specs/desktop-session-tree/spec.md` (Requirement «Дерево сессий»).
- **Что не так**: «Узел показывает: агент, runtimeStatus-бейдж, задачный статус если есть». `SessionTreeNode` имеет `taskId` и `stateCode` (для STATE), но не `statusProjection` (это `TaskDto`). «Задачный статус» — это `stateCode` (код состояния графа) или `statusProjection` (RUNNING/WAITING/…)? Если `stateCode` — ок (есть в узле). Если `statusProjection` — надо доп. запрос `GET /tasks/{id}`.
- **Предложение**: зафиксировать: в узле дерева показывается `stateCode` (из TreeNode); `statusProjection` — только в панели задачи (по `GET /tasks/{id}`).

### M-5. Shell: «auto-подключение релея к активной сессии» — не в spec
- **Файл**: `proposal.md` (desktop-shell: «авто-подключение релея к активной сессии»), `specs/desktop-shell/spec.md` (нет такого требования).
- **Что не так**: proposal обещает авто-подключение, но spec desktop-shell не содержит требования. Это поведение relay-клиента (desktop-relay-client), не shell.
- **Предложение**: перенести в desktop-relay-client spec («при старте приложения с сохранённой активной сессией — авто-connect + register») или убрать из proposal.

### M-6. Tasks: 3.4 «Подтверждение первой регистрации» — дублирует spec
- **Файл**: `tasks.md` 3.4, `specs/desktop-relay-client/spec.md` (Requirement «Безопасность локального исполнения»).
- **Что не так**: task 3.4 описывает подтверждение, но spec уже содержит это (Scenario «первый раз на сессии»). Дублирование не проблема, но task не ссылается на spec-требование.
- **Предложение**: в task 3.4 добавить ссылку «(spec: desktop-relay-client → Безопасность локального исполнения)».

---

## MINOR

### m-1. Relay: «reconcloud-попытки» — опечатка
- **Файл**: `specs/desktop-relay-client/spec.md` (Scenario «takeover с другого устройства»).
- **Что не так**: «прекращает reconcloud-попытки» → «reconnect-попытки».

### m-2. Chat: «индикатор TURN_RUNNING» — где
- **Файл**: `specs/desktop-chat/spec.md` (Requirement «Отправка и команды»).
- **Что не так**: «индикатор TURN_RUNNING» — не определено, где именно (рядом с полем ввода? в заголовке?).
- **Предложение**: зафиксировать: индикатор в строке состояния чата (рядом с полем ввода), текст «агент работает…».

### m-3. Design: D-86 «markdown-it (или marked — финализируется в пачке A)» — не финализировано
- **Файл**: `design.md` D-86.
- **Что не так**: решение не финализировано («или … финализируется в пачке A»). Для propose-стадия это допустимо, но лучше зафиксировать (markdown-it — зрелее, sanitize-плагин).
- **Предложение**: зафиксировать `markdown-it` + `markdown-it-sanitizer` (или `DOMPurify`).

### m-4. Tasks: 6.1 e2e — «stub-LLM ответы» — не определено
- **Файл**: `tasks.md` 6.1.
- **Что не так**: «оркестратор (stub-LLM ответы) вызывает локальный bash» — не определено, как stub-LLM отдаёт tool-call (WireMock? фиксированный JSON?).
- **Предложение**: зафиксировать: WireMock (или втест-HTTP-сервер) с фиксированным LLM-ответом, содержащим tool-call `bash`.

### m-5. Artifacts: «open-in-OS (temp-копия)» — расширение
- **Файл**: `specs/desktop-artifacts/spec.md` (Requirement «Скачивание и открытие»).
- **Что не так**: «временная копия в `userData/cache/` с тем же именем». Если два файла с одинаковым именем из разных каталогов — коллизия.
- **Предложение**: temp-имя = `hash(path) + basename` или ULID-prefix.

---

## NIT

### n-1. Proposal: «pnpm/npm — выбрать в design» — не выбрано
- **Файл**: `proposal.md` (Impact: «npm-скрипты в корне (`pnpm`/`npm` — выбрать в design)»), `design.md` D-90 (не упоминает).
- **Что не так**: не выбрано. Tasks 1.5 используют `pnpm`.
- **Предложение**: зафиксировать `pnpm` (в design D-90 или tasks 1.1).

### n-2. Shell: «tray-иконка … опционально, конфиг `showTray` (дефолт true)» — дефолт
- **Файл**: `specs/desktop-shell/spec.md` (Requirement «Запуск и окно»).
- **Что не так**: «опционально» + «дефолт true» — противоречие (если опционально, дефолт должен быть false).
- **Предложение**: «tray-иконка — конфиг `showTray` (дефолт true)».

### n-3. Chat: «поиск по `q=`» — где
- **Файл**: `specs/desktop-chat/spec.md` (Requirement «Список сессий»).
- **Что не так**: «поиск по `q=`» — не определено, есть ли поле поиска в UI (input) или только URL-параметр.
- **Предложение**: зафиксировать: поле поиска в шапке списка сессий (debounce 300ms → `GET /sessions?q=…`).

---

## Трассировка tasks → spec (покрытие)

| Spec-требование | Task | Статус |
|---|---|---|
| desktop-shell: Запуск и окно | 1.1, 1.2 | OK |
| desktop-shell: SSO-логин | 2.1 | OK (H-6: видимое/скрытое окно) |
| desktop-shell: Конфиг сервера | 2.2 | OK |
| desktop-shell: IPC-мост | 1.3 | OK |
| desktop-shell: Логи | 1.2 | OK |
| desktop-relay-client: Подключение | 3.1 | OK |
| desktop-relay-client: Регистрация | 3.2 | OK (H-2: workspace-occupied) |
| desktop-relay-client: tool.call | 3.3 | OK (H-1: cancel) |
| desktop-relay-client: Heartbeat | 3.1 | OK (H-5: reconnect) |
| desktop-relay-client: Безопасность | 3.4 | OK |
| desktop-chat: Список сессий | 4.1 | OK (H-3: PARKED_CLIENT) |
| desktop-chat: Лента | 4.2 | **B-2**: ASYNC_ACCEPTED |
| desktop-chat: Отправка | 4.3 | OK (M-3: slash vs кнопки) |
| desktop-chat: SSE | 4.4 | OK |
| desktop-chat: Релей-статус | 4.5 | OK |
| desktop-session-tree: Дерево | 5.1 | OK (H-4: обновление) |
| desktop-session-tree: Задачи | 5.2 | OK (M-4: статус) |
| desktop-session-tree: Переключение | 4.1/5.1 | OK |
| desktop-artifacts: Браузер | 5.3 | **B-1**: нет каталог-эндпоинта |
| desktop-artifacts: Скачивание | 5.3 | OK (m-5: temp-имя) |

## Соответствие AGENTS.md

- **Contract-first**: D-89 (генерация TS из openapi.yaml) — соответствует. WS-фреймы — ручные типы (WS вне OpenAPI) — ок.
- **Конфиг**: все числовые параметры — конфиг (handshake timeout, backoff, confirmCommands, showTray, log-level) — соответствует.
- **Дизайн-решения**: D-86…D-91 — в design.md, будут в decisions.md (task 6.3) — соответствует.
- **Сценарий необходимости**: Web Desktop — первый реальный клиент (proposal Why) — соответствует.
- **Безопасность**: contextIsolation + no nodeIntegration (design Risks, spec desktop-shell IPC) — соответствует. D-88 (локальное исполнение без path-guard) — обосновано (машина пользователя, perimeter SSO).
- **Тесты**: Vitest + Playwright-electron e2e — соответствует (tasks 3.5, 4.6, 5.4, 6.1).

## Вердикт

**REJECT** — 2 BLOCKER (B-1, B-2) требуют переработки spec до approve. HIGH (H-1…H-7) — фикс в spec. MEDIUM/MINOR/NIT — по усмотрению.
