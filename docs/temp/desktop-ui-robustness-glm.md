# Отчёт: desktop-ui-robustness (GLM-5.3-Flash, субагент-разработчик)

Дата: 2026-09-25. Change: `openspec/changes/desktop-ui-robustness/` (создан до кода, `openspec validate --strict` — valid). Базовая точка: 9976660 (relay-connect-consent-confirm).

## Баг A — disconnect не терминирует регистрацию (relay-client.ts)

**Суть**: `disconnect()` сбрасывал сокет/регистрацию, но не трогал `registering` (обещание in-flight), `consentResolvers` (ожидающие согласования) и `openConsent`. Повторный `register` той же сессии возвращал старое зависшее обещание (дедуп), consent-пере-опрос отдавал запрос, на который уже некому отвечать — «подключено, но ничего не происходит» до `relayRegisterTimeoutMs`.

**Решение**:
- `sendRegister` получает единый `finish(outcome)` (clearTimer + removeListener + сброс `registering/registeringSession` + resolve) и сохраняет его в новом поле `registerAbort`.
- `disconnect()` вызывает `abortRegistering()` → in-flight `register` разрешается `{ok:false, code:'disconnected', message:'relay disconnected'}` немедленно; тип consent-резолвера расширен до `(boolean | 'disconnected')`; все ожидающие согласования разрешаются исходом `'disconnected'`, `pendingConsent`/`openConsent` очищаются.
- `register()` при consent-исходе `'disconnected'` возвращает `{ok:false, code:'disconnected', ...}` без эмита статуса и без вранья «User declined» (статус disconnected уже эмитит сам `disconnect()`).
- Терминальные исходы (registered/error/timeout) теперь тоже идут через `finish` — единая точка очистки.

**Тесты** (`relay-client.test.ts`, +2):
- «terminates an in-flight registration when disconnect arrives during consent»: disconnect во время ожидания согласования → немедленный исход `disconnected`, `getPendingConsent()===null`, повторная регистрация той же сессии проходит полный путь (hello#2 → welcome → consent → registered).
- «terminates an in-flight register frame on disconnect instead of timing out»: после согласования, до ответа сервера (сервер с `autoAckRegister:false`, таймаут поднят до 5с) → исход `disconnected`, а не «registration timeout».

## Баг B — ошибка каталога агентов изображала «агентов нет» (useSessions.ts)

**Суть**: `agents.list().catch(() => null)` — 401/рестарт бэкенда/сеть превращались в пустой список агентов без какого-либо сообщения; в живом прогоне это увело диагностику.

**Решение**:
- `useSessions`: отдельная `loadAgents()` c `agentsError` (успех/пусто/ошибка различаются; список агентов при сбое не перезаписывается); вызов из `refresh()` (→ и при монтировании) — кнопка «↻» перезагружает каталог после восстановления бэкенда.
- `SessionList.vue`: новый prop `agentsError`, вывод существующим стилем `.error` (`data-testid="agents-error"`) рядом с ошибкой списка — нового визуального языка нет.
- `ChatView.vue`: прокидывает `agentsError` в `SessionList`.

**Тесты**:
- `use-sessions.test.ts` (новый, хост-компонент над композаблом): сбой → `agentsError` с текстом; реально пустой каталог → `agentsError===null` (ветки различны); после сбоя `refresh()` восстанавливает каталог и снимает ошибку.
- `components/session-list.test.ts` (новый): параграф ошибки виден/отсутствует, класс `error`.

**Аудит соседних composables**: `useChat`/`useTask`/`useSessionTree` — ошибки загрузки кладут в свои `error`-ref (показываются в UI), глотаются только легитимные вещи: фолбэки конфига (`useSessions:96`, `useSessionTree:77`), malformed SSE-фреймы (`useTask:137`), JSON-fallback (`ChatFeed:59`, `RelayDialogs:10`), no-op навигации (`ChatView:83`). Единственное проблемное проглатывание в домене — каталог агентов; исправлено оно.

## Баг C — два крайних случая из отчёта §7

**C1 (залипание prompt-состояния при падении IPC) — воспроизводим, исправлен.**
`useRelay.respondConsent/respondToolConfirm`: раньше `consent.value = null` выполнялся ДО `await` IPC — при reject диалог исчезал, main-резолвер висел. Теперь: захват текущего запроса → `await` доставки → очистка только при успехе и только если состояние не заменено новым запросом. При падении invoke исключение уходит в обработчик клика, диалог остаётся, ответ можно повторить.
Тесты (`use-relay.test.ts`, +2): mockRejectedValue → prompt остаётся → повторный успешный вызов очищает (для обоих диалогов).

**C2 (мусор в %TEMP%) — воспроизводим, исправлен, с найденной дополнительной причиной.**
- electron-smoke: `--user-data-dir` и save-target удаляются в `finally` (`rmSync`).
- stub-server: `StubHandle.close()` удаляет workspace-каталог.
- **Найденная причина утечки**: `ensureWorkspace()` вызывался на module-level — Playwright грузит файл дважды (сбор тестов + воркер), каждая загрузка создавала каталог-«призрак», который никто не закрывал (дельта +1 каталог за прогон даже после добавления `rmSync` в `close()`). Создание перенесено внутрь `startStub()` → за прогон создаётся и удаляется ровно один каталог. Проверено замером: `harness-e2e-*` дельта = 0 за прогон electron-smoke и за два прогона stub.

## Команды и результаты

- `pnpm verify`: **зелёный** — lint, typecheck node+web, Vitest **29 файлов / 222 теста passed** (было 213: +2 relay-client, +3 use-sessions, +2 session-list, +2 use-relay), build ok.
- `pnpm e2e:electron`: **2 passed** (~5 c).
- `pnpm e2e:stub`: **7 passed** (~7.4 c).
- `openspec validate desktop-ui-robustness --strict`: **valid**.
- Гигиена: дельта `harness-e2e-*` в `%TEMP%` = 0 на прогон (замеры выше).
- Backend (Java/Maven/docker/compose/README/deployment-readme) не трогался.

## Открытые риски

1. Повторный клик по диалогу после падения IPC пробрасывает исключение в обработчик клика (unhandled rejection в консоли renderer) — принято сознательно: падение invoke возможно только на разрушенном канале; молча глотать против духа бага B.
2. `disconnect()` во время согласования решает ожидание исходом `disconnected` без эмита отдельного статуса — UI уже получает `disconnected`-статус от самого `disconnect()`; если владелец захочет различать «пользователь отключился во время согласования» — отдельный код статуса (эволюция).
3. Каталог агентов не перезагружается автоматически по таймеру — восстановление через «↻»/переоткрытие view (вне задачи, см. Non-goals change'а).

---

## 8. Фикс-раунд (решение судьи по кросс-чеку: F1 + два дешёвых)

### F1 (major) — at-most-once на prompt согласия; main не буферизует ответы без открытого prompt

**Суть**: esolveRegistrationConsent при отсутствии резолвера запоминал ответ в pendingConsent; equestRegistrationConsent «съедал» его при следующей регистрации той же сессии — тихий обход per-session consent (D-93). В renderer корректность держалась на timing'е: двойной клик по кнопкам диалога до завершения доставки давал второй IPC-вызов с тем же sessionId.

**Анализ легитимности race**: окно между показом prompt и постановкой резолвера в main отсутствует — consentResolvers.set выполняется синхронно до emit('registrationConsent'); renderer не может ответить на несуществующий диалог (ре-фетч pendingConsent до emit возвращает null). Значит буфер не имел легитимного потребителя — **механизм удалён** (явное состояние не потребовалось).

**Решение**:
- main (elay-client.ts): буфер pendingConsent удалён; esolveRegistrationConsent без резолвера — идемпотентный no-op; equestRegistrationConsent без early-ветки. Инвариант: следующая регистрация той же сессии всегда запрашивает диалог.
- renderer (useRelay.ts): флаги занятости consentInFlight/	oolConfirmInFlight — повторный вызов во время доставки no-op («ровно один ответ»); инвариант симметрично проверен для espondToolConfirm.
- UI (RelayDialogs.vue): локальный delivering-статус — обе кнопки обоих диалогов :disabled на время доставки; клики идут через nswerConsent/nswerToolConfirm.
- Дополнительно из того же семейства: (а) disconnect() разрешает confirmWaiters отказом — emit('toolResult', callId, 'command rejected by user', -1) добавлен в отказную ветку handleToolCall (исход отказа теперь виден renderer'у); (б) renderer при статусе disconnected|fatal закрывает prompt-диалоги и снимает флаги занятости; (в) mSync в electron-smoke с maxRetries: 5 (флейк EBUSY/EPERM на Windows).

**Тесты** (+6: +3 main, +3 renderer):
- elay-client.test.ts «ignores an answer with no open consent and asks again on the next registration» — ответы без открытого prompt (approve и deny) игнорируются, следующая регистрация показывает диалог, register-фрейм до ответа не уходит.
- elay-client.test.ts «delivers exactly one answer per consent prompt» — двойной ответ → ровно один доставлен, регистрация завершается по первому.
- elay-client.test.ts «resolves pending command confirmations with a rejection on disconnect» — disconnect при ожидании подтверждения → терминальный отказ без запуска процесса.
- use-relay.test.ts «answers a consent/tool-confirm prompt at most once while delivery is in flight» (×2) — in-flight повторный вызов no-op, ровно один IPC.
- use-relay.test.ts «closes pending prompts when the relay disconnects».
- components/relay-dialogs.test.ts «disables the consent buttons while an answer is being delivered».

**Правки существующих тестов**: main-тесты, отвечавшие на согласование сразу после welcome (раньше спасал буфер), теперь ждут getPendingConsent() !== null — то же ожидание, что у реального renderer'а; прежний тест «consumes an early consent decision» инвертирован в «ignores an answer with no open consent» (ранний ответ больше не легитимен по D-93).

### Команды и результаты (фикс-раунд)

- pnpm verify: **зелёный** — Vitest **29 файлов / 228 тестов passed** (+6 к 222), build ok.
- pnpm e2e:electron: **2 passed** (~5 c).
- pnpm e2e:stub: **7 passed** (~7.5 c).
- openspec validate desktop-ui-robustness --strict: **valid**.
- Гигиена: дельта harness-e2e-* в %TEMP% = 0 за прогон.

### Осталось открытым

- Ничего по F1 и двум дешёвым пунктам. Прежние известные крайние случаи (unhandled rejection при повторном клике после падения IPC; отсутствие отдельного статус-кода «отключился во время согласования»; каталог агентов без таймер-релоада) — без изменений, вне scope.
