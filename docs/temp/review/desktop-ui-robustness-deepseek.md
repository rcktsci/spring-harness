# Ревью change `desktop-ui-robustness` (незакоммиченное рабочее дерево)

Рецензент: субагент-ревьюер (DeepSeek-V4.1-Flash). Дата: 2026-09-25. Базовая точка: `9976660`.
Вход: `openspec/changes/desktop-ui-robustness/` (proposal/design/tasks + delta-спеки), рабочее дерево, отчёт `docs/temp/desktop-ui-robustness-glm.md`.
Линза: корректность состояния и жизненный цикл (relay-client A; useSessions B; диалоги C1; e2e-гигиена C2). Сборка/тесты/docker не запускались; заявления проверял по коду.
Смежный change `ci-backend-image` в дереве не разбирается (вне задачи).

## Что подтверждено (A, B, C2)

**A — disconnect терминирует регистрацию (закрыто).**
- `relay-client.ts:350-355` — единая точка завершения: `finish(outcome)` (`clearTimeout` + `removeListener('frame', onFrame)` + `registerAbort=null` + `resolve`), сохранена в `registerAbort` (`:394`). Терминальные исходы (registered/error/timeout) идут через `finish` (`:345-348`, `:358-392`) — двойных `resolve` не осталось (после `finish` таймер снят, слушатель удалён, `registerAbort` обнулён).
- `disconnect()` (`:210-230`) резолвит ожидающие consent-промисы исходом `'disconnected'`, чистит `consentResolvers`/`pendingConsent`/`openConsent`, затем `abortRegistering()` (`:407-412`) резолвит in-flight `register` исходом `{ok:false, code:'disconnected'}`. `register()` при `allowed === 'disconnected'` возвращает ошибку разрыва без эмита статуса и без вранья «User declined» (`:293-295`); статус `disconnected` уже эмитит сам `disconnect()`. Повторная регистрация проходит полный путь (дедуп снят — `registering`/`registeringSession` обнулены).
- Тесты ловят регресс по существу: `relay-client.test.ts:215-235` (разрыв во время согласования: немедленный исход, `getPendingConsent()===null`, повторная регистрация до `registered`), `:237-254` (разрыв после согласования, до `registered`, при `autoAckRegister:false` и таймауте 5с → не «registration timeout»).

**B — каталог агентов (закрыто).** `useSessions.ts:37-48` — `loadAgents()` с отдельным `agentsError`: успех → `agents`; пусто → `agentsError=null`; сбой → `agentsError` без перезаписи `agents`; вызывается из `refresh()` (`:85-88`) → и из `onMounted` (`:107-116`). UI: `SessionList.vue:137-143` (`data-testid="agents-error"`, класс `error`), прокинуто из `ChatView.vue:175`. Тесты: `use-sessions.test.ts` (сбой vs пусто vs восстановление), `session-list.test.ts` (видимость/стиль). Семантика «пусто ≠ не загрузилось» сохранена.

**C2 — e2e уборка (закрыто).** `electron-smoke.spec.ts:126-127,175-177,227-228` — `--user-data-dir` и save-target удаляются в `finally`; `stub-server.ts:792` — `ensureWorkspace()` перенесён с module-level в `startStub()` (устранён «призрачный» каталог при двойной загрузке файла тест-раннером), workspace снимается в `StubHandle.close()` (`:811`). Module-level ФС-побочных эффектов нет (in-memory `newSession` — не утечка).

## Находка

### F1 (MAJOR, блокирует). Повторный ответ на consent-диалоге не запрещён → загрязнение `pendingConsent` → тихий обход согласия при следующей регистрации
- Прежний код (`9976660`) очищал состояние **синхронно до** `await` (`consent.value = null`), поэтому второй клик видел `null` и второй IPC не отправлялся. Фикс C1 убрал эту защиту, но не добавил замену: `useRelay.ts:87-98` теперь `await`-ит IPC и очищает состояние только после успеха, **без флага «ответ в полёте»**, а кнопки диалога — без `:disabled` (`RelayDialogs.vue:44-59`, `:77-92`). Второй клик (или approve+deny) во время in-flight доставки отправляет второй IPC c тем же `sessionId`.
- В main второй вызов `resolveRegistrationConsent` (`relay-client.ts:725-737`) не находит резолвер (`delete` при первом вызове) и **безусловно** кладёт ответ в `pendingConsent`: `:735`. Дальнейший `requestRegistrationConsent` для этой сессии (`:707-710`) потребляет «раннее решение» и возвращает его без показа диалога → следующая регистрация той же сессии проходит **без подтверждения пользователя** (`allowed===true` при approve-дубле, либо молчаливый отказ при deny-дубле). Это нарушает смысл D-93 (per-session consent) и сценарий delta `desktop-relay-client` «UI согласования первой регистрации».
- `disconnect()`-очистка `pendingConsent` (`:224`) этот путь не закрывает: загрязнение возникает при переключении сессий (`register` без `disconnect`), а ответ, пришедший уже после `disconnect()`, снова пишет `pendingConsent` (`:735`).
- Тестов нет: `use-relay.test.ts:161-188` проверяет только сбой и успех одного вызова; кейса «два быстрых вызова» нет. `resolveConfirmation` для tool-confirm идемпотентен (`:659-666` — без `pending`-карты), поэтому ущерб несёт именно consent.

**Требование к исправлению** (минимально достаточное):
1. renderer: гарантировать at-most-once на prompt — например, флаг/`Set` «ответ в полёте» по объекту запроса в `respondConsent`/`respondToolConfirm` и/или `:disabled` на кнопках, пока ответ доставляется; после успеха/сбоя флаг снимается.
2. main: не буферизовать ответ, когда никакой prompt не открыт (`openConsent === null` и нет резолвера) — либо вести учёт уже отвеченных prompt'ов, чтобы дубль/поздний ответ не создавал `pendingConsent`. Это защищает и от ответа, доставленного после `disconnect()`.
3. Регрессионный тест: два вызова подряд → доставлен один ответ и последующий `register` той же сессии снова требует согласия (не авто-отвечает).

## Прочие наблюдения (не блокеры)

- `disconnect()` не чистит `confirmWaiters`/`cancelledCalls` (`:214-230`): открытый tool-confirm-диалог после разрыва остаётся, и approve может запустить локальное исполнение уже при закрытом сокете (результат не уйдёт). Вне scope A (регистрация), но стоит отдельной косметики.
- `electron-smoke.spec.ts:175` `rmSync(userData, {recursive:true, force:true})` без `maxRetries`, тогда как stub-server использует `maxRetries:5` (`:811`) — на Windows после `app.close()` возможен EBUSY/EPERM. Потенциальный флейк; добавить `maxRetries` для симметрии.
- e2e по-прежнему оставляет `~/harness-workspaces/<sessionId>` (main делает `mkdir` до согласия) — вне объявленного scope C2 (`%TEMP%`), зафиксировано как известное.

## Итог

A, B и C2 закрыты по существу и покрыты адекватными тестами. C1 закрыт лишь частично: устранено залипание при сбое IPC, но удалённая синхронная очистка не заменена защитой от второго ответа, что даёт гонку с загрязнением `pendingConsent` и тихим обходом согласия на следующей регистрации (F1, major). Требуется at-most-once на prompt + защита main от буферизации ответа при закрытом prompt + регрессионный тест.

## ВЕРДИКТ: `reject`
