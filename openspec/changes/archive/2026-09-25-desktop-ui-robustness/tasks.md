## 1. Подготовка

- [x] 1.1 Change создан до кода (proposal/design/tasks + delta-спеки desktop-relay-client, desktop-chat).

## 2. Баг A — disconnect терминирует регистрацию

- [x] 2.1 `relay-client.ts`: `registerAbort`-хук в `sendRegister`; `disconnect()` разрешает in-flight `register` исходом «relay disconnected», consent-промисы — исходом `disconnected`, чистит `pendingConsent`/`openConsent`.
- [x] 2.2 Тест: disconnect во время ожидания согласования → register завершается немедленно с явной ошибкой, повторная регистрация проходит полный путь.
- [x] 2.3 Тест: disconnect во время register-фрейма (после согласования) → исход «relay disconnected», а не «registration timeout».

## 3. Баг B — каталог агентов

- [x] 3.1 `useSessions.ts`: `loadAgents()` c `agentsError`; вызов при монтировании и в `refresh()`.
- [x] 3.2 `SessionList.vue`: показ `agentsError` существующим стилем; `ChatView.vue`: прокидывание.
- [x] 3.3 Тесты: сбой каталога → ошибка видна, «пусто» ≠ «не загрузилось»; `refresh()` восстанавливает каталог после сбоя.

## 4. Баг C — крайние случаи

- [x] 4.1 C1: `useRelay.respondConsent/respondToolConfirm` — очистка только после успешного IPC; тесты: падение IPC сохраняет prompt, успех очищает.
- [x] 4.2 C2: e2e чистит `--user-data-dir`, save-target, workspace стаба.

## 5. Верификация

- [x] 5.1 `pnpm verify` зелёный.
- [x] 5.2 `pnpm e2e:electron` и `pnpm e2e:stub` зелёные.

## 6. Фикс-раунд (решение судьи: F1 + два дешёвых)

- [x] 6.1 F1 (main): буфер `pendingConsent` удалён; ответ без открытого prompt — идемпотентный no-op; следующая регистрация той же сессии всегда показывает диалог (обход D-93 невозможен).
- [x] 6.2 F1 (renderer): at-most-once — флаги занятости в `useRelay.respondConsent/respondToolConfirm` + `disabled`-кнопки в `RelayDialogs` на время доставки; инвариант проверен и для tool-confirm.
- [x] 6.3 Регрессионный тест: два ответа на один prompt → ровно один ответ; следующая регистрация показывает диалог.
- [x] 6.4 `disconnect()` разрешает `confirmWaiters` отказом (без запуска процесса); renderer закрывает prompt-диалоги на disconnected/fatal; тест.
- [x] 6.5 `rmSync` в electron-smoke с `maxRetries`.
- [x] 6.6 Повторный прогон: `pnpm verify`, `pnpm e2e:electron`, `pnpm e2e:stub`, `openspec validate --strict`.
