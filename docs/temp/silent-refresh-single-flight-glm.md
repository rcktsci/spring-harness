# Отчёт: silent-refresh-single-flight (GLM-5.3-Flash, субагент-разработчик)

Дата: 2026-09-25. Change: `openspec/changes/silent-refresh-single-flight/` (создан до кода, `openspec validate --strict` — valid). Delta-спека: `desktop-shell` (MODIFIED «SSO-логин и хранение токена» — silent refresh описан именно там).

## Симптом (из живого прогона, main.log 2026-09-25)

`10:46:58.303` и `10:46:58.326` — два `silent refresh succeeded` за 23 мс: два параллельных `refreshIfNeeded` ушли с одним refresh-токеном. При ротации refresh-токенов (дефолт Keycloak «Revoke Refresh Token») повторное использование отозванного токена → `invalid_grant` → HTTP 400 → `silent refresh failed; clearing session` → пользователь на `/login`; следующий прогон — «no agents in catalog» (сессия стёрта). На старте приложения параллельных потребителей много: список сессий, каталог агентов, SSE, relay auto-register, артефакты.

## Задача A — single-flight

**Решение** (`auth.ts`):
- Модульная переменная `refreshInFlight: Promise<string | null> | null`. `refreshIfNeeded`: свежий токен → вернуть без сети; истёк → если in-flight нет, создать `doRefresh(cfg, refreshToken)` (единственный сетевой вызов); все параллельные вызовы ожидают одно и то же обещание → одинаковый результат для всех.
- `finally { refreshInFlight = null }` — сброс при любом settle (успех и ошибка), следующая пачка снова может обновиться.
- Очистка токенов при неудаче — ровно один раз (внутри единственного `doRefresh`), а не на каждого ожидающего.
- Семантика для вызывающих не изменилась: `refreshIfNeeded → string | null`, `requireAccessToken → string` (кидает «Not signed in…» при null).

**Тесты** (`tests/unit/auth-refresh.test.ts`, новый, 6 тестов; `token-store`/`logger`/`fetch` замоканы, `vi.resetModules()` сбрасывает модульное состояние in-flight):
- «runs exactly one network refresh for a batch of parallel consumers» — 5 параллельных `requireAccessToken` на истёкшем токене → **ровно один** fetch, все получают `'fresh'`, `saveTokens` один раз, `clearTokens` не вызван.
- «clears tokens exactly once when Keycloak rejects the grant» — 400 `invalid_grant` → все 5 получают отказ (`Not signed in`), `clearTokens` **ровно один раз**, в error-логе причина Keycloak (`invalid_grant` + `error_description`).
- «starts a fresh refresh for the next batch after a transient failure» — после транзиентной неудачи следующая пачка делает новый сетевой refresh (in-flight не залип).

## Задача B — разделение исходов и диагностика

**Решение** (`auth.ts`):
- Ошибки refresh-вызова классифицированы двумя типами: `RefreshGrantError` — HTTP **4xx** на token endpoint (грант недействителен/отозван) → терминально: `clearTokens()` один раз, `log.error` с телом ответа; `RefreshTransientError` — сетевые исключения, **5xx**, не-JSON тело 2xx — токены **сохраняются**, `log.warn`, вернуть `null`; следующий запрос инициирует новый refresh.
- Текст ошибки теперь содержит тело ответа Keycloak (`refresh endpoint 400: {"error":"invalid_grant",...}`) — читается одним `text()`; раньше был только код статуса.
- `parseTokenResponse` (аномальный 2xx без `access_token`) отнесён к временным ошибкам — не логинит пользователя заново.
- Поведение гейта не менялось: `isSessionUsable` (истёк + есть refresh-токен → сессия пригодна) работает как раньше; при транзиентной ошибке токены остаются, гейт пускает, вызовы получают 401 → повторный refresh. `SafeStorageUnavailableError` при чтении хранилища — null без очистки (как раньше); нечитаемое хранилище — clear (как раньше).

**Тесты**:
- «keeps tokens when the token endpoint is unreachable» — сеть → все null, `clearTokens`/`saveTokens` не вызывались, warn-лог с причиной (`ECONNREFUSED`).
- «keeps tokens on a 5xx from the token endpoint» — 503 → null, без очистки.
- «does not hit the token endpoint when the access token is fresh» — свежий токен → ноль fetch, ноль побочных эффектов.

## Границы решения (в design change'а, D-2)

- 4xx-классификация вместо парсинга тела (`invalid_grant` и пр.) — клиент не должен зависеть от формата ошибок Keycloak; класс статусов стабилен.
- Retry с backoff и proactive refresh по таймеру — non-goals (за владельцем, см. proposal).
- Серверный/Keycloak конфиг не меняется; ротация refresh-токенов остаётся включённой.

## Команды и результаты

- `pnpm verify`: **зелёный** — lint, typecheck node+web, Vitest **30 файлов / 234 теста passed** (+6 auth-refresh), build ok.
- `pnpm e2e:electron`: **2 passed** (~5 c).
- `pnpm e2e:stub`: **7 passed** (~7.3 c).
- `openspec validate silent-refresh-single-flight --strict`: **valid**.
- Backend (Java/Maven/docker/compose/README) не трогался; файлы параллельных сессий не затронуты.

## Что осталось открытым

1. Proactive refresh по таймеру (за N секунд до истечения) — не делался (non-goal); single-flight снимает острую часть, но при долгом простое первый запрос после паузы всё ещё будет ждать refresh.
2. Retry с backoff при транзиентных ошибках внутри `refreshIfNeeded` — не делался (non-goal); сейчас — одна попытка на запрос, повтор на следующем запросе.
3. Флейк наблюдались один раз при комбинированном прогоне нового тест-файла с соседним (`auth-session`) — при одиночном и полном прогонах стабильно зелёный; на изоляцию per-file не влияет, но если повторится — кандидат на явный `vi.unstubAllGlobals()` в `afterEach`.

---

## 8. Фикс-раунд (решение судьи: F1 обязательно + F2–F4)

### F1 (major) — logout/смена конфигурации во время in-flight refresh «воскрешал» сессию

**Суть**: doRefresh безусловно saveTokens(refreshed) — если во время сетевого вызова выполнен logout (или сменился issuer), токены дописывались обратно, eadLoginState снова показывал loggedIn, явный Sign out не работал.

**Решение**: эпоха сессии — модульный счётчик в uth.ts.
- invalidateSession() (экспорт) — инкремент; вызывается из logout() и из index.ts при смене issuer (вместе с clearTokens).
- 	erminateSession() — инкремент + очистка; используется во всех терминальных очистках внутри efreshIfNeeded/doRefresh (нет refresh-токена, отказ гранта, нечитаемое хранилище).
- doRefresh захватывает эпоху до сетевого вызова; при settle с изменившейся эпохой результат отбрасывается: токены не сохраняются, ожидающие получают «нет токена» (equireAccessToken → reject «Not signed in»), сессия остаётся завершённой. В catch-ветке при смене эпохи — выход без побочных эффектов (никакого повторного clear поверх logout).

**Тест**: «discards an in-flight refresh result when logout happens mid-flight» — 3 параллельных equireAccessToken на истёкшем токене, fetch зависает; посреди — logout(cfg); после release ответа: все ожидающие получают отказ, store.save не вызван ни разу (токен «zombie» не записан), store.tokens === null, eadLoginState(30) === { loggedIn: false }.

### F2 (minor) — 408/429 не должны логинить заново

**Суть**: любой 4xx считался грант-отказом → 429 (rate limit)/408 давали лишний logout.

**Решение**: грант-отказ — 4xx **кроме** 408/429; оба отнесены к транзиентным (токены сохраняются). Формулировки delta-спеки (MODIFIED-требование) и design D-2 синхронизированы: «4xx token endpoint, кроме retry-later кодов 408/429».

**Тест**: it.each([408, 429]) → все ожидающие null, clearTokens не вызван, saveTokens не вызван.

### F3 (minor) — тело ответа в ошибке аномального 2xx

**Суть**: 2xx без ccess_token кидал «token response missing access_token» без тела — design D-3 и delta обещали детали.

**Решение**: отказ parseTokenResponse в efreshTokens оборачивается в RefreshTransientError с сообщением парсинга + телом ответа.

**Тест**: «includes the response body when a 2xx reply has no access_token» — 2xx {"foo":"bar"} → null, без очистки/сохранения, warn-лог содержит и упоминание ccess_token, и тело {"foo":"bar"}.

### F4 (minor) — детерминированность auth-тестов

**Решение**: fterEach(() => { vi.unstubAllGlobals(); }) в uth-refresh.test.ts — снимает глобальный fetch-стаб после каждого теста (отмеченный мной однократный флейк комбинированного прогона).

### Команды и результаты (фикс-раунд)

- pnpm verify: **зелёный** — Vitest **30 файлов / 238 тестов passed** (+4: logout-mid-flight, 408/429 each, 2xx-тело; часть в it.each), build ok.
- pnpm e2e:electron: **2 passed** (~5 c).
- pnpm e2e:stub: **7 passed** (~7.4 c).
- openspec validate silent-refresh-single-flight --strict: **valid** (delta и design синхронизированы с фактическим поведением).

### Осталось открытым

1. Proactive refresh и retry/backoff — прежние non-goals, не расширены.
2. AbortController для отмены уже отправленного POST при logout — не делался: результат и так отбрасывается по эпохе, отмена транспорта не добавляет корректности (зафиксировано в design как отвергнутая альтернатива).
3. Сетевой refresh при смене issuer остаётся напрасным запросом к старому host (результат отбрасывается) — приемлемо, event-driven отмена вне scope.

---

## 9. Hardening — санитизация тела token endpoint в логах

**Суть**: текст ошибки refresh-вызова включал сырое тело ответа; аномальный ответ теоретически может содержать токены (ccess_token/efresh_token/id_token) — проект запрещает логировать секреты.

**Решение** (uth.ts): функция sanitizeBody(text) применяется во всех четырёх местах формирования текста ошибки efreshTokens (грант-отказ 4xx, транзиентный HTTP, не-JSON 2xx, аномальный 2xx без ccess_token):
- JSON-тело: рекурсивное маскирование — значения полей, чьё имя содержит 	oken/secret/password/credential (без учета регистра, любая вложенность), заменяются на [masked]; **имена полей сохраняются** — по логу видно, какие поля пришли, но не их значения.
- Не-JSON тело — просто обрезка.
- Ограничение длины: константа BODY_LOG_LIMIT_CHARS (512) — применяется и к JSON-, и к не-JSON-телу.
- Поведение обновления не изменилось: маскирование касается только текста ошибки/лога.

**Проверка терминальных очисток**: все три ветки терминальной очистки (	oken store unreadable, «нет refresh-токена», грант-отказ) уже идут через 	erminateSession() (инкремент эпохи + clear) — сделано в фикс-раунде F1; поведение корректно, дополнительная правка кода не требовалась.

**Артефакты**: design Goals дополнены (408/429 — временные; санитизация; дискворд результата после смены сессии), D-3 переписан под санитизацию, delta-спека desktop-shell дополнена («в санитизированном виде: значения секретных полей… маскируются, имена сохраняются, длина тела ограничена»), tasks.md — секция 6.

**Тесты** (+2, всего 12 в uth-refresh.test.ts):
- «masks secret fields of the response body in error logs» — 2xx с efresh_token/id_token (без ccess_token) → в warn-логе "refresh_token":"[masked]" / "id_token":"[masked]", поля не секретные (scope) видны, значения SECRET-* в лог не попали.
- «truncates oversized response bodies in error logs» — 4xx с телом 1000 символов → error-лог короче исходного тела, хвоста (900 символов подряд) нет.

**Команды и результаты**: pnpm verify зелёный (**30 файлов / 240 тестов**, +2); pnpm e2e:electron 2 passed; pnpm e2e:stub 7 passed; openspec validate silent-refresh-single-flight --strict — valid.

**Открытым остаётся**: как и прежде — proactive refresh и retry/backoff (non-goals); лимит длины — техническая константа диагностики (не параметр поведения, в конфиг не выносился).

---

## 10. Hardening-остатки (minor от ревьюера, approve)

### 10.1 Маскирование поддерева под секретным ключом-контейнером

**Суть**: maskSecrets проверял секретность ключа только на скалярном значении — под секретным ключом-контейнером (	okens, credentials) маскировались лишь вложенные поля, чьи имена сами матчатся; остальные значения поддерева оставались видимыми.

**Решение**: проверка ключа перенесена в начало maskSecrets — под ключом с секретным паттерном возвращается [masked] для значения **целиком** (объект/массив/скаляр, любая глубина); несекретные ключи рекурсивно обходятся как прежде, их поля остаются в логе.

**Тест**: «masks the whole subtree under a secret container key» — тело с credentials (объект с user/plain) и 	okens (массив объектов с ccess_token) → в логе "credentials":"[masked]", "tokens":"[masked]", "scope":"openid" виден; "user"/"plain"/"note"/"access_token" в логе отсутствуют.

### 10.2 exchangeCode — сырое тело token endpoint

**Суть**: ветка !res.ok в exchangeCode печатала сырое тело (формальный незакрытый чек «сырое тело не логируется нигде»).

**Решение**: тело пропущено через ту же sanitizeBody(text).

**Покрытие**: поведение прикрыто применением той же функции, которая покрыта юнит-тестами (маскирование, JWT-подобные, обрезка) — отдельный интеграционный тест login-флоу не требовался.

### 10.3 JWT-подобные подстроки в не-JSON телах

**Суть**: не-JSON тело только обрезалось — JWT-подобная строка попала бы в лог целиком.

**Решение**: выбран вариант маскирования (не «не логировать тело» — диагностика сохраняется): regex из трёх base64url-сегментов по 8+ символов через точки (JWT_LIKE_PATTERN) заменяет подстроки на [jwt-like] до обрезки длины. Минимальная длина сегмента отсекает ложные срабатывания вида версий 1.2.3.

**Тест**: «does not log jwt-like strings from a non-JSON body» — 4xx с AccessToken: eyJ….eyJ….<sig> → лог содержит [jwt-like] и контекст AccessToken:, не содержит ни заголовок JWT, ни подпись.

### Команды и результаты

- pnpm verify: **зелёный** — Vitest **30 файлов / 242 теста passed** (+2), build ok.
- pnpm e2e:electron: **2 passed** (~5.1 c).
- pnpm e2e:stub: **7 passed** (~7.4 c).
- openspec validate silent-refresh-single-flight --strict: **valid** (D-3 и delta-спека синхронизированы: поддеревья, JWT-подобные, exchangeCode).

### Осталось открытым

Без изменений: proactive refresh и retry/backoff — non-goals; AbortController при logout — отвергнутая альтернатива (эпоха уже гарантирует корректность).
