## Context

Стор авторизации (`stores/auth.ts`) содержит корректные `login()`/`logout()` (IPC + `refresh()` состояния), гвард читает живое состояние. Но `LoginView` — единственная точка **первого** входа — звал IPC напрямую, минуя стор: `state.loggedIn` оставался `false`, гвард отворачивал навигацию на `/login`. Плюс Sign out из Settings не вёл на `/login`.

## Goals / Non-Goals

**Goals:** единый путь аутентификации через стор; вход с экрана логина → `/chat`; Sign out → `/login`; интеграционный тест с настоящим стеком router/guard/store.

**Non-Goals:** вотчер состояния в гварде; изменение `entryRoute`/`bootstrapped`; изменения main-процесса.

## Decisions

### D-1: Единый путь аутентификации через стор

**Решение**: `LoginView.login()` → `useAuthStore().login()` (тот же вызов, что в Settings); `SettingsView.onSignOut()` после `auth.logout()` выполняет `router.push('/login')`.

**Альтернативы**: стору добавить маршрутизацию — нарушение слоёв (стор не знает роутер); вотчер в `auth-gate` — надёжнее для будущих точек выхода, но добавляет механизм ради второй точки; прямые IPC-вызовы из view — исходный дефект.

**Почему**: точек аутентификации две (Login, Settings); обе идут через стор, переходы — на уровне view, где роутер уже доступен. Гвард читает живое `state` — при живом состоянии навигация срабатывает всегда.

### D-2: Регрессионный тест — настоящий стек router/guard/store

**Решение**: `login-navigation.test.ts` (jsdom): настоящий `createRouter` (memory history) + `installAuthGuard` + настоящий Pinia-стор + настоящие `LoginView`/`SettingsView`; замокан только `window.harness` IPC (`loginState`/`loginStart`/`logout`/`config.get`). Сценарии: (1) не залогинен → `push('/chat')` даёт `/login`; (2) клик «Sign in» в LoginView → `/chat`; (3) навигация в Settings, клик «Sign out» → `/login`.

**Альтернативы**: мок роутера/гварда — исходный баг проходил именно такой тест; изоляция композаблов — не проверяет интеграцию.

**Почему**: баг жил на стыке view→store→guard→router; ловится только интеграцией всех четырёх.
