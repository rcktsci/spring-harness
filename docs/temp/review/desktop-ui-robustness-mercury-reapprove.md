# Re-approval Review: desktop-ui-robustness (Mercury)

**Дата:** 2026-09-25  
**Объект:** незакоммиченное рабочее дерево (итерация F1-фикс)  
**Ревьюер:** Mercury (subagent)

---

## Проверка исправлений по пунктам

### F1 (MAJOR): Буфер pendingConsent и at-most-once

**Устранение буфера:**
- `relay-client.ts:97-98`: `pendingConsent` Map полностью удалён
- `relay-client.ts:716-724`: `resolveRegistrationConsent` теперь no-op если нет openConsent или resolver
  ```
  if (this.openConsent?.sessionId === sessionId) {
    this.openConsent = null;
  }
  const resolve = this.consentResolvers.get(sessionId);
  if (!resolve) {
    return;  // ← no-op при закрытом prompt
  }
  ```
- **Результат:** вторая регистрация той же сессии всегда покажет диалог — обход D-93 невозможен

**At-most-once в renderer:**
- `useRelay.ts:46-47, 100-111, 113-124`: `consentInFlight`/`toolConfirmInFlight` флаги с try/finally
- `RelayDialogs.vue:29-36, 38-45, 69, 77, 104, 112`: :disabled="delivering !== null" на всех кнопках

**Результат:** второй клик во время доставки отбрасывается на уровне renderer → no-op в main

**Вердикт:** ✅ **Исправлено полностью** — периметр защиты на renderer + no-op в main

### Disconnect: очистка confirmWaiters

**Проверка:**
- `relay-client.ts:220-223`: disconnect разрешает все confirmWaiters false (терминальный отказ)
- `relay-client.ts:219`: openConsent = null
- `useRelay.ts:52-61`: status phase disconnected/fatal → очищает prompt-состояние

**Вердикт:** ✅ **Исправлено полностью** — нет залипающих диалогов

### Delta-спека `desktop-relay-client/spec.md`

| Требование | Delta spec | Реализация | Статус |
|---|---|---|---|
| at-most-once | ADDED через `consentInFlight`/`toolConfirmInFlight` + `:disabled` | ✅ |
| no-op при закрытом prompt | `resolveRegistrationConsent` returns early | ✅ |
| disconnect во время подтверждения | `confirmWaiters` разрешены false → toolResult «command rejected by user» | ✅ |

**3 новых scenario в delta:**
1. Параллельная регистрация другой сессии → register-in-progress
2. Отказ согласования отражается в статусе
3. Disconnect во время команды → terminal toolResult

**Вердикт:** ✅ **Delta-спека соответствует реализации, не ослаблена**

### Тесты

| Тест | Coverage |
|---|---|
| `relay-client.test.ts:215-235` | disconnect во время consent |
| `relay-client.test.ts:237-254` | disconnect во время register frame |
| `relay-client.test.ts:256-283` | at-most-once: двойной клик на consent |
| `relay-client.test.ts:285-307` | disconnect во время tool.confirm |
| `relay-client.test.ts:309-331` | no-op при закрытом prompt |
| `use-relay.test.ts:98-143` | IPC падение + cleanup |

**Вердикт:** ✅ **6+ новых тестов ловят регресс по существу**

### UX каталога агентов (баг B)

**Проверка:**
- `useSessions.ts:37-48`: `loadAgents()` разделяет успех/пусто/ошибка
- `SessionList.vue:137-143`: ошибка показывается `.error`-стилем
- **Результат:** «пусто» (agentsError=null) ≠ «не загрузилось» (agentsError=текст)

**Вердикт:** ✅ **Воспроизведено и не нарушено**

### e2e гигиена (баг C2)

**Проверка:**
- `electron-smoke.spec.ts:175, 229`: `rmSync(userData, {recursive: true, force: true, maxRetries: 5})`
- `stub-server.ts:811`: `rmSync(workspaceRoot, {recursive: true, force: true, maxRetries: 5})`

**Вердикт:** ✅ **maxRetries добавлен для симметрии**

---

## Итог

`approve`

F1 полностью закрыт: pendingConsent удалён, at-most-once на renderer (флаги + :disabled), no-op в main при закрытом prompt. Disconnect чистит confirmWaiters. Delta-спека соответствует реализации. 6+ тестов ловят регресс. UX агентов сохранён. e2e гигиена с maxRetries.
