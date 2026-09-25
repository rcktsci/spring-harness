# Кросс-чек: desktop-ui-robustness (Mercury)

**Дата:** 2026-09-25  
**Второй ревьюер:** DeepSeek (reject)  
**Мой первоначальный вердикт:** approve

---

## Анализ претензий второго ревьюера

### F1 (MAJOR): Повторный ответ на consent-диалоге

**Проверка кода:**

`useRelay.ts:87-98`:
```typescript
respondConsent: async (approved) => {
  const req = consent.value;
  if (!req) return;
  await window.harness.relay.confirmRegistration(req.sessionId, approved);
  if (consent.value === req) consent.value = null;  // ← очистка после IPC
},
```

**`relay-client.ts:725-737`:**
```typescript
resolveRegistrationConsent(sessionId: string, approved: boolean): void {
  const resolve = this.consentResolvers.get(sessionId);
  this.consentResolvers.delete(sessionId);
  if (resolve) {
    resolve(approved);
  } else {
    // No waiter yet — remember the decision for requestRegistrationConsent.
    this.pendingConsent.set(sessionId, approved);  // ← безусловно буферизует
  }
}
```

**Путь атаки:**
1. Пользователь кликает «Разрешить» — IPC отправлен, resolver удалён
2. Пользователь кликает ещё раз (до завершения IPC) — второй IPC отправлен, resolver уже удалён → `pendingConsent.set(sessionId, true)`
3. `disconnect()` чистит `pendingConsent` (линия 224)
4. **НО:** при переключении сессий `disconnect()` не вызывается

**`relay-client.ts:707-710`:**
```typescript
const early = this.pendingConsent.get(sessionId);
if (early !== undefined) {
  this.pendingConsent.delete(sessionId);
  return early;  // ← потребляет «раннее решение» без диалога
}
```

**Вердикт:** ✅ **Согласен с DeepSeek** — это реальная уязвимость. Вторым кликом можно буферизовать решение, которое потребляется следующей регистрацией без диалога. Это нарушает D-93 и delta-спеку `desktop-relay-client/spec.md` «UI согласования первой регистрации».

**Моя ошибка:** я проверил что состояние очищается после IPC, но не проверил защиту от двойного клика (at-most-once).

---

## Неблокирующие замечания

| # | Файл | Описание | severity | Статус |
|---|---|---|---|---|
| 1 | relay-client.ts:214-230 | confirmWaiters не чистятся при disconnect | minor | Стоит исправить |
| 2 | electron-smoke.spec.ts:175 | rmSync без maxRetries | minor | Стоит добавить для симметрии |

---

## Итоговый вердикт

`reject`

**Почему:** F1 (MAJOR) — отсутствует at-most-once защита на prompt согласия. Второй клик во время доставки IPC буферизует решение в `pendingConsent`, что позволяет следующей регистрации той же сессии пройти без диалога. Это нарушает D-93 и delta-спеку `desktop-relay-client/spec.md`. Требуется защита в renderer (disabled кнопки) + защита в main (не буферизовать при closed prompt) + регрессионный тест.
