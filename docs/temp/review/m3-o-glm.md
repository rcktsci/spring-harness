# Ревью пачки O (spawn + subagent + read_compacted + subtree cancel) — GLM-5.3-Flash

Дата: 2026-09-19. Объект: execution/impl/{SubagentSpawner, SubtreeCanceller, ReadCompactedTool}, TurnManagerImpl.requestStop (каскад), SessionStoreImpl.createChildSession/findSubtree, Session.depth-проекция, конфиги harness.spawn.*/harness.compact.read-max-bytes, AgentTurnEngine (manifest-гейты). Сборки не запускались.

**Вердикт: APPROVE** — незакрытых: 0; 1 nit вне критического пути (spawn-timeout оставляет субагента работать — задокументировать поведение).

---

## (1) SubagentSpawner (O.1) — ✅
Строго синхронный: родительский виртуальный поток ждёт (`awaitCompletion` — поллинг, отклонение dev №2; критерий D-10: `lastTurnOutcome != null И lastSeq <= lastConsumedSeq`). Depth-гейт **до** создания (`parent.depth + 1 > max-depth` → `forbidden (depth-limit)`); workspace-strategy — enforce: не-`inherit` → `forbidden (workspace-strategy)` (D-66: M3 только inherit); owner/depth — в `createChildSession` (`owner = parent.getOwnerUserId()`, `depth = parent.depth + 1`, kind=FREE — заодно закрывает nit n-4 плана); ревизия агента — latest по key. Manifest-гейт: `spawn_subagent` добавляется в tool-declarations только при `permissions_jsonb.metaTools = true` (AgentTurnEngine:110–112), явный вызов без флага → `forbidden (no-metaTools)` (строка 386); `read_compacted` — всем (O.4). Промпт субагента — USER-событие с params (параметры приложены текстом — MVP-профиль, ок).

## (2) Финал субагента → TOOL_RESULT (O.2) — ✅
Маппинг исходов: `COMPLETED` + непустой output → OK; `CANCELLED` → `cancelled «субагент отменён (subtree-cancelled)»`; `FAILED` → ERROR с output; таймаут ожидания → `spawn-timeout` ERROR (см. nit). Результат — на ходу родителя, где вызван spawn (по построению: блокирующий вызов внутри исполнения tool-call, write-ahead TOOL_CALL уже в журнале). Если родительский Turn умер до возврата (отмена/рестарт) — результат идёт обычным журнальным порядком, парковые/отменённые сессии поднимает wake/POLL (Javadoc + J-фикс) — соответствует re-approval MJ-1.

## (3) SubtreeCanceller (O.3) — ✅
`TurnManager.requestStop` → `cancelSubtree`: BFS по `findSubtree(sessionId, null)` (recursive CTE, всё поддерево включая середину дерева); каждой сессии `cancel_requested = true` (атомарный UPDATE); активные Turn'ы прерываются немедленно (`ActiveTurnRegistry` → `cancellation.cancel()` — их CANCELLED-кадры пишет движок); незакрытые pending TOOL_CALL сессий **без** живого Turn'а — синтетический `TOOL_RESULT CANCELLED «subtree-cancelled»` под `sess-{id}`-локом с проверкой «первый финальный выигрывает» (D-64). No-wake — отклонение dev №3, обоснование корректно: синтетика увеличивает `last_seq` → M1 `PollWakeJob` подберёт сессию (POLL-страховка D-03/D-33); идемпотентность — повторный stop: незакрытых нет, активных нет → no-op.

## (4) ReadCompactedTool (O.4) — ✅
Same-session only: ULID резолвится глобально (`findMessageRef`), но `ref.sessionId != sessionId` → `not-found (no-such-message)` — cross-session чтение невозможно (MJ-2-фикс плана выдержан); указывать можно и на COMPACT, и на покрытое сообщение (берётся последний покрывающий компакт); оригиналы — по covers (D-44), COMPACT не модифицируется; лимит `harness.compact.read-max-bytes` (16KB в yml) с маркером `[truncated]`; доступ — всем агентам в собственной сессии (спека).

## (5) D-61 / D-69 — ✅
D-61: depth — колонка `session.depth` (миграция 017, пачка N), проекция в `Session`, инкремент при spawn. D-69: non-inheritance — по построению: дочерняя сессия создаётся под ревизию `agentKey` субагента, и именно её `permissions_jsonb.metaTools` определяет доступность orchestrator-tools (никакого копирования флага нет); тест 5.1 (sub-coder без orchestrator-tools) — в пачке.

## (6) Отклонения dev — ✅ три, все обоснованы
Пакет `execution/impl` вместо `agent/` — ArchUnit-нейтрально (execution.impl внутренний, правил не трогает; финальное размещение — на усмотрение M-пачки); поллинг вместо `CompletableFuture.get` — детерминированный таймаут + отсутствие вложенного block-join на виртуальных потоках; no-wake на cancel — M1 POLL-страховка (см. (3)).

## (7) ArchUnit — ✅
Всё новое — в `execution.impl` (internal), Cross-module вызовы — через `SessionStore` (session), без чужих `.impl`; слой-граф не менялся (плановое расширение под `agent` — пачка S/6.1).

---

## Nit (не блокирует)

- **n-1**: `spawn-timeout` (30 мин дефолт) — субагент **продолжает работать** после возврата ERROR родителю: повторный spawn родителем породит дубль-агента; «осиротевший» субагент добирает свой ход и его output никем не читается. Спека этот исход не описывала. Предложение: задокументировать в apply-notes O (поведение: «субагент продолжает независимо; очистка — stop поддерева») — либо отменять child по spawn-timeout (вызов SubtreeCanceller по child-сессии) в пачке-доработке.

## Позитив

- Каскад отмены разложен по правилам: флаг → прерывание живых → синтетика мёртвых, всё под локами с D-64-семантикой; no-wake обоснован POLL-страховкой, а не «забыли».
- spawn-timeout и depth-limit возвращают **ошибки инструмента**, а не исключения — модель получает управляемый отказ (D-38-стиль).
- Гейты манифеста и гейты исполнения разделены (скрыт из declarations + forbidden на прямом вызове) — двойная защита как у transition.

## Вердикт

**APPROVE** — незакрытых: 0 (1 nit: задокументировать/обработать spawn-timeout-поведение субагента). Пачка закрывает subagent-lifecycle; готова к P (orchestrator-metaTools) / Q (MCP).
