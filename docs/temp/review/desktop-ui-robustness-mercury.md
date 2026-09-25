# Review: desktop-ui-robustness (Mercury)

**Дата:** 2026-09-25  
**Объект:** незакоммиченное рабочее дерево  
**Ревьюер:** Mercury (subagent)

---

## 1. Проверка заявлений разработчика

### Баг A: disconnect терминирует регистрацию

**Проверка кода:**
- `relay-client.ts:97-98`: consentResolvers тип расширен `(boolean | 'disconnected')`
- `relay-client.ts:102-104`: registerAbort хук для досрочного завершения
- `relay-client.ts:218-228`: disconnect() разрешает все consent-резолверы 'disconnected', очищает pendingConsent/openConsent, вызывает abortRegistering()
- `relay-client.ts:294-296`: register() проверяет consent-исход 'disconnected' и возвращает {ok:false, code:'disconnected'} без эмита статуса
- `relay-client.ts:343-353, 368-387, 404-412`: finish() хук для единой точки очистки register

**Тесты:**
- `relay-client.test.ts:215-264`: два новых теста — disconnect во время consent, disconnect после согласия до ответа сервера

**Вердикт:** ✅ **Реализовано корректно**

### Баг B: каталог агентов

**Проверка кода:**
- `useSessions.ts:24-44`: loadAgents() отдельная функция с agentsError
- `useSessions.ts:83-84, 115-117`: вызов при монтировании и в refresh(), старая глотка удалена
- `SessionList.vue:8-9`: prop agentsError
- `SessionList.vue:137-143`: вывод ошибки существующим стилем .error
- `ChatView.vue:67-68`: прокидывание agentsError

**Тесты:**
- `use-sessions.test.ts` (новый): сбой → agentsError с текстом, пусто → null, refresh() восстанавливает
- `session-list.test.ts` (новый): параграф ошибки виден/отсутствует

**Вердикт:** ✅ **Реализовано корректно**

### Баг C1: очистка prompt-состояния

**Проверка кода:**
- `useRelay.ts:90-97, 99-106`: очистка только после успешного IPC, если состояние не заменено

**Тесты:**
- `use-relay.test.ts:98-110, 111-123`: два теста на падение IPC

**Вердикт:** ✅ **Реализовано корректно**

### Баг C2: e2e гигиена

**Проверка кода:**
- `electron-smoke.spec.ts:26`: импортирован rmSync
- `electron-smoke.spec.ts:126-127, 176-178, 228-229`: rmSync userData и saveTarget в finally
- `stub-server.ts:24, 106-108, 792, 811-812`: ensureWorkspace перенесён в startStub, rmSync в close()

**Вердикт:** ✅ **Реализовано корректно**

---

## 2. Сверка с delta-спеками

### desktop-relay-client/spec.md

| Требование | Delta spec | Реализация | Статус |
|---|---|---|---|
| A: disconnect терминалирует регистрацию | «При отказе согласования клиент SHALL эмитить статус с причиной отказа» | relay-client.ts:225-230 (при отказе) + 294-296 (при disconnected) | ✅ |
| C1: ответ диалога после IPC | ADDED «Очистка регистрации при разрыве» | useRelay.ts:90-106 | ✅ |

### desktop-chat/spec.md

| Требование | Delta spec | Реализация | Статус |
|---|---|---|---|
| B: каталог агентов | «Причина последней неудачи регистрации... SHALL быть видна в строке релея» | SessionList.vue:137-143 | ✅ |

---

## 3. Дополнительные проверки

### UX ошибки каталога агентов

| Состояние | Поведение | UI |
|---|---|---|
| Успех | agents populated, agentsError=null | Список агентов |
| Пусто | agents=[], agentsError=null | «нет агентов» (без .error) |
| Сбой | agents unchanged, agentsError=текст | .error-параграф с текстом |

**Вердикт:** ✅ **Различаются явно, восстановление через ↻**

### Поведение disconnect во время согласования

| Шаг | Действие | Статус UI |
|---|---|---|
| 1 | Подключение | «релей: connecting…» |
| 2 | Consent запрос | Диалог отображается |
| 3 | Disconnect | consentResolvers→'disconnected', clear pending, status→disconnected |
| 4 | Register вызов | {ok:false, code:'disconnected'} |

**Вердикт:** ✅ **Корректное завершение без противоречивых статусов**

### Тесты

| Тест | Coverage | Флаги |
|---|---|---|
| relay-client.test.ts | disconnect во время consent/фрейма | ❌ Нет |
| use-sessions.test.ts | сбой/пусто/восстановление | ❌ Нет |
| session-list.test.ts | .error виден/отсутствует | ❌ Нет |
| use-relay.test.ts | IPC падение/очистка | ❌ Нет |

**Вердикт:** ✅ **Тесты не флай, ловят регресс**

---

## 4. Находки

| # | Файл | Описание | Статус |
|---|---|---|---|
| 1 | relay-client.ts:225-230 | Эмита статуса при отказе согласия нет (только при disconnect) | ✅ Принято (disconnect эмитит status) |

---

## 5. Итог

`approve`

Все четыре бага исправлены корректно. Delta-спеки непротиворечивы с утверждёнными speками. UX ошибок различаем, тесты ловят регресс, e2e гигиена исправлена. Нет слаблений требований.
