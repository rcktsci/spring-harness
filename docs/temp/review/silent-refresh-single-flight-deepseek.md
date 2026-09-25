# Ревью change `silent-refresh-single-flight` (незакоммиченное рабочее дерево)

Рецензент: субагент-ревьюер (DeepSeek-V4.1-Flash). Дата: 2026-09-25.
Вход: `openspec/changes/silent-refresh-single-flight/` (proposal/design/tasks + delta desktop-shell), рабочее дерево, отчёт `docs/temp/silent-refresh-single-flight-glm.md`.
Линза: корректность конкурентности single-flight, взаимодействие с logout/сбросом токенов, классификация исходов, диагностика, тесты. Сборка/тесты/docker не запускались; проверял по коду.

## Что подтверждено

- **Single-flight работает и не имеет гонки check→start.** `refreshIfNeeded` (`web-desktop/src/main/auth.ts:265-299`) до `if (!refreshInFlight)` (`:293`) не содержит `await` (loadTokens/`Date.now` синхронны), поэтому check-and-set атомарен в одном такте: N параллельных потребителей создают ровно одно `doRefresh` (`:301-316`), все получают один результат. `.finally` (`:294-296`) сбрасывает `refreshInFlight` и на успехе, и на ошибке — залипания нет; `doRefresh` не реджектит (ловит всё), висящего обещания не остаётся.
- **Двойных `saveTokens`/`clearTokens` на пачку нет:** единственный `doRefresh` вызывает `saveTokens` один раз (`:304`) или `clearTokens` один раз на grant-ошибке (`:310`).
- **Смесь транзиентной и терминальной ошибок в пачке невозможна** by design — одна сетевая попытка, один исход для всех ожидающих; следующая пачка стартует заново (in-flight сброшен).
- **Классификация (кроме 429, см. F2):** 5xx/сеть/не-JSON 2xx → `RefreshTransientError`, токены сохраняются (`:119-121`, `:127`, `:129-134`); 200 без `access_token` → `parseTokenResponse` кидает (`:71-73`), ловится как транзиентное; токены не чистятся.
- **Утечки токенов в лог нет:** логируется тело **ответа** Keycloak (`:125`, `:133`), тело **запроса** (с `refresh_token`) не логируется нигде; `requireAccessToken` отдаёт renderer'у обезличенное «Not signed in» (`:339`), refresh-ошибка с телом остаётся в main-логе. Токены в текст ошибки не попадают.
- **Тесты** (`web-desktop/tests/unit/auth-refresh.test.ts`) ловят именно регресс: 5 параллельных → 1 fetch/1 save (`:52-65`); 4xx → 1 clear, все отклонены, тело в error-логе (`:67-83`); сеть → 1 fetch/без clear (`:85-101`); 5xx → без clear (`:103-113`); новая пачка после неудачи (`:115-130`); свежий токен → 0 fetch (`:132-143`). Проверяют наблюдаемое (fetch/save/clear/лог), не внутренности.

## Находки

### F1 (MAJOR). In-flight refresh «воскрешает» сессию после logout/смены issuer — нет инвалидации по поколению
`logout()` (`auth.ts:348-356`) делает `clearTokens()` (`:351`), но **не сбрасывает/не инвалидирует** `refreshInFlight` (`:250`). Если `doRefresh` был в полёте, по завершении он безусловно вызывает `saveTokens(refreshed)` (`:304`) — файл токенов пересоздаётся **после** очистки. `readLoginState` (`:364-372`) тогда снова вернёт `loggedIn=true` — явный Sign out отменён. То же для смены issuer: `main/index.ts:209-211` вызывает `clearTokens()` без сброса `refreshInFlight`. Окно гонки = длительность сетевого refresh (refresh триггерится на 401/истечении фоновыми потребителями: SSE-reconnect, relay auto-register, список сессий).

**Требование:** ввести «поколение сессии» (`sessionEpoch`/generation в `token-store`/`auth`), инкрементировать его при `clearTokens`/logout/issuer-change; `doRefresh` снимает epoch до `await` и вызывает `saveTokens` только если epoch не изменился (иначе — не сохранять, вернуть null). Дополнительно `logout()`/issuer-change должны обнулять `refreshInFlight`. Регрессионный тест: запустить refresh с незавершённым fetch → `logout()` → разрешить fetch 200 → `saveTokens` не вызван, `loadTokens()`/`readLoginState` = logged-out.

### F2 (MINOR). Огульная классификация 4xx делает 429 (rate limit) терминальным → неожиданный logout
`refreshTokens` (`auth.ts:124`) считает терминальным **любой** 4xx: `if (res.status >= 400 && res.status < 500) RefreshGrantError`. 429 Too Many Requests — транзиентный троттлинг, а не отказ гранта; при нём пользователь будет выкинут на `/login` — ровно тот класс, который change устраняет для 5xx/сети. 408 Request Timeout — аналогично.

**Требование:** относить 429 (и, при желании, 408) к `RefreshTransientError`; терминальными оставить 400/401 (grant/token rejected). Синхронно поправить формулировку delta-спеки (`desktop-shell/spec.md:11,35,36`: «4xx» → «4xx, кроме rate-limit/timeout») и D-2 (`design.md:26-28`). Тест: 429 → `clearTokens` не вызван.

### F3 (MINOR). Аномальный 2xx без `access_token` не кладёт тело ответа в текст ошибки
Спека (`desktop-shell/spec.md:11`) и D-3 (`design.md:34`) обещают тело ответа в тексте любой refresh-ошибки, но `parseTokenResponse` кидает `new Error('token response missing access_token')` (`auth.ts:71-73`) без тела; `doRefresh` логирует это как транзиентное без тела. Приводит к слепой диагностике именно аномального 2xx.

**Требование:** завернуть это в `RefreshTransientError` с телом (`refresh endpoint 200: missing access_token: ${text}`) либо сузить формулировку спеки до «тело ответа при не-2xx».

### F4 (MINOR). Тестовая гигиена: `vi.stubGlobal('fetch', …)` без `unstub`
В `auth-refresh.test.ts` нет `afterEach(() => vi.unstubAllGlobals())`; разработчик зафиксировал однократный флейк при комбинированном прогоне с `auth-session` (`silent-refresh-single-flight-glm.md:53`). При дефолтной изоляции Vitest per-file это не обязательно блокер, но правка дешёвая и снимает флейк.

**Требование (желательно):** `afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); })`. Флейк сам по себе починки не требует (не воспроизводится на полном прогоне), но правку стоит включить в тот же раунд.

## Итог

Основной инвариант single-flight реализован корректно (атомарный check→start, единый исход, сброс в `finally`, без двойных clear/save), классификация 5xx/сети и диагностика с телом ответа — по существу, утечки токенов в лог нет, тесты ловят регресс. Однако есть незакрытый конкурентный дефект жизненного цикла in-flight обещания: **после logout/смены issuer успешный in-flight refresh пересоздаёт токены (F1, major)** — явный выход отменяется; плюс 429 ошибочно трактуется как терминальный (F2) и пара minor-несоответствий (F3/F4). Это не позволяет считать требования выполненными на 100%.

## ВЕРДИКТ: `reject`
