package se.rocketscien.harness.relay;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import se.rocketscien.harness.config.RelayProperties;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionKind;
import se.rocketscien.harness.session.SessionStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WS-обработчик релея {@code /api/v1/relay} (api-contracts §5, D-83): handshake-стейт-машина
 * ({@code hello} → {@code welcome}; фрейм до hello или чужая версия — close 4403), регистрация
 * на FREE-сессии с декларацией инструментов (валидация сессии/дублей, takeover через реестр),
 * heartbeat (сервер шлёт {@code ping}, разрыв по {@code 2 × heartbeat-interval} без {@code pong})
 * и MDC-логирование регистрации/разрыва (D-77).
 *
 * <p>Маршрутизация {@code tool.call}/{@code tool.result} и runtime-оверлей инструментов —
 * пачка V; здесь контракт соединения и жизненный цикл сессии релея.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RelayWebSocketHandler extends TextWebSocketHandler {

    static final int PROTOCOL_VERSION = 1;
    private static final String ATTR_SESSION = "relay.session";

    private final SessionStore sessionStore;
    private final RelayConnectionRegistry registry;
    private final RelayProperties relayProperties;
    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "relay-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        if (Boolean.TRUE.equals(session.getAttributes().get(RelayHandshakeInterceptor.AUTH_FAILED_ATTRIBUTE))) {
            close(session, RelayCloseCodes.UNAUTHENTICATED, "unauthenticated");
            return;
        }
        String principal = (String) session.getAttributes().get(RelayHandshakeInterceptor.PRINCIPAL_ATTRIBUTE);
        if (principal == null) {
            close(session, RelayCloseCodes.UNAUTHENTICATED, "unauthenticated");
            return;
        }
        WebSocketRelayConnection connection = new WebSocketRelayConnection(
                session, principal,
                (int) relayProperties.sendTimeLimit().toMillis(),
                (int) relayProperties.bufferSizeLimit().toBytes());
        RelaySession state = new RelaySession(principal, connection);
        session.getAttributes().put(ATTR_SESSION, state);
        long interval = relayProperties.heartbeatInterval().toMillis();
        state.heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                () -> heartbeat(session, state), interval, interval, TimeUnit.MILLISECONDS);
        log.info("Реле: соединение открыто — principal={}", principal);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        RelaySession state = stateOf(session);
        if (state == null) {
            return;
        }
        JsonNode frame;
        try {
            frame = objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            close(session, RelayCloseCodes.PROTOCOL_ERROR, "protocol");
            return;
        }
        String type = frame.path("type").asString(null);
        if (state.phase == Phase.AWAITING_HELLO) {
            if ("hello".equals(type)) {
                handleHello(session, state, frame);
            } else {
                close(session, RelayCloseCodes.PROTOCOL_ERROR, "protocol");
            }
            return;
        }
        switch (type == null ? "" : type) {
            case "register" -> handleRegister(session, state, frame);
            case "pong" -> state.lastPong.set(System.currentTimeMillis());
            default -> log.debug("Реле: неизвестный фрейм '{}' (principal={})", type, state.principal);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        RelaySession state = stateOf(session);
        if (state == null) {
            return;
        }
        cleanup(state);
        log.info("Реле: соединение закрыто — principal={}, sessionId={}, status={}",
                state.principal, state.registeredSessionId, status);
    }

    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
    }

    private void handleHello(WebSocketSession session, RelaySession state, JsonNode frame) {
        int protocol = frame.path("protocol").asInt(-1);
        if (protocol != PROTOCOL_VERSION) {
            close(session, RelayCloseCodes.PROTOCOL_ERROR, "protocol-mismatch");
            return;
        }
        state.phase = Phase.READY;
        send(state, frame("welcome", Map.of("protocol", PROTOCOL_VERSION)));
    }

    private void handleRegister(WebSocketSession session, RelaySession state, JsonNode frame) {
        UUID sessionId = parseUuid(frame.path("sessionId").asString(null));
        if (sessionId == null) {
            close(session, RelayCloseCodes.PROTOCOL_ERROR, "protocol");
            return;
        }
        Optional<Session> target = sessionStore.findSession(sessionId);
        if (target.isEmpty()) {
            reject(state, session, "session-not-found", "Сессия не найдена");
            return;
        }
        if (target.get().kind() != SessionKind.FREE) {
            reject(state, session, "wrong-session-kind", "Релей доступен только FREE-сессиям");
            return;
        }
        JsonNode tools = frame.path("client").path("tools");
        if (hasDuplicateToolNames(tools)) {
            reject(state, session, "duplicate-tool-name", "Дублирующиеся имена инструментов");
            return;
        }
        if (registry.register(sessionId, state.connection) == RelayConnectionRegistry.RegisterOutcome.OCCUPIED) {
            reject(state, session, "workspace-occupied", "Сессия занята другим пользователем");
            return;
        }
        state.registeredSessionId = sessionId;
        logRegistration(sessionId, state.principal, tools.size());
        send(state, frame("registered", Map.of("sessionId", sessionId.toString())));
    }

    private void heartbeat(WebSocketSession session, RelaySession state) {
        long interval = relayProperties.heartbeatInterval().toMillis();
        if (System.currentTimeMillis() - state.lastPong.get() > 2 * interval) {
            log.info("Реле: heartbeat-таймаут — разрыв (principal={}, sessionId={})",
                    state.principal, state.registeredSessionId);
            close(session, CloseStatus.GOING_AWAY.getCode(), "heartbeat-timeout");
            return;
        }
        send(state, frame("ping", Map.of()));
    }

    private void reject(RelaySession state, WebSocketSession session, String code, String message) {
        send(state, frame("error", Map.of("code", code, "message", message)));
        close(session, RelayCloseCodes.CONFLICT, code);
    }

    private void cleanup(RelaySession state) {
        if (!state.closed.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> heartbeat = state.heartbeat;
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        if (state.registeredSessionId != null) {
            registry.unregister(state.registeredSessionId, state.connection);
        }
    }

    private void logRegistration(UUID sessionId, String principal, int toolCount) {
        MDC.put("sessionId", sessionId.toString());
        MDC.put("principal", principal);
        try {
            log.info("Реле: регистрация на сессии — инструментов={}", toolCount);
        } finally {
            MDC.remove("sessionId");
            MDC.remove("principal");
        }
    }

    private void send(RelaySession state, String frame) {
        state.connection.sendText(frame);
    }

    private String frame(String type, Map<String, Object> fields) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.putAll(fields);
        return objectMapper.writeValueAsString(frame);
    }

    private void close(WebSocketSession session, int statusCode, String reason) {
        try {
            if (session.isOpen()) {
                session.close(new CloseStatus(statusCode, reason));
            }
        } catch (IOException e) {
            log.debug("Закрытие WS-сессии релея не удалось: {}", e.getMessage());
        }
    }

    private boolean hasDuplicateToolNames(JsonNode tools) {
        if (tools == null || !tools.isArray()) {
            return false;
        }
        Set<String> names = new HashSet<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asString(null);
            if (name != null && !names.add(name)) {
                return true;
            }
        }
        return false;
    }

    private UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private RelaySession stateOf(WebSocketSession session) {
        return (RelaySession) session.getAttributes().get(ATTR_SESSION);
    }

    private enum Phase {
        AWAITING_HELLO,
        READY
    }

    private static final class RelaySession {

        private final String principal;
        private final RelayConnection connection;
        private final AtomicLong lastPong = new AtomicLong(System.currentTimeMillis());
        private final AtomicBoolean closed = new AtomicBoolean();
        private Phase phase = Phase.AWAITING_HELLO;
        private UUID registeredSessionId;
        private ScheduledFuture<?> heartbeat;

        private RelaySession(String principal, RelayConnection connection) {
            this.principal = principal;
            this.connection = connection;
        }
    }
}
