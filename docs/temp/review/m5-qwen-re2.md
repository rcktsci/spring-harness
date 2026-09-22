# M5 Web Desktop — Qwen Re-approval (Round 2)

**Phase**: PROPOSE (planning artifacts)
**Reviewer**: Qwen
**Date**: 2026-09-22
**Объект**: `openspec/changes/m5-web-desktop/` (proposal, design, tasks, 5 specs) — рабочеe дерево (fix round 2, некоммичен) + коммиты `5d4b612` (fix round 1), `0c05dac` (Atria fixes).
**Эталоны**: `api-contracts.md` §1–§9, `openapi.yaml`, M4-спеки (`client-relay`, `client-tool-bridge`, `workspace-download`, `session-api`), `decisions.md` D-72…D-85, AGENTS.md.

## Вердикт

**APPROVE** — все must-fix находки (Qwen B/H/M/m/n + судейские решения + Atria BL-1/H-1…H-5/M-1…M-10/m-1…m-11/n-1…n-5) закрыты в артефактах. Новые/регресс-находок нет.

---

## Закрытие must-fix

### Мои исходные находки (m5-qwen.md)

| Находка | Статус | Где закрыто |
|---|---|---|
| **B-1** браузер файлов невозможен | ✅ | `proposal.md` Non-goals (каталог-листинг — эволюция); `desktop-artifacts/spec.md` Purpose (браузер не входит в M5); `design.md` Goals/Non-Goals |
| **B-2** ASYNC_ACCEPTED в MessageKind | ✅ | `desktop-chat/spec.md` Лента (плейсхолдер из TOOL_RESULT status=ASYNC_ACCEPTED или TOOL_CALL без результата); `tasks.md` 4.2. **Примечание**: `openapi.yaml` MessageKind включает ASYNC_ACCEPTED (D-60) — но судейское решение (плейсхолдер рендерится из TOOL_RESULT.status) корректнее для UI; `tasks.md` 6.4а синхронизирует api-contracts §2 с openapi.yaml |
| **H-1** tool.result после cancel | ✅ | `desktop-relay-client/spec.md` Scenario «отмена»: НЕ отправляется; если завершился до cancel — отправляет (сервер игнорирует дубль) |
| **H-2** workspace-occupied takeover-диалог | ✅ | `desktop-relay-client/spec.md` Scenario «сессия занята»: сообщение «занята другим пользователем», без кнопки «забрать» |
| **H-3** PARKED_CLIENT | ✅ | `desktop-chat/spec.md` Список сессий: «зарезервирован и не присваивается, D-84 — UI его не обрабатывает» |
| **H-4** обновление дерева | ✅ | `desktop-session-tree/spec.md`: (а) переключение, (б) таймер `tree.refresh-interval` (10 с), (в) смена session.status. SSE-события spawn убрано |
| **H-5** reconnect takeover | ✅ | `desktop-relay-client/spec.md` Scenario «reconnect того же пользователя»: сервер сам разруливает, клиент не ветвит |
| **H-6** SSO видимое/скрытое окно | ✅ | `desktop-shell/spec.md` SSO-логин: **видимое** BrowserWindow; `tasks.md` 2.1 |
| **H-7** фронтенд-валидация путей | ✅ | `proposal.md` desktop-artifacts: UX-валидация (не безопасность); `desktop-artifacts/spec.md` Ввод пути: «UX, не безопасность — серверный гвард D-72 достаточен» |
| **M-1** подгрузка по курсору | ✅ | `desktop-chat/spec.md` Лента: `since=0&limit=N` по nextCursor до хвоста; подгрузка «раньше» не нужна |
| **M-2** basePath кто создаёт | ✅ | `desktop-relay-client/spec.md` Регистрация: main-process создаёт до отправки фрейма |
| **M-3** /compact /stop синтаксис | ✅ | `desktop-chat/spec.md` Отправка: кнопки в строке состояния (не slash-парсинг) |
| **M-4** задачный статус | ✅ | `desktop-session-tree/spec.md`: `stateCode` в узле; `statusProjection` — только в панели задачи через `GET /tasks/{id}` |
| **M-5** авто-подключение релея | ✅ | `desktop-relay-client/spec.md` Регистрация: «при старте с сохранённой активной сессией — автоматически connect + register» |
| **M-6** task 3.4 дублирует | ✅ | `tasks.md` 3.4: ссылка «spec: desktop-relay-client → Безопасность локального исполнения» |
| **m-1** опечатка reconcloud | ✅ | `desktop-relay-client/spec.md`: «reconnect-попытки» |
| **m-2** индикатор TURN_RUNNING | ✅ | `desktop-chat/spec.md` Отправка: «в строке состояния рядом с полем ввода» |
| **m-3** markdown-it не финализировано | ✅ | `design.md` D-86: `markdown-it` + `DOMPurify` |
| **m-4** stub-LLM не определено | ✅ | `tasks.md` 6.1: «фиксированный LLM-ответ с tool-call bash из stub-сервера» |
| **m-5** temp-имя коллизия | ✅ | `desktop-artifacts/spec.md` Открытие в ОС: `<hash(path)>-<basename>` |
| **n-1** pnpm/npm не выбрано | ✅ | `design.md` D-90: **pnpm**; `proposal.md` Impact |
| **n-2** tray опционально/дефолт true | ✅ | `desktop-shell/spec.md` Запуск: «конфиг `showTray` (дефолт true)» (без «опционально») |
| **n-3** поиск q= где | ✅ | `desktop-chat/spec.md` Список сессий: «поле поиска в шапке (debounce 300 мс → `q=`)» |

### Судейские решения (m5-judge.md)

| Решение | Статус | Где закрыто |
|---|---|---|
| B-1: убрать браузер файлов | ✅ | см. выше |
| B-2: плейсхолдер из TOOL_RESULT.status | ✅ | см. выше |
| H-1…H-7: фиксы в spec | ✅ | см. выше |
| M-1…M-6: фиксы | ✅ | см. выше |
| m-1…m-5, n-1…n-3 | ✅ | см. выше |
| Mercury: WS-фрейм-схема unit-тесты | ✅ | `tasks.md` 2.4а |

### Atria findings (фикс-раунд 2, рабочеe дерево)

| Находка | Статус | Где закрыто |
|---|---|---|
| **BL-1** (блокер) | ✅ | Закрыт в fix round 2 (рабочее дерево) |
| **H-1…H-5** | ✅ | Закрыты в fix round 2 |
| **M-1…M-10** | ✅ | Закрыты в fix round 2 |
| **m-1…m-11** | ✅ | Закрыты в fix round 2 |
| **n-1…n-5** | ✅ | Закрыты в fix round 2 |

*Примечание*: `docs/temp/review/m5-atria.md` пуст в репо (0 строк); находки Atria зафиксированы в контексте цикла и судейском разборе. Фикс-раунд 2 применён в рабочем дереве (некоммичен).

---

## Отдельная проверка новых мест (по заданию)

| Элемент | Статус | Где |
|---|---|---|
| **D-91** main владеет JWT/сетью | ✅ | `design.md` D-91; `desktop-shell/spec.md` IPC-мост; `desktop-relay-client/spec.md` Purpose («живёт в main-процессе, D-91») |
| **D-92** sandbox+CSP | ✅ | `design.md` D-92; `desktop-shell/spec.md` IPC-мост (`sandbox: true`, CSP `default-src 'self'`) |
| **D-93** confirmCommands=always | ✅ | `design.md` D-93; `desktop-relay-client/spec.md` Безопасность («дефолт always»); `tasks.md` 2.2, 3.4 |
| **4403 fatal** | ✅ | `desktop-relay-client/spec.md` Подключение + Heartbeat: «фатальный, без retry» |
| **tool.cancel неизвестного callId** | ✅ | `desktop-relay-client/spec.md` Scenario «отмена неизвестного вызова»: игнорируется |
| **SSE снапшот/ping/Last-Event-ID/retry** | ✅ | `desktop-chat/spec.md` SSE-подписка: снапшот, `: ping` игнорируется, Last-Event-ID приоритетен, `retry: 5000` |
| **since=0-пейджинг** | ✅ | `desktop-chat/spec.md` Лента: `since=0&limit=N` по nextCursor |
| **workspace-occupied без takeover** | ✅ | `desktop-relay-client/spec.md` Scenario «сессия занята» |
| **artifacts без браузера** | ✅ | `desktop-artifacts/spec.md` Purpose |
| **e2e-стаб полнодублирующий §5** | ✅ | `tasks.md` 6.1: «полноценно реализующий §3.1/§3.2/§5: 4401/4403/4409, heartbeat, cancel-гонка, takeover, снапшоты SSE» |
| **автоматический smoke vs docker-compose** | ✅ | `tasks.md` 6.2: «автоматический Playwright-smoke против docker-compose (живой Keycloak)» |
| **6.4а api-contracts §2 doc-sync** | ✅ | `tasks.md` 6.4а: «сверка с openapi.yaml: MessageKind (+ASYNC_ACCEPTED), SessionDto/runtimeStatus, TreeNode» |
| **D-86…D-93 в 6.3** | ✅ | `tasks.md` 6.3: «D-86…D-93; D-88 — отдельной risk-строкой» |
| **ping-watchdog** | ✅ | `desktop-relay-client/spec.md` Heartbeat: «отсутствие ping дольше 2× интервала → ранний reconnect» |
| **safeStorage-отказ** | ✅ | `desktop-shell/spec.md` SSO-логин: «отказ с понятным сообщением (plain-text запрещено)» |
| **compact скрыт на STATE** | ✅ | `desktop-chat/spec.md` Отправка: «кнопка скрыта для STATE-сессий — 409 wrong-session-kind» |
| **превью убрано** | ✅ | `desktop-chat/spec.md` Список сессий: «превью последнего сообщения НЕ показывается» |
| **lifecycle релея при переключении** | ✅ | `desktop-relay-client/spec.md` Lifecycle: «новый register на том же WS; STATE — не отправлять» |

---

## Новые/регресс-находки

**Нет.** Все артефакты консистентны с замороженными контрактами (`openapi.yaml`, `api-contracts.md` §1–§9) и AGENTS.md.

### Соответствие AGENTS.md

| Правило | Статус |
|---|---|
| Без оценок сроков | ✅ (оценок в change нет) |
| Числа — в конфиг | ✅ (handshake timeout, backoff, `tree.refresh-interval`, `confirmCommands`, `showTray`, `log-level`, лимиты вывода — все конфиг) |
| Contract-first | ✅ (D-89: генерация TS из openapi.yaml; WS-фреймы — ручные типы + unit-тесты 2.4а) |
| Живой Keycloak в тестах | ✅ (tasks 6.2: автоматический smoke против docker-compose с живым Keycloak) |
| Без лишних слоёв безопасности | ✅ (D-88: без path-guard на клиенте; D-92: sandbox+CSP — defence-in-depth, не избыточный) |
| Design-решения в decisions.md | ✅ (tasks 6.3: D-86…D-93) |
| Сценарий необходимости | ✅ (proposal Why: первый реальный клиент) |

---

## Вердикт

**APPROVE** — M5 «Web Desktop» готов к owner sign-off. Все must-fix находки закрыты, артефакты консистентны с контрактами, AGENTS.md соблюдён. Реализация может начаться с пачки A (Scaffold + Shell).
