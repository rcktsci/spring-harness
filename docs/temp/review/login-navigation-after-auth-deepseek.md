# Ревью change `login-navigation-after-auth` (незакоммиченное рабочее дерево)

Рецензент: субагент-ревьюер (DeepSeek-V4.1-Flash). Дата: 2026-09-25.
Вход: `openspec/changes/login-navigation-after-auth/` (proposal/design/tasks + delta desktop-shell), рабочее дерево (`LoginView.vue`, `SettingsView.vue`, `stores/auth.ts`, `auth-gate.ts`, new test), отчёт `docs/temp/login-navigation-after-auth-glm.md`.
Линза: воспроизводит ли тест баг, полнота (нет ли других расхождений состояния/стором/кэша гварда), не сломана ли навигация, соответствие delta-спеке, конвенции. Сборка/тесты/docker не запускались; проверял по коду.

## Дефект и фикс — корректны

- **Вход через стор.** `LoginView.vue:4,7,15` — `useAuthStore()` и `await auth.login()` (`stores/auth.ts:12-15`: `loginStart()` + `refresh()`), затем `router.push('/chat')` (`:16`). `refresh()` обновляет `state.loggedIn` из `loginState()` → гвард видит актуальное состояние.
- **Выход ведёт на /login.** `SettingsView.vue:36-37` — после `await auth.logout()` (обновляет стор) `await router.push('/login')`.
- **Гвард не менялся и не кэширует решение.** `auth-gate.ts:21-27`: `bootstrapped ??= auth.refresh().then(...)`, `await bootstrapped`, затем `entryRoute(name, auth.state.loggedIn)` — кэшируется только первичный refresh-промис (барьер), решение читается из живого стора на каждой навигации. После login/logout стор свежий → переходы срабатывают.
- **Других обходов стора нет.** grep по renderer: `window.harness.auth.*` вызывается только в `stores/auth.ts`; `useAuthStore` — в LoginView/SettingsView/guard. Единый путь соблюдён.

## Тест воспроизводит регресс и не «зелёный из-за моков»

- `tests/unit/login-navigation.test.ts` собирает реальный стек: настоящий `createRouter` (memory) + `installAuthGuard` + реальный Pinia-стор + настоящие SFC `LoginView`/`SettingsView` в `RouterView` (`:42-55`); замокан только `window.harness` IPC, причём `loginStart`/`logout` эмулируют main (мутируют `harnessState.loggedIn`, `:24-31`), а `loginState` его отдаёт (`:23`).
- **Дискриминация старого кода.** На прежнем `LoginView` прямой `loginStart()` не трогал стор (`state.loggedIn` оставался false) → `router.push('/chat')` → guard `entryRoute('chat', false)` → `/login`; ассерт `toBe('/chat')` (`:83`) падал бы. Аналогично старый Settings без `push` оставлял `/settings`; ассерт `toBe('/login')` (`:97`) падал бы. Сценарий 1 (`:65-71`) страхует, что guard реальный (иначе `/chat` не завернулся бы на `/login`).
- **Покрытие переходов:** не залогинен → `/login` (`:65`); первый вход с экрана логина → `/chat` (`:73`); Settings доступен залогиненному и Sign out → `/login` (`:86`). Все затронутые переходы покрыты; детали моков не «делают» ассерты (проверяется реальная навигация `router.currentRoute`).

## Delta-спека — соответствует реализации, не ослаблена

- `specs/desktop-shell/spec.md` ADDED «Навигация после аутентификации» (`:9-26`): единый store; после входа — `/chat`; guard пропускает по актуальному состоянию; выход → `/login`; первый вход и переавторизация из Settings ведут себя одинаково. Реализация совпадает: LoginView и Settings `onSignIn` оба идут через `auth.login()`, Sign out — через `auth.logout()` + `push('/login')`. Выбор ADDED (а не MODIFIED) вместо конфликта с активным `relay-session-workspace-isolation` — обоснован и на уникальность имени не влияет.
- Молчаливых исключений нет: LoginView/Settings ловят ошибки и показывают их (`error`), не глотают. Новых комментариев в коде и зашитых чисел нет (Settings `onSignIn`-комментарий — до этого change'а).

## Наблюдение (не блокирует, вне scope change'а)

- **Непроизвольная потеря сессии не обновляет стор/не ведёт на `/login`.** Если main очищает токены после неудачи silent refresh (`silent-refresh-single-flight`) или `requireAccessToken` возвращает «Not signed in», renderer об этом не уведомляется: `bootstrapped` в гварде — one-shot (повторный `refresh()` не вызывается), `auth.state.loggedIn` остаётся `true`, авто-перехода на `/login` нет. Это существовавшее до change'а расхождение, а не регрессия; правка потребовала бы нового механизма main→renderer (auth-событие/вотчер), что выходит за заявленный scope (proposal Non-goals: две точки аутентификации — Login и Settings). Отмечаю как точку на будущее (третий путь выхода), для данного change'а не блокер.

## Итог

Дефект «вход через прямой IPC минует стор → застрял на /login» закрыт по существу: и LoginView, и Settings идут через единый стор, guard читает живое состояние (кэшируется только первичный refresh-барьер), Sign out возвращает на `/login`. Интеграционный тест воспроизводит оба регресса на реальном стеке и падал бы на старом коде. Delta-спека соответствует реализации; навигация в остальных view не сломана; обходов стора больше нет. Остаточное — непроизвольная потеря сессии (наблюдение, вне scope). Новых блокеров нет.

## ВЕРДИКТ: `approve`
