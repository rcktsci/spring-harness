# M4 Batch V Review — Mercury-2.5

**Дата:** 2026-09-22  
**Ревьюер:** Mercury-2.5  
**Коммит:** `169f59b` (client-tool-bridge + routing)

---

## Резюме

Batch V реализует runtime-оверлей клиентских инструментов (D-80), маршрутизацию по сессии с parent-chain-видимостью (D-84) и идемпотентность по callId (D-81). ArchUnit подтверждает границы relay/execution (D-85). 551 тест зелёных (12 новых). AGENTS.md соблюдён.

---

## 1. D-80/D-81/D-84 соответствие

### D-80: Runtime-only оверлей, lifecycle по соединению

`ClientToolRegistry.java:57–64`:

```java
public void attach(UUID sessionId, RelayConnection connection, List<ToolDescriptor> tools) {
    overlays.put(sessionId, new Overlay(connection, List.copyOf(tools)));
}
public void detach(UUID sessionId, RelayConnection connection) {
    overlays.computeIfPresent(sessionId, (key, overlay) ->
            overlay.connection() == connection ? null : overlay);
    // in-flight → LOST
}
```

- Оверлей живёт в памяти, не в БД
- detach очищает оверлей и закрывает in-flight вызовы как LOST
- CAS по identity (takeover новым соединением не затирается)

### D-81: Идемпотентность по callId, журнал — Turn-поток

`ClientToolRegistry.java:69–76, 81–107`:

```java
public void completeResult(String callId, String output, Integer exitCode) {
    PendingCall pendingCall = pending.get(callId);
    if (pendingCall == null) return; // tombstone, no-op
    pendingCall.future().complete(toResult(pendingCall, output, exitCode));
}
```

- completion-map на соединение (`callId → CompletableFuture`)
- WS-поток только complete'ит future
- Turn-поток пишет журнал под sess-локом (D-81)
- first-final-wins: поздние tool.result игнорируются

### D-84: toolset сессии = наличие соединения в parent-цепочке

`ClientToolRegistry.java:40–56`:

```java
private Optional<UUID> resolveSession(UUID sessionId) {
    Set<UUID> visited = new HashSet<>();
    UUID current = sessionId;
    while (current != null && visited.add(current)) {
        if (overlays.containsKey(current)) return Optional.of(current);
        Session session = sessionStore.findSession(current).orElse(null);
        current = session == null ? null : session.parentSessionId();
    }
    return Optional.empty();
}
```

- root FREE-сессия с соединением → CLIENT
- sub-сессии (spawn_subagent) поднимаются по parentSessionId → видят тот же оверлей
- task-сессии (parent NULL) → SERVER автоматически

### AgentTurnEngine: резолв и гейт

`AgentTurnEngine.java:151–165, 289–307`:

```java
// D-84: порядок резолва — server callbacks → client overlay → tool-not-available
if (clientToolBridge.resolve(session.id(), pendingCall.tool()).isPresent()) {
    return clientToolBridge.invoke(...);
}
// CLIENT-toolset: нативные файловые не резолвятся
if (state.clientToolset || state.clientToolNames.contains(pendingCall.tool())) {
    return ToolResult.error(..., "tool-not-available");
}
```

- resolve order: server callbacks → overlay → tool-not-available
- async-классификация: только по серверным колбэкам (client bash → relay, не в async window)
- Stale-манифест внутри Turn'а: имена выхвачены на старте Turn, disconnect → tool-not-available

---

## 2. Архитектура (D-85)

### ArchUnit (`ArchitectureRulesTest.java:104, 211–225`)

```
execution mayOnlyAccessLayers(session, identity, task, workflow, intelligence, mcp)
// без relay
```

- SPI `ClientToolBridge` в `execution`
- Реализация в `relay`
- Негативный тест: `relayViolationIsCaught` (execution → relay запрещена)

### Fix noCycles scanner (`ArchitectureRulesTest.java:84–87`)

```java
private static final JavaClasses DOMAIN_CLASSES = new ClassFileImporter()
        .importPaths(Paths.get("target", "classes"))
        .that(JavaClass.Predicates.resideInAnyPackage(
                BASE + ".api..", BASE + ".execution..", /* ... */ BASE + ".relay.."));
```

- Исправлен false positive (test fixture → pinned to target/classes)

---

## 3. Тестирование

### Unit-тесты реестра (`ClientToolRegistryTest.java:48–187`)

| Тест | Покрытие |
|------|----------|
| `clientSessionIsResolvedByParentChainOnly()` | parent-chain resolve |
| `resolveAndManifestWalkParentChain()` | visibility по цепочке |
| `invokeRoutesToolCallAndReturnsClientResult()` | успешный вызов |
| `invokeRejectsInvalidArgsWithParamsSchemaWithoutDispatching()` | params-schema validation |
| `invokeWithoutToolsetIsToolNotAvailable()` | SERVER-toolset |
| `invokeTimesOutWithoutClientResult()` | tool-call-timeout |
| `firstFinalResultWinsAndLateResultIsIgnored()` | идемпотентность |
| `disconnectDropsOverlayAndLosesInFlightCall()` | disconnect → LOST |
| `detachIsCasByIdentitySoTakeoverOverlaySurvives()` | CAS по identity |

### Интеграционные (`ClientToolTurnWireMockTest.java:38–113`)

| Тест | Покрытие |
|------|----------|
| `hallucinatedNativeToolInClientSessionIsNotRoutedToServer()` | bash → tool-not-available в CLIENT |
| `declaredClientToolIsRoutedToRelay()` | маршрутизация overlay |
| `invalidClientArgsEndAsParamsSchema()` | params-schema |

---

## 4. AGENTS.md соответствие

| Правило | Проверка |
|---------|----------|
| Один инстанс на VM, без энтерпрайз-раздутия | ✓ in-memory overlay, нет multi-instance |
| Все числовые параметры — конфиг | ✓ `harness.relay.tool-call-timeout` |
| Каждая сущность — сценарий необходимости | ✓ client-tool-bridge для roaming |
| Design-решения в `decisions.md` | ✓ D-80/D-81/D-84/D-85 зафиксированы |

---

## 5. Замечания

**Stale-манифест внутри Turn'а:** имена клиентских инструментов выхвачены на старте Turn. Если disconnect внутри Turn'а — вызовы → tool-not-available. Это задокументировано и принято.

**Client bash:** клиент может декларировать bash через MCP-сервер, но в CLIENT-toolset нативные файловые не резолвятся (гейт в executeToolCall). Это соответствует D-84.

---

## Итог

**Статус:** Принято  
**Аппрув Mercury-2.5:** ✓

Batch V корректен. D-80/D-81/D-84/D-85 полностью реализованы, тесты покрывают ключевые сценарии, ArchUnit подтверждает границы.
