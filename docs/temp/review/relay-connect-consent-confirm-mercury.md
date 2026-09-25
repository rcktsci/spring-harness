# Review: relay-connect-consent-confirm (Mercury, subagent)

**Дата:** 2026-09-24  
**Проверяемый change:** openspec/changes/relay-connect-consent-confirm/

---

## 1. Проверка заявлений разработчика

### Утверждение про stub-сервер и `welcome {protocol}`
> stub-сервер отправлял `welcome { protocolVersion: 1 }`, клиент по api-contracts §5.1 ждёт `welcome { protocol: 1 }` — парсер отбрасывал фрейм → handshake-таймаут

**Вердикт:** ✅ **Правда, подтверждено**  
git diff web-desktop/tests/e2e/stub-server.ts:649:
```
-        protocolVersion: SUPPORTED_PROTOCOL,
+        protocol: SUPPORTED_PROTOCOL,
```

Это не «фиктивное исправление» — старый stub действительно был несовместим с контрактом. Клиентский парсер (ws-frames.ts:parseRelayFrame) ожидает поле `protocol`, а не `protocolVersion`. На живой стенд это не влияло (сервер отправляет правильно), но e2e-инфраструктура была сломана.

---

## 2. Соответствие контрактам

### desktop-relay-client/spec.md §3.2 (регистрация на сессии)

| Требование | Реализация | Статус |
|---|---|---|
| ПРИ открытии FREE root-сессии → connect + register | ChatView.vue:70-78 (watch activeSession → ensureConnected) | ✅ |
| STATE-сессии не регистрируются | useRelay.ts:72 (if kind === 'STATE' return) | ✅ |
| basePath по умолчанию ~/harness-workspaces/{sessionId} | relay-client.ts:240 (join(homedir(), 'harness-workspaces', sessionId)) | ✅ |
| Стартовая авторегистрация с сохранённой сессией | main/index.ts:relayActiveSessionId (существует) + pendingConsent re-fetch | ✅ |

### desktop-relay-client/spec.md §Безопасность локального исполнения

| Требование | Реализация | Статус |
|---|---|---|
| Первая регистрация → подтверждение пользователя | RelayDialogs.vue:26-62 (consent-dialog) | ✅ |
| confirmCommands=always → подтверждение каждой команды | RelayDialogs.vue:64-95 (tool-confirm-dialog) | ✅ |
| Диалог показывает команду и аргументы | RelayDialogs.vue:72-75 (tool name + args JSON) | ✅ |

### D-93 (confirmCommands)

**Реализация:** Полностью соответствует. Dialog показывает:
- Инструмент (bash/файловый)
- Аргументы (JSON)
- basePath (каталог исполнения)

---

## 3. Анализ открытых вопросов разработчика

| # | Вопрос | Оценка |
|---|---|---|
| 1 | **Двухфазный UX decline**: после «Отклонить» диалог появляется снова при каждом открытии | ✅ **Нормальное поведение** — соответствует спеке, не требует D-решения |
| 2 | **Сообщения на английском** | ✅ **Принято** — закреплены unit-тестами, локализация — эволюция |
| 3 | **Takeover-война**: авто-connect после superseded | ✅ **Нормально** — как ручная кнопка, для «один инстанс на VM» риск минимален |
| 4 | **`relay.status.reason` не показывает decline-исход** | ⚠️ **Minor** — improvement, но не нарушение контракта |
| 5 | **Временные каталоги в %TEMP%** | ✅ **Нормально** — e2e-артефакты, не влияет на продакшн |

---

## 4. e2e-тесты

### Stub-server контракту §5.1

| Фрейм | Требование | Stub |
|---|---|---|
| `hello` → `welcome` | поле `protocol` (не `protocolVersion`) | ✅ Исправлено |
| `register` → `registered` | `sessionId`, `toolCount` | ✅ (lines 690-694) |
| `tool.call` доставка | зарегистрированному соединению | ✅ (deliverToolCallWhenRegistered) |

### real DOM-селекторы

| Старый селектор | Новый селектор | Где |
|---|---|---|
| `button[data-session-id]` | `li.session-item` | electron-smoke.spec.ts:130 |
| Н/A | `data-testid="consent-dialog"` | electron-smoke.spec.ts:135 |
| Н/A | `data-testid="tool-confirm-dialog"` | electron-smoke.spec.ts:180 |

### Полный tool-цикл

| Сценарий | Покрытие |
|---|---|
| `confirmCommands=never` → consent → tool.result | ✅ Scenario 1 |
| `confirmCommands=always` → deny | ✅ Scenario 2 Round 1 |
| `confirmCommands=always` → approve | ✅ Scenario 2 Round 2 |
| Real save-as с записью на диск | ✅ Scenario 1 steps 5 |

**Вердикт:** e2e — полноценный страж, селекторы из реального DOM, tool-цикл выполняется полностью.

---

## 5. Безопасность подтверждения

| Требование | Реализация | Статус |
|---|---|---|
| Диалог показывает, что будет исполнено | RelayDialogs.vue:72-75 (tool name, args, basePath) | ✅ |
| Нет пути авто-подтверждения (D-93 default=always) | e2e использует `confirmCommands=never` для первого сценария; второй — реальный deny/approve | ✅ |
| basePath показан в обоих диалогах | consent: line 38, tool-confirm: line 73 | ✅ |

---

## 6. Найденные проблемы

| Severity | Файл:строка | Описание |
|---|---|---|
| minor | ChatView.vue:170-171 | После decline релей остаётся «подключён» (не registered); reason не показывается. Improvement, но не баг. |

---

## 7. Вердикт

`approve`

Change соответствует контрактам (§5 api-contracts, desktop-relay-client/spec.md, D-93). e2e-инфраструктура исправлена (stub теперь слёт каноничный `welcome {protocol}`), тесты покрывают полный tool-цикл с обоими диалогами. Все три дыры M5 закрыты.
