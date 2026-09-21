# M4 Batch W Review — Mercury-2.5

**Дата:** 2026-09-22  
**Ревьюер:** Mercury-2.5  
**Коммит:** `b4a8bb9` (disconnect/cancel/restart-scan)

---

## Резюме

Batch W реализует три ключевых сценария M4: (5.1) disconnect → LOST для in-flight вызовов, (5.2) tool.cancel с каскадом поддерева, (5.3) restart-scan для клиентских и серверных вызовов. Реализация соответствует D-77/D-81. 557 тестов зелёных (5 новых).

---

## 1. D-77/D-81 соответствие

### D-77: WS-handshake аудит — логи, не БД

`ClientToolRegistry.java:102–110`:

```java
public void detach(UUID sessionId, RelayConnection connection) {
    overlays.computeIfPresent(sessionId, (key, overlay) ->
            overlay.connection() == connection ? null : overlay);
    int[] closed = {0};
    pending.values().removeIf(pendingCall -> {
        if (pendingCall.connection() != connection) return false;
        pendingCall.future().complete(
                ToolResult.lost(pendingCall.callId(), pendingCall.tool(), LOST_ON_DISCONNECT));
        closed[0]++;
        return true;
    });
    if (closed[0] > 0) {
        log.info("Реле: разрыв соединения сессии {} — in-flight вызовов закрыто LOST: {}",
                sessionId, closed[0]);
    }
}
```

- Логирование с MDC (sessionId, principal через RelayConnection)
- БД не используется (D-77)

### D-81: Идемпотентность по callId, Turn-поток пишет журнал

`ClientToolRegistry.java:112–120`:

```java
public void completeResult(String callId, String output, Integer exitCode) {
    pruneTombstones();
    PendingCall pendingCall = pending.get(callId);
    if (pendingCall == null) {
        log.debug("Реле: tool.result по неизвестному/завершённому callId {} — игнор", callId);
        return;
    }
    pendingCall.future().complete(toResult(pendingCall, output, exitCode));
    pendingCall.markCompleted();
}
```

- WS-поток только complete'ит future
- Turn-поток пишет журнал (D-81)
- Tombstones прореживаются по tool-call-timeout (pruneTombstones)

---

## 2. Новая функциональность (Batch W)

### 5.1: Disconnect → LOST для in-flight вызовов

Уже рассмотрено выше (detach).

### 5.2: tool.cancel с каскадом поддерева

`ClientToolBridge.java:28–31`:

```java
void cancel(UUID sessionId, String callId);
```

`ClientToolRegistry.java:125–138`:

```java
public void cancel(UUID sessionId, String callId) {
    PendingCall pendingCall = pending.get(callId);
    if (pendingCall == null) {
        cancelledBeforeDispatch.add(callId);
        return;
    }
    dispatchCancel(pendingCall);
}

private void dispatchCancel(PendingCall pendingCall) {
    pendingCall.connection().sendText(adapter.toolCancelFrame(pendingCall.callId()));
    pendingCall.future().complete(
            ToolResult.cancelled(pendingCall.callId(), pendingCall.tool(), CANCELLED_REASON));
    pendingCall.markCompleted();
}
```

**AgentTurnEngine:** (289–307):

```java
// W (5.2): stop/каскад прерывает in-flight клиентский вызов
AutoCloseable interruptor = cancellation.registerInterrupt(
        () -> clientToolBridge.cancel(session.id(), pendingCall.callId()));
try {
    return clientToolBridge.invoke(...);
} finally {
    interruptor.close();
}
```

**ClientToolAdapter.java:55–60**:

```java
public String toolCancelFrame(String callId) {
    Map<String, Object> frame = new LinkedHashMap<>();
    frame.put("type", "tool.cancel");
    frame.put("callId", callId);
    return objectMapper.writeValueAsString(frame);
}
```

### 5.3: Restart-scan для клиентских вызовов

`RestartScanTest.java:62–78`:

```java
@Test
void pendingClientToolCallClosedWithLostOnRestart() {
    // W (5.3): клиентский вызов идёт тем же журнальным паттерном TOOL_CALL/TOOL_RESULT —
    // рестарт-скан (in-memory реестр соединений пуст) закрывает его LOST «перезапуск».
    Session session = newSession();
    sessionStore.appendEvent(session.id(), MessageKind.TOOL_CALL, null, Map.of(
            "callId", "call-client-1", "toolCallId", "call-llm-client", "tool", "jira.list_issues",
            "arguments", Map.of("project", "ABC")));

    restartScanRunner.restartScan();

    assertThat(journalField(session.id(), "TOOL_RESULT", "status")).isEqualTo("LOST");
    assertThat(journalField(session.id(), "TOOL_RESULT", "output")).contains("перезапуск");
    assertThat(journalField(session.id(), "TOOL_RESULT", "tool")).isEqualTo("jira.list_issues");
}
```

- Клиентские вызовы закрываются тем же путём (journal pattern)
- Restart-scan tool-agnostic

---

## 3. Тестирование

### Unit-тесты (`ClientToolRegistryTest.java:203–245`)

| Тест | Покрытие |
|------|----------|
| `cancelCompletesInFlightCallAsCancelledAndNotifiesClient()` | cancel → CANCELLED, tool.cancel frame отправлен |
| `cancelBeforeDispatchReturnsCancelledWithoutToolCall()` | race: cancel до invoke → CANCELLED без tool.call |

### Интеграционные (`RestartScanTest.java:62–78`)

| Тест | Покрытие |
|------|----------|
| `pendingClientToolCallClosedWithLostOnRestart()` | restart-scan → client tool LOST |

---

## 4. AGENTS.md соответствие

| Правило | Проверка |
|---------|----------|
| Один инстанс на VM, без энтерпрайз-раздутия | ✓ in-memory реестр, CAS, нет multi-instance |
| Все числовые параметры — конфиг | ✓ `harness.relay.tool-call-timeout` |
| Каждая сущность — сценарий необходимости | ✓ disconnect/cancel/restart — core сценарии roaming |

---

## 5. Замечания к AgentTurnEngine

**5.2: регистрация interruptor:** `AgentTurnEngine.java:289–307`:

```java
AutoCloseable interruptor = cancellation.registerInterrupt(
        () -> clientToolBridge.cancel(session.id(), pendingCall.callId()));
try {
    return clientToolBridge.invoke(...);
} finally {
    interruptor.close();
}
```

Правильно: interruptor регистрируется в TurnCancellation, что обеспечивает каскад отмены по поддереву через per-session TurnCancellation.

**Гонка cancel/subscribe:** `ClientToolRegistry.java:141–168`:

```java
// stop пришёл до публикации вызова — CANCELLED без tool.call
if (cancelledBeforeDispatch.remove(callId)) {
    pending.remove(callId);
    return ToolResult.cancelled(callId, toolName, CANCELLED_REASON);
}
// cancel выиграл гонку с публикацией
if (future.isDone()) {
    pending.remove(callId);
    return future.getNow(ToolResult.cancelled(callId, toolName, CANCELLED_REASON));
}
```

Правильно: обрабатываются обе гонки (до отправки tool.call и после, но до ответа).

---

## 6. Итог

**Статус:** Принято  
**Аппрув Mercury-2.5:** ✓

Batch W корректен. D-77/D-81 соблюдены, cancel/restart-scan реализованы согласно design, тесты покрывают ключевые сценарии.
