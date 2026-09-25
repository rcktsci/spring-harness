# Review: Web Desktop Relay UI Gaps

**Дата:** 2026-09-24  
**Ревьюер:** Mercury (subagent)  
**Проверяемый диагноз:** D-D дыры в Web Desktop-клиенте

---

## Итог по пунктам A-D

| Пункт | Статус | Пояснение |
|---|---|---|
| **A. Нет авто-подключения релей при открытии сессии** | ✅ **ПОДТВЕРЖДЕНО** | `toggleRelay` вызывается только кнопкой (ChatView.vue:146, 275). Нет trigger в `onSelect`/`openRouteSession`. |
| **B. Нет UI подтверждения первой регистрации** | ✅ **ПОДТВЕРЖДЕНО** | `consent` ref заполняется (useRelay.ts:40), но не рендерится нигде. `respondConsent()` не вызывается из UI. |
| **C. Нет UI подтверждения команды инструмента** | ✅ **ПОДТВЕРЖДЕНО** | `toolConfirm` ref заполняется (useRelay.ts:41-43), но не рендерится. `respondToolConfirm()` не вызывается из UI. При `confirmCommands=always` Turn уйдёт по таймауту. |
| **D. Нет persist активной relay-сессии** | ✅ **ПОДТВЕРЖДЕНО** | Нет сохранения `relayActiveSessionId` / `registration` в config. При рестарте app — нет авто-connect+register. |

---

## Сверка с контрактом

### docs/design/api-contracts.md §5 и openspec/specs/client-relay/spec.md
- §5.2: регистрация требует `sessionId`, `basePath`, `client.tools[]`
- §5.4: heartbeat, reconnect, close-коды

**Отклонение:** Контракт определяет протокол, но не UI-сценарии (они в desktop-relay-client/spec.md).

### openspec/specs/desktop-relay-client/spec.md (D-91/D-93)

| Требование | Реализация | Статус |
|---|---|---|
| **Line 14-15:** При открытии сессии клиент подключается | Нет — только ручная кнопка (ChatView.vue:146) | ❌ Дефект |
| **Line 24:** При старте с сохранённой активной сессией — автоматический connect + register | Нет — нет persist сессии | ❌ Дефект |
| **Line 114-115:** Первая регистрация — показывается подтверждение с basePath и инструментами | Нет — consent ref не рендерится | ❌ Дефект |
| **Line 118-120:** confirmCommands=always → каждая команда показывается пользователю | Нет — toolConfirm ref не рендерится | ❌ Дефект |

### docs/design/web-desktop-client.md

| # | Сценарий | Где | Реализация |
|---|---|---|---|
| 2.3 | WS-релей | src/main/relay-client.ts | ✅ Есть |
| 2.4 | `confirmCommands=always`, per-session consent | src/main/relay-client.ts + useRelay.ts | ✅ IPC есть |
| 2.7 | Кнопки Compact/Stop в статус-баре | ChatView.vue | ✅ Есть, но relay-кнопка только ручная |

**Дефекты:** Сценарии 2.3/2.4 реализованы на IPC-уровне, но нет UI-рендеринга в renderer.

### docs/design/decisions.md

| Решение | Ключевой момент | Реализация | Статус |
|---|---|---|---|
| **D-93:** `confirmCommands=always` | per-session consent на первой регистрации + режим «никогда» | IPC есть (relay-client.ts:628-655), UI нет | ❌ Дефект |
| **D-88:** Локальное исполнение без path-guard | D-88 (owner-risk): подтверждение при первой регистрации | Нет UI-подтверждения | ❌ Дефект |

---

## Чек-лист событий релея (по контракту §5)

| Событие сервера | Требование клиента | Текущий статус | UI? |
|---|---|---|---|
| `welcome` | handshake завершён | ✅ (relay-client.ts:480-495) | N/A |
| `registered` | регистрация успешна | ✅ (relay-client.ts:292-306) | N/A |
| `error` + close 4409 | показать сообщение об отказе | ⚠️ (relay-client.ts:307-327, но нет renderer-уведомления) | ❌ Нет |
| `tool.call` | выполнить локально + подтвердить | ✅ IPC (relay-client.ts:543-554) | ❌ Нет (toolConfirm не рендерится) |
| `tool.cancel` | прервать исполнение | ✅ (relay-client.ts:597-605) | N/A |
| `ping` | ответить `pong` | ✅ (relay-client.ts:498-500) | N/A |
| close 4401 | silent refresh + reconnect | ✅ (relay-client.ts:409-436) | N/A |
| close 4403 | показать fatal-сообщение | ⚠️ (relay-client.ts:377-388, но нет renderer-уведомления) | ❌ Нет |
| close 4409 `superseded` | показать "открыто в другом месте" | ✅ (relay-client.ts:390-407, статус в relayLabel) | ⚠️ Только статус в relayLabel |

**Поведение:** 5 событий не имеют UI-представления; 2 события имеют только статус в relayLabel без диалога.

---

## E2E-страж клиента

### Что должно покрывать e2e-тесты:

1. **Сценарий auto-connect:**
   - Открывается FREE root-сессия
   - Ожидание relay-status → `connected` или `registering` в течение N секунд

2. **Сценарий registration consent:**
   - Первая регистрация на сессии
   - Появление UI-диалога с basePath и списком инструментов
   - Кнопки "Allow"/"Deny"

3. **Сценарий tool-call confirmation:**
   - Вызов инструмента (bash/файловый)
   - Появление UI-диалога подтверждения
   - Кнопки "Allow"/"Deny"/"Cancel"

4. **Сценарий persistence/reconnect:**
   - Закрытие приложения при активной relay-сессии
   - Перезапуск
   - Проверка: сессия автоматически подключается и регистрируется

### Проблемы с текущей разметкой:

| Компонент | Идентификатор | Статус |
|---|---|---|
| ChatView relay-status | `data-testid="relay-status"` | ✅ |
| ChatView relay-toggle | `data-testid="relay-toggle"` | ✅ |
| Consent диалог | — | ❌ Отсутствует |
| Tool confirm диалог | — | ❌ Отсутствует |

**Вывод:** E2E-тесты на relay UI сейчас не могут пройти — тестовые селекторы не существуют.

---

## Вердикт

`diagnosis-confirmed`

Все 4 пункта диагностики верны. Реализация релея в Web Desktop имеет критические UI-пробелы:
- Нет авто-подключения (нарушает openspec/specs/desktop-relay-client/spec.md:24)
- Нет UI для consent/tool confirmation (нарушает D-93)
- Нет persist сессии (нарушает openspec/specs/desktop-relay-client/spec.md:24)

Это дефекты реализации против утверждённого контракта, а не новые дизайн-решения.
