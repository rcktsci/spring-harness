# M5 propose — судейский разбор (оркестратор)

Ревьюеры: Qwen (REJECT: 2 BLOCKER, 7 HIGH, 5 MEDIUM, 5 MINOR, 3 NIT), Mercury (approve pending cross-checks), Atria (сессия закончена, находок нет — из цикла исключена).

## Судейские решения (все находки Qwen приняты)

- **B-1**: каталог-листинга на сервере нет → из desktop-artifacts убирается «браузер файлов»; остаётся скачивание/открытие по известному пути (ручной ввод + кликабельные пути из chat/tool-результатов). Каталог-листинг — non-goal/эволюция (мини-амендмент при необходимости).
- **B-2**: `ASYNC_ACCEPTED` — это `payload.status` TOOL_RESULT, не MessageKind. Формулировка ленты исправлена: плейсхолдер «ожидает результат» рендерится из TOOL_RESULT со status=ASYNC_ACCEPTED (или TOOL_CALL без результата); поздний TOOL_RESULT (`late=true`) заменяет его.
- **H-1**: после `tool.cancel` клиент НЕ отправляет `tool.result`; если процесс завершился до cancel — отправляет (сервер игнорирует дубль по callId).
- **H-2**: `workspace-occupied` = другой principal → сообщение «занята другим пользователем», без кнопки «забрать». Takeover для того же principal происходит автоматически при reconnect (клиент просто шлёт register).
- **H-3**: `PARKED_CLIENT` — зарезервирован, не присваивается (D-84); UI не обрабатывает (явная оговорка в spec).
- **H-4**: дерево обновляется: (а) при переключении на сессию, (б) по таймеру (конфиг `tree.refresh-interval`, дефолт 10 с) пока активно, (в) при смене `session.status`. SSE-события spawn нет — убрано.
- **H-5**: при reconnect клиент шлёт `register` с теми же параметрами; сервер сам разруливает idempotent/takeover/error — клиент не ветвит.
- **H-6**: SSO-авторизация — **видимое** BrowserWindow (пользователь видит форму Keycloak), закрывается после redirect; silent refresh — без окна.
- **H-7**: из proposal убирается «доп. слой валидации»; в spec добавляется как **UX**-требование: клиент проверяет относительность пути (не абсолютный, без `..`) перед отправкой — ранняя удобная ошибка, не безопасность (серверный гвард D-72 достаточен).
- **M-1**: начальная загрузка ленты — пейджинг `since=0&limit=N` по nextCursor до хвоста (история ограничена compact-политикой); новые сообщения — через SSE; подгрузка «раньше» скроллом-вверх не нужна (вся история от 0 загружена).
- **M-2**: `basePath` создаёт main-process при обработке register (до отправки фрейма).
- **M-3**: compact/stop — **кнопки** в UI (без slash-парсинга).
- **M-4**: в узле дерева — `stateCode` (из TreeNode); `statusProjection` — только в панели задачи через `GET /tasks/{id}`.
- **M-5**: авто-подключение релея при старте — перенесено в spec desktop-relay-client.
- **M-6**: task 3.4 — ссылка на spec-требование.
- **m-1..m-5, n-1..n-3**: опечатка reconnect; индикатор в строке состояния чата; `markdown-it` + `DOMPurify` зафиксированы; e2e stub-LLM = in-test HTTP-сервер с фиксированным JSON-ответом с tool-call; temp-имя = `hash(path)-basename`; `pnpm` зафиксирован; tray-дефолт true (убрать «опционально»); поле поиска с debounce 300 ms.
- **Mercury**: ws-frames drift — добавлен task 2.4а: unit-тесты WS-фрейм-схемы (парсинг всех фреймов по §5). Оценки сроков не даём (AGENTS).

## Вердикт

REJECT → фиксы применяются в proposal/specs/design/tasks. После — re-approve Qwen + Mercury (Atria недоступна).

## Фикс-раунд 2 (находки Atria, m5-atria.md)

Ревью Atria формально исключено из цикла (сессия закончена), но must-fix подтверждены судьёй и закрыты наравне с Qwen:

- **BL-1/H-2/H-3/H-5** — уже закрыты раундом 1.
- **H-1** → D-91 переписан: main владеет JWT (safeStorage) и всеми сетевыми клиентами (REST/SSE/WS); renderer — только IPC; инициатива tool.call — серверная.
- **H-4** → D-93: `confirmCommands` дефолт `always`, per-session consent; D-88 — risk-аппрув владельца отдельной строкой (task 6.3).
- **M-1** — закрыт раундом 1 (since=0-пейджинг).
- **M-2** — cancel для неизвестного callId игнорируется (relay spec).
- **M-3** — 4403 fatal без retry (relay spec, task 3.1).
- **M-4** — SSE: снапшот при коннекте, `: ping` игнор, retry 5000, Last-Event-ID; task SSE — since/снапшот (chat/tree spec, D-87).
- **M-5** — закрыт раундом 1.
- **M-6** — клиентский таймаут < серверного tool-call-timeout; progress потолок не продлевает (relay spec, D-88).
- **M-7** → D-92: sandbox: true + CSP + DOMPurify.
- **M-8** — lifecycle переключения сессий у релея (relay spec).
- **M-9** — e2e-стаб полнодублирующий §5; smoke — автоматический против docker-compose (живой Keycloak) (design Risks, tasks 6.1/6.2).
- **M-10** — task 6.4а: doc-sync api-contracts §2 (ASYNC_ACCEPTED, TreeNode taskId/stateCode).
- **m-5/m-6/m-7/m-8/m-9/m-10** — конфиги в tasks 3.1; превью сообщения убрано; compact скрыт на STATE; ping-watchdog; safeStorage-отказ; ОС в design Risks.
- **n-4/n-5** — DOMPurify зафиксирован (D-86); места UI зафиксированы в chat spec.

## Re-approve (финал цикла)

- **Qwen**: APPROVE — docs/temp/review/m5-qwen-re2.md
- **Mercury**: APPROVE — docs/temp/review/m5-mercury-re2.md
- **Atria**: недоступна (исключена судьёй; находки закрыты и верифицированы в re-approve обоих)

**Итог: 100% консенсус доступных ревьюеров темы.** `openspec validate m5-web-desktop --strict` — valid. Цикл propose закрыт; на owner-аппрув: D-88 risk-строка + общий freeze → impl (пачки A–F).
