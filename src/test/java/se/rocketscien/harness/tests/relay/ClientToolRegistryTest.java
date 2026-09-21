package se.rocketscien.harness.tests.relay;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import se.rocketscien.harness.config.RelayProperties;
import se.rocketscien.harness.execution.ToolDescriptor;
import se.rocketscien.harness.execution.ToolResult;
import se.rocketscien.harness.execution.ToolStatus;
import se.rocketscien.harness.relay.ClientToolAdapter;
import se.rocketscien.harness.relay.ClientToolRegistry;
import se.rocketscien.harness.relay.RelayConnectionRegistry;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionKind;
import se.rocketscien.harness.session.SessionStore;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M4 T.5: оверлей клиентских инструментов и маршрутизация (D-80/D-81/D-84) — parent-chain
 * видимость, resolve/manifest, invoke (успех/params-schema/tool-timeout), идемпотентный
 * результат, disconnect (tool-not-available + LOST in-flight), CAS по identity соединения.
 */
class ClientToolRegistryTest {

    private final SessionStore sessionStore = mock(SessionStore.class);
    private final RelayConnectionRegistry connections = new RelayConnectionRegistry();

    private UUID root;
    private UUID child;
    private UUID state;
    private ClientToolRegistry registry;
    private TestRelayConnection connection;

    @BeforeEach
    void setUp() {
        root = UUID.randomUUID();
        child = UUID.randomUUID();
        state = UUID.randomUUID();
        when(sessionStore.findSession(root)).thenReturn(Optional.of(session(root, SessionKind.FREE, null)));
        when(sessionStore.findSession(child)).thenReturn(Optional.of(session(child, SessionKind.FREE, root)));
        when(sessionStore.findSession(state)).thenReturn(Optional.of(session(state, SessionKind.STATE, null)));

        registry = new ClientToolRegistry(sessionStore, connections,
                new ClientToolAdapter(JsonMapper.builder().build()), properties(Duration.ofSeconds(30)));
        connection = new TestRelayConnection("alice", registry);
        connections.register(root, connection);
    }

    @Test
    void clientSessionIsResolvedByParentChainOnly() {
        registry.attach(root, connection, List.of(descriptor()));

        assertThat(registry.isClientSession(root)).isTrue();
        assertThat(registry.isClientSession(child)).isTrue();
        assertThat(registry.isClientSession(state)).isFalse();
        assertThat(registry.isClientSession(UUID.randomUUID())).isFalse();
    }

    @Test
    void resolveAndManifestWalkParentChain() {
        registry.attach(root, connection, List.of(descriptor()));

        assertThat(registry.resolve(child, "jira.list_issues")).isPresent();
        assertThat(registry.resolve(child, "unknown")).isEmpty();
        assertThat(registry.manifest(child)).hasSize(1);
        assertThat(registry.manifest(child).getFirst().getToolDefinition().name()).isEqualTo("jira.list_issues");
        assertThat(registry.manifest(state)).isEmpty();
    }

    @Test
    void invokeRoutesToolCallAndReturnsClientResult() {
        registry.attach(root, connection, List.of(descriptor()));

        ToolResult result = registry.invoke(child, "call-1", "jira.list_issues", Map.of("project", "ABC"));

        assertThat(result.status()).isEqualTo(ToolStatus.OK);
        assertThat(result.output()).isEqualTo("client-output");
        assertThat(connection.sentToolCall("jira.list_issues")).isTrue();
        assertThat(connection.sent().getFirst()).contains("\"sessionId\":\"" + child + "\"");
    }

    @Test
    void invokeRejectsInvalidArgsWithParamsSchemaWithoutDispatching() {
        registry.attach(root, connection, List.of(descriptor()));

        ToolResult result = registry.invoke(root, "call-2", "jira.list_issues", Map.of());

        assertThat(result.status()).isEqualTo(ToolStatus.ERROR);
        assertThat(result.output()).contains("params-schema");
        assertThat(connection.sent()).isEmpty();
    }

    @Test
    void invokeWithoutToolsetIsToolNotAvailable() {
        registry.attach(root, connection, List.of(descriptor()));

        ToolResult result = registry.invoke(UUID.randomUUID(), "call-3", "jira.list_issues", Map.of("project", "A"));

        assertThat(result.status()).isEqualTo(ToolStatus.ERROR);
        assertThat(result.output()).contains("tool-not-available");
    }

    @Test
    void invokeTimesOutWithoutClientResult() {
        RelayProperties shortTimeout = properties(Duration.ofMillis(100));
        ClientToolRegistry timeoutRegistry = new ClientToolRegistry(sessionStore, connections,
                new ClientToolAdapter(JsonMapper.builder().build()), shortTimeout);
        TestRelayConnection silent = new TestRelayConnection("alice", timeoutRegistry).silent();
        connections.register(root, silent);
        timeoutRegistry.attach(root, silent, List.of(descriptor()));

        ToolResult result = timeoutRegistry.invoke(root, "call-4", "jira.list_issues", Map.of("project", "A"));

        assertThat(result.status()).isEqualTo(ToolStatus.ERROR);
        assertThat(result.output()).contains("tool-timeout");
    }

    @Test
    void firstFinalResultWinsAndLateResultIsIgnored() throws Exception {
        registry.attach(root, connection, List.of(descriptor()));
        connection.silent();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ToolResult> future = pool.submit(() ->
                    registry.invoke(root, "call-5", "jira.list_issues", Map.of("project", "A")));
            await().atMost(Duration.ofSeconds(2)).until(() -> connection.sentToolCall("jira.list_issues"));

            registry.completeResult("call-5", "first", 0);
            registry.completeResult("call-5", "second", 1);

            ToolResult result = future.get(2, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(ToolStatus.OK);
            assertThat(result.output()).isEqualTo("first");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void disconnectDropsOverlayAndLosesInFlightCall() throws Exception {
        registry.attach(root, connection, List.of(descriptor()));
        connection.silent();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ToolResult> future = pool.submit(() ->
                    registry.invoke(root, "call-6", "jira.list_issues", Map.of("project", "A")));
            await().atMost(Duration.ofSeconds(2)).until(() -> connection.sentToolCall("jira.list_issues"));

            registry.detach(root, connection);

            assertThat(future.get(2, TimeUnit.SECONDS).status()).isEqualTo(ToolStatus.LOST);
            assertThat(registry.isClientSession(root)).isFalse();
            assertThat(registry.invoke(root, "call-7", "jira.list_issues", Map.of("project", "A")).output())
                    .contains("tool-not-available");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void detachIsCasByIdentitySoTakeoverOverlaySurvives() {
        TestRelayConnection takeover = new TestRelayConnection("alice", registry);
        registry.attach(root, connection, List.of(descriptor()));
        registry.attach(root, takeover, List.of(descriptor()));

        registry.detach(root, connection);

        assertThat(registry.isClientSession(root)).isTrue();
        assertThatCode(() -> registry.detach(root, takeover)).doesNotThrowAnyException();
        assertThat(registry.isClientSession(root)).isFalse();
    }

    @Test
    void cancelCompletesInFlightCallAsCancelledAndNotifiesClient() throws Exception {
        registry.attach(root, connection, List.of(descriptor()));
        connection.silent();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ToolResult> future = pool.submit(() ->
                    registry.invoke(root, "call-cancel", "jira.list_issues", Map.of("project", "A")));
            await().atMost(Duration.ofSeconds(2)).until(() -> connection.sentToolCall("jira.list_issues"));

            registry.cancel(root, "call-cancel");

            assertThat(future.get(2, TimeUnit.SECONDS).status()).isEqualTo(ToolStatus.CANCELLED);
            assertThat(connection.sent()).anyMatch(frame -> frame.contains("\"type\":\"tool.cancel\""));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancelBeforeDispatchReturnsCancelledWithoutToolCall() {
        registry.attach(root, connection, List.of(descriptor()));

        registry.cancel(root, "call-early");
        ToolResult result = registry.invoke(root, "call-early", "jira.list_issues", Map.of("project", "A"));

        assertThat(result.status()).isEqualTo(ToolStatus.CANCELLED);
        assertThat(connection.sent()).isEmpty();
    }

    @Test
    void cancelAfterCompletionIsNoOp() {
        registry.attach(root, connection, List.of(descriptor()));
        assertThat(registry.invoke(root, "call-done", "jira.list_issues", Map.of("project", "A")).status())
                .isEqualTo(ToolStatus.OK);
        int sentBefore = connection.sent().size();

        registry.cancel(root, "call-done");

        assertThat(connection.sent()).hasSize(sentBefore);
        assertThat(connection.sent()).noneMatch(frame -> frame.contains("\"type\":\"tool.cancel\""));
    }

    private static ToolDescriptor descriptor() {
        return new ToolDescriptor("jira.list_issues", "List Jira issues",
                Map.of("type", "object",
                        "properties", Map.of("project", Map.of("type", "string")),
                        "required", List.of("project")),
                "client.mcp:jira");
    }

    private static RelayProperties properties(Duration toolCallTimeout) {
        return new RelayProperties(Duration.ofSeconds(30), toolCallTimeout, Duration.ofSeconds(10),
                DataSize.ofKilobytes(512), List.of("*"));
    }

    private static Session session(UUID id, SessionKind kind, UUID parentSessionId) {
        return new Session(id, kind, "title", UUID.randomUUID(), null, null, UUID.randomUUID(),
                parentSessionId, parentSessionId == null ? 0 : 1, false, 0, 0, null,
                Instant.now(), Instant.now());
    }
}
