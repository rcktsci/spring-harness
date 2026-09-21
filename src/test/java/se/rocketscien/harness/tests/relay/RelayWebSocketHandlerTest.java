package se.rocketscien.harness.tests.relay;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.unit.DataSize;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import se.rocketscien.harness.config.RelayProperties;
import se.rocketscien.harness.relay.ClientToolAdapter;
import se.rocketscien.harness.relay.ClientToolRegistry;
import se.rocketscien.harness.relay.RelayCloseCodes;
import se.rocketscien.harness.relay.RelayConnection;
import se.rocketscien.harness.relay.RelayConnectionRegistry;
import se.rocketscien.harness.relay.RelayHandshakeInterceptor;
import se.rocketscien.harness.relay.RelayWebSocketHandler;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionKind;
import se.rocketscien.harness.session.SessionStore;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M4 T.3: unit-тесты handshake-стейт-машины и регистрации релея (api-contracts §5).
 * Транспорт подменяется: {@code WebSocketSession} — Mockito-мок (для verify close),
 * {@link RelayConnection} — записывающая заглушка через seam {@code createConnection}.
 */
class RelayWebSocketHandlerTest {

    private final RelayConnectionRegistry registry = new RelayConnectionRegistry();
    private final SessionStore sessionStore = mock(SessionStore.class);
    private final RecordingConnection connection = new RecordingConnection("alice");

    @Test
    void closesUnauthenticatedWhenHandshakeFailed() throws IOException {
        TestHandler handler = handler(Duration.ofHours(1));
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(RelayHandshakeInterceptor.AUTH_FAILED_ATTRIBUTE, true);
        WebSocketSession session = sessionMock(attributes);

        handler.afterConnectionEstablished(session);

        verifyClosed(session, RelayCloseCodes.UNAUTHENTICATED);
    }

    @Test
    void closesProtocolErrorOnFrameBeforeHello() throws IOException {
        TestHandler handler = handler(Duration.ofHours(1));
        WebSocketSession session = authenticatedSession(handler);

        handler.feed(session, "{\"type\":\"register\",\"sessionId\":\"" + UUID.randomUUID() + "\"}");

        verifyClosed(session, RelayCloseCodes.PROTOCOL_ERROR);
    }

    @Test
    void closesProtocolErrorOnUnknownProtocolVersion() throws IOException {
        TestHandler handler = handler(Duration.ofHours(1));
        WebSocketSession session = authenticatedSession(handler);

        handler.feed(session, "{\"type\":\"hello\",\"protocol\":2}");

        verifyClosed(session, RelayCloseCodes.PROTOCOL_ERROR);
    }

    @Test
    void answersWelcomeToHello() {
        TestHandler handler = handler(Duration.ofHours(1));
        WebSocketSession session = authenticatedSession(handler);

        handler.feed(session, "{\"type\":\"hello\",\"protocol\":1}");

        assertThat(connection.sentContains("\"type\":\"welcome\"")).isTrue();
        assertThat(connection.sentContains("\"protocol\":1")).isTrue();
    }

    @Test
    void rejectsUnknownSession() throws IOException {
        TestHandler handler = handler(Duration.ofHours(1));
        WebSocketSession session = established(handler);
        UUID sessionId = UUID.randomUUID();
        when(sessionStore.findSession(sessionId)).thenReturn(Optional.empty());

        register(handler, session, sessionId, "[]");

        assertThat(connection.sentContains("session-not-found")).isTrue();
        verifyClosed(session, RelayCloseCodes.CONFLICT);
        assertThat(registry.findForSession(sessionId)).isEmpty();
    }

    @Test
    void rejectsStateSession() throws IOException {
        TestHandler handler = handler(Duration.ofHours(1));
        WebSocketSession session = established(handler);
        UUID sessionId = UUID.randomUUID();
        when(sessionStore.findSession(sessionId)).thenReturn(Optional.of(target(sessionId, SessionKind.STATE, null)));

        register(handler, session, sessionId, "[]");

        assertThat(connection.sentContains("wrong-session-kind")).isTrue();
        verifyClosed(session, RelayCloseCodes.CONFLICT);
    }

    @Test
    void rejectsChildSessionWithoutRegisteredParent() throws IOException {
        TestHandler handler = handler(Duration.ofHours(1));
        WebSocketSession session = established(handler);
        UUID sessionId = UUID.randomUUID();
        when(sessionStore.findSession(sessionId))
                .thenReturn(Optional.of(target(sessionId, SessionKind.FREE, UUID.randomUUID())));

        register(handler, session, sessionId, "[]");

        assertThat(connection.sentContains("wrong-session-kind")).isTrue();
        verifyClosed(session, RelayCloseCodes.CONFLICT);
        assertThat(registry.findForSession(sessionId)).isEmpty();
    }

    @Test
    void rejectsDuplicateToolNames() throws IOException {
        TestHandler handler = handler(Duration.ofHours(1));
        WebSocketSession session = established(handler);
        UUID sessionId = UUID.randomUUID();
        when(sessionStore.findSession(sessionId)).thenReturn(Optional.of(target(sessionId, SessionKind.FREE, null)));

        register(handler, session, sessionId, "[{\"name\":\"a\"},{\"name\":\"a\"}]");

        assertThat(connection.sentContains("duplicate-tool-name")).isTrue();
        verifyClosed(session, RelayCloseCodes.CONFLICT);
    }

    @Test
    void rejectsWhenSessionOccupiedByAnotherPrincipal() throws IOException {
        TestHandler handler = handler(Duration.ofHours(1));
        WebSocketSession session = established(handler);
        UUID sessionId = UUID.randomUUID();
        when(sessionStore.findSession(sessionId)).thenReturn(Optional.of(target(sessionId, SessionKind.FREE, null)));
        registry.register(sessionId, new RecordingConnection("bob"));

        register(handler, session, sessionId, "[]");

        assertThat(connection.sentContains("workspace-occupied")).isTrue();
        verifyClosed(session, RelayCloseCodes.CONFLICT);
    }

    @Test
    void registersOnFreeRootSession() {
        TestHandler handler = handler(Duration.ofHours(1));
        WebSocketSession session = established(handler);
        UUID sessionId = UUID.randomUUID();
        when(sessionStore.findSession(sessionId)).thenReturn(Optional.of(target(sessionId, SessionKind.FREE, null)));

        register(handler, session, sessionId, "[{\"name\":\"jira.list_issues\"}]");

        assertThat(connection.sentContains("\"type\":\"registered\"")).isTrue();
        assertThat(registry.findForSession(sessionId)).contains(connection);
    }

    @Test
    void reRegisterOnAnotherSessionDropsStaleKey() {
        TestHandler handler = handler(Duration.ofHours(1));
        WebSocketSession session = established(handler);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(sessionStore.findSession(first)).thenReturn(Optional.of(target(first, SessionKind.FREE, null)));
        when(sessionStore.findSession(second)).thenReturn(Optional.of(target(second, SessionKind.FREE, null)));

        register(handler, session, first, "[]");
        register(handler, session, second, "[]");

        assertThat(registry.findForSession(first)).isEmpty();
        assertThat(registry.findForSession(second)).contains(connection);
    }

    @Test
    void heartbeatTimeoutClosesAndUnregisters() {
        TestHandler handler = handler(Duration.ofMillis(50));
        WebSocketSession session = established(handler);
        UUID sessionId = UUID.randomUUID();
        when(sessionStore.findSession(sessionId)).thenReturn(Optional.of(target(sessionId, SessionKind.FREE, null)));
        register(handler, session, sessionId, "[]");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ArgumentCaptor<CloseStatus> captor = ArgumentCaptor.forClass(CloseStatus.class);
            verify(session).close(captor.capture());
            assertThat(captor.getValue().getCode()).isEqualTo(CloseStatus.GOING_AWAY.getCode());
        });
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(registry.findForSession(sessionId)).isEmpty());
    }

    private TestHandler handler(Duration heartbeatInterval) {
        RelayProperties properties = new RelayProperties(heartbeatInterval, Duration.ofMinutes(5),
                Duration.ofSeconds(10), DataSize.ofKilobytes(512), List.of("*"));
        return new TestHandler(sessionStore, registry, properties, connection);
    }

    private WebSocketSession authenticatedSession(TestHandler handler) {
        WebSocketSession session = sessionMock(authenticated());
        handler.afterConnectionEstablished(session);
        return session;
    }

    /** Аутентификация + успешный handshake ({@code hello}) — сессия готова к {@code register}. */
    private WebSocketSession established(TestHandler handler) {
        WebSocketSession session = authenticatedSession(handler);
        handler.feed(session, "{\"type\":\"hello\",\"protocol\":1}");
        return session;
    }

    private void register(TestHandler handler, WebSocketSession session, UUID sessionId, String toolsJson) {
        handler.feed(session, "{\"type\":\"register\",\"sessionId\":\"" + sessionId
                + "\",\"client\":{\"version\":\"1\",\"tools\":" + toolsJson + "}}");
    }

    private static Session target(UUID sessionId, SessionKind kind, UUID parentSessionId) {
        return new Session(sessionId, kind, "title", UUID.randomUUID(), null, null, UUID.randomUUID(),
                parentSessionId, parentSessionId == null ? 0 : 1, false, 0, 0, null,
                Instant.now(), Instant.now());
    }

    private static Map<String, Object> authenticated() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(RelayHandshakeInterceptor.PRINCIPAL_ATTRIBUTE, "alice");
        return attributes;
    }

    private static WebSocketSession sessionMock(Map<String, Object> attributes) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private static void verifyClosed(WebSocketSession session, int expectedCode) throws IOException {
        ArgumentCaptor<CloseStatus> captor = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(captor.capture());
        assertThat(captor.getValue().getCode()).isEqualTo(expectedCode);
    }

    /** Seam: обработчик создаёт наше записывающее соединение вместо реального транспорта. */
    private static final class TestHandler extends RelayWebSocketHandler {

        private final RelayConnection connection;

        private TestHandler(SessionStore sessionStore, RelayConnectionRegistry registry,
                            RelayProperties properties, RelayConnection connection) {
            super(sessionStore, registry, new ClientToolRegistry(sessionStore, registry,
                            new ClientToolAdapter(JsonMapper.builder().build()), properties),
                    properties, JsonMapper.builder().build());
            this.connection = connection;
        }

        @Override
        protected RelayConnection createConnection(WebSocketSession session, String principal) {
            return connection;
        }

        private void feed(WebSocketSession session, String payload) {
            handleTextMessage(session, new TextMessage(payload));
        }
    }

    private static final class RecordingConnection implements RelayConnection {

        private final String principal;
        private final List<String> sent = new CopyOnWriteArrayList<>();

        private RecordingConnection(String principal) {
            this.principal = principal;
        }

        @Override
        public String principal() {
            return principal;
        }

        @Override
        public void sendText(String frame) {
            sent.add(frame);
        }

        @Override
        public void close(int statusCode, String reason) {
        }

        private boolean sentContains(String needle) {
            return sent.stream().anyMatch(frame -> frame.contains(needle));
        }
    }
}
