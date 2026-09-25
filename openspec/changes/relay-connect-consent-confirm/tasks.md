## 1. Подготовка

- [x] 1.1 Change создан (proposal/design/tasks + delta-спеки desktop-relay-client, desktop-chat) до кода.

## 2. Main: pending-consent

- [x] 2.1 `relay-client.ts`: хранение текущего consent-запроса, `getPendingConsent()`, очистка при `resolveRegistrationConsent`.
- [x] 2.2 `ipc-contract.ts` (`RELAY_PENDING_CONSENT`) + `index.ts` (handler) + `preload/index.ts` (`relay.pendingConsent()`).

## 3. Renderer: синглтон-состояние и диалоги

- [x] 3.1 `useRelay.ts`: module-scoped состояние, wire-once подписки, `ensureConnected` (гейты: STATE / уже зарегистрирована / дедупликация), показ причины отказа в статусе.
- [x] 3.2 `RelayDialogs.vue`: consent-диалог и tool-confirm-диалог (глобальный оверлей), pending-consent re-fetch при монтировании.
- [x] 3.3 `App.vue`: монтаж `RelayDialogs`.
- [x] 3.4 `ChatView.vue`: авто-подключение при открытии FREE-сессии (`watch(activeSession)` → `ensureConnected`), `relayLabel` показывает `reason`.

## 4. Тесты

- [x] 4.1 Unit `use-relay.test.ts`: гейты `ensureConnected`, respondConsent/respondToolConfirm, pending-consent re-fetch в диалогах.
- [x] 4.2 Unit `relay-client.test.ts`: `getPendingConsent()` до ответа, очистка после ответа.
- [x] 4.3 e2e `stub-server.ts`: WS `tool.call` зарегистрированному соединению (ретраи до регистрации), `toolCount` из `client.tools[]`, кроссплатформенная сценарная команда; **fix** — `welcome` по §5.1 с полем `protocol` (вместо `protocolVersion`; фрейм отбрасывался парсером клиента → handshake-таймаут; старый e2e этого не ловил, т.к. WS-цикл в нём не выполнялся).
- [x] 4.4 e2e `electron-smoke.spec.ts`: переписан под реальный DOM — сценарий 1 (confirmCommands=never: consent → полный tool-цикл → save-as с записью файла), сценарий 2 (confirmCommands=always: отклонение и разрешение команды через диалог).

## 5. Верификация

- [x] 5.1 `pnpm verify` зелёный (lint + typecheck + unit + build).
- [x] 5.2 `pnpm e2e:electron` зелёный локально (Windows, нативный дисплей).

## 6. Ревью-фиксы (консенсус DeepSeek + Mercury: F1–F4)

- [x] 6.1 F1: decline → `relay-client.register()` эмитит статус (`consent-declined` + reason); `relayLabel` вынесен в `lib/relay-label.ts`, «подключён» только при registered; guard-тесты (unit relay-label, unit relay-client, e2e-шаг decline).
- [x] 6.2 F2: STATE-сессия — индикатор «релей доступен только для root-сессий» (перекрывает статус другой сессии); scenario в delta desktop-chat.
- [x] 6.3 F3: политика параллельных регистраций — `register-in-progress` для другой сессии (без side-effects), `consent-pending` для той же, `registeringSession` в main, per-session Set в renderer; unit-тесты.
- [x] 6.4 F4: `web-desktop/docs/smoke.md` синхронизирован с актуальным e2e (2 сценария, реальные селекторы, decline-шаг, save-as с записью).
- [x] 6.5 Повторный прогон: `pnpm verify`, `pnpm e2e:electron`, `pnpm e2e:stub`.
