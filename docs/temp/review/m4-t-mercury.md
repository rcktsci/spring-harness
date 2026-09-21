# M4 Batch T Review — Mercury-2.5

**Дата:** 2026-09-22  
**Ревьюер:** Mercury-2.5  
**Коммит:** `14ce35c` (relay infra: websocket starter, ArchUnit, registry, handler)

---

## Резюме

Batch T реализует инфраструктуру релея согласно ADR D-78/D-83/D-85. Архитектурные границы чисты: ArchUnit проверяет `relay → {execution, session}` и запрещает `execution ↛ relay` (только SPI `ClientToolBridge`). Реестр соединений — in-memory с takeover/identity-CAS. Конфиг вынесен в `RelayProperties`/`WorkspaceDownloadProperties`. 502 теста зелёных. AGENTS.md соблюден.

---

## 1. Архитектурная чистота relay-слоя

### ArchUnit (`ArchitectureRulesTest.java:112`)

```
relay mayOnlyAccessLayers(execution, session)
```

- **Допустимые зависимости:** `execution` (SPI `ClientToolBridge`), `session` (валидация при регистрации)
- **Запрещено:** `execution ↛ relay` — доменный слой не зависит от реализации релея

### Негативный тест (`ArchitectureRulesTest.java:211–225`)

```java
void relayViolationIsCaught() {
    // execution → relay запрещена, только SPI ClientToolBridge
}
```

Фикстуры:
- `ArchUnitRelayBridgeViolator` (execution) → ссылается на relay
- `ArchUnitRelayFixture` (relay) → пустой класс для нарушения
- Тест подтверждает: нарушение роняет правило с описанием

### SPI (`ClientToolBridge.java:1–30`)

```java
public interface ClientToolBridge {
    boolean isClientSession(UUID sessionId);
    Optional<ToolDescriptor> resolveTool(UUID sessionId, String toolName);
    ToolResult invoke(UUID sessionId, String callId, String toolName, JsonNode args);
}
```

- Объявлен в `execution`
- Реализация в `relay` — связывание через Spring

---

## 2. ADR-соответствие

| ADR | Реализация | Статус |
|-----|------------|--------|
| **D-78** | `RelayConnectionRegistry` (in-memory `ConcurrentHashMap<sessionId, Connection>`) | ✓ |
| | `register`: takeover при том же principal, 4409 `superseded` | ✓ |
| | `register`: `workspace-occupied` при ином principal | ✓ |
| | `unregister`: CAS `remove(key, expectedConnection)` | ✓ |
| **D-83** | `RelayWebSocketHandler` (handshake state machine: 4401, hello/welcome, 4403) | ✓ |
| | `RelayHandshakeInterceptor` (Bearer-JWT → SSO gate) | ✓ |
| | `spring-boot-starter-websocket` в pom | ✓ |
| **D-85** | SPI `ClientToolBridge` в `execution` | ✓ |
| | ArchUnit: `execution ↛ relay` | ✓ |

---

## 3. Конфигурация

`RelayProperties` (`harness.relay.*`):
- `heartbeat-interval: 15s`
- `tool-call-timeout: 5m`
- `send-time-limit`, `buffer-size-limit`

`WorkspaceDownloadProperties` (`harness.workspace.download.*`):
- `max-bytes: 10MB`
- `allow-extensions: [.txt, .log, .log, .xml, .csv]`

Проверка биндинга: `ConfigPropertiesBindingTest`.

---

## 4. Тестирование

### Unit-тесты реестра (`RelayConnectionRegistryTest.java`)

| Тест | Покрытие |
|------|----------|
| `registersFreeSession()` | базовая регистрация |
| `reRegisterSameConnectionIsIdempotent()` | идемпотентность |
| `takeoverBySamePrincipalClosesOldConnection()` | takeover, 4409 superseded |
| `otherPrincipalIsRejectedAndKeepsExisting()` | workspace-occupied |
| `unregisterIsCasByIdentity()` | CAS-удаление |

---

## 5. AGENTS.md соответствие

| Правило | Проверка |
|---------|----------|
| Один инстанс на VM, без энтерпрайз-раздутия | ✓ in-memory registry, CAS, нет распределённой блокировки |
| Все числовые параметры — конфиг | ✓ `harness.relay.*`, `harness.workspace.download.*` |
| Каждая сущность — сценарий необходимости | ✓ relay для roaming, tool-bridge для клиентских инструментов |

---

## Итог

**Статус:** Принято  
**Аппрув Mercury-2.5:** ✓

Batch T корректен. Архитектурные границы подтверждены, ADR D-78/D-83/D-85 реализованы, конфиг вынесен, тесты покрывают ключевые сценарии.
