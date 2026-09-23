# Ревью M5 batch B (Qwen)

Скоуп: задачи 2.1–2.4а; `specs/desktop-shell/spec.md`; design D-87/D-89/D-91/D-93.
Верификация по файлам и артефактам (сборки не запускались — дев-сессия прогнала
lint/typecheck/test 34/34/build/generate:api, всё pass).

## Вердикт: APPROVE

Ключевые инварианты выдержаны. PKCE корректен (S256, state, verifier не логируется),
safeStorage — только keychain с отказом при недоступности, токены не уходят в renderer
(D-91), silent refresh без окна, logout чистит всё, confirmCommands=always, ws-frames
полностью покрывают §5, закрытые коды 4401/4403/4409 классифицированы, открытые каналы
явно отдают not-implemented. Тесты осмысленные (RFC-вектор PKCE, proof отсутствия
plain-text на диске, полный парсинг фреймов §5 + close-коды).

Ниже — находки, не блокирующие аппрув.

## Находки

### MEDIUM — `keycloakRedirectUri` из конфига не используется (дрейф конфига)
- `src/shared/ipc-contract.ts:52` — `keycloakRedirectUri: 'http://127.0.0.1:43189/callback'`
  объявлен в `DEFAULT_CONFIG` и в `ServerConfig` (строка 82), редактируемый в Settings.
- `src/main/auth.ts:45,86` — фактический `redirect_uri` строится как
  `http://127.0.0.1:${port}/callback` с **эфемерным** портом loopback-сервера
  (`server.listen(0, ...)` → `auth.ts:143`). Конфиг-значение нигде не читается.
- Риск: пользователь меняет `keycloakRedirectUri` в Settings → изменение молча
  игнорируется; в Keycloak должен быть зарегистрирован именно динамический
  loopback-URI, а не зафиксированный `43189`. Конфиг-поле вводит ложное ощущение
  настраиваемости.
- Действие: либо использовать `cfg.keycloakRedirectUri` как базовый URI (с заменой
  порта на эфемерный), либо убрать поле из `ServerConfig`/`DEFAULT_CONFIG` и
  Settings, задокументировав, что redirect — динамический loopback.

### MINOR — `pending` не сбрасывается при закрытии окна до redirect
- `src/main/auth.ts:163-213` — `win.on('closed', ...)` резолвит промис `cancelled`,
  но `cleanupPending()` вызывается только после `await` (строка 215). Если окно
  закрыто, `pending` остаётся до возврата в `startLogin`. На практике это безвредно
  (промис уже резолвлен, `cleanupPending` отработает), но `pending !== null` между
  закрытием окна и возвратом контрола может дать ложный `denied` при быстром
  повторном `startLogin`.
- Действие: вызывать `cleanupPending()` внутри `win.on('closed')` (идемпотентно —
  `pending = null` на входе), либо не блокировать повторный логин по `pending`.

### MINOR — `logout` не сбрасывает `pending`/не закрывает незакрытое login-окно
- `src/main/auth.ts:283-289` — `logout` чистит токены и открывает Keycloak logout,
  но не трогает `pending`. Если logout инициирован, пока открыто login-окно,
  loopback-сервер и окно останутся. Редкий сценарий (logout из Settings при
  незавершённом логине), но для «logout чистит всё» — зазор.
- Действие: в `logout` добавить `cleanupPending()`.

### MINOR — `parseRelayFrame` не валидирует `protocol` на равенство версии
- `src/main/ws-frames.ts:165-170` — `welcome` принимается при любом числовом
  `protocol` (проверка только `hasNumber`). Контракт §5.1: версия вне поддержки →
  close 4403 `protocol-mismatch`. Клиент обязан сверять `protocol ===
  RELAY_PROTOCOL_VERSION` на стороне WS-клиента (batch C). Сейчас парсер
  «пропускает» несовместимую версию.
- Действие: в `parseRelayFrame` для `welcome` требовать
  `payload.protocol === RELAY_PROTOCOL_VERSION`, либо явно задокументировать, что
  сверка версии — ответственность WS-клиента (batch C), и добавить unit-тест.

### MINOR — `error`-фрейм не сужает `code` к `RELAY_REGISTRATION_ERROR`
- `src/main/ws-frames.ts:176-182` — `code` принимается как любой `string`, хотя
  §5.2 фиксирует набор кодов отказа регистрации. `RelayRegistrationError`-тип
  существует, но не используется в `RelayErrorFrame.code`.
- Действие: сузить `code` к `RelayRegistrationError` (с fallback для будущих
  кодов) или оставить `string` с комментарием, что набор расширяется.

### MINOR — тест `token-store` не покрывает ветку `SafeStorageUnavailableError`
- `tests/unit/token-store.test.ts` — мокается только `isEncryptionAvailable: true`.
  Ключевой инвариант «plain-text невозможен, отказ при отсутствии keychain»
  (spec: «safeStorage недоступен — отказ с понятным сообщением») не покрыт
  unit-тестом: нет кейса `isEncryptionAvailable: false` → `loadTokens`/`saveTokens`
  бросают `SafeStorageUnavailableError`.
- Действие: добавить тест с `isEncryptionAvailable: false`, ожидая
  `SafeStorageUnavailableError` из `saveTokens` и `loadTokens`.

### MINOR — тест PKCE не проверяет, что verifier не логируется
- `tests/unit/pkce.test.ts` — покрывает alphabet/length, RFC-вектор, state. Но
  инвариант «verifier не логируется» (чек-лист) не верифицируется: в `auth.ts`
  verifier уходит только в `code_verifier` тела запроса (`auth.ts:87`) и в
  `PendingLogin.pkce`; логи (`auth.ts:161,254`) не содержат verifier. Это верно по
  коду, но нет теста, фиксирующего отсутствие утечки в лог.
- Действие: опционально — добавить assertion, что `log.*` вызовы в login-флоу не
  содержат `codeVerifier` (сейчас подтверждено визуально: утечки нет).

## Подтверждённые (без замечаний)

- PKCE S256: `pkce.ts:23-28` — SHA-256 + base64url, RFC-вектор в тесте
  (`pkce.test.ts:23-27`). `code_challenge_method: S256` (`auth.ts:53`).
- state: генерируется (`auth.ts:140`), сверяется в callback (`auth.ts:189`).
- loopback-сервер: `server.listen(0, '127.0.0.1')` (`auth.ts:143`), закрывается в
  `cleanupPending` (`auth.ts:223`) после каждого исхода.
- safeStorage: `assertSafeStorage()` (`token-store.ts:35-39`) бросает
  `SafeStorageUnavailableError` при `isEncryptionAvailable() === false`;
  `saveTokens`/`loadTokens` шифруют/дешифруют через `safeStorage`
  (`token-store.ts:56,63`); plain-text на диск не пишется (тест
  `token-store.test.ts:82-87`).
- Токены не в renderer: preload (`preload/index.ts`) отдаёт только `LoginState`
  (без токена); `readLoginState` (`auth.ts:294-301`) возвращает `loggedIn`-флаг;
  IPC-каналы не передают JWT (D-91).
- Silent refresh без окна: `refreshIfNeeded` (`auth.ts:234-265`) — fetch, без
  BrowserWindow.
- Logout чистит: `clearTokens()` + Keycloak end-session (`auth.ts:283-289`).
- confirmCommands дефолт `always`: `DEFAULT_CONFIG.confirmCommands = 'always'`
  (`ipc-contract.ts:65`), зафиксирован тестами (`config-binding.test.ts:24`,
  `ipc-contract.test.ts:22`).
- Числа только в DEFAULT_CONFIG: все числовые параметры — в `DEFAULT_CONFIG`
  (`ipc-contract.ts:48-66`); в main-коде числа — только RFC-константы PKCE
  (`pkce.ts:11,12,39`), close-коды §5.5 (`ws-frames.ts:14-16`), HTTP-статусы
  loopback-ответа (`auth.ts:173,185,190,198,203`) и `1000` (мс→с). Ничего
  «настраиваемого» в коде.
- ws-frames покрывают §5: hello/welcome/register/registered/error/tool.call/
  tool.progress/tool.result/tool.cancel/ping/pong + close 4401/4403/4409
  (`ws-frames.ts`); парсинг и классификация покрыты тестами
  (`ws-frames.test.ts`), сверка с `api-contracts.md` §5.1–5.5 — полная.
- Закрытые коды классифицированы: `classifyCloseCode` (`ws-frames.ts:218-235`) —
  4401 recoverable (silent refresh), 4403 fatal, 4409 registration; тесты
  `ws-frames.test.ts:89-120`.
- Открытые каналы явно not-implemented: `registerIpcStubs` (`index.ts:117-136`)
  бросает `not implemented in batch B: <channel>` для всех не-реализованных
  каналов — не тихий no-op.
- CSP `default-src 'self'` (`renderer/index.html:5`), sandbox/contextIsolation/
  nodeIntegration (`window.ts:68-72`, `auth.ts:153-157`) — D-92.
- Генерация из openapi.yaml: `src/api/generated/openapi.d.ts` — MessageKind
  (включая ASYNC_ACCEPTED), runtimeStatus, TreeNode — присутствуют (D-89).
