## 1. Подготовка

- [x] 1.1 Change создан до кода (proposal/design/tasks + delta-спеки desktop-relay-client, desktop-shell).

## 2. Дефект 1 — per-session изоляция каталогов

- [x] 2.1 `relay-client.ts`: `sessionBasePaths: Map<sessionId, basePath>`; запись при `registered`; `handleToolCall` берёт каталог только по `call.sessionId`; отказ «no registration for session …» при отсутствии записи; очистка в `disconnect()` (close с реконнектом не чистит).
- [x] 2.2 Тест (а): вызов для сессии без записи в соответствии отклоняется, процесс не запускается.
- [x] 2.3 Тест (б): последовательные регистрации A→B с разными basePath, команда каждой сессии исполняется в её каталоге (проверка через `cwd` в spawn).
- [x] 2.4 Тест (в): реконнект + перерегистрация с новым basePath не оставляет исполнение в старом каталоге.

## 3. Дефект 2 — таймаут запросов к Keycloak

- [x] 3.1 Конфиг `keycloakRequestTimeoutMs` (DEFAULT_CONFIG + тип); `refreshTokens`/`exchangeCode` — fetch с `AbortSignal.timeout`.
- [x] 3.2 Тест: зависший fetch → таймаут, исход временный (токены сохранены), причина в логе.

## 4. Дефект 3 — consent не съедает таймаут регистрации

- [x] 4.1 Тест-фиксация: произвольное ожидание ответа consent не приводит к таймауту регистрации.
- [x] 4.2 Срыв по таймауту → статус `register-timeout` с причиной; повтор регистрации возможен без перезапуска приложения; тест.

## 5. Верификация

- [x] 5.1 `pnpm verify` зелёный.
- [x] 5.2 `pnpm e2e:electron` и `pnpm e2e:stub` зелёные.
- [x] 5.3 `openspec validate relay-session-workspace-isolation --strict`.

## 6. Фикс-раунд (minor от ревьюеров, approve)

- [x] 6.1 `sessionBasePaths` очищается на терминальных закрытиях (4409 superseded, 4403 protocol-mismatch), как и на `disconnect()`; test-hook `trackedSessionCount`; тесты на оба close-кода + восстановление при новой регистрации.
- [x] 6.2 Отказ-тест усилен: маркер `EXECUTED-IN:<cwd>` доказывает, что процесс не запускался и чужой каталог не использовался.
- [x] 6.3 Формулировка таймаута KC точна: `timed out after <N> ms` (зависание) отличима от `unreachable` (недоступность) — в refreshTokens и exchangeCode; тест abort проверяет точный текст.
