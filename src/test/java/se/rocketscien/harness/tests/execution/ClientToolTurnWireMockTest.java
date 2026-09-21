package se.rocketscien.harness.tests.execution;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.execution.ToolDescriptor;
import se.rocketscien.harness.execution.TurnManager;
import se.rocketscien.harness.relay.ClientToolRegistry;
import se.rocketscien.harness.relay.RelayConnectionRegistry;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.session.TurnOutcome;
import se.rocketscien.harness.tests.relay.TestRelayConnection;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * M4 T.5: маршрутизация клиентского оверлея в агентном цикле (D-84) — галлюцинированный
 * {@code bash} в CLIENT-сессии не доходит до серверного workspace (tool-not-available),
 * декларированный инструмент маршрутизируется в релей, кривые args — params-schema.
 * Соединение — программный {@link TestRelayConnection} (WS-клиент — batch X).
 */
class ClientToolTurnWireMockTest extends BaseApplicationTest {

    private static final String PATH = "/v1/chat/completions";

    @Autowired
    private TurnManager turnManager;
    @Autowired
    private SessionStore sessionStore;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IdGenerator idGenerator;
    @Autowired
    private Environment environment;
    @Autowired
    private ClientToolRegistry clientToolRegistry;
    @Autowired
    private RelayConnectionRegistry relayConnectionRegistry;

    @BeforeEach
    void resetStubs() {
        llmWireMock.resetAll();
    }

    @Test
    void hallucinatedNativeToolInClientSessionIsNotRoutedToServer() {
        Session session = newSession();
        TestRelayConnection connection = attachEmptyOverlay(session);
        try {
            stubToolCallThenFinal("client-hallucination", "call-1", "bash",
                    "{\"command\":\"echo SHOULD-NOT-RUN\"}");
            runTurn(session);

            assertThat(journalField(session.id(), "TOOL_RESULT", "status")).isEqualTo("ERROR");
            assertThat(journalField(session.id(), "TOOL_RESULT", "output")).contains("tool-not-available");
        } finally {
            detach(session, connection);
        }
    }

    @Test
    void declaredClientToolIsRoutedToRelay() {
        Session session = newSession();
        TestRelayConnection connection = attachOverlay(session, List.of(jiraDescriptor()));
        try {
            stubToolCallThenFinal("client-routed", "call-2", "jira.list_issues",
                    "{\"project\":\"ABC\"}");
            runTurn(session);

            assertThat(journalField(session.id(), "TOOL_RESULT", "status")).isEqualTo("OK");
            assertThat(journalField(session.id(), "TOOL_RESULT", "output")).contains("client-output");
            assertThat(connection.sentToolCall("jira.list_issues")).isTrue();
        } finally {
            detach(session, connection);
        }
    }

    @Test
    void invalidClientArgsEndAsParamsSchema() {
        Session session = newSession();
        TestRelayConnection connection = attachOverlay(session, List.of(jiraDescriptor()));
        try {
            stubToolCallThenFinal("client-params", "call-3", "jira.list_issues", "{}");
            runTurn(session);

            assertThat(journalField(session.id(), "TOOL_RESULT", "status")).isEqualTo("ERROR");
            assertThat(journalField(session.id(), "TOOL_RESULT", "output")).contains("params-schema");
            assertThat(connection.sent()).isEmpty();
        } finally {
            detach(session, connection);
        }
    }

    private TestRelayConnection attachEmptyOverlay(Session session) {
        return attachOverlay(session, List.of());
    }

    private TestRelayConnection attachOverlay(Session session, List<ToolDescriptor> tools) {
        TestRelayConnection connection = new TestRelayConnection("alice", clientToolRegistry);
        relayConnectionRegistry.register(session.id(), connection);
        clientToolRegistry.attach(session.id(), connection, tools);
        return connection;
    }

    private void detach(Session session, TestRelayConnection connection) {
        clientToolRegistry.detach(session.id(), connection);
        relayConnectionRegistry.unregister(session.id(), connection);
    }

    private void runTurn(Session session) {
        sessionStore.appendEvent(session.id(), MessageKind.USER,
                session.ownerUserId(), Map.of("text", "выполни задачу"));
        turnManager.tryStart(session.id());
        awaitOutcome(session.id(), TurnOutcome.COMPLETED);
    }

    private static ToolDescriptor jiraDescriptor() {
        return new ToolDescriptor("jira.list_issues", "List Jira issues",
                Map.of("type", "object",
                        "properties", Map.of("project", Map.of("type", "string")),
                        "required", List.of("project")),
                "client.mcp:jira");
    }

    private Session newSession() {
        return ExecutionFixtures.newSession(jdbcTemplate, sessionStore, idGenerator, environment);
    }

    private void stubToolCallThenFinal(String scenario, String callId, String tool, String argumentsJson) {
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(toolCallChunk(callId, tool, argumentsJson), usageChunk(1, 1)))
                .willSetStateTo("final"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario(scenario)
                .whenScenarioStateIs("final")
                .willReturn(sse(textChunk("готово"), usageChunk(1, 1))));
    }

    private void awaitOutcome(UUID sessionId, TurnOutcome outcome) {
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> sessionStore.findSession(sessionId)
                        .map(s -> s.lastTurnOutcome() == outcome)
                        .orElse(false));
    }

    private String journalField(UUID sessionId, String kind, String field) {
        return jdbcTemplate.queryForObject(
                "SELECT payload_jsonb ->> ? FROM session_message WHERE session_id = ? AND kind = ?"
                        + " ORDER BY seq DESC LIMIT 1",
                String.class, field, sessionId, kind);
    }

    private static ResponseDefinitionBuilder sse(String... events) {
        StringBuilder body = new StringBuilder();
        for (String event : events) {
            body.append("data: ").append(event).append("\n\n");
        }
        body.append("data: [DONE]\n\n");
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(body.toString());
    }

    private static String textChunk(String content) {
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"},\"finish_reason\":null}]}";
    }

    private static String toolCallChunk(String id, String tool, String argumentsJson) {
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"" + id
                + "\",\"type\":\"function\",\"function\":{\"name\":\"" + tool + "\",\"arguments\":\""
                + argumentsJson.replace("\"", "\\\"") + "\"}}]},\"finish_reason\":\"tool_calls\"}]}";
    }

    private static String usageChunk(int promptTokens, int completionTokens) {
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":" + promptTokens + ",\"completion_tokens\":" + completionTokens
                + ",\"total_tokens\":" + (promptTokens + completionTokens) + "}}";
    }
}
