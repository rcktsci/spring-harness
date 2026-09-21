# Ревью M4 batch V (client-tool-bridge + routing), commit 169f59b

> Ревьюер: DeepSeek-V4.1-Flash (субагент).
> Дата: 2026-09-22.
> Объект: `execution/{ClientToolBridge,ToolDescriptor,AgentTurnEngine}.java`, `relay/{ClientToolRegistry,ClientToolAdapter,RelayWebSocketHandler}.java`, тесты (`ClientToolRegistryTest`, `ClientToolTurnWireMockTest`, `TestRelayConnection`, `ArchitectureRulesTest`), `tasks.md §4`.
> Контекст: спеки `client-tool-bridge`, `agent-turn` (M4), D-80/D-81/D-84/D-85, M1–M3-канон (`TurnManagerImpl` sess-лок, `AsyncToolExecutor` D-64, `LimitedJsonSchemaValidator` D-58, `ContainerWorkspaceTools.bash` — non-zero exit ≠ error).
> Сборки не запускались; dev verify — 551 тест.
> Severity: **HIGH** — обход гейта/ломает контракт; **MEDIUM** — баг/гонка; **MINOR/NIT** — косметика/edge.

## Сводка

| Severity | Кол-во |
|---|---|
| HIGH | 0 |
| MEDIUM | 1 |
| MINOR/NIT | 8 |
| **Итого** | **9** |

По пунктам промпта: (1) резолвер-гейт — ✅ обхода нет; (2) parent-chain — ✅ корректно, цикл защищён; (3) completion-map — ✅ first-final-wins/journal-Turn-поток корректны; (4) SPI — ✅ ArchUnit/контракты не сломаны; (5) takeover/attach/detach — ⚠️ **V-1** (stale-оверлей при re-register на другую сессию); (6) спеки — ✅ кроме V-2 (exitCode-маппинг не определён); (7) AGENTS — ✅.

---

## MEDIUM

### V-1. `handleRegister` не снимает клиентский оверлей прежней сессии при перерегистрации тем же соединением — stale CLIENT на заброшенной сессии
- **Пункт:** `RelayWebSocketHandler.java:184–193` (T-8) vs `:261–264` (`cleanup`), `ClientToolRegistry.attach/detach`.
- **Проблема:** при повторной регистрации того же соединения на **другую** сессию хендлер делает `registry.unregister(previousSessionId, state.connection)`, но **не** `clientToolRegistry.detach(previousSessionId, …)`. `cleanup` (при закрытии) снимает оверлей только для `state.registeredSessionId` (последняя сессия). Итог: старая сессия навсегда остаётся в `overlays` с этим соединением → `isClientSession(old)=true`, `manifest/resolve` старые инструменты, а `invoke` шлёт `tool.call` (с `sessionId` старой сессии) в соединение, уже привязанное к новой. Это ровно T-8-сценарий («re-register на другую сессию»), забытый для клиентского реестра; утечка памяти + ложный CLIENT-toolset.
- **Тесты:** `RelayWebSocketHandlerTest.reRegisterOnAnotherSessionDropsStaleKey` проверяет только `RelayConnectionRegistry`, не `ClientToolRegistry`; `ClientToolRegistryTest` пути перерегистрации не покрывает.
- **Предложение:** в T-8-ветке вызывать `clientToolRegistry.detach(previousSessionId, state.connection)` вместе с `registry.unregister(...)`; добавить тест «re-register на другую сессию → оверлей старой очищен».

---

## MINOR / NIT

### V-2 (MINOR). `toResult`: любой non-zero `exitCode` → `ToolStatus.ERROR` — расходится с семантикой нативного bash
- **Пункт:** `ClientToolRegistry.java:175–179`.
- **Проблема:** `ContainerWorkspaceTools.bash` при non-zero exit возвращает `ToolResult.ok(..., exitCode, ...)` (agent-tools §5: «non-zero exit для bash — не ошибка инструмента»). Клиентский `bash` (exitCode≠0) здесь помечается `ERROR`. Для client-declared `bash` поведение будет отличаться от серверного — расхождение контракта; в спеке `client-relay`/`client-tool-bridge` маппинг не зафиксирован.
- **Предложение:** определить маппинг в §5: либо `OK` + `exitCode` (как нативный bash), либо явно «non-zero exit → ERROR только для не-bash» / согласовать с владельцем.

### V-3 (MINOR). `parseToolDescriptors` может бросить Jackson-исключение → резкий обрыв WS вместо чистого отказа
- **Пункт:** `RelayWebSocketHandler.java:205–225`.
- **Проблема:** `objectMapper.writeValueAsString(schemaNode)` + `readValue(..., TypeReference<Map>)` не обёрнуты; при аномальном `inputSchema` исключение вылетит из `handleTextMessage` → контейнер закроет соединение вне контракта (ни `error`-фрейма, ни 4403).
- **Предложение:** обернуть в try/catch → `reject(..., "duplicate-tool-name"?)`/`protocol` (или `inputSchema=null`), не роняя соединение.

### V-4 (MINOR). Task 4.4 отмечен `[x]`, но заявленного spawn-теста нет
- **Пункт:** `tasks.md §4.4` («тест: spawn из CLIENT-root видит клиентский инструмент; task-сессия — SERVER»); `SubagentSpawner.java` не менялся; `ClientToolRegistryTest` строит parent-chain на **мок-`SessionStore`**, реальный `spawn_subagent` не задействован.
- **Проблема:** резолв по parent-chain протестирован на уровне реестра, но не через фактический spawn (проверка `parent_session_id`-проводки и depth). Traceability: задача помечена выполненной при неполном покрытии.
- **Предложение:** либо добавить тест реального spawn из CLIENT-root (или integration), либо переформулировать 4.4 как «parent-chain резолв покрыт unit-тестом реестра».

### V-5 (MINOR). Tombstones `pending` не ограничены — рост на время соединения
- **Пункт:** `ClientToolRegistry.java:61,135` — запись остаётся до `detach`; снимается только на disconnect/takeover.
- **Проблема:** за долгую сессию число клиентских вызовов накапливает completed-futures (tombstone ради first-final-wins). Утечка памяти, в пределе — существенная.
- **Предложение:** ограничить окно tombstone (TTL/лимит на соединение) или периодически чистить completed-записи, не ломая «первый финал выигрывает».

### V-6 (NIT). `state.clientToolNames` фактически дублирует `state.clientToolset`
- **Пункт:** `AgentTurnEngine.java:145–150,325–331,480–486`.
- **Проблема:** `clientManifest` и `clientToolset` снимаются почти одновременно; при `clientToolset==true` гейт срабатывает и без набора имён; при `false` набор пуст. `clientToolNames` играет роль лишь в узком окне (attach между двумя вызовами). Не вредно, но читается как основной механизм.
- **Предложение:** оставить как страховку, но комментарий уточнить (или убрать второй `resolve`-вызов).

### V-7 (NIT). Ошибка `sendText` глотается → ожидание полного `tool-call-timeout` вместо быстрого отказа
- **Пункт:** `WebSocketRelayConnection.sendText` (catch Exception), `ClientToolRegistry.invoke:136–138`.
- **Проблема:** если кадр не ушёл (закрыто/переполнено), вызов ждёт `tool-call-timeout` (5 мин) вместо немедленного LOST/ERROR. Смягчается тем, что `detach` по разрыву завершит future LOST — но только если соединение увидено мёртвым (heartbeat до 2×15с).
- **Предложение:** при неудачной отправке завершать future (`ToolResult.lost/error`) сразу; либо проверять `connection` перед put.

### V-8 (NIT). `tool.cancel` и рестарт-скан-контракт — batch W
- `RelayWebSocketHandler` обрабатывает `tool.result`/`tool.progress`; `tool.cancel` (спека agent-turn «stop во время клиентского tool.call») — пачка W. `invoke` не регистрирует прерыватель в `TurnCancellation`, поэтому `stop` во время in-flight клиентского вызова не разбудит Turn до `tool-call-timeout`. Это ожидаемо для порядка пачек, но зафиксировать зависимость (W) стоит явно.

### V-9 (NIT, унаследованное). Toolset производен от соединения: после `detach` сессия возвращается к SERVER
- `isClientSession` = наличие оверлея (соединения). После disconnect `overlay` удаляется → next Turn на той же сессии видит `clientToolset=false` → нативные серверные инструменты **снова доступны** (R-2 из re-approval propose). Соответствует D-84/спеке (client-tool-bridge:25 — «нет соединения → native server tools»), но расходится с изначальным намерением владельца «всегда tool-not-available». Не дефект V; внести в apply-notes X.

---

## Подтверждено (не находки)

- **(1) Резолвер-гейт:** `AgentTurnEngine.executeToolCall` — серверные колбэки (transition/MCP/orchestrator/spawn/read_compacted) → `clientToolBridge.resolve` → в CLIENT/`clientToolNames` → **tool-not-available**; `agentTools.execute` (native) недостижим при `clientToolset`. Галлюцинированный/неклиентский `bash` в CLIENT серверно не исполняется (WireMock-тест `hallucinatedNativeToolInClientSessionIsNotRoutedToServer`). Клиентский `bash` идёт в релей. `isAsyncTool` — MCP async-capable + нативный `bash` **только в SERVER**; клиентский `bash` в окно `AsyncToolExecutor` не попадает.
- **(2) Parent-chain:** `resolveSession` поднимается по `parentSessionId` с visited-защитой; FREE-ребёнок видит оверлей root; STATE-сессия задачи (`parent NULL`) → SERVER; покрыто `clientSessionIsResolvedByParentChainOnly`/`resolveAndManifestWalkParentChain`.
- **(3) Completion-map:** `invoke` — `pending.put` → `sendText` → `future.get(tool-call-timeout)`; timeout → `ToolResult ERROR tool-timeout` + tombstone; disconnect → `detach` completes LOST; journal пишет **Turn-поток** (движок) под `sess`-локом, WS-поток только `completeResult`; first-final-wins атомарен (`CompletableFuture.complete`); поздний/повторный `tool.result` — no-op. Restart-скан закрывает осиротевшие TOOL_CALL обычным путём (W).
- **(4) SPI:** `manifest()`, `invoke(..., Map<String,Object>)`, `ToolDescriptor(name, description, Map inputSchema, source)`; ArchUnit `relayViolationIsCaught` + слои зелёные; `DOMAIN_CLASSES` пришпилен к `target/classes` (устранён false-positive от test-фикстур `execution→relay`). Публичных контрактов/персиста не задевает.
- **(5) Takeover:** `RelayConnectionRegistry` (CAS unregister, takeover same-principal) — ок; `ClientToolRegistry.detach` CAS by identity — оверлей нового соединения переживает detach старого (`detachIsCasByIdentitySoTakeoverOverlaySurvives`). Замечание — V-1 (re-register на другую сессию).
- **(6) Спеки:** порядок резолва, toolset CLIENT/SERVER, `params-schema` при кривых args (валидатор D-58, без отправки кадра), `tool-not-available` после disconnect, `duplicate-tool-name` (T) — соответствуют. Единственный нюанс — V-2.
- **(7) AGENTS:** таймаут только из `RelayProperties`; Jackson 3 (`tools.jackson`); Lombok; импорты и слои корректны.

## Вердикт

**REJECT — 1 MEDIUM (V-1: stale клиентский оверлей при перерегистрации соединения на другую сессию) + 3 MINOR (V-2 exitCode-маппинг, V-3 uncaught parse, V-4 traceability spawn-теста) + 5 NIT.**

Ядро пачки выполнено корректно: резолвер-гейт закрывает галлюцинированные нативные вызовы в CLIENT, parent-chain/task-SERVER работает, completion-map/first-final-wins/journal-Turn-поток реализованы по D-81, SPI и ArchUnit не сломаны, конфиг/Jackson 3/Lombok в норме. Блокер приёмки один — **V-1** (жизненный цикл attach/detach: та же ошибка класса T-8, но для `ClientToolRegistry`); после фикса и добавления теста — re-approve.

---

# Re-approval M4 batch V (2026-09-22, рабочее дерево поверх 169f59b)

> Проверены незакоммиченные фиксы V-1…V-7 (`ClientToolRegistry`, `RelayWebSocketHandler`, `RelayConnection`, `WebSocketRelayConnection`, `AgentTurnEngine`, тесты, `api-contracts.md` §5, client-relay spec). Сборки не запускались; dev verify — 552 теста.
> Все 1 MEDIUM + 5 MINOR + 2 NIT round-1 закрыты; новых MEDIUM/HIGH не появилось.

## Статусы находок round-1

| # | Sev | Статус | Проверка |
|---|---|---|---|
| V-1 | MEDIUM | **закрыто** | `RelayWebSocketHandler.java:195–199`: при смене сессии — `registry.unregister(prev, conn)` **и** `clientToolRegistry.detach(prev, conn)`; тест `reRegisterOnAnotherSessionDropsStaleKey` дополнен проверками `isClientSession(first)==false`, `isClientSession(second)==true`, `resolve(second,"b")` |
| V-2 | MINOR | **закрыто** | `ClientToolRegistry.toResult` → всегда `ToolResult.ok(..., exitCode, ...)`; семантика native bash; зафиксировано в `api-contracts §5.3` и client-relay spec |
| V-3 | MINOR | **закрыто** | `parseToolDescriptors` обёрнут `try/catch` → `close(4403 protocol)`, не abrupt close (`:178–186`); описано в §5 |
| V-4 | MINOR | **закрыто** | Добавлен реальный spawn-путь тест `spawnedChildSeesClientOverlayViaParentChain` (`createChildSession` → child видит оверлей root через parent-chain) |
| V-5 | MINOR | **закрыто** | `pruneTombstones()`: завершённые future удаляются по TTL = `tool-call-timeout`; `markCompleted()` в `completeResult`/timeout; `detach` удаляет записи соединения |
| V-6 | NIT | **закрыто** | Комментарий уточнён: `clientToolSet` — основной гейт, `clientToolNames` — узкая страховка окна Turn↔disconnect |
| V-7 | NIT | **закрыто** | `RelayConnection.sendText` → `boolean`; при `false` — `pending.remove` + `ToolResult.lost("не доставлено…")` без ожидания таймаута; все реализации/тесты обновлены |
| V-8 | NIT | **не входит** | `tool.cancel` / рестарт-скан-контракт — batch W (ожидаемо) |
| V-9 | NIT | **открыто (унаследовано)** | Connection-derived toolset (после detach → SERVER) — свойство D-84, вне scope V; занести в apply-notes X |

## Замечания к фиксам (не блокеры)

- **N-1 (NIT).** V-7-путь возвращает `LOST` с текстом «не доставлено: соединение закрыто» — статус верный, но формулировка отличается от спековой «потеряно при отключении исполнителя». Косметика.
- **N-2 (NIT).** После V-2 клиентский инструмент **не может** вернуть `ERROR` через `tool.result` (любой `exitCode` → OK). Для «ошибочных» client-MCP-инструментов ошибка выражается только текстом output. Решение осознанное и задокументировано в §5.3; если владелец захочет ERROR-семантику — отдельное расширение кадра.
- **N-3 (NIT).** `pruneTombstones` вызывается (O(n) по `pending`) на каждом `invoke`/`completeResult`; при TTL-окне объём ограничен, но при высокой частоте вызовов — линейный скан. Приемлемо для одного инстанса; при нужде — фоновая чистка/`ConcurrentLinkedQueue`.

## Трассировка tasks → spec

| Спека/задача | Проверка |
|---|---|
| client-tool-bridge (декларация/видимость/гейт/overlay/валидация) | 4.1–4.3, 4.5 — ✅; 4.4 spawn-путь — ✅ (новый тест) |
| agent-turn (резолв/tool-result/tool-not-available) | 4.3 — ✅; `tool.cancel` — W |
| api-contracts §5 (register/protocol/tool.result) | обновлён под V-2/V-3 — ✅ |

## Вердикт re-approval

**APPROVE — 0 блокеров.** V-1 (stale-оверлей при re-register) закрыт вызовом `clientToolRegistry.detach` + тестом; V-2…V-7 закрыты материально и (где нужно) отражены в §5/client-relay spec. Новых MEDIUM/HIGH нет. Остаточные N-1…N-3 — косметика; V-8 (tool.cancel/рестарт-скан) и V-9 (connection-derived toolset) ожидаемо вне scope V (W/apply-notes).
