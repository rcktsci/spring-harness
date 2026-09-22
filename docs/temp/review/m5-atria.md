# Ревью M5 PROPOSE «Web Desktop» — Atria-Dawn

- **Фаза**: PROPOSE (planning artifacts: proposal.md, design.md, tasks.md, 5 specs)
- **Объект**: `openspec/changes/m5-web-desktop/`
- **Эталон**: `docs/design/api-contracts.md` §1–§9, `src/main/resources/api/openapi.yaml` (машиночитаемый контракт), M4-спеки (`client-relay`, `client-tool-bridge`, `workspace-download`, `session-api`), `docs/design/decisions.md` D-72…D-85, AGENTS.md
- **Сборки/тесты**: не запускались (запрещено)
- **Дата**: 2026-09-22

## Итог

Архитектурно зрелое, хорошо структурированное предложение: изоляция от Maven-сборки, contract-first (D-89), pure-consumer (без серверных изменений), D-86…D-91 оформлены по стандарту (решение → альтернативы → почему). Соответствие готовому контракту в целом высокое: WS-фреймы, close-коды 4401/4403/4409, SSE-семантика, REST-пути — бьются с §5/§3/§2/§8.

Но: **1 BLOCKER** (невозможное требование к замороженному контракту) + **5 HIGH** (в т.ч. неотдекларированное архитектурное решение «где живёт JWT/WS/REST»), без исправления которых freeze некорректен. Вердикт: **REJECT (условно)** — после фиксов must-fix повторная ревизия → approve.

---

## BLOCKER

### BL-1. Артефакты: «браузер файлов» невозможен — нет каталог-эндпоинта
- **Файлы**: `specs/desktop-artifacts/spec.md` (Requirement «Браузер файлов workspace», сценарии «открытие браузера»/«подкаталог»), `tasks.md` 5.3, `proposal.md` (capabilities `desktop-artifacts`)
- **Что не так**: spec требует «список (имя, размер, изменение) с навигацией по подкаталогам; рефреш». Замороженный контракт §8 предоставляет **только** `GET /api/v1/sessions/{id}/workspace/files?path=<relative>` (openapi.yaml: operationId `downloadWorkspaceFile`) — отдачу **файла**; каталог → 422 `path-invalid` (подтверждено M4-спекой `workspace-download`: «запрошен каталог → 422 path-invalid»). Каталог-листинга в контракте нет. Фраза «для каталогов — клиентская навигация, сервер отдаёт только файлы» не объясняет, откуда клиент возьмёт имена/размеры/даты — их неоткуда взять. Требование и оба сценария — мёртвые.
- **Предложение**: вариант (а) — сузить capability до «скачивание/открытие по известному пути» (путь из чата/дерева/ручной ввод; листинг — явный non-goal «эволюция»); вариант (б) — мини-амендмент серверного контракта (`GET .../workspace/dirs?path=`), но это прямо противоречит Non-goals («серверные доработки любого рода») и design Risks. Рекомендую **(а)**. Примечание: для SERVER-сессий файлы в workspace создаёт агент — пользователь знает имена из ленты/логов; для CLIENT-сессий серверный workspace и так пуст (§8).

---

## HIGH

### H-1. Не отдекларировано: кто владеет JWT и где живут REST/WS/SSE-клиенты
- **Файлы**: `design.md` D-91 (`renderer: api/ (generated + clients)`, `composables/ (useSse, useRelay, useSession)`), `specs/desktop-shell/spec.md` (IPC-мост), `proposal.md` (Non-goals: «`auth/ticket` — не нужен (Electron держит JWT)»)
- **Что не так**: архитектурно критичное решение не зафиксировано, а из артефактов следует, что renderer сам делает REST/SSE/WS с Bearer. Следствия:
  1. **WS с Bearer в принципе невозможен в renderer**: Chromium `WebSocket` не умеет ставить заголовок `Authorization`. Контракт §5 требует Bearer-JWT (либо билет `auth/ticket` — §9, WebUI-фаза). M5 выкинул ticket из Non-goals — **единственное** обоснование этого non-goal: WS-клиент работает в **main** (Node `ws`), renderer-ный `useRelay` — лишь IPC-мост. Это нужно сказать явно (сейчас из D-91 следует обратное).
  2. **Утечка секрета через XSS**: renderer рендерит markdown и tool-output, пришедшие от агента (markdown-it + sanitize — хорошо, но это не единичный слой). Если renderer держит JWT для `fetch` — инъекция в UI уводит токен. Electron-way: **main владеет токеном** (safeStorage), сам делает REST/fetch-stream/WS, отдаёт в renderer только данные по IPC; save-as/open-in-OS/dilog — и так main-only API.
  3. IPC-сценарий «renderer инициирует локальное исполнение bash» (desktop-shell) перепутал направление: инициатива — серверная (`tool.call` через WS в main). В режиме `confirmCommands` поток: main → renderer (команда на подтверждение) → confirm → main исполняет → `tool.result`.
- **Предложение**: дополнить design (D-86/D-91) и desktop-shell: «секреты и все сетевые клиенты (REST, SSE fetch-stream, WS) — в main-процессе; renderer получает только типизированный IPC-мост (`contextBridge`), JWT в renderer не попадает; `useRelay`/`useSse`/api-обёртки — IPC-консьюмеры». Это закрывает и Non-goal по `auth/ticket`, и H-1.2, и драфт `sandbox: true` (см. M-7).

### H-2. `workspace-occupied` — диалог «забрать» невалиден
- **Файлы**: `specs/desktop-relay-client/spec.md` (Requirement «Регистрация…», сценарий «сессия занята»), `tasks.md` 3.2
- **Что не так**: по §5.2/D-78 `workspace-occupied` = сессия занята живым соединением **другого** principal; takeover (4409 `superseded` старого) работает только для **того же** principal. Повторный register при чужом занятии снова даст `workspace-occupied`. Диалог «забрать?» обещает невозможное. Штатный takeover того же пользователя происходит автоматически при reconnect/re-register — без всякого диалога.
- **Предложение**: `workspace-occupied` → информационное «сессия занята другим пользователем», без кнопки «забрать». Takeover-сценарий убрать; оставить: «reconnect → повторный register → сервер сам разбирается (idempotent для того же соединения / takeover для того же principal / error)» (перекликается с Qwen H-5 — согласен).

### H-3. `tool.result` после `tool.cancel` — самопротиворечивый сценарий
- **Файлы**: `specs/desktop-relay-client/spec.md` (сценарий «отмена»)
- **Что не так**: «`tool.result` не отправляется (или отправляется после явного завершения — по контракту §5.3)». Контракт: поздний `tool.result` игнорируется сервером (идемпотентность по callId). Спека должна содержать **одно** поведение.
- **Предложение**: «после `tool.cancel` клиент останавливает процесс (SIGTERM → SIGKILL по таймауту) и **не отправляет** `tool.result`; если процесс успел завершиться до прихода cancel — результат отправляется, сервер его игнорирует». Заодно добавить **отсутствующее** клиентское обязательство из §5.3: клиент обязан игнорировать `tool.cancel` для неизвестного `callId` (гонка stop с публикацией вызова) — это MEDIUM-уровневая дыра в спеке (см. M-2).

### H-4. `confirmCommands` — дефолт не зафиксирован нигде; D-88 требует явного согласия владельца
- **Файлы**: `specs/desktop-relay-client/spec.md` (Requirement «Безопасность локального исполнения»: «дефолт never для root-сессий владельца? — фиксируется в design»), `design.md` D-88 (дефолт не указан)
- **Что не так**: spec делегирует в design, design — молчит. Между тем это **самый рискованный decision M5**: оркестратор (prompt-injection через вывод инструментов) получает arbitrary `bash` на хосте владельца. Асимметрия: серверный `bash` исполняется в per-session Docker-контейнере (D-30), а клиентский — **на голой машине** без sandbox. AGENTS: «прежде чем писать "защиту", назвать угрозу и её реального потребителя; не признал владелец — не делать» — D-88 угрозу называет верно, но owner-апрува в artifacts ещё нет (PROPOSE), а дефолт `never` делает исполнение полностью тихим.
- **Предложение**: (1) зафиксировать дефолт до impl — рекомендую `always` для вновь подключаемых сессий (разрешение выдаётся per-session на экране «разрешить оркестратору выполнять команды в X» и может быть отозвано); (2) вынести D-88 отдельной строкой risk-апрува в PROPOSE-обзор для владельца; (3) серверный `tool-call-timeout` (5 мин, §5.4) — потолок жизни клиентского вызова, `tool.progress` его **не** сбрасывает (контракт не даёт такого) — отразить как_known limitation (см. M-6).

### H-5. Несоответствие имени capability/spec: `desktop-artifacts` vs «workspace-artifacts»
- **Файлы**: `specs/desktop-artifacts/spec.md` (заголовок `# workspace-artifacts Specification`), `proposal.md` (capability `desktop-artifacts`), `tasks.md` 6.6/6.7
- **Что не так**: папка и capability — `desktop-artifacts`, заголовок спеки — `workspace-artifacts` (при том, что в `openspec/specs/` уже живёт серверная `workspace-download`). `openspec validate m5-web-desktop --strict` (task 6.6) — на это попадает. Нужно: заголовок `# desktop-artifacts Specification` + строка в Purpose о соотношении с серверной `workspace-download` (потребитель, не дубликат).

---

## MEDIUM

- **M-1. Чат: подгрузка ранних сообщений и initial-tail.** `GET /sessions/{id}/messages?since=&limit=` — интервал `(since, …]`, ascending, `nextCursor` = seq последнего события (openapi.yaml `MessagePage`) — **forward-only**, `before` нет. Спека требует «подгрузку более ранних по курсору при скролле вверх» — курсором в ту сторону нет. Плюс initial-load: `since` по умолчанию 0 → возвращаются **самые старые**, а не хвост. Решение, которое осталось недосказанным: открывать по `SessionDto.lastSeq` (или snapshot `session.status` из SSE) → `since = lastSeq − N`, окно расширять при недостатке (seq-пробылы из-за COMPACT-скрытых событий), дедуп по seq (REST+SSE перекрытие). Зафиксировать стратегию в spec; «по курсору» — убрать/переформулировать. (Согласен с Qwen M-1, дополняю initial-tail.)
- **M-2. Relay: пропущено обязательство «игнорировать `tool.cancel` для неизвестного `callId`»** (§5.3, гонка stop vs публикация). Добавить сценарий.
- **M-3. Relay: нет обработки 4403** (`protocol`/`protocol-mismatch` — §5.1/§5.5). Сейчас reconnect-цикл на 4403 будет крутиться вечно. Фикс: 4403 → fatal, без retry, «сервер требует другую версию протокола».
- **M-4. SSE-требования недоопределены.** desktop-chat: не упомянуты (1) первый ивент при коннекте/реконнекте — snapshot `session.status` (§3.1) — клиент должен уметь его поглощать; (2) `ping`-комментарии (`: ping`, 15 c) — игнорировать; (3) `retry: 5000`; (4) `Last-Event-ID` заголовок приоритетнее `?since=` — design D-87 передаёт его через `?since=`; при `fetch`-стриме можно и нужно слать **заголовок** (контракт позволяет и то, и то, но заголовок — каноничнее). desktop-session-tree: для task SSE (§3.2) не описан курсор `since=task_event_seq` и snapshot `task.status` при коннекте.
- **M-5. Дерево: триггер обновления не определён** («по SSE-событию или poll»). События «sub-session created» в §3.1 нет. Точные варианты: (а) refresh дерева при `message.created` с TOOL_CALL/TOOL_RESULT `spawn_subagent` в потоке root-сессии (оркестраторский вызов виден в ленте); (б) refresh при переключении/`session.status`; (в) poll-таймер (конфиг). Зафиксировать (рекомендую (а)+(б), poll — опционально). (Согласен с Qwen H-4, уточняю триггер.)
- **M-6. Клиентские инструменты: потолок `tool-call-timeout`** (сервер, 5 мин, §5.4). Долгий локальный `bash` (> 5 мин) закроется синтетическим `tool-timeout` на сервере, а процесс на клиенте останется (или отправит игнорируемый `tool.result`). Спеке — сказать про потолок и про то, что клиентский таймаут (tasks 3.3) должен быть меньше серверного; `tool.progress` не продлевает срок (контракт не предполагает).
- **M-7. Electron-харднинг неполный.** design Risks: `contextIsolation` + `nodeIntegration: false` — есть; `sandbox: true` и CSP — нет. Renderer рендерит агентский markdown/tool-output. Если H-1 принят (main владеет секретами), CSP + sandbox — дешёвая глубина (одна строка + заголовок); sanitize markdown — отдельно (renderer, DOMPurify).
- **M-8. Релей- lifecycle при переключении сессий.** Регистрация — на FREE root-сессию; sub-session-чаты (STATE) не регистрируются (релей виден по parent-chain, D-84). Спеки не говорят, что происходит с WS-регистрацией при переключении активной сессии (re-register на новой / одно соединение на всю сессию). Зафиксировать.
- **M-9. Тест-бар против AGENTS.** AGENTS: «живой Keycloak в контексте всегда (профили-заглушки SSO запрещены)». Playwright-electron e2e против stub-сервера (tasks 6.1) — ок для UI-логики, но stub должен честно реализовать §5 целиком (4401/4403/4409, heartbeat, cancel-гонка, takeover), иначе e2e будет зелёным на неверном протоколе. Smoke «против живого сервера — manual» (6.2) — слабее проектной планки; лучше — автоматический Playwright-smoke против docker-compose (живой Keycloak, тестовый realm/user): desktop — всего лишь клиент, это дёшево.
- **M-10. api-contracts.md §2 отстал от openapi.yaml** (первопричина ошибочного Qwen B-2 — см. кросс-чек). §2 перечисляет MessageKind без `ASYNC_ACCEPTED` и TreeNode без `taskId`/`stateCode`, а openapi.yaml (и `MessageKind.java`, `AgentTurnEngine`) их содержит. D-89 генерирует из openapi.yaml — типы будут верные; но ручные `ws-frames.ts` и люди читают §2/§5. Синхронизировать §2 (doc-fix, можно в рамках M5 doc-sync — tasks 6.4, без серверных изменений).

---

## MINOR

- **m-1. `basePath` — «создаётся при отсутствии»**: не сказано, кто и когда (main до отправки `register`, `fs.mkdir recursive`). (Qwen M-2 — согласен.)
- **m-2. `/compact`, `/stop`**: не определено, это слэш-команды или кнопки. Рекомендация — кнопки + подтверждение для stop (парсинг слэшей не нужен).
- **m-3. «Задачный статус» в узле дерева**: узел содержит `stateCode` (openapi.yaml `SessionTreeNode`), `statusProjection` — только в панели задачи через `GET /tasks/{id}`. Зафиксировать.
- **m-4. «Авто-подключение релея к активной сессии»** (proposal, desktop-shell) — в спеках требования нет. Перенести в desktop-relay-client или убрать.
- **m-5. Хардкод чисел в tasks** (3.1: handshake 10 c, backoff 1→30 с; design: `retry: 5000`): AGENTS — «все числовые параметры — конфиг». Для desktop — `config.json`/defaults-модуль; в tasks — явно «значения — из конфига».
- **m-6. Список сессий «последнее сообщение»**: SessionDto не содержит превью сообщения — нужен доп. запрос (`?since=lastSeq−1&limit=1`) на сессию, либо убрать превью.
- **m-7. `/compact` на STATE-сессии** → 409 `wrong-session-kind` (§2). UI drilling в sub-session должен это понимать (прятать compact).
- **m-8. Клиентский ping-watchdog**: сервер рвёт при 2× heartbeat без pong, но при half-open (спящий ноут) клиент не узнает об этом быстро. Клиентский watchdog на отсутствие `ping` → ранний reconnect.
- **m-9. `safeStorage` недоступен** (Linux без keyring) — понятная ошибка, не тихой деградацией в plain-text (spec desktop-shell прямо запрещает токены в plain-text — хорошо).
- **m-10. Локальные инструменты и ОС**: целевой десктоп — Windows (win-nsis первый таргет); «shell ОС» = `cmd.exe /c`, path-семантика `glob`/`grep`/`fs` различается. Объявить поддерживаемые ОС и per-OS-нейтральность инструментального слоя.
- **m-11. Артефакты: temp-копия «с тем же именем»** — коллизия при одинаковых именах из разных каталогов; ULID/hash-префикс. (Qwen m-5 — согласен.)

## NIT

- **n-1.** `specs/desktop-relay-client/spec.md`: «прекращает reconcloud-попытки» → «reconnect-попытки». (Qwen m-1.)
- **n-2.** `pnpm`/`npm` не выбрано (proposal делегирует в design, D-90 молчит, tasks 1.5 уже `pnpm`) — зафиксировать `pnpm`.
- **n-3.** desktop-shell: «tray-иконка … опционально, конфиг `showTray` (дефолт true)» — «опционально» убрать (конфиг и есть опциональность).
- **n-4.** D-86: `markdown-it` vs `marked` «финализируется в пачке A» — для PROPOSE терпимо, но sanitize должен быть DOMPurify в renderer (не `markdown-it-sanitizer` — мёртвый пакет).
- **n-5.** «индикатор TURN_RUNNING» и «поиск по `q=`» — не определено место в UI (строка ввода / шапка списка). (Qwen m-2/n-3.)

---

## Кросс-чек коллег

- **Qwen B-2 (ASYNC_ACCEPTED — не MessageKind) — НЕ ВЕРНО.** Проверено по авторитетным источникам: `openapi.yaml` `MessageKind: enum: [..., COMPACT, ASYNC_ACCEPTED]`, `MessageKind.java` (перечисление содержит `ASYNC_ACCEPTED`), `AgentTurnEngine` пишет в журнал `MessageKind.ASYNC_ACCEPTED` (плейсхолдер «в полёте»). M5-спека desktop-chat **права** — рендерить ASYNC_ACCEPTED как kind. Первопричина ошибки — **api-contracts.md §2 отстаёт от openapi.yaml** (вынесено в M-10). Вердикт Qwen REJECT на основании 2 блокеров не подтверждается — блокер один (BL-1).
- **Qwen BL-1 (артефакты/каталог-листинг) — согласен** (BL-1), проверено: единственный workspace-эндпоинт — `downloadWorkspaceFile`, каталоги → 422.
- **Qwen H-1/H-2/H-3/H-4/H-5/H-6/H-7 — согласен** (H-4 — с уточнением триггера `spawn_subagent` TOOL_RESULT в root-потоке; H-6 «скрытое BrowserWindow» — да, OAuth-окно должно быть видимым, пользователь вводит креды).
- **Qwen MEDIUM/MINOR/NIT — в основном согласен** (см. m-1…n-5), кроме M-1 — дополняю initial-tail-стратегией.
- **Mercury-2.5**: (1) таблица оценок сроков (3–4 дня на пачку, итого ~21–27 дней) **нарушает AGENTS.md «Никогда не давать оценок сроков»** — убрать; (2) «APPROVE pending cross-checks» — преждевременно: BL-1 и H-1…H-5 пропущены; (3) рекомендации по Batch C («on `workspace-occupied` show modal; confirm → retry register → server sends 4409 to old peer») — **контрактно неверны** (см. H-2: `workspace-occupied` — чужой principal, takeover невозможен); (4) «Config-driven params ✅» — частично: literals в tasks 3.1 (см. m-5); (5) предложение валидировать ws-frames тестами — **беру на заметку** (хорошо: Vitest-ассерт покрытия типов всех фреймов §5.1–5.4).

---

## Соответствие AGENTS.md

| Правило | Статус | Примечание |
|---|---|---|
| Contract-first (спека → генерация) | ✅ | D-89: openapi-typescript из `api/openapi.yaml`; WS-типы ручные (вне OpenAPI) — обоснованно |
| Серверных изменений нет | ⚠️ | Декларировано; BL-1 (артефакты) и M-1 (backward-paging) — кандидаты на mini-amend; M-10 — doc-fix |
| Изоляция от Maven / новый стек | ✅ | `web-desktop/` вне модулей, свой `.gitignore` |
| Все числовые параметры — конфиг | ⚠️ | m-5: literals в tasks 3.1 |
| Решения в decisions.md (решение → альтернативы → почему) | ✅ | D-86…D-91 по формату; добавление — task 6.3 |
| Сущности со сценарием необходимости | ✅ | Web Desktop — первый реальный потребитель (proposal Why); новых серверных сущностей нет |
| Безопасность: изоляция исполнения = контейнер; слои не плодить | ⚠️ | D-88 осознанно выходит за рамку (голый хост вместо контейнера) — оформить risk-апрув владельца + дефолт confirmCommands (H-4); sandbox/CSP — дешёвая глубина (M-7) |
| Тесты воспроизводят рантайм, живой Keycloak | ⚠️ | M-9: stub e2e + manual smoke — ниже планки; автоматизировать smoke против docker-compose |
| Ревью-конвейер (findings в docs/temp/review) | ✅ | данный файл; кросс-чек — выше |
| Оценки сроков | ❌ | Mercury нарушил (см. кросс-чек) |

## Вердикт

**REJECT (условно)** — must-fix перед freeze: **BL-1, H-1, H-2, H-3, H-4, H-5** + желательно M-1…M-6 (они размазаны по спекам и легко правятся в той же итерации). M-7…M-11, MINOR, NIT — на усмотрение разработчика. После исправлений — повторная ревизия (re-approve всеми ревьюерами темы), затем generate/freeze.
