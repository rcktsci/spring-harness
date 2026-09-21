# M4 propose — судейский разбор (оркестратор)

Ревьюеры: DeepSeek (19 находок, REJECT), Mercury (approve), GLM (rate-limit до 2026-09-24 — исключён из цикла).

## Судейские решения

### J-1 (H-1, M-6, M-7, приёмка): модель маршрутизации — по сессии, не по задаче

**Проблема**: D-79 (root FREE = CLIENT / task = SERVER) противоречит каноническому CLIENT_EXEC как типу workspace состояния задачи (D-11/D-12, api-contracts §5 `register {taskId, binding}`).

**Решение**: принять модель владельца как канон и явно суспендить старую:
- Единица маршрутизации — **сессия**. `register { sessionId, basePath, client: {...} }` ( sessionId, не taskId) — регистрация на root FREE-сессии.
- Оверлей виден: самой root-сессии + её sub-сессиям (через parent-цепочку до root). Сессии задач (`create_task` → отдельный `session_id`) не имеют connection в цепочке → SERVER **автоматически**, без отдельного правила.
- **D-84 (supersede D-12)**: CLIENT_EXEC как тип workspace состояния задачи больше не используется релеем — задачи исполняются только серверно. Значение `CLIENT_EXEC` в graph-schema остаётся зарезервированным (не валидируется как используемое). Register по `(taskId, binding)` удаляется из §5.
- `PARKED_CLIENT` **выводится из M4** (см. J-5).

### J-2 (H-2): cancel_requested — R-1 сохраняется

MODIFIED «Отмена Turn'а» НЕ откатывает R-1: флаг персистентный, tryStart no-op, сброс только явным resume. В дельте оставить M3-текст, добавив только клиентский `tool.cancel`.

### J-3 (H-3): гейт на уровне резолвера, не только манифеста

- В CLIENT-toolset нативные файловые (read_file/write_file/edit_file/bash/glob/grep) **не резолвятся** (не только из манифеста) — `AgentTurnEngine` в CLIENT не вызывает NativeAgentTools для них.
- Порядок резолва: (1) серверные колбэки **кроме нативных файловых в CLIENT** (metaTools, MCP доступны); (2) клиентский оверлей; (3) `tool-not-available`.
- async-классификация — только по серверным колбэкам (клиентский bash не уходит в AsyncToolExecutor).
- Stale-манифест внутри Turn'а — принят и задокументирован (манифест per-Turn; резолв — live).

### J-4 (M-1, M-3, M-4, N-6): wire-контракт и реализуемость

- В pom добавляется `spring-boot-starter-websocket` (Impact исправлен).
- Исходящие кадры — через общий мьютекс/очередь на соединение (`ConcurrentWebSocketSessionDecorator`) — в задачи пачки T.
- Идемпотентность: completion-map на соединение (`callId → CompletableFuture`), tombstone до конца соединения; **журнал пишет только Turn-поток** под `sess`-локом (WS-поток только complete'ит future). Синтетический timeout/LOST — тот же механизм (first-final-wins на future).
- Heartbeat: сервер инициатор `ping`, клиент `pong`; разрыв по 2×interval.
- `tool` в `tool.call` — free-form имя декларации (не enum).

### J-5 (H-4, M-5): PARKED_CLIENT и grace — упрощение

- `PARKED_CLIENT` **не реализуется в M4** (остаётся зарезервированным значением enum). Владелец: «всегда ERROR tool-not-available» — авто-парковки нет, сессия не ждёт.
- Grace-период задачи и ERROR-переход по таймауту **выводятся из M4**: отключение клиента → in-flight вызовы → синтетический LOST; оверлей очищается; дальнейшие вызовы клиентских инструментов → `tool-not-available`. Продолжение работы — переподключением (роуминг: register по sessionId новым соединением, takeover).
- `session-api` delta **убирается** из change (изменений требований нет).

### J-6 (M-2): реестр — CAS по identity + takeover

- `register` на sessionId: если соединение живо и это **то же соединение** — idempotent success; **другое соединение того же principal** → takeover (старое закрывается 4409 `superseded`, новое регистрируется); другой principal → 4409 `workspace-occupied`.
- `unregister` — CAS по connection-identity (`remove(key, expectedConnection)`), чтобы протухший сокет не снёс новое соединение.

### J-7 (M-8): canonical-гвард — статика вместо стрима, NOFOLLOW

- Размер файла проверяется **до** стрима (`Files.size`) → честный 413 до отправки заголовков. Усечение потока убирается из контракта.
- Обход: посегментная проверка symlink'ов (`Files.isSymbolicLink` на каждый компонент) + canonical-резолв + containment в корне; открытие с `LinkOption.NOFOLLOW_LINKS` на финальный компонент. Остаточный TOCTOU — принят и зафиксирован в рисках (потребитель — аутентифицированный SSO-пользователь, threat-model D-72).

### J-8 (M-7): download — серверный workspace, отдельно от роуминга

- `GET /sessions/{id}/workspace/files` отдаёт **серверный** каталог сессии (`workspaces/sessions/{sessionId}`). Для CLIENT-сессий он может быть пуст (файлы у клиента) — задокументированное ограничение; основное назначение — SERVER-сессии и будущий Web Desktop.
- Приёмка роуминга: переподключение и продолжение работы **без** скачивания (клиент хранит свои файлы сам). Download покрывается независимыми тестами на SERVER-сессии.

### J-9 (M-9): ArchUnit — relay → execution, SPI-интерфейс

- `ToolCallback`/`ToolResult` SPI остаются в `execution`; `relay` реализует адаптер (relay → execution разрешено). `execution ↛ relay` — обращение только через интерфейс, Spring-связывание. Модуля `workspace` не существует — убрать из плана; используется `common`/`session`.

### J-10 (M-10, N-3, N-4, N-5): доки и каталог

- §5 api-contracts переписывается в составе change (wire-контракт: error-фрейм, welcome, close-коды, free-form tool name, heartbeat-направление) — task 1.3.
- Синк `agent-tools.md` (направление «клиентский оверлей»), `client-cli.md` (CLI отменён — пометка supersede), `architecture.md` (слой relay), `execution-model.md` (источники: native/metaTools/MCP/client).
- Каталог ошибок §6: `file-not-found`, `path-invalid`, `extension-not-allowed`, `superseded`, `duplicate-tool-name`, `tool-not-available` (WS error-фрейм).
- N-5: mojibake; расширения с точкой, case-insensitive.

## Вердикт

REJECT → фиксы применяются (J-1…J-10). Изменения сессионные: rewrite proposal/design/3 specs + tasks; session-api delta убирается. После — re-approve DS + Mercury (GLM недоступен до 09-24).
