## Why

Живой прогон: сессия умирает сама (401 от сервера → silent refresh получает отказ гранта → `terminateSession` чистит токены), но renderer об этом не узнаёт: стор продолжает считать `loggedIn: true`, гвард никуда не ведёт, пользователь видит стену ошибок «Not signed in. Open Settings → Sign in and retry» вместо экрана логина. Восстановление — только перезапуск приложения. Main знает о потере — renderer нет.

## What Changes

- **Main → renderer уведомление**: терминальная потеря сессии (`terminateSession`: отказ гранта, отсутствие refresh-токена, нечитаемое хранилище) broadcast'ит `auth:session-lost` по существующему типизированному IPC-мосту. Интерактивный Sign out событие **не** шлёт (у него свой UI-переход).
- **Renderer**: гвард подписан на событие — при потере обновляет стор (`refresh()`), и если сессия не восстановима, выполняет переход на `/login` из любого view. Повторные события не создают циклов: переход выполняется только если текущий маршрут не `/login` и сессия не восстановилась.
- **Transient-исходы не уведомляют**: сеть/5xx при refresh не терминальны (зафиксировано в `silent-refresh-single-flight`) — события нет, сессия жива, следующий запрос пробует снова.

## Capabilities

### Modified Capabilities

- `desktop-shell` — новое требование «Уведомление о потере сессии» (терминальная потеря сессии доводится до renderer через IPC-мост; UI приводится в согласованное состояние: стор + переход на `/login`; интерактивный Sign out не дублируется).

### New Capabilities

Нет.

## Impact

- **Код**: `main/renderer-bridge.ts` (+`broadcastToRenderer`), `main/auth.ts` (`terminateSession` → broadcast), `shared/ipc-contract.ts` (+`AUTH_SESSION_LOST`), `preload/index.ts` (+`auth.onSessionLost`), `renderer/src/auth-gate.ts` (+подписка и переход), `main/index.ts` (если понадобится мост для `mainWindow`).
- **Тесты**: `auth-refresh.test.ts` (+broadcast на терминальную очистку, отсутствие broadcast на logout и на транзиентных исходах), новый `session-loss-navigation.test.ts` (реальный router/guard/store: потеря из чата → `/login`, повтор — без цикла, транзиентный — без перехода).
- **Backend**: без изменений.

## Non-goals

- Централизованный вотчер состояния авторизации (переходы по-прежнему в точках: guard-подписка на потерю, view на интерактивный вход/выход).
- Отмена/рассылка событий в relay/SSE при потере сессии (они прекращают работу сами: 401 → refresh-отказ → статусы; UI-переход — по событию).
- Ретраи или автологин после потери сессии.
