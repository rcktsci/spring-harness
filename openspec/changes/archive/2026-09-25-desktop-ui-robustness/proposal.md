## Why

Три дефекта, найденных ревью-конвейером предыдущего change'а и живыми прогонами:

- **A (заторможенность релея)**: `RelayClient.disconnect()` не сбрасывает `registering`/`consentResolvers`/`openConsent` — если соединение/приложение уходит из регистрации in-flight, зависшие promise'ы и дедуп регистрируют «подключено, но ничего не происходит»: повторный `register` той же сессии возвращает старый зависший promise до истечения `relayRegisterTimeoutMs`, pending-consent пере-опрос рендерит запрос, на который уже некому отвечать.
- **B (блокер диагностики, живой стенд)**: `useSessions` глотает ошибку каталога агентов (`agents.list().catch(() => null)`) — любой сбой API (401, рестарт бэкенда, сеть) рисуется как «агентов нет». В живом прогоне пустой каталог из-за рестарта бэкенда был принят за отсутствие агентов, что увело диагностику в сторону.
- **C (два крайних случая из отчёта `relay-connect-consent-confirm` §7)**: (C1) `respondConsent`/`respondToolConfirm` очищают prompt-состояние **до** `await` IPC — при падении invoke диалог исчезает, а resolver в main остаётся висеть; (C2) e2e оставляет каталоги в `%TEMP%` (`harness-e2e-user-*`, `harness-e2e-save-*`, workspace стаба).

## What Changes

- **A**: `disconnect()` терминирует in-flight регистрацию: `register`-promise разрешается `{ok:false, message:'relay disconnected'}` (новый abort-хук в `sendRegister`), ожидающие consent-промисы разрешаются исходом `disconnected` (без вранья «User declined»), `pendingConsent`/`openConsent` очищаются. Повторная регистрация после disconnect проходит полный путь немедленно. Регрессионный тест: disconnect во время ожидания согласования.
- **B**: загрузка каталога агентов — отдельная функция с собственным `agentsError`: успех/пусто/ошибка различаются, ошибка показывается в списке сессий (существующий стиль `.error`), `refresh()` (кнопка «↻») перезагружает каталог. Покрыто тестами обе ветки + ветка восстановления.
- **C1**: порядок «await IPC → очистка состояния (если оно не заменено новым)»; при падении IPC диалог остаётся и ответ можно повторить. Тесты на залипание/очистку.
- **C2**: e2e чистит за собой: временные `--user-data-dir` и save-target в electron-smoke, workspace-каталог стаба в `StubHandle.close()`.

## Capabilities

### Modified Capabilities

- `desktop-chat` — «Список сессий»: каталог агентов различает «пусто» и «не загрузилось», ошибка видна, refresh перезагружает каталог.

### Added Capabilities-требования (в существующих спеках)

- `desktop-relay-client` — новые требования: «Очистка регистрации при разрыве» (A), «Устойчивость ответов диалогов» (C1).

## Impact

- **Код**: `web-desktop/src/main/relay-client.ts` (A), `web-desktop/src/renderer/src/composables/useSessions.ts` + `components/SessionList.vue` + `views/ChatView.vue` (B), `composables/useRelay.ts` (C1), `tests/e2e/{electron-smoke.spec.ts,stub-server.ts}` (C2).
- **Тесты**: `relay-client.test.ts` (+2), новый `session-list.test.ts` и `use-sessions.test.ts`, `use-relay.test.ts` (+2 ветки падения IPC).
- **Backend**: без изменений. Числовых параметров новых нет (таймауты уже в конфиге).

## Non-goals

- Ретраи каталога агентов по таймеру (кнопка «↻» и повторное открытие view покрывают восстановление).
- Переписывание UI-языка ошибок — переиспользуется существующий стиль списка.
- Чистка `%TEMP%` от каталогов прошлых (до-фиксовых) прогонов.
