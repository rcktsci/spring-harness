# Review: relay-session-workspace-isolation (Mercury)

**Дата:** 2026-09-25  
**Объект:** незакоммиченное рабочее дерево  
**Ревьюер:** Mercury (subagent)

---

## Проверка заявлений разработчика

### Дефект 1: bash исполнялся в каталоге чужой сессии

**Проверка кода:**
- `relay-client.ts:104-105`: `sessionBasePaths: Map<sessionId, basePath>` — per-session mapping
- `relay-client.ts:618-619`: `handleToolCall` читает `sessionBasePaths.get(call.sessionId)` вместо `this.basePath`
- `relay-client.ts:622-628`: отсутствие записи → отказ `no registration for session <id>` с `exitCode: -1`
- `relay-client.ts:376-377`: запись добавляется при `registered` (`sessionBasePaths.set(sessionId, basePath)`)
- `relay-client.ts:227`: `disconnect()` очищает `sessionBasePaths.clear()`

**Тесты:**
- `relay-client.test.ts:261-283`: rejects tool call for unregistered session → no process execution
- `relay-client.test.ts:285-330`: successively registered sessions execute in their own workspaces
- `relay-client.test.ts:332-374`: reconnect → fresh basePath, old entry not used

**Вердикт:** ✅ **Исправлено полностью** — per-session workspace routing реализован

### Дефект 2: запросы к Keycloak без таймаута

**Проверка кода:**
- `auth.ts:94, 151`: `AbortSignal.timeout(cfg.keycloakRequestTimeoutMs)` на обоих fetch
- `ipc-contract.ts`: `keycloakRequestTimeoutMs` добавлен (DEFAULT_CONFIG = 15 000)

**Тесты:**
- `auth-refresh.test.ts`: aborts hanging token endpoint request by configured timeout

**Вердикт:** ✅ **Исправлено полностью** — таймаут из конфига, транзиентный исход

### Дефект 3: таймер регистрации съедается ожиданием consent

**Проверка кода:**
- `relay-client.ts:349-360`: timer стартует после отправки фрейма (внутри sendRegister)
- `relay-client.ts:352-361`: таймаут эмитит статус `{code: 'register-timeout', reason: 'registration timeout'}`

**Тесты:**
- `relay-client.test.ts:376-404`: does not consume register timeout while consent is pending
- `relay-client.test.ts:406-437`: surfaces register-timeout status and allows retry

**Вердикт:** ✅ **Исправлено полностью** — таймер после consent, статус виден

---

## Дополнительные проверки

### Delta-спека `desktop-relay-client`

| Требование | Delta spec | Реализация | Статус |
|---|---|---|---|
| sessionId → basePath routing | MODIFIED «Локальное исполнение tool.call» | relay-client.ts:104-628 | ✅ |
| Отказ для незарегистрированной сессии | MODIFIED | relay-client.ts:622-628 | ✅ |
| Записи переживают переключение | MODIFIED | relay-client.ts:376, 227 | ✅ |
| Отказ по таймауту виден | MODIFIED «Регистрация на сессии с декларацией» | relay-client.ts:352-361 | ✅ |
| Consent вне таймаута регистрации | MODIFIED | relay-client.ts:349-360 | ✅ |

**Вердикт:** ✅ **Delta-спека соответствует реализации**

### UX

| Ситуация | Пользователь видит | Действие |
|---|---|---|
| Регистрация по таймауту | «релей: регистрация по таймауту» | Кнопка «Подключить» повторяет |
| Чужая сессия | «no registration for session <id>» в ленте | Сервер маршрутизирует корректно |
| Keycloak не отвечает | «silent refresh postponed — transient failure» в логе | Следующий запрос попробует снова |

**Вердикт:** ✅ **UX корректен, нет состояния «залогинен, но всё падает» — гейт isSessionUsable продолжает работать**

### Безопасность

| Токен/секрет | Утечка в логи? |
|---|---|
| access_token | ❌ Нет |
| refresh_token | ❌ Нет |
| sessionId | ✅ Да (диагностика) |

**Вердикт:** ✅ **Нет утечки токенов**

### Согласование с D-88/D-79

| Требование | Реализация | Статус |
|---|---|---|
| D-88: basePath дефолт | сохраняется (relay-client.ts:240) | ✅ |
| D-79: per-session workspace | sessionBasePaths map | ✅ |
| Записи прошлых сессий | живут до disconnect (осознанный компромисс) | ✅ |

**Вердикт:** ✅ **Согласовано, зафиксировано как компромисс**

### Конкурентные регистрации

**Проверка кода:**
- `relay-client.ts:250-259`: `busyRegisteringOtherThan()` — политика `register-in-progress`/`consent-pending` (уже в проде с relay-connect-consent-confirm)
- `relay-client.ts:104`: per-session map дополнительно гарантирует корректность

**Вердикт:** ✅ **Запрет уже в проде, map защищает от класса бага**

### Тесты

| Тест | Coverage | Флаги |
|---|---|---|
| unregistered session reject | tool.call отказ | ❌ Нет |
| sequential sessions | own workspaces | ❌ Нет |
| reconnect fresh basePath | dirB not dirA | ❌ Нет |
| consent vs timeout | no consume | ❌ Нет |
| register-timeout visible | retry | ❌ Нет |

**Вердикт:** ✅ **Тесты ловят регресс, не флай**

---

## Итог

`approve`

Главный баг (выполнение команды в чужом workspace) исправлен через per-session map. Таймаут Keycloak из конфига (15s), транзиентный исход. Таймаут регистрации виден и повторяем. Delta-спека соответствует реализации, UX корректен, нет утечки токенов. 5+ новых тестов ловят регресс.
