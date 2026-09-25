# Ре-аппрув: `desktop-ui-robustness` (закрытие F1 + двух неблокеров)

Рецензент: субагент-ревьюер (DeepSeek-V4.1-Flash). Дата: 2026-09-25. Базовая точка: `9976660`.
Вход: текущее рабочее дерево, `openspec/changes/desktop-ui-robustness/`, отчёт `docs/temp/desktop-ui-robustness-glm.md`.
Мой прежний вердикт: reject (F1 major — нет at-most-once на prompt согласия) + два неблокера. Судья: F1 обязателен, неблокеры в итерацию.
Сборка/тесты/docker не запускались; проверял по коду.

## F1: at-most-once на prompt — ЗАКРЫТО с обеих сторон

**Renderer (гарантия синхронная, не на тайминге DOM):**
- `web-desktop/src/renderer/src/composables/useRelay.ts:97-118`: `respondConsent`/`respondToolConfirm` проверяют `consentInFlight`/`toolConfirmInFlight` **синхронно** до любого `await`; повторный вызов во время доставки — no-op, флаг снимается в `finally`. Это закрывает гонку независимо от Vue-патча microtask.
- `web-desktop/src/renderer/src/components/RelayDialogs.vue:5-26,65-…`: `delivering`-ref + `answerConsent`/`answerToolConfirm` (`if (delivering.value !== null) return;` до await) + `:disabled` на всех четырёх кнопках. Второй слой защиты; синхронный guard срабатывает раньше, чем успевает примениться `disabled`.
- Тесты: `tests/unit/use-relay.test.ts:188-224` (два синхронных вызова → ровно 1 IPC, оба диалога), `tests/unit/components/relay-dialogs.test.ts:99-118` (кнопки disabled во время доставки).

**Main (no-op-инвариант, буфер удалён):**
- `web-desktop/src/main/relay-client.ts:96-101` — поле `pendingConsent` удалено полностью (grep по файлу: остался только метод `getPendingConsent`).
- `:703-712` `requestRegistrationConsent` больше не потребляет раннее решение; `:724-734` `resolveRegistrationConsent` при отсутствии резолвера делает `return` (no-op) и **не** буферизует ответ. Значит, дубль/поздний ответ не может быть применён к следующей регистрации той же сессии — обход D-93 невозможен.
- Тесты: `tests/unit/relay-client.test.ts:357-375` («ignores an answer with no open consent and asks again on the next registration»: два ответа до регистрации — no-op, затем `register` эмитит consent, `register`-фрейм не отправлен, ответ завершает ok), `:377-391` («delivers exactly one answer per consent prompt»).

**Проверка «не сломан ли легитимный путь, который покрывал буфер»:** резолвер и `openConsent` ставятся **синхронно** в одном блоке до `emit('registrationConsent')` (`relay-client.ts:703-712`); renderer узнаёт о prompt только из этого emit или из `pendingConsent()` (возвращает тот же `openConsent`) — значит, ответ renderer'а всегда приходит после установки резолвера. Легитимного потребителя у буфера не было; удаление корректно. Существующие тесты приведены в соответствие (`await server.waitFor(() => client.getPendingConsent() !== null)` перед ответом) — это отражает реальный порядок, а не маскирует регресс.

## Разрыв во время подтверждения команды — ЗАКРЫТО

- `web-desktop/src/main/relay-client.ts:221-227` — `disconnect()` резолвит `confirmWaiters` значением `false` (отказ) и очищает карту.
- `:620-629` — при `!approved` эмитится `toolResult(callId,'command rejected by user',-1)` (терминальный результат для renderer) и отправляется `tool.result` (при закрытом сокете — no-op); процесс не запускается.
- Renderer: `useRelay.ts:51-59` — на статус `phase==='disconnected'|'fatal'` очищает `consent`/`toolConfirm` и сбрасывает флаги занятости; диалоги закрываются.
- Тест: `relay-client.test.ts:393-421` (confirm pending → disconnect → `toolResult='command rejected by user'`).
- Delta-спека: требование «Очистка регистрации при разрыве» дополнено ожиданием подтверждения (`specs/desktop-relay-client/spec.md:11`) + новый `Scenario: разрыв во время ожидания подтверждения команды` (`:23-26`).

## Два неблокера — ЗАКРЫТЫ

- `confirmWaiters`: см. выше (резолв отказом + очистка + терминальный `toolResult`).
- `rmSync` с `maxRetries:5`: `web-desktop/tests/e2e/electron-smoke.spec.ts:175,176,228`, `web-desktop/tests/e2e/stub-server.ts:811` — симметрично с workspace-каталогом.

## Delta-спека и design — не ослаблены, соответствуют коду

- `specs/desktop-relay-client/spec.md:11,23-26` — разрыв во время подтверждения; `:30` — at-most-once + no-op-инвариант («ответ без открытого запроса — идемпотентный no-op, не буферизуется; следующая регистрация всегда показывает диалог»); новые scenario «двойной ответ на один prompt» (`:42-45`) и «ответ без открытого запроса» (`:47-50`). Требования аддитивны, ранее утверждённые не переписаны и не ослаблены.
- `design.md:33-39` (D-3 переписан) и `:49-58` (фикс-раунд по решению судьи) — обоснование удаления буфера и at-most-once совпадает с кодом.
- Bug B (`desktop-chat`) и C2 не затронуты фиксом и остаются закрытыми (см. прошлые ревью); регрессий не вносилось.

## Тесты ловят регресс (а не детали реализации)

- `relay-client.test.ts`: no-op без prompt + повторный диалог; ровно один ответ на prompt; разрыв во время consent; разрыв после согласования до `registered` (при `autoAckRegister:false` и таймауте 5с → не «registration timeout»); разрыв во время confirm. Проверяют наблюдаемое поведение (исходы, эмиты, отсутствие `register`-фрейма).
- `use-relay.test.ts`: сохранение prompt при сбое IPC (обе ветки), at-most-once (обе ветки), закрытие prompt'ов на disconnect.
- `relay-dialogs.test.ts`: disabled во время доставки.
- Обновление существующих тестов (`waitFor(getPendingConsent()!==null)`) корректно отражает отсутствие буфера; flake-паттернов не вижу (короткие sleep только после детерминированного disconnect/микрозадачи).

## Остаточные наблюдения (не блокеры)

- `register()` проверяет `kind !== 'FREE'` до busy-проверок (`relay-client.ts:238-248`), а этот путь эмитит статус с `phase:'disconnected'` — при открытом FREE-consent он теперь закрыл бы prompt в renderer, оставив резолвер main висеть. Путь недостижим из UI: `ensureConnected`/`toggleRelay` пропускают STATE, `RELAY_SET_SESSION` возвращает `wrong-session-kind` до вызова `relay.register`, а прямой вызов `register(..., 'STATE')` никем не используется. Порядок проверок — унаследованный, вне scope; можно отметить на будущее.
- `emit('toolResult')` на обычный отказ пользователя (`:621`) никого в renderer не слушает (`tool.onResult` не подписан) — безвредно, но чуть расширяет эмиты.

## Итог

F1 закрыт по существу в обоих слоях: renderer гарантирует at-most-once синхронно (флаги + disabled), main больше не буферизует ответ без открытого prompt (no-op), поэтому обход consent через дубль или смену сессии невозможен, а следующая регистрация той же сессии всегда показывает диалог. Легитимный путь ответа сохранён (резолвер ставится синхронно до emit — буфер не имел потребителя). Отказ при disconnect доходит до renderer терминальным `toolResult` и закрывает диалоги; оба неблокера исправлены; delta-спека дополнена (at-most-once, no-op, разрыв во время confirm) и соответствует коду; тесты ловят регресс. Новых блокеров нет.

## ВЕРДИКТ: `approve`
