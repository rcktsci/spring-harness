# Ревью M5 batch F (final)

**Вердикт:** APPROVE — блокеров нет, накопленные замечания documentation/test-minor.

---

## 1. openspec/specs — соответствие финальным артефактам

- **APPROVE**. Спеки в `openspec/specs/` (`desktop-shell`, `desktop-chat`, `desktop-relay-client`, `desktop-session-tree`, `desktop-artifacts`) идентичны архивным, актуальны и отражают реализованный код.
- **Находка (documentation/minor):** `apply-notes.md` строки 25 и 71 утверждают, что среди 5 новых спек в `openspec/specs/` есть `web-desktop-client`. Фактически такого каталога нет ни в `openspec/specs/`, ни в архиве `openspec/changes/archive/2026-09-24-m5-web-desktop/specs/`. Правильный набор 5 спек: `desktop-shell`, `desktop-chat`, `desktop-relay-client`, `desktop-session-tree`, `desktop-artifacts` (см. `AGENTS.md` строка 63 — верно). **Действие:** поправить `apply-notes.md` (архивный документ), убрать `web-desktop-client` из списка 5 спек, отдельно упомянуть `docs/design/web-desktop-client.md` как supersede-документ.

---

## 2. doc-sync vs openapi.yaml / код

- **APPROVE**. `api-contracts.md` §2 синхронизирован:
  - `MessageKind` включает `ASYNC_ACCEPTED` ✅
  - `SessionDto` не содержит `taskId`/`stateCode`/`parentSessionId` (только через `GET …/tree`) ✅
  - `TreeNode` содержит `stateCode?` ✅
  - `PARKED_CLIENT` задокументирован как зарезервированное значение enum ✅
- `decisions.md` D-86…D-93 присутствуют; D-88 сформулирована как **owner-risk-apprув** с mitigations (`confirmCommands=always`, server canonical-guard D-72, perimeter SSO) и отдельной строкой аппрува. ✅
- `roadmap.md` — M5 DONE. ✅
- `architecture.md` §1a — Web Desktop как первый потребитель контрактов. ✅
- `operations.md` §6 — сборка/дистрибуция desktop. ✅
- `agent-tools.md` — источник 5 (desktop-client) добавлен. ✅
- `web-desktop-client.md` — содержит supersede-note, корректен. ✅

- **Находка (documentation/minor):** `AGENTS.md` строка 91 содержит dead link `[docs/design/client-cli.md]` — файл физически отсутствует (переименован/superseded). **Действие:** заменить ссылку на `docs/design/web-desktop-client.md` или убрать markdown-link.

---

## 3. stub-server — не маскирует ли реальный протокол?

stub-server.ts — test fixture (не прод). Замечания ниже не ломают happy-path e2e, но создают delta с `api-contracts.md` §5, что важно для точности тестового покрытия.

| # | Что в спеке §5 | Что в stub | Severity | Действие |
|---|---|---|---|---|
| S-1 | Close **4403** для frame-before-hello / protocol-mismatch | Не реализован. Bad frame → close **4400** (нестандартный код); `hello` принимается без проверки `protocol` версии. | test/minor | Добавить проверку `protocol: 1` и 4403 close для mismatch / frame до hello. |
| S-2 | Heartbeat: JSON-фрейм `ping {}` от сервера | Используется **native WS ping** (`socket.ping()`). Клиентский ping-watchdog, ожидающий JSON `ping`, не тестируется. | test/minor | Заменить `socket.ping()` на `send(JSON.stringify({ type: 'ping' }))` с интервалом; обработать `pong` JSON. |
| S-3 | SSE `Last-Event-ID` заголовок приоритетен над `?since=` | Stub обрабатывает только `?since=` query param; `Last-Event-ID` игнорируется. | test/minor | Добавить чтение `req.headers['last-event-id']` и приоритет над `since=`. |
| S-4 | `TreeNode` содержит `stateCode?` (STATE-узлы) | `/tree` возвращает `{ id, parentSessionId, kind, agent, runtimeStatus, lastSeq, lastActivityAt }` — `stateCode` отсутствует. | test/minor | Добавить `stateCode` для STATE-сессий в ответе `/tree` (хотя бы `null` для FREE). |

**Замечание:** `tasks.md` 6.1 декларирует "полноценно реализующий §5 + §3.1/§3.2". С учётом S-1…S-3 формулировка "полноценно" — натяжка, но для stub fixture это не блокер.

---

## 4. env-bypass — не активируется в проде

- **APPROVE**. `config.ts:readE2eEnvOverrides()` возвращает `null` если `HARNESS_E2E_ENABLED` не установлен (строка 33). ✅
- **APPROVE**. `token-store.ts:loadTokens()` использует bypass только при `HARNESS_E2E_ENABLED === '1' && HARNESS_E2E_TOKEN` (строка 48). ✅
- Переменные `HARNESS_E2E_*` прокидываются исключительно из `electron-smoke.spec.ts` / Playwright-process; в обычном запуске Electron-приложения их нет.

---

## 5. AGENTS.md — факты верны?

- Основной текст M5 (строки 62–64) — факты верны.
- Раздел «Эволюция после M5» (строки 65–78) — синхронизирован с `roadmap.md`. ✅
- **Находка (documentation/minor):** dead link `client-cli.md` (см. п.2 выше).

---

## 6. e2e код — соответствие tasks 6.1–7.3

- `stub-server.spec.ts` — покрывает scripted SSE flow, workspace GET (D-72), WS handshake/register/tool.result echo, 4409 superseded, 401 bad token. Зелёный (дев-прогон). ✅
- `electron-smoke.spec.ts` — содержит chat + bash tool-call + artifact save-as. Но заявленный в `tasks.md` 6.1 / `apply-notes.md` шаг **«spawn sub-session → дерево»** в коде не представлен (нет assert на tree, нет шага spawn). Это покрыто unit-тестами (batch E), но e2e-сценарий не является полным в точном смысле `tasks.md`.
  - **Severity:** test/minor (unit-покрытие дерева есть).
  - **Действие:** либо добавить шаги в `electron-smoke.spec.ts` (spawn via stub scenario + assert tree render), либо скорректировать `tasks.md`/`apply-notes.md` — убрать «spawn sub-session → дерево» из декларации e2e full-сценария.

- `electron-smoke.spec.ts` строка 118: `const result =     await app.evaluate(...)` — лишние пробелы. cosmetic.

---

## Резюме

| # | Файл:строка | Severity | Действие |
|---|---|---|---|
| 1 | `apply-notes.md`:25,71 | documentation/minor | Убрать `web-desktop-client` из списка 5 спек; упомянуть отдельно как supersede-документ. |
| 2 | `AGENTS.md`:91 | documentation/minor | Исправить dead link `client-cli.md` → `web-desktop-client.md`. |
| 3 | `stub-server.ts`:529–533 | test/minor | Реализовать 4403 close для protocol-mismatch / frame до hello. |
| 4 | `stub-server.ts`:514–522 | test/minor | JSON-фрейм `ping {}` вместо native `socket.ping()`. |
| 5 | `stub-server.ts`:378–382 | test/minor | Обработать `Last-Event-ID` заголовок приоритетнее `?since=`. |
| 6 | `stub-server.ts`:324–338 | test/minor | Добавить `stateCode?` в TreeNode ответе `/tree`. |
| 7 | `electron-smoke.spec.ts` (отсутствуют шаги spawn/tree) | test/minor | Синхронизировать `tasks.md`/apply-notes с реальным скоупом e2e или дополнить spec. |
| 8 | `electron-smoke.spec.ts`:118 | cosmetic | Убрать лишние пробелы. |

Ни одна находка не нарушает работоспособность или безопасность. Фиксы — documentation/test-minor, могут быть сделаны постфактум (clean-up) или оставлены как известное ограничение stub fixture.
