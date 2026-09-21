# Ревью M4 batch W (disconnect/cancel/restart-scan), commit b4a8bb9

> Ревьюер: DeepSeek-V4.1-Flash (субагент).
> Дата: 2026-09-22.
> Объект: `execution/{AgentTurnEngine,ClientToolBridge}.java`, `relay/{ClientToolRegistry,ClientToolAdapter}.java`, тесты (`ClientToolTurnWireMockTest` +2, `RestartScanTest` +1, `ClientToolRegistryTest` +2), `tasks.md §5`.
> Контекст: спеки `client-relay` (§5.3 tool.cancel/LOST), `agent-turn` (M4 MODIFIED «Отмена Turn'а (stop)»), D-59/D-64/D-78/D-81/D-84; M1–M3-канон (`TurnCancellation`, `ActiveTurnRegistry`, `SubtreeCanceller`, `RestartScanRunner`, `TurnManagerImpl`).
> Сборки не запускались; dev verify — 557 тестов.
> Severity: **HIGH** — ломает контракт/дубли в журнале; **MEDIUM** — баг/гонка; **MINOR/NIT** — косметика/edge.

## Сводка

| Severity | Кол-во |
|---|---|
| HIGH | 0 |
| MEDIUM | 0 |
| MINOR/NIT | 6 |
| **Итого** | **6** |

По пунктам промпта: (1) гонки cancel — ✅ все сценарии разобраны, дублей/потерь нет (W-1/W-2/W-3 — краевые); (2) LOST/disconnect vs timeout vs рестарт-скан — ✅ один финал, дублей журнала нет; (3) журнал только Turn-потоком — ✅; (4) спеки client-relay/agent-turn — ✅ (W-4 — тест-пробел каскада); (5) AGENTS — ✅.

---

## Разбор гонок (основное)

**Сценарии stop (все — первый финал выигрывает через `CompletableFuture.complete`):**

| # | Момент stop | Механизм | Итог |
|---|---|---|---|
| 1 | до `registerInterrupt` (engine уже видит `isCancelled`) | engine пишет синтетический CANCELLED в цикле, `executeToolCall` не зовётся | нет `tool.call`, журнал CANCELLED ✅ |
| 2 | между `registerInterrupt` и `pending.put` | `cancel()` → `pending==null` → `cancelledBeforeDispatch.add`; `invoke` consume'ит → CANCELLED без `tool.call` | нет кадра ✅ (тест `cancelBeforeDispatchReturnsCancelledWithoutToolCall`) |
| 3 | после `pending.put`, до `future.get` / во время ожидания | `cancel()` → `dispatchCancel`: `tool.cancel` + future CANCELLED; `invoke` вернёт CANCELLED | ✅ (тесты `cancelCompletesInFlight...`, интеграционный `stopDuringInFlight...`) |
| 4 | в узком окне между `future.isDone()` и `sendText(tool.call)` | `dispatchCancel` шлёт `tool.cancel`, затем `invoke` шлёт `tool.call` | **W-2** (cancel-before-call ordering) |
| 5 | после завершения вызова (результат/таймаут) | interruptor уже снят в `finally` | cancel не шлётся ✅ |

**first-final-wins / отсутствие дублей журнала:**
- `detach` → LOST (complete + remove), `dispatchCancel` → CANCELLED (complete), timeout → ERROR (complete), `completeResult` → OK (complete). Все — атомарный `CompletableFuture.complete`, повторный — no-op.
- Журнал `TOOL_RESULT` пишет **только Turn-поток** (`executeToolCall` возвращает единственный `ToolResult` → `sessionStore.appendEvent`). WS-поток/stop-поток/close-поток лишь завершают future. ✅
- Рестарт-скан — только на старте процесса (живого Turn нет), закрывает `TOOL_CALL` без результата LOST «операция потеряна при перезапуске»; клиентские вызовы идут тем же журнальным паттерном (`RestartScanRunner` tool-agnostic). ✅ (тест `pendingClientToolCallClosedWithLostOnRestart`)
- Дублей `TOOL_RESULT` по одному callId нет ни в одном сценарии; `params-schema`/`tool-not-available` возвращаются до журналирования результата движком (один раз).

**Каскад поддерева:** `SubtreeCanceller.cancelSubtree` для каждой сессии поддерева зовёт `activeTurns.get(id).cancel()`; у каждого живого Turn'а свой interruptor → `clientToolBridge.cancel(session.id(), callId)` для in-flight клиентского вызова ребёнка. ✅ (W-4 — нет теста каскада).

---

## MINOR / NIT

### W-1 (MINOR). `cancelledBeforeDispatch` может «протечь» и остаётся без очистки
- **Пункт:** `ClientToolRegistry.java:65,115–122,171–180`.
- **Проблема:** флаг добавляется, если `cancel` не нашёл `pending`. Он снимается только внутри `invoke` — **после** проверок `resolveSession`/`descriptor`/`connection`/`validate`. Если `invoke` вернёт `tool-not-available`/`params-schema`/`send-failure`, флаг по этому `callId` останется навсегда (набор не чистится). Также `cancel` для уже завершённого (tombstone) вызова заново шлёт `tool.cancel` (дубликат кадра).
- **Влияние:** медленная утечка `Set<String>`; дубликат `tool.cancel` безвреден. `callId` уникален (ULID), ложной отмены другого вызова нет.
- **Предложение:** на входе `invoke` (до ранних возвратов) снять флаг `cancelledBeforeDispatch.remove(callId)` и, если он был, вернуть CANCELLED; либо ограничить набор (TTL/размер). Параметр `sessionId` в `cancel` не используется — либо задействовать, либо убрать.

### W-2 (MINOR). Узкое окно cancel-before-`tool.call` (ordering)
- **Пункт:** `ClientToolRegistry.java:176–185` (проверка `future.isDone()` → `connection.sendText(tool.call)`).
- **Проблема:** если `cancel` успевает между проверкой `future.isDone()` и отправкой `tool.call`, клиент получает `tool.cancel` (неизвестный callId), затем `tool.call` и может исполнить его; сервер журналирует CANCELLED, а побочный эффект на клиенте возможен. Спека §5.3 не определяет cancel-before-call.
- **Предложение:** после `sendText(tool.call)` повторно проверить `future.isDone()` и при отмене дослать `tool.cancel`; либо публиковать+отменять атомарно (синхронизация на `pendingCall`).

### W-3 (NIT). Повторный `stop` шлёт повторный `tool.cancel` (идемпотентность на уровне кадра)
- **Пункт:** `TurnCancellation.cancel()` итерация + `dispatchCancel` (`:124–129`).
- **Проблема:** двойной `requestStop` при ещё зарегистрированном interruptor'е вызовет `dispatchCancel` дважды → два `tool.cancel` (future уже завершён). Функционально безопасно, спека «повторный stop идемпотентен» строго на кадры не распространяется.
- **Предложение:** отправлять кадр только если `future.complete(cancelled)` вернул `true`.

### W-4 (MINOR). Task 5.2 отмечен `[x]`, но теста каскада поддерева нет
- **Пункт:** `tasks.md §5.2` («интеграция с `SubtreeCanceller` (каскад по поддереву — клиентские вызовы детей тоже отменяются)»); тесты — только одиночная сессия (`stopDuringInFlightClientCallSendsCancelAndJournalsCancelled`), нет spawn/child-каскада.
- **Проблема:** реализация каскада есть (per-session `TurnCancellation`), но именно заявленный сценарий (stop root → отменён клиентский вызов ребёнка) не покрыт — риск регресса при изменениях `SubtreeCanceller`.
- **Предложение:** добавить тест «stop root с ребёнком, у ребёнка in-flight клиентский вызов → ребёнку `tool.cancel`, журнал ребёнка CANCELLED».

### W-5 (NIT). `SubtreeCanceller.closePendingToolCalls` не шлёт `tool.cancel` сессиям без живого Turn
- **Пункт:** `SubtreeCanceller.java:60–86` (только «синтетический CANCELLED» в журнал).
- **Проблема:** для сессии без живого Turn'а (нет interruptor'а) `tool.cancel` клиенту не уходит — клиент может продолжать исполнение. Для клиентских вызовов это практически невозможно (вызов синхронный внутри живого Turn'а), но пометить как известное ограничение стоит (apply-notes).

### W-6 (NIT). Устаревший javadoc `ClientToolRegistry`
- **Пункт:** `ClientToolRegistry.java:40–41` («tombstone до конца соединения», «завершение — из WS-потока»).
- **Проблема:** после V-5 tombstone ограничены TTL `tool-call-timeout`; future теперь завершают и cancel-поток, и disconnect-поток. Док отстал.
- **Предложение:** обновить формулировку.

---

## Подтверждено (не находки)

- **Каскад/stop:** `AgentTurnEngine` регистрирует interruptor в `TurnCancellation` только для клиентских вызовов; `SubtreeCanceller` каскадно вызывает `cancel()` по поддереву; `TurnCancellation.registerInterrupt` запускает interruptor немедленно при уже активной отмене (закрывает пре-диспатч-гонку).
- **Journal-only-Turn-thread:** cancel/disconnect/WS-потоки не пишут в журнал — только завершают future; движок пишет `TOOL_RESULT` один раз на вызов.
- **Рестарт-скан:** `RestartScanRunner` tool-agnostic, LOST «операция потеряна при перезапуске»; интеграционный тест подтверждает (журнал: status LOST, tool сохранён).
- **Спеки:** `client-relay` §5.3 (`tool.cancel`, «повторный tool.result после cancel игнорируется»), `agent-turn` MODIFIED «Отмена Turn'а (stop)» (клиентский `tool.call` → `tool.cancel` → синтетический CANCELLED) — соблюдены.
- **AGENTS:** таймаут — из `RelayProperties`; Jackson 3; Lombok; ArchUnit-направления не затронуты (`execution` ↔ SPI `ClientToolBridge`).

## Вердикт

**APPROVE — 0 блокеров (3 MINOR: W-1 flag-leak/duplicate-cancel, W-2 cancel-before-call window, W-4 нет теста каскада; 3 NIT: W-3, W-5, W-6).**

Ключевые требования пачки выполнены корректно: все гонки stop (до/после отправки, double, после result) разрешаются «первым финальным»; disconnect-LOST / tool-timeout / рестарт-скан не дают дублей `TOOL_RESULT` и не пересекаются по журналу; журнал пишет только Turn-поток; спеки `client-relay`/`agent-turn` соблюдены. Рекомендации W-1/W-2/W-4 полезны до архива M4, но не блокируют пачку.
