## Why

Живой прогон 2026-09-25: «Sign in» → SSO проходит, токены сохраняются (main.log: `tokens persisted to keychain`), но приложение **остаётся на `/login`** — нужен перезапуск. В драйвере: `[post-login] {"route":"/login"}`, `[chat-route] {"route":"/login"}`.

Причина: `LoginView.vue` вызывает `window.harness.auth.loginStart()` напрямую, минуя Pinia-стор авторизации. `state.loggedIn` остаётся `false`, и гвард (`auth-gate.ts`) на `router.push('/chat')` возвращает на `/login`. Через стор вход работает (Settings → Sign in), т.е. единственный путь первого входа в приложение — сломанный.

Смежное: после Sign out из Settings состояние стора обновляется, но переход на `/login` не выполняется — пользователь остаётся на `/settings` с мёртвой сессией до первой навигации.

## What Changes

- **Вход с экрана логина — через стор**: `LoginView` использует `useAuthStore().login()` (единый путь с Settings): `loginStart()` + `refresh()` → гвард видит актуальное состояние → переход на `/chat` срабатывает.
- **Выход возвращает на экран логина**: после `auth.logout()` в Settings выполняется переход на `/login`.
- Гвард (`installAuthGuard`/`entryRoute`/`bootstrapped`) не меняется: `bootstrapped` кэширует первичный refresh, но гвард читает живое состояние стора — правки не требуются (проверено).

## Capabilities

### Modified Capabilities

- `desktop-shell` — новое требование «Навигация после аутентификации» (координация renderer-навигации после login/logout; экран логина и переходы — shell-уровень).

### New Capabilities

Нет.

## Impact

- **Код**: `web-desktop/src/renderer/src/views/LoginView.vue` (через стор), `views/SettingsView.vue` (переход после Sign out).
- **Тесты**: новый интеграционный тест `tests/unit/login-navigation.test.ts` — настоящий vue-router + `installAuthGuard` + настоящий стор (мок только IPC): не залогинен → `/login`; вход с экрана логина → `/chat`; Sign out из Settings → `/login`.
- **Backend**: без изменений.

## Non-goals

- Автоматический редирект-вотчер состояния авторизации (централизованный watch в гварде) — переходы выполняются в точках login/logout; возвращаться к вотчеру имеет смысл при появлении третьей точки выхода.
- Изменение гварда (`entryRoute`/`bootstrapped`) — поведение корректно при живом состоянии стора.
