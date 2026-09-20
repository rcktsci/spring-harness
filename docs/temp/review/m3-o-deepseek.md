# Ревью M3 batch O: spawn_subagent + read_compacted + subtree cancel

> Ревьюер: DeepSeek-V4.1-Flash (субагент).
> Дата: 2026-09-20.
> Объект: `execution/impl/{SubagentSpawner,ReadCompactedTool,SubtreeCanceller}`, `execution/AgentTurnEngine` (манифест/диспетчер/гейт), `execution/impl/TurnManagerImpl` (requestStop → каскад), `session/{Session,SessionEntity,SessionStore(+Impl),impl/StateSessionServiceImpl}`, `config/SpawnProperties`, `CompactProperties`, тесты O, `apply-notes.md` §O.
> Контекст: `execution-model.md §4/§6`, спека `subagent-lifecycle` (D-10/D-61/D-66/D-69), R8, D-44/D-67, M1-семантика stop.
> Сборки не запускались; сверка по исходникам.
> Severity: **HIGH**; **MEDIUM**; **MINOR/NIT**.

## Сводка

| Severity | Кол-во |
|---|---|
| HIGH | 1 |
| MEDIUM | 1 |
| MINOR/NIT | 4 |
| **Итого** | **6** |

---

## HIGH

### O-1. `SubagentSpawner.awaitCompletion` не проверяет `pending_tool_calls == 0`: запаркованный субагент считается завершённым досрочно
- **Пункт:** `execution/impl/SubagentSpawner.java:124-146` (условие `child.lastTurnOutcome() != null && child.lastSeq() <= child.lastConsumedSeq()`); спека `subagent-lifecycle` «Завершение субагента» (`(а) ход без tool-call … **И** pending_tool_calls == 0 (D-10)`).
- **Цитата:** «Завершение = «ход субагента закончен И pending_tool_calls==0» (D-10)» (spec/proposal); код: `if (child != null && child.lastTurnOutcome() != null && child.lastSeq() <= child.lastConsumedSeq()) return true;`.
- **Проблема:** субагент, вызвавший async-capable `bash`, превысивший окно, паркуется: `TurnManagerImpl` публикует `PARKED_ASYNC`, а `AgentTurnEngine` завершает Turn `COMPLETED` с `lastConsumedSeq = seq(ASYNC_ACCEPTED) = lastSeq` (N-пачка). Условие спавнера истинно → `awaitCompletion` возвращает `true` **до** позднего результата; `outcome=COMPLETED`, `findLastAssistantText` может быть пустым → родитель получает `spawn-failed: субагент завершился без ответа` либо преждевременный текст; финальный ответ субагента (из Turn'а, поднятого поздним результатом) родителю уже не доставляется. Для M3 (async bash — часть объёма) это ломает семантику `spawn_subagent`.
- **Предложение:** в `awaitCompletion` добавить условие «нет незакрытых вызовов»: `sessionStore.findPendingToolCalls(child).isEmpty()` **и** сессия не в `PARKED_ASYNC` (`runtimeStatus` не хранится в `Session` — либо добавить, либо проверять отсутствие `ASYNC_ACCEPTED` без финального `TOOL_RESULT`); тест «субагент паркуется на async bash → spawn не возвращается до позднего результата».

---

## MEDIUM

### O-2. Stop поддерева не «гасит» запаркованные сессии: CANCELLED-результаты без wake делают их eligible → POLL возобновляет модель
- **Пункт:** `execution/impl/SubtreeCanceller.java:42-57` (wake после каскада не выполняется); `TurnManagerImpl.requestStop`; спека `subagent-lifecycle` «Отмена поддерева»; `execution-model.md §6` («узел + потомки гаснут»).
- **Цитата:** apply-notes O п.4: «wake после каскада НЕ выполняется… Запаркованных поднимут POLL (5s) или поздний результат — модель увидит CANCELLED-результаты».
- **Проблема:** для запаркованной сессии `closePendingToolCalls` дописывает `TOOL_RESULT CANCELLED`, увеличивая `last_seq` → сессия снова eligible; POLL-джоба запускает Turn, `resetCancelRequested` сбрасывает флаг, и модель **продолжает работу после stop** (с CANCELLED-результатами), вопреки «поддерево гаснет». Немедленный wake сознательно убран, но POLL достигает того же эффекта позже — противоречие между M1-семантикой нелипкого `cancel_requested` и D-08/§6.
- **Предложение:** определить и зафиксировать: либо CANCELLED-закрытие не делает сессию eligible (не поднимать Turn без нового внешнего события), либо ввести «отменённое» состояние сессии (терминальность по stop), либо явно задокументировать, что остановленное поддерево резюмируемо POLL'ом (и обновить §6/spec). Сейчас поведение не соответствует заявленному.

---

## MINOR / NIT

### O-3. spawn-timeout оставляет субагента-сироту
- **Пункт:** `SubagentSpawner.awaitCompletion` (по истечении `harness.spawn.timeout-ms` → `spawn-timeout` без отмены ребёнка).
- **Проблема:** родитель получает ошибку, а дочерняя сессия продолжает исполняться в фоне (её результат «теряется»).
- **Предложение:** при timeout инициировать отмену дочерней сессии (`SubtreeCanceller.cancelSubtree(child)` / `requestStop`).

### O-4. `read_compacted` возвращает `not-found`, если id — видимое (непокрытое) сообщение
- **Пункт:** `execution/impl/ReadCompactedTool.java:68-71` (`originals.isEmpty()` → `not-found (no-such-message)`).
- **Проблема:** id существует в сессии, но не покрыт COMPACT → ответ «нет сообщения» вводит модель в заблуждение; спека оговаривает только неизвестный/чужой id.
- **Предложение:** отдельный ответ «не компактировано / сообщение видимо» либо явное `not-compacted`.

### O-5. Плейсхолдер-заглушки `declaration()` у `spawn_subagent`/`read_compacted`
- **Пункт:** `SubagentSpawner.declaration()`, `ReadCompactedTool.declaration()` — `FunctionToolCallback` с no-op телом.
- **Проблема:** исполнение перехватывается движком по имени (ок), но при прямом вызове Spring AI (вне движка) вернётся маркер-строка. Тот же паттерн, что у `TransitionMetaTool` (принят ранее) — замечание для консистентности/доков.
- **Предложение:** оставить, но зафиксировать инвариант в Javadoc/тесте.

### O-6. `SubtreeCanceller.closePendingToolCalls` — ранний `return` при занятом локе пропускает остальные вызовы сессии
- **Пункт:** `SubtreeCanceller.java:68-72` (`lock == null → return`).
- **Проблема:** если лок занят (живой Turn), возврат прерывает цикл по pending-вызовам этой сессии — остальные остаются незакрытыми (их закроет движок/POLL/поздний результат); для идемпотентности stop — приемлемо, но `return` вместо `continue`/break — неявно.
- **Предложение:** `break` (явно «сессия занята — её результаты пишет Turn») с комментарием.

---

## Проверка фокусных пунктов

1. **D-08 stop(сессия) = каскад по `parent_session_id` через `SubtreeCanceller`** — ✅ структурно: `TurnManagerImpl.requestStop` → `SubtreeCanceller.cancelSubtree` (BFS `SessionStore.findSubtree(id,null)`, включает саму сессию): `requestCancel` всем → отмена активных Turn'ов → закрытие незакрытых TOOL_CALL под `sess-{id}` с «первый финальный выигрывает»; идемпотентно. Тесты `SubtreeCancelApiTest` (активная ветка + parked-ветка). Семантический остаток — O-2.
2. **Owner inheritance через parent** — ✅ `SessionStoreImpl.createChildSession`: `owner_user_id = parent.getOwnerUserId()` (не JWT субагента, не ключ агента), `depth = parent.depth + 1` (D-61), ревизия — latest по `agentKey`; `Session.depth` поднят в проекцию. Тесты `SubagentSpawnTest` (happy path, depth-limit, no-metaTools).
3. **`read_compacted` same-session (R8)** — ✅ `findMessageRef(ULID) → (sessionId,seq)` + проверка `ref.sessionId().equals(current)`; `findCompactedOriginals(sessionId, seq)` — последний покрывающий COMPACT, оригиналы по `covers`; COMPACT не модифицируется; усечение по `harness.compact.read-max-bytes`. Тест «чужой id → not-found». Cross-session — вне M3 (R8).
4. **Поллинг vs `CompletableFuture.get`** — ⚠️ осознанное отклонение O.2 задокументировано (apply-notes O п.2): блокирующий поллинг `findSession(child)` (интервал `harness.spawn.poll-interval`, таймаут `harness.spawn.timeout-ms`), heartbeat родительского лока идёт отдельным executor'ом (`SessionLockManager.HeldLock`) — TTL не истекает при долгом spawn. Семантика «строго синхронный» сохранена; дефект — условие завершения (O-1).

## Вердикт

**REJECT — 6 находок (1 HIGH: O-1 условие завершения субагента игнорирует pending async → преждевременный возврат `spawn_subagent`; 1 MEDIUM: O-2 stop поддерева не гасит запаркованные сессии (POLL возобновляет); 4 MINOR/NIT: O-3…O-6).** Блокер — O-1 (нарушение D-10/спеки `subagent-lifecycle` в M3-ядре).

---

# Re-approval M3 batch O (2026-09-20)

> Проверены: `SubagentSpawner.awaitCompletion`, `TurnManagerImpl.tryStart`, `SessionStoreImpl.appendEvent`, `SubtreeCanceller`, тесты. Сборки не запускались.

## Статусы находок

| # | Sev | Статус | Проверка |
|---|---|---|---|
| O-1 | HIGH | **закрыто** | `awaitCompletion` требует `lastTurnOutcome != null && lastSeq<=lastConsumedSeq` **И** `findPendingToolCalls(child).isEmpty()` **И** `broadcaster.statusSnapshot(child).runtimeStatus() == IDLE`; тест `spawnerWaitsForParkedSubagentToFinish` («воркер в парковке — spawn не закрывается; поздний результат → финал → TOOL_RESULT родителю»). Преждевременный возврат на `PARKED_ASYNC` устранён |
| O-2 | MEDIUM | **закрыто в коде (см. R-1)** | `TurnManagerImpl.tryStart` при `session.cancelRequested()` → no-op (POLL/поздний результат/re-скан не поднимают остановленное поддерево); явный resume — USER-допись (`SessionStoreImpl.appendEvent` сбрасывает `cancel_requested` в той же транзакции) и `AgentStateBootstrapper`; тест `stopKeepsSubtreeStoppedUntilExplicitResume`; M1-тест `stopOnIdle...NextMessageStillWorks` зелёный |
| O-3 | MINOR | **закрыто (задокументировано)** | apply-notes §O п.12: spawn-timeout оставляет child жить — сознательно (неинвазивно; stop поддерева/POLL/скан страхуют) |
| O-4 | MINOR | **не закрыто** | `ReadCompactedTool` по-прежнему отдаёт `not-found` для видимого (непокрытого) сообщения |
| O-5 | NIT | **не закрыто** | `declaration()`-заглушки (принятый паттерн) |
| O-6 | NIT | **не закрыто** | `SubtreeCanceller.closePendingToolCalls` — ранний `return` при занятом локе |

## Остаточные / новые находки

### R-1 [MEDIUM]. Персистентный stop меняет M1-требование `agent-turn`, но MODIFIED-дельта не заведена
- **Пункт:** `TurnManagerImpl.tryStart` (gate `cancelRequested`); M1 `openspec/specs/agent-turn` «Отмена Turn'а (stop)»; M3-дельта `agent-turn` (ADDED-only); `apply-notes.md` §O п.11.
- **Цитата:** M1: «Флаг `cancel_requested` SHALL сбрасываться при завершении Turn'а (любой исход) **и на старте нового Turn'а**; stop по сессии без активного Turn'а не имеет последующего эффекта»; код: reset теперь только на USER-дописи, старт Turn'а под флагом — no-op.
- **Проблема:** поведение M1-контракта изменено (флаг персистентен, reset при старте Turn'а убран), но это не оформлено как MODIFIED-требование в M3-дельте `agent-turn`/`session-api` — расхождение спеки и реализации (и формально нарушение M1-требования).
- **Предложение:** добавить MODIFIED-требование «Персистентный stop / явный resume» в дельту `agent-turn` (или `session-api`) и синхронизировать `execution-model §6`; при желании — уточнить условие сброса (только USER).

### R-2 [HIGH]. `spawn_subagent` не наблюдает `TurnCancellation`: stop поддерева не прерывает родительский spawn, сценарий «subtree-cancelled» не достигается
- **Пункт:** `SubagentSpawner.execute(UUID parentSessionId, String callId, Map arguments)` — без `TurnCancellation`; `AgentTurnEngine:389` `spawner.execute(...)` без cancellation; `awaitCompletion` (условие `lastSeq <= lastConsumedSeq`).
- **Цитата:** спека `subagent-lifecycle`: «**Scenario: субагент отменён stop'ом** — WHEN родительский сеанс вызывает stop(session) … THEN TOOL_RESULT «subtree-cancelled»; родитель продолжает».
- **Проблема:** `awaitCompletion` не проверяет `cancellation` и ждёт `lastSeq<=lastConsumedSeq`. При stop поддерева `SubtreeCanceller`/движок дописывают CANCELLED-результаты (**lastSeq > lastConsumedSeq**, по D-45 CANCELLED потребляет watermark рендера) → условие не выполняется никогда до таймаута; родитель, блокированный в `spawn_subagent`, висит до `harness.spawn.timeout-ms` (30 мин) и получает `spawn-timeout` вместо быстрого `subtree-cancelled`. Если остановлен корень, `cancelSubtree` отменяет и родительский Turn, но spawner его отмену игнорирует — stop «не долетает» до заблокированного turn'а.
- **Предложение:** передать `TurnCancellation` в `SubagentSpawner.execute`/`awaitCompletion` и завершать ожидание по отмене (маппинг в `ToolResult.cancelled "subtree-cancelled"`); либо в `awaitCompletion` распознавать отменённое состояние ребёнка (например, `lastTurnOutcome == CANCELLED` / отсутствие активного Turn'а + флаг) независимо от watermark. Тест «stop во время spawn → родитель получает CANCELLED promptly».

## Проверка

- O-1 закрыт корректно (полный D-10: pending==0 + IDLE) — ключевой блокер снят.
- O-2 по коду решает исходную проблему (POLL/поздний результат не будят остановленное), но требует спека-синка (R-1) и оставляет дыру в stop↔spawn (R-2).
- O-3 задокументирован; O-4…O-6 — не тронуты (minor/nit).

**REJECT — 2 незакрытых (1 HIGH: R-2 stop не прерывает `spawn_subagent` → 30-мин зависание, «subtree-cancelled» не доставляется; 1 MEDIUM: R-1 персистентный stop без MODIFIED-дельты/M1-синка) + O-4…O-6 (minor/nit).** O-1/O-2-код и O-3 — приняты.

---

# Re-approval 2 M3 batch O (2026-09-20)

> Проверены: `SubagentSpawner.awaitCompletion` (SpawnWait), `SubagentSpawnTest`, `specs/agent-turn/spec.md` (MODIFIED-дельта). Сборки не запускались.

## Статусы находок

| # | Sev | Статус | Проверка |
|---|---|---|---|
| R-2 | HIGH | **закрыто** | `awaitCompletion` → `SpawnWait {COMPLETED, CANCELLED, TIMEOUT}`: на `child.cancelRequested()` (опрос каждые `harness.spawn.poll-interval`) немедленный возврат `CANCELLED`; `execute` маппит в `ToolResult.cancelled(..., "subtree-cancelled")` без ожидания `harness.spawn.timeout-ms`. Тест `stopDuringSpawnCancelsParentPromptly` (stop во время spawn → CANCELLED `<subtree-cancelled>` вместо 30-мин таймаута) |
| R-1 | MEDIUM | **закрыто** | `specs/agent-turn/spec.md` — секция `## MODIFIED Requirements` (mini-amendment R-1): явный supersede M1-требования «флаг сбрасывается на завершении/старте Turn'а» — после `requestStop`/`SubtreeCanceller` флаг персистентен, `tryStart` при флаге — no-op (POLL/поздний async/рестарт-скан не будят), resume — только USER-сообщение (`SessionStore.appendEvent`) или вход/resume задачи (`AgentStateBootstrapper`). Сценарии добавлены |
| O-4 | MINOR | **остаётся** | `ReadCompactedTool`: видимое (непокрытое) сообщение → `not-found` |
| O-5 | NIT | **остаётся** | `declaration()`-заглушки (принятый паттерн) |
| O-6 | NIT | **остаётся** | `SubtreeCanceller.closePendingToolCalls` — ранний `return` при занятом локе |

## Проверка

- R-2 устранён по существу: stop поддерева мгновенно разрешает родительский `spawn_subagent` (CANCELLED), сценарий спеки «субагент отменён stop'ом → TOOL_RESULT «subtree-cancelled»; родитель продолжает» выполняется; 30-минутное зависание снято.
- R-1: изменение M1-семантики `cancel_requested` больше не «молчаливое» — оформлено MODIFIED-дельтой `agent-turn` со сценариями; модель поведения (персистентный stop + явный USER-resume) консистентна с кодом (`TurnManagerImpl.tryStart` gate, `SessionStoreImpl.appendEvent` reset).
- O-1 (полный D-10), O-2 (персистентный stop), O-3 (spawn-timeout задокументирован) — без регрессий; `mvn verify` 465.

## Остаток (не блокирует)

- O-4/O-5/O-6 — minor/nit, не тронуты пачкой; перенести в S-пачку/бэклог (O-4 — ясный ответ «не компактировано» вместо `not-found`).

**APPROVE — 0 блокеров (3 остаточных minor/nit: O-4, O-5, O-6).** Пачка O принята.