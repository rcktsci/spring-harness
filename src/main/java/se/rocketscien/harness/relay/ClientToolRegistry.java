package se.rocketscien.harness.relay;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.common.jsonschema.JsonSchemaError;
import se.rocketscien.harness.config.RelayProperties;
import se.rocketscien.harness.execution.ClientToolBridge;
import se.rocketscien.harness.execution.ToolDescriptor;
import se.rocketscien.harness.execution.ToolResult;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionStore;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime-оверлей клиентских инструментов (M4, D-80/D-84), реализует SPI
 * {@link ClientToolBridge}. Оверлей живёт на время активного WS-соединения: {@code register}
 * наполняет его, disconnect — очищает; в БД не персистится.
 *
 * <p>Видимость — по parent-цепочке: соединение зарегистрировано на FREE root-сессии, sub-сессии
 * ({@code spawn_subagent}) поднимаются к ней по {@code parentSessionId} и видят тот же оверлей;
 * task-сессии (STATE, parent NULL) связи не имеют → toolset SERVER. Резолв live — на момент
 * вызова (манифест собирается на Turn).</p>
 *
 * <p>Маршрутизация: валидация args по inputSchema (D-58) → {@code tool.call} в соединение →
 * блокирующее ожидание на {@link CompletableFuture} с {@code harness.relay.tool-call-timeout};
 * завершение — из WS-потока ({@link #completeResult}); журнал пишет Turn-поток (D-81). Финальный
 * результат один на callId (tombstone до конца соединения), поздний {@code tool.result}
 * игнорируется.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClientToolRegistry implements ClientToolBridge {

    private static final String TOOL_NOT_AVAILABLE = "tool-not-available";
    private static final String LOST_ON_DISCONNECT = "потеряно при отключении исполнителя";

    private final SessionStore sessionStore;
    private final RelayConnectionRegistry connections;
    private final ClientToolAdapter adapter;
    private final RelayProperties relayProperties;

    /** Оверлей сессии (ключ — сессия регистрации, обычно FREE root): соединение + декларация. */
    private final Map<UUID, Overlay> overlays = new ConcurrentHashMap<>();

    /** In-flight вызовы (callId → future); запись живёт до disconnect как tombstone (D-81). */
    private final Map<String, PendingCall> pending = new ConcurrentHashMap<>();

    /** Наполнение оверлея при успешной регистрации (lifecycle = соединение, D-80). */
    public void attach(UUID sessionId, RelayConnection connection, List<ToolDescriptor> tools) {
        overlays.put(sessionId, new Overlay(connection, List.copyOf(tools)));
        log.info("Реле: оверлей сессии {} наполнен ({} инструментов)", sessionId,
                tools == null ? 0 : tools.size());
    }

    /**
     * Очистка оверлея при разрыве: CAS по identity соединения (takeover новым соединением не
     * затирается); in-flight вызовы этого соединения закрываются синтетическим LOST (D-80).
     */
    public void detach(UUID sessionId, RelayConnection connection) {
        overlays.computeIfPresent(sessionId, (key, overlay) ->
                overlay.connection() == connection ? null : overlay);
        pending.values().removeIf(pendingCall -> {
            if (pendingCall.connection() != connection) {
                return false;
            }
            pendingCall.future().complete(
                    ToolResult.lost(pendingCall.callId(), pendingCall.tool(), LOST_ON_DISCONNECT));
            return true;
        });
    }

    /** Финальный результат клиентского вызова из WS-потока; повторный результат — no-op (D-81). */
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

    @Override
    public boolean isClientSession(UUID sessionId) {
        return resolveSession(sessionId).isPresent();
    }

    @Override
    public List<ToolCallback> manifest(UUID sessionId) {
        return resolveSession(sessionId)
                .map(overlays::get)
                .map(overlay -> overlay.tools().stream().map(adapter::declaration).toList())
                .orElseGet(List::of);
    }

    @Override
    public Optional<ToolDescriptor> resolve(UUID sessionId, String toolName) {
        return resolveSession(sessionId).flatMap(rootSessionId -> tool(rootSessionId, toolName));
    }

    @Override
    public ToolResult invoke(UUID sessionId, String callId, String toolName, Map<String, Object> args) {
        pruneTombstones();
        UUID rootSessionId = resolveSession(sessionId).orElse(null);
        if (rootSessionId == null) {
            return ToolResult.error(callId, toolName, TOOL_NOT_AVAILABLE);
        }
        ToolDescriptor descriptor = tool(rootSessionId, toolName).orElse(null);
        if (descriptor == null) {
            return ToolResult.error(callId, toolName, TOOL_NOT_AVAILABLE);
        }
        RelayConnection connection = connections.findForSession(rootSessionId).orElse(null);
        if (connection == null) {
            return ToolResult.error(callId, toolName, TOOL_NOT_AVAILABLE);
        }
        List<JsonSchemaError> errors = adapter.validate(descriptor, args);
        if (!errors.isEmpty()) {
            return ToolResult.error(callId, toolName, "params-schema: " + formatErrors(errors));
        }
        CompletableFuture<ToolResult> future = new CompletableFuture<>();
        PendingCall pendingCall = new PendingCall(callId, rootSessionId, connection, toolName, future);
        pending.put(callId, pendingCall);
        // V-7: недоставленный кадр (закрытое/переполненное соединение) — немедленный LOST, не ждём timeout.
        if (!connection.sendText(adapter.toolCallFrame(callId, sessionId, toolName, args))) {
            pending.remove(callId);
            return ToolResult.lost(callId, toolName, "не доставлено: соединение закрыто");
        }
        try {
            return future.get(relayProperties.toolCallTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            ToolResult timeout = ToolResult.error(callId, toolName, "tool-timeout");
            // tombstone: поздний tool.result по этому callId станет no-op
            future.complete(timeout);
            pendingCall.markCompleted();
            return timeout;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error(callId, toolName, "interrupted");
        } catch (ExecutionException e) {
            return ToolResult.error(callId, toolName,
                    e.getCause() == null ? "invoke-failed" : e.getCause().getMessage());
        }
    }

    /** Root-сессия оверлея по parent-цепочке (цикл защищён посещёнными узлами). */
    private Optional<UUID> resolveSession(UUID sessionId) {
        Set<UUID> visited = new HashSet<>();
        UUID current = sessionId;
        while (current != null && visited.add(current)) {
            if (overlays.containsKey(current)) {
                return Optional.of(current);
            }
            Session session = sessionStore.findSession(current).orElse(null);
            current = session == null ? null : session.parentSessionId();
        }
        return Optional.empty();
    }

    private Optional<ToolDescriptor> tool(UUID rootSessionId, String toolName) {
        Overlay overlay = overlays.get(rootSessionId);
        if (overlay == null || toolName == null) {
            return Optional.empty();
        }
        return overlay.tools().stream().filter(descriptor -> toolName.equals(descriptor.name())).findFirst();
    }

    /**
     * Клиентский результат (V-2): {@code exitCode} — информативное поле, non-zero ≠ ошибка
     * инструмента (та же семантика, что у native bash — agent-tools §5); статус всегда OK.
     * ERROR оставлен протокольным ошибкам/таймауту/LOST.
     */
    private static ToolResult toResult(PendingCall pendingCall, String output, Integer exitCode) {
        return ToolResult.ok(pendingCall.callId(), pendingCall.tool(), output, exitCode, null, null);
    }

    /** Tombstone (завершённые future) живут не дольше окна {@code tool-call-timeout} (V-5). */
    private void pruneTombstones() {
        long ttl = relayProperties.toolCallTimeout().toMillis();
        long now = System.currentTimeMillis();
        pending.values().removeIf(pendingCall -> pendingCall.future().isDone()
                && pendingCall.completedAt().get() > 0
                && now - pendingCall.completedAt().get() > ttl);
    }

    private static String formatErrors(List<JsonSchemaError> errors) {
        return errors.stream()
                .map(error -> error.pointer() + ": " + error.rule() + " (" + error.message() + ")")
                .reduce((left, right) -> left + "; " + right)
                .orElse("invalid args");
    }

    private record Overlay(RelayConnection connection, List<ToolDescriptor> tools) {
    }

    private record PendingCall(String callId, UUID rootSessionId, RelayConnection connection, String tool,
                               CompletableFuture<ToolResult> future, AtomicLong completedAt) {

        private PendingCall(String callId, UUID rootSessionId, RelayConnection connection, String tool,
                            CompletableFuture<ToolResult> future) {
            this(callId, rootSessionId, connection, tool, future, new AtomicLong());
        }

        private void markCompleted() {
            completedAt.compareAndSet(0L, System.currentTimeMillis());
        }
    }
}
