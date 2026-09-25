# Ревью change `session-loss-ui-state` (незакоммиченное рабочее дерево)

Рецензент: субагент-ревьюер (DeepSeek-V4.1-Flash). Дата: 2026-09-25.
Вход: `openspec/changes/session-loss-ui-state/` (proposal/design/tasks + delta desktop-shell), рабочее дерево (`main/auth.ts`, `main/renderer-bridge.ts`, `shared/ipc-contract.ts`, `preload/index.ts`, `renderer/src/auth-gate.ts`, тесты), отчёт `docs/temp/session-loss-ui-state-glm.md`.
Линза: полнота терминальных путей, гонки, совместимость с одобренным поведением, IPC-контракт, тесты. Сборка/тесты/docker не запускались; проверял по коду.

## Терминальные пути → событие: покрыто, транзиентные не шлют

- `terminateSession()` (`main/auth.ts:315-318`) — единственная точка терминальной потери: `sessionEpoch++`, `clearTokens()`, `broadcastToRenderer(IPC.AUTH_SESSION_LOST)`. Вызывается ровно из трёх мест: нечитаемое хранилище (`:349`), отсутствие refresh-токена (`:361`), отказ гранта (`:389`). Все три → событие. grep по main: других `clearTokens()` только `logout()` (`:431`, без broadcast — интерактивный) и смена issuer в `index.ts:212` (интерактивная, см. ниже).
- **Транзиентные не шлют:** сеть/5xx/408/429 не доходят до `terminateSession` (ветки `RefreshTransientError` → `log.warn`), `SafeStorageUnavailableError` в `refreshIfNeeded` → `return null` без очистки/broadcast. Тест `auth-refresh.test.ts`: grant → `broadcastToRenderer('auth:session-lost')` (`:100`); сеть/5xx (`:117,132`) и `logout` (`:135-143`) → broadcast не вызван. Соответствует delta-спеке (сценарии «транзиентный не выкидывает», «интерактивный выход не дублируется»).
- **Main-список `implemented` не трогали** — канал push-only; generic-stub `ipcMain.handle` для `auth:session-lost` (`index.ts:283-289`) безвреден (никто не `invoke`-ит).

## Гонки и идемпотентность — корректно

- **Повторные события/лавина 401.** Main: single-flight refresh вызывает `terminateSession` один раз на пачку; после очистки `loadTokens()` → null → ранний `null` (без broadcast). Renderer: `handleSessionLoss` (`auth-gate.ts:34-42`) — `await auth.refresh()` → если `loggedIn` ещё true, выходит; переход только при `router.currentRoute.value.path !== '/login'`. Два одновременных события → два `refresh()`, оба push('/login') — vue-router дубликат-навигацию гасит. Цикла нет; тест `session-loss-navigation.test.ts:75-89` (повтор на `/login` — идемпотентно).
- **Событие во время/после активного логина.** `handleSessionLoss` перечитывает фактическое состояние (`auth.refresh()` → `loginState`); при живой сессии (`loggedIn:true`) — `return` без перехода (`auth-gate.ts:36-38`; тест `:91-101`). Позднее событие после успешного ре-логина не выкидывает свежую сессию (отчёт риск #3).
- **Окна.** `broadcastToRenderer` (`renderer-bridge.ts:30-34`) идёт по `BrowserWindow.getAllWindows()`; `sendToRenderer` проверяет `isDestroyed()` → destroyed skip, пусто → no-op (тест `renderer-bridge.test.ts:58-76`). Потеря событий при всех закрытых окнах самовосстанавливается: следующий запуск читает `loginState` → не залогинен (гвард `bootstrapped`). Модальное login-окно (без preload) получает пустой `send` — безвредно.
- **`bootstrapped` гварда не конфликтует:** `handleSessionLoss` дёргает `auth.refresh()` напрямую, а не кэшированный промис; push('/login') идёт через тот же guard → `entryRoute('login', false)` → allow. Bootstrap-барьер остаётся one-shot, решение читается из живого стора.

## Совместимость с одобренным поведением — не сломана

- **Sign out:** `logout()` (`auth.ts:430-431`) — `invalidateSession()`+`clearTokens()` без broadcast; Settings по-прежнему `push('/login')` (`login-navigation-after-auth`) — двойного перехода нет.
- **single-flight:** broadcast добавляется в уже единственный терминальный путь; число сетевых refresh не меняется, `terminateSession` на пачку один раз.
- **consent/регистрация/startup auto-register:** не затронуты (нет изменений в relay-client; событие — общий auth-канал).
- **IPC-контракт:** `IPC.AUTH_SESSION_LOST` в `ipc-contract.ts:50`; preload `auth.onSessionLost` (`preload/index.ts:42-47`) регистрирует `ipcRenderer.on` и возвращает `removeListener`-unsub; renderer подписывается один раз в `installAuthGuard` (`auth-gate.ts:29-31`) — утечки слушателей нет (один подписчик на процесс). Существующий `auth-gate.test.ts` мок дополнен `onSessionLost` (`:31,46,55`) — иначе `installAuthGuard` упал бы.

## Тесты — по делу, без «зелёного из-за моков»

- `session-loss-navigation.test.ts`: реальный `createRouter`+`installAuthGuard`+Pinia+стор, мок только IPC-моста; потеря из чата → `/login` **и** `state.loggedIn=false` (`:61-73`); повтор идемпотентен (`:75-89`); событие при живой сессии — остаётся в чате (`:91-101`). На старом коде (нет подписки) `emitSessionLost` ничего не вызовет, ассерт `/login` упал бы — тест дискриминирует.
- `auth-refresh.test.ts` — broadcast-побочки на терминальный/транзиентный/logout.
- `renderer-bridge.test.ts` — «всем живым, destroyed skip, пусто no-op».

## Наблюдения (не блокируют)

1. **(minor, вне перечисленного scope) Смена issuer в Settings** (`index.ts:211-213`) тоже терминально чистит токены, но broadcast не шлёт (design D-3: только непроизвольная потеря; смена issuer — интерактивна). При этом стор остаётся `loggedIn:true` — пользователь на `/settings` может войти заново, но при переходе в `/chat` увидит «Not signed in». Стоит решить отдельно (refresh стора/переход на `/login` при смене issuer) — не входит в заявленные три непроизвольных пути.
2. **(observation) `SafeStorageUnavailableError`** не терминален (токены не чистятся) → события нет; стор остаётся `loggedIn:true`. Отличается от «нечитаемое хранилище» (эта ветка шлёт событие); поведение унаследовано из `silent-refresh-single-flight`, для данного change'а корректно.
3. **(observation) `handleSessionLoss` без try/catch**, но `auth.refresh()` → `AUTH_LOGIN_STATE`, который в main ловит внутренние ошибки и не реджектит (`index.ts:305-315`) — unhandled rejection на этом пути не возникает.
4. **(observation) unsub от `onSessionLost` не сохраняется** — подписчик один (startup), утечки нет.

## Итог

Непроизвольная терминальная потеря сессии (грант-отказ, нет refresh-токена, нечитаемое хранилище) доводится до renderer событием из единственной точки `terminateSession`, стор обновляется и UI уходит на `/login` из любого view; повторные события идемпотентны, транзиентные исходы и интерактивный Sign out событие не порождают. Гонки (лавина 401, событие после ре-логина, отсутствие окон, кэш `bootstrapped`) разобраны корректно; одобренные пути не сломаны; IPC-контракт чистый; тесты ловят регресс по существу. Остаточное — смена issuer в Settings (интерактивна, вне scope) как точка на будущее. Новых блокеров нет.

## ВЕРДИКТ: `approve`

---

# Дополнение: смена issuer → событие (2026-09-25)

Проверка заявленного фикса: main шлёт `auth:session-lost` и при смене issuer; неключевые настройки событие не порождают; 2 регрессионных теста.

## Что подтверждено по коду

- **Событие только на issuer.** `index.ts:206-215`: ветка `if (baseUrlChanged || issuerChanged)`; внутри неё `if (issuerChanged) { clearTokens(); invalidateSession(); broadcastToRenderer(IPC.AUTH_SESSION_LOST); }`. Неключевые настройки (theme, `confirmCommands`, `showTray`, `logLevel`, лимиты) не меняют `serverBaseUrl`/`keycloakIssuer` → в блок не входят → ни очистки, ни события. BaseUrl-без-issuer: токены не чистятся (issuer тот же) → события нет — корректно.
- **Нет пути «токены почищены, событие не ушло».** Все `clearTokens()` в main: `terminateSession` (`auth.ts:315-318`, broadcast), issuer-change (`index.ts:212` + `:214` broadcast), `logout()` (`auth.ts:430-431`, интерактивный — событие не нужно by design D-3). Прочих нет.
- **Событие на смене issuer безопасно для non-keycloak настроек.** Условие строго по `issuerChanged`; renderer-обработчик идемпотентен (route-guard) — повторного выкидывания нет.
- **Артефакты синхронизированы.** Delta-спека `desktop-shell` дополнена клаузой про issuer и сценариями «смена issuer инвалидирует сессию» / «неключевая настройка не инвалидировала» (`spec.md:11,33-41`); design D-4 (`design.md:37-43`).

## Находки

### F1 (MAJOR, тест-покрытие фикса). Новая строка `index.ts:214` не покрыта ни одним тестом, а заявленные «регрессионные» тесты её не сторожат
- `session-loss-navigation.test.ts` — это **renderer**-интеграция (jsdom, `window.harness` замокан); main-код не исполняется. Событие `auth:session-lost` в тесте «routes to /login when the issuer is changed in settings» (`:120-134`) эмитит **сам мок** `config.set` (`:37-44`), а не `onServerConfigChanged`. Значит, тест пройдёт и при удалённой строке `index.ts:214` — он проверяет реакцию renderer'а на событие, а не то, что main его шлёт при смене issuer. Т.е. единственная новая production-строка фикса не защищена регрессией.
- Main-теста на `onServerConfigChanged` нет (функция не экспортирована, `index.ts:201`); отчёт §9 это признаёт («main-тестов нет»), но тогда заявление «2 регрессионных теста» над фиксом неверно.
- **Требование:** сделать классификацию «изменение конфига инвалидирует сессию» тестируемой — вынести чистую функцию (напр. `sessionInvalidatedByConfig(prev, next): boolean` или `changedKeycloakEndpoints(prev, next)`) в отдельный модуль и покрыть юнит-тестами (issuer → true; `baseUrl`-only/theme/`confirmCommands`/`showTray` → false), а `onServerConfigChanged` использовать её; **либо** добавить main-тест с замоканным electron, который дергает обработчик смены конфига и ассертит `broadcastToRenderer('auth:session-lost')` на issuer и его отсутствие на неключевой настройке.

### F2 (MINOR). Тест «неключевая настройка» не вызывает `config.set` — проверка тривиальна
- `session-loss-navigation.test.ts:136-147`: `setValue(false)` на чекбоксе `showTray`, затем ассерты без клика по Save. `SettingsView.save()` вызывается только кнопкой `[data-testid="save"]` (`SettingsView.vue:132-138`), вотчеров на `cfg` нет → `window.harness.config.set` не вызывается. Поэтому ассерты «остаёмся на `/settings`, `loggedIn:true`» выполняются независимо от того, шлёт ли main событие на неключевых настройках; тест зелёный и на до-фиксовом коде. В отчёте §9 он описан как «save showTray=false» — фактически save нет.
- **Требование:** после `setValue` кликнуть `[data-testid="save"]`, чтобы прошёл реальный путь `SettingsView → config.set` (мок уже различает issuer/не-issuer, `:39`), и ассертить `config.set` вызван + остаёмся на `/settings`/`loggedIn:true`.

### Наблюдение
- Селектор `inputs[1]` (issuer) в тесте issuer-смены (`:127-128`) индексный и хрупкий — при перестановке полей тест сломается не по существу; лучше `data-testid`/`label`.

## Итог дополнения

Идемпотентность и корректность классификации в main подтверждены чтением кода: событие уходит только на `issuerChanged`, неключевые настройки и baseUrl-only его не порождают, путей «clear без события» (кроме интерактивного logout) нет; спека/design синхронизированы. Однако заявленные регрессионные тесты **не сторожат** новую строку `index.ts:214` (F1, major: renderer-мок эмитит событие сам; main-теста нет), а тест «неключевая настройка» фактически не вызывает `config.set` (F2, minor). Требуется сделать классификацию тестируемой и починить F2.

## ВЕРДИКТ: `reject`

---

# Дополнение 2: классификатор вынесен в `shared` (2026-09-25)

Проверка заявленного: чистая `sessionInvalidatingChange`, используемая main и renderer-моком; матричный тест; тест неключевой настройки сохраняет её.

## F1 (major) — ЗАКРЫТО

- **Чистая функция:** `web-desktop/src/shared/config-invalidation.ts` — `sessionInvalidatingChange(prev, next)` = `keycloakIssuer` ИЛИ `keycloakClientId` изменились. `onServerConfigChanged` использует её (`index.ts:205,209`), решение вынесено из-под `issuerChanged`; broadcast идёт по `if (sessionInvalidated)` (`:209-215`), а не по `issuerChanged`.
- **Матричный тест с двусторонней дискриминацией:** `config-invalidation.test.ts` — true для issuer/clientId (`:8-11`), false для baseUrl/theme/`confirmCommands`/`showTray`/`sessionListLimit`/`windowHeight` и идентичного конфига (`:13-24`). Константа `true` падает на false-кейсах, константа `false` — на true-кейсах. ✓
- **Renderer-мок завязан на ту же функцию:** `session-loss-navigation.test.ts:43` — `if (sessionInvalidatingChange(DEFAULT_CONFIG, next))` → эмулирует main; благодаря этому renderer-тесты консистентны с классификатором.
- Это ровно тот вариант, который я требовал («вынести чистую функцию и покрыть юнит-тестами»); main-строка wired к единственной протестированной точке принятия решения.

## F2 (minor) — ЗАКРЫТО

- `session-loss-navigation.test.ts:140-154`: `showTray` переключается и **клик по `[data-testid="save"]`** (`:147`); ассерты: `configSetCalls === 1` (`:150`), кнопка Save на месте, маршрут `/settings`, `loggedIn: true` (`:152-153`). Реальный путь `SettingsView.save → config.set` пройден; issuer-тест (`:124-138`) и неключевой теперь действительно различаются.

## Классификатор — семантика корректна

`keycloakClientId` вшит и в авторизационный запрос (`auth.ts` `authUrl`), и в обмен кода, и в refresh-грант (`client_id` в теле `exchangeCode`/`refreshTokens`), и в logout — refresh-токен привязан к клиенту Keycloak, смена id инвалидирует сохранённую сессию. Поэтому issuer+clientId → true корректно. `serverBaseUrl` (API-хост, не Keycloak), theme/лимиты/окна/`confirmCommands`/`showTray`/`logLevel` сессию не инвалидируют → false. Соответствует коду конфигурации и использованию `keycloakClientId`.

## Слои/циклы — чисто

`shared/config-invalidation.ts` импортирует только `./ipc-contract.js` (тот же shared-слой, типы); main (`index.ts`) и renderer-тесты импортируют его — циклов нет, `shared` — существующий общий слой (там же `ServerConfig`), нарушений слоёв нет. `config-invalidation.test.ts` — чистый (без DOM/Vue), типизируется node-tsconfig.

## Замечания (не блокирующие)

1. **(minor, латентное изменение поведения) `sse?.unsubscribe()`/`taskSse?.unsubscribe()`/`config:changed` переехали ИЗ-ПОД `if (baseUrlChanged || issuerChanged)` наружу** (`index.ts:216-242`): теперь они выполняются на любой смене настроек (theme/`confirmCommands`/`showTray`), а `config:changed` шлётся всегда. Сейчас регресс недостижим: единственный вызов `CONFIG_SET` — из `SettingsView.save()`, а при переходе на `/settings` ChatView размонтируется и `useChat.onUnmounted` уже отписывает SSE; `config:changed` в renderer никто не слушает. Но семантически «drop SSE» относился к смене endpoint'а — стоит вернуть под `if (sessionInvalidated || baseUrlChanged || issuerChanged)` (или оставить только `baseUrlChanged`), иначе при появлении always-on SSE/мультиокна это сломает ленту.
2. **(minor, док) Delta-спека и design D-4 говорят про «Keycloak-параметры (issuer)»**, тогда как классификатор покрывает и `clientId`. Формулировку стоит расширить до «issuer/clientId», чтобы артефакт отражал код.
3. **(minor, тест) Селектор `inputs[1]`** (`session-loss-navigation.test.ts:132`) индексный — хрупок к перестановке полей; лучше `data-testid`/`label`.

## Итог дополнения 2

F1 закрыт: семантика инвалидации вынесена в чистую `sessionInvalidatingChange`, покрытую матричным тестом с двусторонней дискриминацией, и main wired к ней; регресс классификатора («уходит при любой смене» / «не уходит при issuer») теперь ломает тест. F2 закрыт: тест неключевой настройки реально сохраняет и проверяет `configSetCalls`/маршрут/стор. Слой/циклы чисты; семантика (issuer+clientId) корректна. Остаточное — латентный перенос SSE-отписки из endpoint-гарда (недостижим сейчас) и формулировка спеки — minor.

## ВЕРДИКТ: `approve`

---

# Дополнение 3: декларативный план смены endpoint (2026-09-25)

Проверка: `planEndpointChange(prev, next)` → `onServerConfigChanged` исполняет план; побочные эффекты снова под условием; матрица дискриминирует.

## Что подтверждено

- **(1) Побочные эффекты восстановлены под условием.** `index.ts:202-244`: `const plan = planEndpointChange(config, next)`; `invalidateSession` → `clearTokens`+`invalidateSession`+`broadcastToRenderer(AUTH_SESSION_LOST)` (`:208-214`); `sseResubscribe` → `log` + `sse?.unsubscribe()`/`taskSse?.unsubscribe()` (`:215-220`); `clientsRebuild` → пересоздание `sse`/`taskSse`/`relay` (`:221-240`); `notifyConfigChanged` → `config:changed` (`:241-243`). Неключевая настройка (theme/`confirmCommands`/`showTray`/лимиты) даёт план со всеми `false` (`config-invalidation.ts:55-70`) → ни отписки SSE, ни `config:changed`, ни очистки — прежний регресс устранён. Сверка с HEAD по-кейсово: baseUrl-only / issuer-only / baseUrl+issuer — прежние эффекты сохранены (+broadcast на issuer), non-keycloak — снова пусто. Ничего не потеряно.
- **(2) Семантика плана корректна.** `sessionInvalidated = issuerChanged || clientIdChanged` (`:31`) — оба вшиты в грант/refresh (см. дополнение 2). `endpointChanged = baseUrl || sessionInvalidated` → `sseResubscribe` и `notifyConfigChanged` (`:32,35,37`): при смене issuer/clientId API-URL не меняется, поэтому клиенты не пересоздаются — их токен-колбэк (`async () => refreshIfNeeded(config, …)`) захватывает модульный `config` по ссылке и читает новую конфигурацию; пересоздание (`clientsRebuild`) действительно нужно только при смене `serverBaseUrl`, который клиенты захватывают в конструкторе (`:36`, комментарий `:21-22`). Логика не переусердствует и не недоделает.
- **(3) Матрица честная.** `config-invalidation.test.ts:27-80`: baseUrl → `{F,T,T,T}`, issuer → `{T,T,F,T}`, clientId → `{T,T,F,T}`, theme/showTray → `{F,F,F,F}`, baseUrl+issuer → `invalidate && rebuild` (частично). Проверки через `toEqual` фиксируют полный план, а не одно поле. Дискриминация: константа-«всё true» падает на non-keycloak кейсах, константа-«всё false» — на baseUrl/issuer/clientId; снятие любого гарда ломает соответствующий кейс. Ошибочной комбинации не закреплено.
- **(5) Одобренное ранее не сломано.** Sign out (`logout()` без broadcast, Settings push `/login`) — не затронут; single-flight/`terminateSession` broadcast — без изменений; consent/регистрация — не трогались; релей: `clientsRebuild` только на baseUrl (как в HEAD), на issuer/clientId деградирует сам (design non-goal) — как раньше.

## Замечания (не блокирующие)

1. **(minor, артефакты) `tasks.md` не синхронизирован с D-5.** `openspec/changes/session-loss-ui-state/tasks.md` заканчивается секцией 7 (классификатор `sessionInvalidatingChange`); пункта про `planEndpointChange`/D-5 нет (`grep planEndpointChange|sseResubscribe|clientsRebuild` по tasks — пусто). Design D-5 (`design.md:47-53`) есть, но tasks отстал на итерацию. Требование: добавить пункт (вынесение плана + матрица + `onServerConfigChanged` исполняет план).
2. **(minor, отчёт) `docs/temp/session-loss-ui-state-glm.md` (§10 — про F1/F2) не описывает D-5** — раздел про план отсутствует; стоит дописать §11.
3. **(minor, док) Delta-спека (`specs/desktop-shell/spec.md:11`) говорит «Keycloak-параметры (issuer)»,** тогда как классификатор покрывает и `clientId`; формулировку стоит расширить (не откачена, но неполна).
4. **(observation) `sessionInvalidatingChange` в проде больше не используется** — `onServerConfigChanged` идёт через `planEndpointChange`; функция осталась только для renderer-мока. Чтобы не дублировать логику инвалидации в двух местах, `planEndpointChange` мог бы звать её.

## Итог дополнения 3

Остаток из дополнения 2 закрыт: SSE-отписка и `config:changed` снова под условием через декларативный план; неключевая настройка не даёт побочных эффектов; семантика плана корректна (rebuild только при baseUrl, invalidate при issuer/clientId, уведомление/отписка при endpoint-смене); матрица двусторонне дискриминирует; одобренные пути не задеты. Остаточное — рассинхрон `tasks.md`/отчёта с D-5 и формулировка спеки — minor.

## ВЕРДИКТ: `approve`
