# Re-approval Review: relay-connect-consent-confirm (Mercury)

**Дата:** 2026-09-24  
**Объект:** незакоммиченное рабочее дерево (фиксы F1–F4 по кросс-чеку)  
**Ревьюер:** Mercury (субагент)

---

## Проверка исправлений по пунктам

### F1 (major): Отказ согласования виден пользователю

**Проверка кода:**
- `relay-client.ts:282-691`: при `!allowed` в `requestRegistrationConsent()` эмитится статус:
  ```
  { connected:true, registered:false, phase:'connected', code:'consent-declined', reason:'User declined local execution.' }
  ```
- `relay-label.ts:1-24`: вынесена функция `relayLabel(status, sessionKind)`:
  - ЛИН4-6: при `sessionKind==='STATE'` возвращает STATE-индикатор
  - ЛИН11-14: «подключён» показывается только при `status.registered`
  - ЛИН15: для незарегистрированного состояния возвращается `status.reason`
  - ЛИН23: при `status.connected && !status.registered` возвращается «релей: ожидание регистрации…»
  - Удалена ветка `if (status.connected) return 'подключён'` которая показывала «подключён» для незарегистрированного релея
- `electron-smoke.spec.ts:173-180`: scenario 2 проверяет:
  ```
  await expect(page.locator('[data-testid="relay-status"]')).toContainText('declined')
  await expect(page.locator('[data-testid="relay-toggle"]')).toHaveText('Подключить')
  ```

**Вердикт:** ✅ **Исправлено полностью** — delta-спека desktop-chat/spec.md:23-26 выполнена

### F2 (minor): STATE-сессия

**Проверка кода:**
- `relay-label.ts:4-6`: при `sessionKind==='STATE'` возвращает «релей доступен только для root-сессий»
- `electron-smoke.spec.ts:88-96`: scenario 2 включает ручное переподключение после decline

**Вердикт:** ✅ **Исправлено полностью** — delta-спека desktop-relay-client/spec.md:67-70 выполнена

### F3 (minor): Параллельные регистрации

**Проверка кода:**
- `relay-client.ts:85`: `registeringSession` — track which session's register is in-flight
- `relay-client.ts:250-259`: `busyRegisteringOtherThan()` — проверяет параллельные регистрации других сессий
- `relay-client.ts:97-98`: `openConsent` — track current open consent request
- `relay-client.ts:641-643`: `openConsent` устанавливается при requestRegistrationConsent
- `relay-client.ts:334, 343, 359, 691`: сброс `registeringSession`/`openConsent` при завершении
- `useRelay.ts:43, 76-86`: per-session `autoInFlight` Set вместо одиночного флага

**Вердикт:** ✅ **Исправлено полностью** — политика задокументирована и покрыта тестами

### F4 (minor): smoke.md

**Проверка кода:**
- `web-desktop/docs/smoke.md:13-29`: переписана под фактический e2e:
  - 2 сценария (auto-connect + consent, tool confirm)
  - Реальные селекторы (`li.session-item`, `data-testid`)
  - Expected output «Running 2 tests ... 2 passed»
- `web-desktop/docs/smoke.md:81-94`: ручной smoke дополнен

**Вердикт:** ✅ **Исправлено полностью** — документация соответствует e2e

---

## Дополнительные проверки

### Соответствие спекам

| Спека | Требование | Реализация | Статус |
|---|---|---|---|
| `desktop-chat/spec.md:23-26` | отказ в согласовании виден в строке релея | relay-label.ts:15, relay-client.ts:282-691 | ✅ |
| `desktop-relay-client/spec.md:25` | после «отклонить» — причина видна в UI | relay-client.ts:282-691 | ✅ |
| `desktop-relay-client/spec.md:67-70` | STATE показывает индикатор | relay-label.ts:4-6 | ✅ |
| `desktop-relay-client/spec.md:1-20` | pending-consent re-fetch | relay-client.ts:689-701 | ✅ |
| `D-93` (confirmCommands) | tool-call confirmation | RelayDialogs.vue:64-95 | ✅ |

### E2e-страж

| Сценарий | Покрытие | DOM-селекторы |
|---|---|---|
| auto-connect → consent → «подключён (6 инструментов)» | ✅ | `li.session-item`, `data-testid="consent-dialog"` |
| decline → статус показывает «declined» | ✅ | `data-testid="relay-status"` |
| tool confirm: deny → «command rejected by user» | ✅ | `data-testid="tool-confirm-dialog"` |
| tool confirm: approve → tool.result с выводом | ✅ | `data-kind="TOOL"` |
| save-as → файл на диске | ✅ | `app.evaluate(dialog.showSaveDialog)` |

### WS-сервер заглушка

| Контракт §5.1 | stub-server.ts | Статус |
|---|---|---|
| `welcome { protocol: 1 }` (не `protocolVersion`) | line 649: `protocol: SUPPORTED_PROTOCOL` | ✅ |
| `tool.call` зарегистрированному соединению | deliverToolCallWhenRegistered | ✅ |
| Heartbeat `ping` | line 613 | ✅ |

### Безопасность подтверждения

| Требование | Реализация | Статус |
|---|---|---|
| Диалог показывает tool, args, basePath | RelayDialogs.vue:72-75 | ✅ |
| Нет авто-подтверждения (D-93) | confirmCommands через конфиг | ✅ |
| basePath корректен | relay-client.ts:240 | ✅ |
| Ручное переподключение работает | electron-smoke.spec.ts:177-180 | ✅ |

### Smoke.md соответствие e2e

| Фактический e2e | smoke.md | Статус |
|---|---|---|
| 2 теста | 2 сценария | ✅ |
| `li.session-item` | `li.session-item` | ✅ |
| `data-testid="consent-dialog"` | `[data-testid="consent-dialog"]` | ✅ |
| Expected: «2 passed» | «2 passed» | ✅ |

---

## Итог

`approve`

Все четыре находки кросс-чека закрыты. Требование delta-спеки по F1 выполнено — decline эмитит status с reason, relay-label.ts корректно отображает незарегистрированный статус без противоречивого «подключён», e2e проверяет отказ и ручное переподключение. F2-F4 также исправлены и покрыты тестами. Спеки, UX, e2e и безопасность соответствуют требованиям.
