# Re-approval Review: silent-refresh-single-flight (Mercury)

**Дата:** 2026-09-25  
**Объект:** незакоммиченное рабочее дерево (фикс F1 + итерация)  
**Ревьюер:** Mercury (subagent)

---

## Проверка исправлений

### F1 (MAJOR): logout во время in-flight refresh

**Проверка кода:**
- `auth.ts:253-255`: модульный `sessionEpoch = 0`
- `auth.ts:258-261`: `invalidateSession()` — инкремент `sessionEpoch`
- `auth.ts:263-266`: `terminateSession()` — инкремент + `clearTokens()`
- `auth.ts:379-382`: `logout()` вызывает `invalidateSession()` перед `clearTokens()`
- `auth.ts:208-212`: `index.ts` при смене issuer вызывает `invalidateSession()`
- `auth.ts:297-307`: `doRefresh` захватывает `epoch = sessionEpoch` до сетевого вызова
- `auth.ts:301-304`: при settle с `epoch !== sessionEpoch` результат отбрасывается (без saveTokens)
- `auth.ts:311-313`: в catch при смене эпохи — exit без побочных эффектов

**Тесты:**
- `auth-refresh.test.ts:140-159`: logout mid-flight → saveTokens не вызван, tokens = null, readLoginState(loggedIn=false)

**Вердикт:** ✅ **Исправлено полностью**

### F2 (minor): 408/429 как транзиентные

**Проверка кода:**
- `auth.ts:114-115`: 4xx **кроме** 408 и 429 → RefreshGrantError

**Тесты:**
- `auth-refresh.test.ts:120-132`: `it.each([408, 429])` → clearTokens не вызван

**Вердикт:** ✅ **Исправлено полностью**

### F3 (minor): тело ответа в ошибке аномального 2xx

**Проверка кода:**
- `auth.ts:123-130`: parseTokenResponse-отказ включает парсинг + тело ответа

**Тесты:**
- `auth-refresh.test.ts:133-144`: 2xx `{"foo":"bar"}` → warn-лог содержит `{"foo":"bar"}`

**Вердикт:** ✅ **Исправлено полностью**

### F4 (minor): детерминированность тестов

**Проверка кода:**
- `auth-refresh.test.ts:46-48`: `afterEach(() => { vi.unstubAllGlobals(); })`

**Вердикт:** ✅ **Исправлено полностью**

---

## Дополнительные проверки

### Delta-спека `desktop-shell`

| Требование | Delta spec | Реализация | Статус |
|---|---|---|---|
| logout во время refresh | ADDED | auth.ts:253-313, 379-382 | ✅ |
| Rate limit (408/429) не логинит заново | ADDED | auth.ts:114-115, test:120-132 | ✅ |
| Тело ответа в диагностике | MODIFIED | auth.ts:114-130 | ✅ |

**Вердикт:** ✅ **Delta-спека соответствует реализации**

### UX ошибок

| Ситуация | Исход | Сообщение |
|---|---|---|
| Keycloak 400 (invalid_grant) | clearTokens → /login | `refresh endpoint 400: {"error":"invalid_grant",...}` |
| Keycloak 408/429 | tokens kept | `refresh endpoint 429: slow down` |
| Сеть/5xx | tokens kept | `refresh endpoint unreachable: ECONNREFUSED` |

**Вердикт:** ✅ **Различаются явно, нет состояния «залогинен, но всё падает» — гейт isSessionUsable продолжает работать с сохранёнными токенами**

### Безопасность логов

| Токен/секрет | Есть в логе? |
|---|---|
| access_token | ❌ Нет |
| refresh_token | ❌ Нет |
| error/grant, error_description | ✅ Да (диагностика) |
| endpoint status | ✅ Да |

**Вердикт:** ✅ **Нет утечки токенов**

### Single-flight (легитимный)

**Проверка кода:**
- `auth.ts:250-251`: `refreshInFlight` сбрасывается в `finally`
- `auth.ts:297-298`: `epoch` захватывается до сетевого вызова

**Тесты:**
- `auth-refresh.test.ts:52-65`: 5 параллельных → 1 fetch, 1 saveTokens

**Вердикт:** ✅ **Single-flight не сломан**

### Тесты

| Тест | Coverage | Флаги |
|---|---|---|
| parallel consumers | 1 fetch | ❌ Нет |
| 400 grant rejected | 1 clearTokens | ❌ Нет |
| network unreachable | tokens kept | ❌ Нет |
| 5xx | tokens kept | ❌ Нет |
| next batch after failure | new refresh | ❌ Нет |
| fresh token | 0 fetch | ❌ Нет |
| 408/429 | tokens kept | ❌ Нет |
| 2xx anomaly | body in error | ❌ Нет |
| logout mid-flight | result discarded | ❌ Нет |

**Вердикт:** ✅ **Тесты ловят регресс, детерминированы (vi.unstubAllGlobals в afterEach)**

---

## Итог

`approve`

F1 полностью закрыт: epoch-механизм (invalidateSession/terminateSession) отбрасывает refresh-результат после logout/смены issuer. F2-F4 исправлены (408/429 как транзиентные, тело ответа в 2xx, vi.unstubAllGlobals). Delta-спека соответствует реализации. 9 тестов ловят регресс.
