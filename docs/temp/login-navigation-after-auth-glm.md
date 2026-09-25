# Отчёт: login-navigation-after-auth (GLM-5.3-Flash, субагент-разработчик)

Дата: 2026-09-25. Change: `openspec/changes/login-navigation-after-auth/` (создан до кода, `openspec validate --strict` — valid). Delta-спека: `desktop-shell` — ADDED «Навигация после аутентификации» (ADDED, а не MODIFIED: требование «SSO-логин и хранение токена» уже MODIFIED в активном change `relay-session-workspace-isolation` — уникальное имя исключает конфликт при архивации).

## Суть дефекта

`LoginView.vue` (единственная точка **первого** входа) звал `window.harness.auth.loginStart()` напрямую, минуя Pinia-стор. `state.loggedIn` оставался `false` → гвард (`entryRoute`) на `router.push('/chat')` возвращал на `/login`. При этом токены были сохранены (`tokens persisted to keychain`) — сессия жива, а UI заперт на экране логина до перезапуска. Через стор вход работал (Settings → Sign in) — единственный сломанный путь был самым главным.

## Решение (design D-1)

- `LoginView.login()` → `useAuthStore().login()` (единый путь со Settings): `loginStart()` + `refresh()` → гвард видит актуальное состояние → `/chat`.
- `SettingsView.onSignOut()` — после `auth.logout()` выполняет `router.push('/login')` (раньше пользователь оставался на `/settings` с мёртвой сессией до первой навигации).
- Гвард (`entryRoute`/`bootstrapped`) **не менялся**: `bootstrapped` кэширует первичный refresh-промис (не результат), гвард читает живое состояние стора — при живом состоянии навигация срабатывает всегда. Смежные места проверены: `auth.loginStart`/`logout`/`loginState` больше нигде не вызываются в обход стора (grep: только `stores/auth.ts`, гвард — `refresh()`).

**Отвергнутые альтернативы**: маршрутизация из стора (нарушение слоёв); централизованный вотчер состояния в гварде — надёжнее для будущих точек выхода, но добавляет механизм ради второй точки (зафиксировано в Non-goals: вернуться при появлении третьей).

## Регрессионный тест

`tests/unit/login-navigation.test.ts` (jsdom) — **настоящий стек**: `createRouter` (memory history) + реальный `installAuthGuard` + реальный Pinia-стор + настоящие `LoginView`/`SettingsView` в `RouterView`-шелле; замокан только IPC (`loginState`/`loginStart`/`logout`/`config.get`; loginStart/logout мутируют эмулированное состояние — как main).

Сценарии (3):
1. Не залогинен запрашивает `/chat` → гвард оставляет на `/login`.
2. **Ключевой регрессионный**: клик «Sign in» на экране логина (`data-testid="login-submit"`) → `/chat` (исходный баг: оставался на `/login`). Тест падает на старом коде — проверено причинностью: стор+гвард настоящие, прямой IPC-вызов не обновляет `state`.
3. Sign out из Settings (`data-testid="sign-out"`) → `/login`.

Плюс добавлен `data-testid="login-submit"` кнопке LoginView (в стиле существующих testid'ов).

## Команды и результаты

- `pnpm verify`: **зелёный** — lint, typecheck node+web, Vitest **31 файл / 253 теста passed** (+3 login-navigation), build ok.
- `pnpm e2e:electron`: **2 passed** (~4.9 c).
- `pnpm e2e:stub`: **7 passed** (~7.4 c).
- `openspec validate login-navigation-after-auth --strict`: **valid**.

## Риски / открытия

1. Переходы после logout/login выполняются в точках вызова (view), а не централизованным вотчером гварда — при появлении третьей точки выхода стоит вернуться к вотчеру (Non-goals, решение осознанное).
2. `bootstrapped` в гварде кэширует только первичный refresh — поведение не менялось; живое состояние берётся из стора.
3. Других обходов стора не найдено (grep по renderer: SettingsView/store/guard — согласованы).
