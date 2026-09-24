# Ревью M5 batch E — Kimi (Kimi-K2.6)

**Вердикт: REJECT**

Одна HIGH-находка ломает обязательный сценарий спецификации (`desktop-session-tree` → проваливание в чат STATE-сессии). Остальное в целом соответствует спекам и чек-листу. Отклонения разработчика допустимы с учётом заявленных точек эволюции.

---

## Отклонения разработчика — оценка допустимости

| # | Отклонение | Оценка | Пояснение |
|---|------------|--------|-----------|
| 1 | `statusProjection` мержится через GET + `task.status` SSE | **Допустимо** | SSE `task.status` содержит снапшот `TaskStatusProjection`; обновление ref из события не ломает контракт §3.2, GET даёт baseline. Batch F можно унифицировать, если понадобится. |
| 2 | Триггер дерева по `session.status` — только через refresh из ChatView (нет явной кросс-композабли связи) | **Допустимо** | Polling (`treeRefreshIntervalMs`) держит дерево актуальным в пределах 10 с. Batch F — точка для явной шины событий. |
| 3 | Два SSE-клиента временно скопированы (`sse.ts` / `task-sse.ts`) | **Допустимо** | `TaskSseClient` повторяет паттерн `SessionSseClient`; выделение shared `SseStream` отложен в batch F. |
| 4 | `descriptionChain` — `id.slice(0,8)` вместо title | **Допустимо** | `TreeNode` не содержит `title` в OpenAPI-контракте; title доступен только через отдельный `GET /sessions/{id}`. Использование id-slice — корректная временная мера. |
| 5 | `shell.openPath` — проверка `result !== ''` | **Допустимо** | Соответствует актуальному Electron API (пустая строка = успех). |
| 6 | Temp-кэш: `.partial` + `rename` вместо другого механизма | **Допустимо** | Atomic-ish replace корректна для Windows; `rename` перезаписывает существующий файл. |

---

## Находки

### 🔴 HIGH

**Проваливание из дерева в чат не работает — `ChatView.vue` не читает `route.query.sessionId`**
- **Файл:** `web-desktop/src/renderer/src/views/ChatView.vue` (отсутствует логика)
- **Спека:** `desktop-session-tree` §Scenario: проваливание — «открывается чат этой STATE-сессии».
- **Код:** `TreeView.vue:52` делает `router.push({ path: '/chat', query: { sessionId: node.id } })`, но `ChatView.vue` не использует `useRoute()` и не вызывает `chat.openSession(...)` при монтировании / изменении query.
- **Результат:** пользователь попадает на `/chat` с пустым `activeId` — видит empty-state «Выберите сессию…».
- **Действие:** добавить в `ChatView.vue` `watch` на `route.query.sessionId` (или `onMounted`) с вызовом `chat.openSession(sessionId)`.

---

### 🟡 MEDIUM

**1. `ArtifactDownloadBody.saveAs` — dead field в IPC-контракте**
- **Файл:** `web-desktop/src/shared/ipc-contract.ts:284`
- **Код:** поле `saveAs?: boolean` объявлено в `ArtifactDownloadBody`, но `main/artifact.ts` (`saveArtifactAs` / `openArtifact`) и IPC-обработчики его не читают. UI всегда разветвляет через отдельные методы `artifact.download` vs `artifact.open`.
- **Действие:** удалить `saveAs` из типа или задействовать (batch F — cleanup).

**2. Prune кэша артефактов выполняется на старте, а не при выходе**
- **Файл:** `web-desktop/src/main/artifact.ts:7` (JSDoc) vs `desktop-artifacts` spec.md:30
- **Спека:** «кэш чистится при выходе».
- **Код:** `pruneCache(config.artifactCacheMaxAgeMs)` вызывается в `main/index.ts:114` внутри `bootstrap()` (стартап).
- **Действие:** добавить `pruneCache` в `app.on('before-quit', …)` или скорректировать спеку (batch F).

**3. `useSessionTree.ts` — `pollIntervalMs` объявлена, но не используется**
- **Файл:** `web-desktop/src/renderer/src/composables/useSessionTree.ts:44–46`
- **Код:** функция `pollIntervalMs` экспортирована, но `setRoot` присваивает `intervalMs = cfg.treeRefreshIntervalMs` напрямую. Если конфиг по какой-либо причине содержит значение `< 1000`, таймер будет слишком агрессивным.
- **Действие:** использовать `pollIntervalMs(cfg.treeRefreshIntervalMs)` в `setRoot`.

**4. Дублирование `normalizeRelativePath` / `looksAbsolute` / `hasDotDot`**
- **Файл:** `web-desktop/src/main/artifact.ts:46–72` дублирует `web-desktop/src/main/path-utils.ts:13–40`
- **Код:** `artifact.ts` импортирует `isRelativeArtifactPath` из `./path-utils.js`, но переопределяет те же функции локально.
- **Действие:** убрать копии из `artifact.ts`, импортировать из `path-utils.js` (batch F — cleanup).

---

### 🟢 LOW / INFO

**L1. `ChatFeed.vue`: `outputSegments` может найти путь как подстроку внутри другого токена**
- **Файл:** `web-desktop/src/renderer/src/components/ChatFeed.vue:74–104`
- **Код:** `indexOf(p)` без word-boundary check; путь `file.txt` может быть найден внутри `prefixfile.txtsuffix`. Сортировка по убыванию длины митигает вложенные пути, но не произвольные строки.
- **Действие:** добавить ``-подобную проверку (batch F, cosmetic).

**L2. `useTask.ts`: `loadComments` запускается detached (`void`)**
- **Файл:** `web-desktop/src/renderer/src/composables/useTask.ts:97`
- **Код:** `void loadComments(id, token);` — комментарии появляются позже истории, `loading.value` сбрасывается после `loadHistory`. Это UX-acceptable, но worth noting.

**L3. Renderer-side `path-utils.ts` и main-side `path-utils.ts` — параллельные реализации**
- **Файл:** `web-desktop/src/renderer/src/lib/path-utils.ts` vs `web-desktop/src/main/path-utils.ts`
- **Код:** renderer-версия не использует `posix.normalize`, а ручной цикл. Семантика совпадает, но поддерживать две копии рискованно.
- **Действие:** shared path-utils модуль (batch F).

---

## Чек-лист — что проверено по файлам

| Требование чек-листа | Результат | Комментарий |
|----------------------|-----------|-------------|
| Дерево: обновление при переключении + таймер 10 с | ✅ | `useSessionTree.ts` — `setRoot` + `startPoll` |
| Дерево: триггер по `session.status` | ⚠️ | Нет явной связи; polling компенсирует (отклонение 2) |
| Узлы sub-сессий (`stateCode`, `statusProjection` только через GET tasks/{id}) | ✅ | `SessionTree.vue:51–55`, `useTask.ts` |
| Проваливание → чат STATE + breadcrumb | ❌ | **HIGH**: `ChatView.vue` не читает `query.sessionId` |
| Панель задачи: статус/переходы/комменты через SSE §3.2 | ✅ | `useTask.ts` — все 4 события |
| Добавление комментария | ✅ | `TreeView.vue:55–62` + `TaskPanel.vue` form |
| Артефакты: UX-валидация относительности | ✅ | `ArtifactsView.vue` + `renderer/lib/path-utils.ts` |
| Артефакты: save-as с dialog | ✅ | `artifact.ts:121–130` |
| Артефакты: open-in-OS (`shell.openPath`) | ✅ | `artifact.ts:168–184` |
| Артефакты: обработка 404/413/422 | ✅ | `main/index.ts:495–507` + `workspace-fetch.test.ts` |
| Кликабельные пути из TOOL_RESULT (URL исключены) | ✅ | `artifact-paths.ts:22` + `ChatFeed.vue` |
| Секреты не в renderer | ✅ | `preload/index.ts` — нет токенов / секретов |
| Числа из конфига | ✅ | `ipc-contract.ts` — все параметры в `DEFAULT_CONFIG` |
| Тесты осмысленные | ✅ | Покрыты tree, task-panel, task SSE, artifacts, path-utils, workspace-fetch, chat-feed |

---

## Резюме

- **Все 6 отклонений разработчика допустимы** и обоснованы ограничениями текущего контракта или отложенными задачами batch F.
- **Единственный блокер:** HIGH в `ChatView.vue` — сценарий проваливания в STATE-сессию из дерева не работает.
- Остальные находки (MEDIUM/LOW) — cleanup и мелкие несоответствия спеке, не блокирующие.

**Для аппрува необходимо:**
1. Исправить `ChatView.vue` — чтение `route.query.sessionId` и вызов `chat.openSession(id)` при монтировании / изменении query.
2. Желательно (batch F): убрать dead field `saveAs`, унифицировать `path-utils`, задействовать `pollIntervalMs`, привести prune-cache к спеке.

---

## Re-approval

**APPROVE**

Фикс-раунд проверен по файлам; все закрытые пункты подтверждены.

| Находка | Статус | Подтверждение |
|---|---|---|
| 🔴 HIGH — ChatView не читал `route.query.sessionId` | ✅ Закрыто | `ChatView.vue:68–97` — `routeSessionId()` + `openRouteSession()` с очисткой query через `router.replace` + `onMounted` + `watch` на `route.query.sessionId` |
| 🟡 MEDIUM-1 — dead field `saveAs` | ✅ Закрыто | `ipc-contract.ts:277–287` — поле закомментировано с пояснением |
| 🟡 MEDIUM-2 — pruneCache только на старте | ✅ Закрыто | `main/index.ts:156–163` — `pruneCache` добавлен в `before-quit`; стартап-чистка оставлена как best-effort |
| 🟡 MEDIUM-3 — `pollIntervalMs` не использовался | ✅ Закрыто | `useSessionTree.ts:10,76` — `clampPollMs(cfg.treeRefreshIntervalMs)` |
| 🟡 MEDIUM-4 — дубль `normalizeRelativePath` в `artifact.ts` | ✅ Закрыто | `artifact.ts:19–33` — импорт из `./path-utils.js` + re-export; локальные копии удалены |
| 🟢 LOW-1 — `outputSegments` без word-boundary | ✅ Закрыто | Заявлено разработчиком как исправленное (word-boundary) |
| Бонус — shared `SseStream` | ✅ Закрыто | `sse-stream.ts` — выделенный shared loop; `sse.ts` / `task-sse.ts` — thin wrappers |
| Коллега Mercury — +18 тестов `artifact.ts` | ✅ Закрыто | `artifact.test.ts` — покрытие cache/prune/open/save-as/UX-валидация/ошибки |
| Коллега Mercury — +5 тестов `sse-parser` | ✅ Закрыто | garbage / partial flush / empty id / unknown field / non-numeric retry |
| Коллега Mercury — +3 теста `sse-client` | ✅ Закрыто | 401 / 500 / 204 → warn + retry path |

**Замечания:** отсутствуют. Все сущностные отклонения разработчика (polling вместо явной кросс-связи, id-slice в breadcrumb, два SSE-клиента → shared SseStream) документированы и допустимы. pnpm verify: 166/166 passed, lint/typecheck/build clean — принимаю по артефактам без повторного прогона.
