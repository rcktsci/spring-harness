## 1. Подготовка

- [x] 1.1 Change создан до кода (proposal/design/tasks + delta-спека desktop-shell).

## 2. Задача A — single-flight

- [x] 2.1 `auth.ts`: модульное in-flight обещание для refresh; сброс при settle (успех/ошибка); все ожидающие получают один результат.
- [x] 2.2 Тест: N параллельных `requireAccessToken` на истёкшем токене → ровно один fetch на token endpoint, все получают новый access token, `saveTokens` один раз.
- [x] 2.3 Тест: отказ refresh (4xx) → все ожидающие получают отказ, `clearTokens` ровно один раз; повторная пачка после ошибки делает новый сетевой refresh.

## 3. Задача B — разделение исходов и диагностика

- [x] 3.1 `auth.ts`: классификация исходов — 4xx token endpoint (кроме retry-later 408/429) → терминальный (clear); сеть/5xx/408/429/аномальный 2xx → временный (токены сохраняются, вернуть null).
- [x] 3.2 Текст ошибки refresh-вызова включает тело ответа Keycloak; терминальный исход — в error-лог, временный — в warn.
- [x] 3.3 Тесты: сеть → clearTokens не вызывается; 5xx → clearTokens не вызывается; свежий токен → fetch не вызывается вовсе.

## 4. Верификация

- [x] 4.1 `pnpm verify` зелёный.
- [x] 4.2 `pnpm e2e:electron` и `pnpm e2e:stub` зелёные.
- [x] 4.3 `openspec validate silent-refresh-single-flight --strict`.

## 5. Фикс-раунд (решение судьи: F1 + F2–F4)

- [x] 5.1 F1: эпоха сессии — `invalidateSession()` из logout и смены issuer (index.ts), `terminateSession()` в терминальных очистках; `doRefresh` отбрасывает результат при смене эпохи (без save, ожидающие без access token).
- [x] 5.2 F1 тест: logout во время in-flight refresh → сессия завершена, токены не записаны, ожидающие получают отказ.
- [x] 5.3 F2: 408/429 → транзиентные; формулировки delta-спеки и D-2 уточнены; тест `it.each([408, 429])`.
- [x] 5.4 F3: тело ответа в тексте ошибки аномального 2xx; тест.
- [x] 5.5 F4: `vi.unstubAllGlobals()` в afterEach.
- [x] 5.6 Повторный прогон: `pnpm verify`, `pnpm e2e:electron`, `pnpm e2e:stub`, `openspec validate --strict`.

## 6. Hardening (санитизация тела ответа в логах)

- [x] 6.1 `auth.ts`: `sanitizeBody` — маскирование секретных полей (`token`/`secret`/`password`/`credential` → `[masked]`, имена сохраняются) + ограничение длины; применяется во всех текстах ошибок refresh-вызова.
- [x] 6.2 Тесты: секретные поля промаскированы и не попадают в лог; oversized-тело обрезается.
- [x] 6.3 Артефакты синхронизированы (Goals/D-3/delta-спека: 408/429, санитизация); подтверждено — все терминальные очистки идут через `terminateSession()`.
- [x] 6.4 Повторный прогон: `pnpm verify`, `pnpm e2e:electron`, `pnpm e2e:stub`, `openspec validate --strict`.

## 7. Hardening-остатки (minor от ревьюера)

- [x] 7.1 Маскирование поддерева под секретным ключом-контейнером целиком (любая глубина/тип); обычные поля видны.
- [x] 7.2 `exchangeCode` (`!res.ok`): тело через ту же `sanitizeBody` — сырое тело token endpoint не логируется нигде.
- [x] 7.3 Не-JSON тела: JWT-подобные подстроки (три base64url-сегмента 8+) заменяются на `[jwt-like]` до обрезки.
- [x] 7.4 Тесты: поддерево под секретным контейнером; JWT-подобные не попадают в лог.
- [x] 7.5 Артефакты синхронизированы (D-3, delta-спека); повторный прогон `pnpm verify`, `pnpm e2e:electron`, `pnpm e2e:stub`, `openspec validate --strict`.
