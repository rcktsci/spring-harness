# Ревью M5 Batch D — Кими

**Вердикт:** APPROVE (с minor-замечаниями; ни одно не блокирует функциональность или контракт).

---

## Проверка по чек-листу

### SSE (fetch-stream в main)
- ✅ **Снапшот `session.status` первым** — `readLoop` передаёт все фреймы в `onFrame` равнозначно; UI (`useChat.ts:96`) обрабатывает `session.status` отдельно от `message.created`. Нет специальной логики «первый», что соответствует спеке §3.1 («клиент поглощает как обычный ивент»).
- ✅ **Ping-комментарии игнорируются** — `createSseParser` отбрасывает строки на `:` (`sse.ts:55-57`); дополнительная защита `frame.event === 'ping'` в `readLoop` (`sse.ts:234`).
- ✅ **`Last-Event-ID` приоритетнее `?since=`** — `sse.ts:190-194`: если `lastEventId !== null` — заголовок, иначе `since=initialSince`.
- ✅ **Retry sticky** — `readLoop:229` обновляет `this.retryMs` из фрейма; тест `sse-parser.test.ts:41-47` покрывает.
- ✅ **Один активный стрим** — `subscribe` синхронно сбрасывает `abort` и `reconnectTimer`; `openStream` работает в одном цикле `while (this.running)`.
- ✅ **Отписка при переключении** — `useChat.ts:110` вызывает `sseUnsub()` перед новым `subscribe`; `preload/index.ts:97-99` отправляет `SSE_UNSUBSCRIBE`.

### Лента сообщений
- ✅ **TOOL_CALL ↔ TOOL_RESULT по `callId`** — `feed.ts:68-78` группирует по `callId` через `Map`.
- ✅ **ASYNC_ACCEPTED → pending** — `feed.ts:90-99` ставит `pending = true`; `isFinalResult` (`feed.ts:51-57`) исключает `ASYNC_ACCEPTED` из финальных.
- ✅ **Late-закрытие placeholder** — `feed.ts:107` обновляет `block.late` и `block.pending` при финальном `TOOL_RESULT`; тест `feed.test.ts:78-118` + `chat-feed.test.ts:91-114` покрывают.
- ✅ **markdown-it + DOMPurify, нет `v-html` без sanitize** — `markdown.ts:4-14` использует `html: false` + `DOMPurify.sanitize`; `ChatFeed.vue:64` — `eslint-disable` с пояснением. USER и ASSISTANT рендерятся через очищенный `md()`.
- ✅ **Все `MessageKind`** — `ChatFeed.vue` обрабатывает USER, ASSISTANT, SYSTEM, COMPACT, TOOL (grouped), и fallback для любых остальных (`v-else-if="entry.type === 'message'"`).

### Compact / Stop
- ✅ **Скрыт/заблокирован для STATE** — `ChatView.vue:166-182`: Compact — `v-if="showCompact"` (зависит от `isFree`); Stop — `v-if="!isFree" disabled` с тултипом.
- ✅ **Stop с подтверждением** — `ChatView.vue:184-213`: первый клик при `working` показывает «остановить Turn? Да/Нет» (`stopConfirm`), второй — отправляет команду.

### Бейджи runtimeStatus (D-84)
- ✅ **PARKED_CLIENT не working** — `feed.ts:124` (`isAgentWorking`) возвращает `false` для `PARKED_CLIENT`; `SessionList.vue:28-41` не выделяет его отдельно (raw fallback).

### Пейджинг истории
- ✅ **`since=0` по `nextCursor` с cap** — `useChat.ts:64-80`: цикл `for (;;)` с `since` от `page.nextCursor`, прерывание по `page.nextCursor == null` и `pages >= cfg.chatHistoryMaxPages`.

### Draft per-session
- ✅ **Per-session черновик** — `ChatView.vue:25-27,55-58`: `drafts` — `Record<string, string>`, watch на `activeId` сохраняет/восстанавливает.

### REST через Bearer
- ✅ **Bearer** — `rest-client.ts:17-22`: `Authorization: Bearer ${token}`; токен получается через `requireAccessToken` (main).

### Числа в конфиге (AGENTS.md — хардкод запрещён)
- ✅ **Почти все числа** в `DEFAULT_CONFIG` (`ipc-contract.ts:58-108`) и читаются из конфига.
- ⚠️ **Minor**: `useSessions.ts:29-30` — локальные дефолты `debounceMs = 300` и `listLimit = 50` до загрузки конфига. Это дублирование `DEFAULT_CONFIG` без импорта. Не критично (перезаписываются в `onMounted`), но формально нарушает правило «нет хардкода чисел». **Рекомендуется:** инициализировать через импорт `DEFAULT_CONFIG` или дождаться конфиг до создания watcher.

### Секрет НЕ В RENDERER
- ✅ **Токен не покидает main** — preload не экспортирует токен; все REST/WS/SSE идут через IPC-хендлеры в main (`rest-client.ts`, `sse.ts`, `relay-client.ts`). Renderer видит только `window.harness` API.

### Тесты
- ✅ **Осмысленные, покрывают контракт**:
  - `sse-parser.test.ts` — базовый фрейм, snapshot, ping, retry, multi-line data, CRLF, default event, flush, sticky id.
  - `sse-client.test.ts` — first connect `?since=0`, history tail `since=N`, reconnect `Last-Event-ID`, unsubscribe без дальнейших коннектов.
  - `feed.test.ts` — все kind, группировка, pending, ASYNC_ACCEPTED, late, ERROR.
  - `chat-feed.test.ts` — рендер всех kind, markdown sanitize, pending placeholder, late marker, toggle collapsible.

---

## Minor находки

| Severity | Файл:строка | Замечание | Действие |
|----------|-------------|-----------|----------|
| minor | `useSessions.ts:29-30` | Локальные хардкод-дефолты `300` и `50` дублируют `DEFAULT_CONFIG`. | Инициализировать через импорт `DEFAULT_CONFIG` или сдвинуть чтение конфига до создания watcher. |
| minor | `sse.ts:249-260` | Ветка `if (this.reconnectTimer)` в `waitReconnect` — dead code в нормальном потоке (таймер всегда null при входе). `void prev;` — явный no-op, намёк на незавершённое намерение очистить старый таймер. | Упростить до одного `setTimeout` без ветвления; или явно `clearTimeout(prev)` перед заменой (если ветвление теоретически нужно). |
| minor | `ChatView.vue:91` | Подтверждение Stop срабатывает при `working`, который включает `PARKED_ASYNC`. Спека требует подтверждение только при «активном TURN_RUNNING». | Уточнить либо спеку (если такая семантика намеренна), либо условие до `runtimeStatus === 'TURN_RUNNING'`. |
| minor | `useChat.ts:132` | `sseConnected.value = true` ставится сразу после `subscribe`, а не по факту открытия потока. Значение не используется в UI (не рендерится), но название вводит в заблуждение. | Либо убрать dead ref, либо переименовать в `sseSubscribed` / подождать первый фрейм для `true`. |

---

## Резюме

Batch D реализует весь скоуп 4.1–4.6 корректно и в соответствии со спеками `desktop-chat` и `desktop-shell` (IPC/SSE). Контрактные требования SSE, ленты, Compact/Stop, paging, draft-per-session, REST Bearer и изоляция секретов — соблюдены. Тесты покрывают happy path и граничные случаи (reconnect, late result, sanitize, toggle). Minor-замечания — стилистика/дублирование, не влияют на функциональность.
