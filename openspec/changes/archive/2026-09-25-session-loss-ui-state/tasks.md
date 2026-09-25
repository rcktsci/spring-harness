## 1. Подготовка

- [x] 1.1 Change создан до кода (proposal/design/tasks + delta-спека desktop-shell).

## 2. Main → renderer

- [x] 2.1 `renderer-bridge.ts`: `broadcastToRenderer(channel, ...args)` — единственная живая точка.
- [x] 2.2 `ipc-contract.ts`: `AUTH_SESSION_LOST`; `auth.ts`: `terminateSession()` шлёт событие (logout и транзиентные исходы — нет); `preload/index.ts`: `auth.onSessionLost(cb)`.

## 3. Renderer

- [x] 3.1 `auth-gate.ts`: подписка на потерю сессии → `auth.refresh()` и переход на `/login` при невосстановимой сессии и маршруте не `/login`.

## 4. Тесты

- [x] 4.1 `auth-refresh.test.ts`: терминальная очистка → broadcast; logout → без broadcast; транзиентные исходы → без broadcast.
- [x] 4.2 `session-loss-navigation.test.ts` (реальный router/guard/store, события от main): потеря из чата → `/login`; повтор без эффекта; транзиентный исход (`loginState` возвращает loggedIn=true) → без перехода.

## 5. Верификация

- [x] 5.1 `pnpm verify` зелёный.
- [x] 5.2 `pnpm e2e:electron` и `pnpm e2e:stub` зелёные.
- [x] 5.3 `openspec validate session-loss-ui-state --strict`.

## 6. Фикс-раунд (смена Keycloak-параметров)

- [x] 6.1 `index.ts`: при смене issuer — `broadcastToRenderer(AUTH_SESSION_LOST)` после очистки токенов; неключевые настройки событие не порождают.
- [x] 6.2 Renderer-интеграционные тесты: смена issuer → `/login` + стор `loggedIn:false`; неключевая настройка → без перехода, сессия активна.

## 7. Фикс-раунд (покрытие решения об инвалидации)

- [x] 7.1 Классификатор `sessionInvalidatingChange(prev, next)` в `shared/config-invalidation.ts` (issuer/clientId → true; остальное → false); `onServerConfigChanged` использует его вместо инлайн-сравнения.
- [x] 7.2 `config-invalidation.test.ts`: матрица issuer/clientId → true; theme/confirmCommands/showTray/limits/window/serverBaseUrl → false; identical → false (исключает и константу true, и константу false).
- [x] 7.3 Мок `config.set` в renderer-тесте завязан на реальный классификатор — регресс «условие убрали/константа» ловится интеграционным тестом.
- [x] 7.4 Тест «неключевая настройка» реально нажимает Save (assert `config.set` вызван с изменённым значением) до проверки маршрута/стора.
- [x] 7.5 Повторный прогон: `pnpm verify`, `pnpm e2e:electron`, `pnpm e2e:stub`, `openspec validate --strict`.

## 8. Фикс-раунд (побочные эффекты под гардом, D-5)

- [x] 8.1 Декларативный план `planEndpointChange(prev, next)`: `invalidateSession` (issuer/clientId), `sseResubscribe` (baseUrl или инвалидация), `clientsRebuild` (только baseUrl), `notifyConfigChanged` (baseUrl или инвалидация); `onServerConfigChanged` исполняет план, `sse.unsubscribe()` и `config:changed` снова под условием.
- [x] 8.2 Матрица `planEndpointChange` в `config-invalidation.test.ts`: baseUrl → rebuild+resubscribe+notify; issuer/clientId → invalidate+resubscribe+notify без rebuild; неключевые настройки → пустой план; issuer+baseUrl → полный план. Снятие гарда ломает матрицу.
- [x] 8.3 Повторный прогон: `pnpm verify` (268 тестов), `pnpm e2e:electron`, `pnpm e2e:stub`, `openspec validate --strict`.
