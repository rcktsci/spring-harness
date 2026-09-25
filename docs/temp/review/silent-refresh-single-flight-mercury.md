# Review: silent-refresh-single-flight (Mercury)

**Дата:** 2026-09-25  
**Объект:** незакоммиченное рабочее дерево  
**Ревьюер:** Mercury (subagent)

---

## Проверка заявлений разработчика

### Задача A: Single-flight

**Проверка кода:**
- `auth.ts:250`: модульное `refreshInFlight: Promise<string | null> | null`
- `auth.ts:286-291`: создание `doRefresh` при отсутствии in-flight, `finally` сбрасывает `refreshInFlight = null`
- `auth.ts:307-312`: `doRefresh` — единственный сетевой вызов на batch

**Тесты:**
- `auth-refresh.test.ts:52-65`: 5 параллельных requireAccessToken → ровно 1 fetch, 1 saveTokens, 0 clearTokens
- `auth-refresh.test.ts:67-83`: 400 → 1 fetch, 1 clearTokens, лог содержит `invalid_grant`

**Вердикт:** ✅ **Реализовано корректно**

### Задача B: Разделение исходов

**Проверка кода:**
- `auth.ts:102-104`, `123-124`: `RefreshGrantError` / `RefreshTransientError`
- `auth.ts:113-126`: 4xx → `RefreshGrantError` (clearTokens), 5xx/network → `RefreshTransientError` (tokens kept)
- `auth.ts:114-125`: тело ответа Keycloak в тексте ошибки
- `auth.ts:301-304`: `doRefresh` — только RefreshGrantError вызывает clearTokens

**Тесты:**
- `auth-refresh.test.ts:85-101`: сеть → clearTokens не вызван
- `auth-refresh.test.ts:103-113`: 5xx → clearTokens не вызван
- `auth-refresh.test.ts:132-143`: свежий токен → 0 fetch

**Вердикт:** ✅ **Реализовано корректно**

### Диагностика

**Проверка кода:**
- `auth.ts:114-125`: `refresh endpoint ${res.status}: ${text}` для 4xx и 5xx

**Вердикт:** ✅ **Тело ответа Keycloak в логе**

---

## Дополнительные проверки

### Соответствие delta-спеке `desktop-shell`

| Требование | Delta spec | Реализация | Статус |
|---|---|---|---|
| Single-flight | ADDED «один сетевой refresh на пачку» | auth.ts:250, 286-291 | ✅ |
| Отказ гранта (4xx) → clearTokens | MODIFIED | auth.ts:113-126, 301-304 | ✅ |
| Транзиентная ошибка → tokens kept | ADDED | auth.ts:127-130 | ✅ |

**Вердикт:** ✅ **Delta-спека соответствует реализации**

### UX ошибки

| Ситуация | Поведение | Сообщение |
|---|---|---|
| Keycloak 4xx (отзыв гранта) | clearTokens → `/login` | `refresh endpoint 400: {"error":"invalid_grant",...}` |
| Сеть/5xx (временная) | tokens сохраняются | `refresh endpoint unreachable: ECONNREFUSED` |

**Вердикт:** ✅ **Различаются явно**

### Безопасность логов

| Токен/секрет | Есть в логе? |
|---|---|
| access_token | ❌ Нет |
| refresh_token | ❌ Нет |
| error_grant, error_description | ✅ Да (диагностика) |
| endpoint status | ✅ Да |

**Вердикт:** ✅ **Нет утечки токенов**

### Регрессия (isSessionUsable)

**Проверка кода:**
- `auth.ts:317-323`: `isSessionUsable` не изменена — возвращает true если токен свеж ИЛИ есть refresh_token
- **Результат:** при транзиентной ошибке tokens сохраняются → гейт продолжает пускать вызовы

**Вердикт:** ✅ **Поведение гейта не нарушено**

### Logout

**Проверка кода:**
- `auth.ts:337-341`: `logout` вызывает `cleanupPending` перед wipe токенов

**Вердикт:** ✅ **Не нарушено**

### Тесты

| Тест | Coverage | Флаги |
|---|---|---|
| parallel consumers | 1 fetch | ❌ Нет |
| 400 grant rejected | 1 clearTokens | ❌ Нет |
| network unreachable | tokens kept | ❌ Нет |
| 5xx | tokens kept | ❌ Нет |
| next batch after failure | new refresh | ❌ Нет |
| fresh token | 0 fetch | ❌ Нет |

**Вердикт:** ✅ **Тесты ловят регресс, не флай**

---

## Итог

`approve`

Single-flight реализован через модульное in-flight обещание. 4xx → clearTokens, сеть/5xx → tokens kept. Диагностика с телом ответа Keycloak. delta-спека соответствует реализации. Нет утечки токенов в логи. 6 новых тестов ловят регресс.
