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
import se.rocketscien.harness.config.KeycloakContextInitializer;
import se.rocketscien.harness.execution.TurnManager;
import se.rocketscien.harness.relay.ClientToolRegistry;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionEventBroadcaster;
import se.rocketscien.harness.session.SessionRuntimeStatus;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.session.TurnOutcome;
import se.rocketscien.harness.tests.relay.TestRelayClient;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * M4 batch X (task 6.2): приёмка «Роуминг» — реальный WS-клиент ({@link TestRelayClient}) на
 * FREE root-сессии оркестратора. Сценарий: офис A регистрируется и исполняет клиентский
 * {@code bash} (не серверный контейнер); takeover офисом B (A — close 4409 {@code superseded});
 * продолжение через B; разрыв B → клиентский вызов становится {@code tool-not-available};
 * суб-сессия (spawn-путь) видит клиентский оверлей по parent-chain. Полный task/create_task
 * контур и Web Desktop — вне M4 (см. apply-notes).
 */
class AcceptanceWorkspaceRoamingTest extends BaseApplicationTest {

    private static final String PATH = "/v1/chat/completions";

    private final HttpClient http = HttpClient.newHttpClient();

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
    private SessionEventBroadcaster broadcaster;

    private String aliceToken;

    @BeforeEach
    void setUp() throws Exception {
        llmWireMock.resetAll();
        aliceToken = keycloakToken("alice", "alice-password");
    }

    @Test
    void roamingAcrossOfficesWithClientTools() throws Exception {
        Session session = ExecutionFixtures.newSessionWithAgent(
                jdbcTemplate, sessionStore, idGenerator, environment, "{\"metaTools\":true}");
        URI relayUri = URI.create("ws://localhost:" + localServerPort + "/api/v1/relay");

        TestRelayClient officeA = new TestRelayClient(relayUri, aliceToken, session.id().toString(),
                "/office-a", declaredTools(), (tool, args) -> respond(tool, args, "A"));
        officeA.connectAndRegister();
        try {
            // Суб-сессия (путь SubagentSpawner.createChildSession) видит клиентский оверлей root по parent-chain.
            String agentKey = jdbcTemplate.queryForObject(
                    "SELECT a.key FROM agent a JOIN session s ON s.agent_revision_id = a.id WHERE s.id = ?",
                    String.class, session.id());
            Session child = sessionStore.createChildSession(session.id(), agentKey, "Субагент");
            assertThat(clientToolRegistry.isClientSession(child.id())).isTrue();

            // Turn 1: оркестратор вызывает клиентский bash → исполняется офисом A, не сервером.
            stubToolThenFinal("roaming-a", "call-a", "bash", "{\"command\":\"echo hi\"}");
            runTurn(session, "выполни через клиента");
            assertThat(journalField(session.id(), "TOOL_RESULT", "status")).isEqualTo("OK");
            assertThat(journalField(session.id(), "TOOL_RESULT", "output")).contains("client-bash-A");
            assertThat(officeA.sentToolResult("client-bash-A")).isTrue();
            assertThat(broadcaster.statusSnapshot(session.id()).runtimeStatus())
                    .as("SSE-снапшот после хода — IDLE")
                    .isEqualTo(SessionRuntimeStatus.IDLE);

            // Офис B: takeover — старое соединение A получает close 4409 superseded.
            TestRelayClient officeB = new TestRelayClient(relayUri, aliceToken, session.id().toString(),
                    "/office-b", declaredTools(), (tool, args) -> respond(tool, args, "B"));
            officeB.connectAndRegister();
            try {
                assertThat(officeA.awaitClosed()).isTrue();
                assertThat(officeA.closeCode()).isEqualTo(4409);
                assertThat(officeA.closeReason()).isEqualTo("superseded");

                // Turn 2: продолжение через офис B.
                stubToolThenFinal("roaming-b", "call-b", "jira.list_issues", "{\"project\":\"ABC\"}");
                runTurn(session, "продолжи");
                assertThat(journalField(session.id(), "TOOL_RESULT", "output")).contains("client-jira-B");
                assertThat(officeB.sentToolResult("client-jira-B")).isTrue();
            } finally {
                officeB.close();
            }

            // Разрыв B: оверлей снят (D-84), следующий клиентский вызов → tool-not-available.
            await().atMost(Duration.ofSeconds(15))
                    .until(() -> !clientToolRegistry.isClientSession(session.id()));
            stubToolThenFinal("roaming-gone", "call-gone", "jira.list_issues", "{\"project\":\"X\"}");
            runTurn(session, "ещё раз");
            assertThat(journalField(session.id(), "TOOL_RESULT", "status")).isEqualTo("ERROR");
            assertThat(journalField(session.id(), "TOOL_RESULT", "output")).contains("tool-not-available");

            // Финал — SUCCESS (COMPLETED) последнего Turn'а.
            assertThat(sessionStore.findSession(session.id()).orElseThrow().lastTurnOutcome())
                    .isEqualTo(TurnOutcome.COMPLETED);
        } finally {
            officeA.close();
        }
    }

    private TestRelayClient.ClientToolResult respond(String tool, Map<String, Object> args, String office) {
        String value = args.values().stream().findFirst().map(String::valueOf).orElse("");
        return "bash".equals(tool)
                ? new TestRelayClient.ClientToolResult("client-bash-" + office + ": " + value, 0)
                : new TestRelayClient.ClientToolResult("client-jira-" + office + ": " + value, 0);
    }

    private static List<Map<String, Object>> declaredTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(Map.of("name", "bash", "description", "Client bash", "source", "client.mcp:local",
                "inputSchema", Map.of("type", "object",
                        "properties", Map.of("command", Map.of("type", "string")),
                        "required", List.of("command"))));
        tools.add(Map.of("name", "jira.list_issues", "description", "Jira issues", "source", "client.mcp:jira",
                "inputSchema", Map.of("type", "object",
                        "properties", Map.of("project", Map.of("type", "string")),
                        "required", List.of("project"))));
        return tools;
    }

    private void runTurn(Session session, String text) {
        sessionStore.appendEvent(session.id(), MessageKind.USER, session.ownerUserId(), Map.of("text", text));
        turnManager.tryStart(session.id());
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> sessionStore.findSession(session.id())
                        .map(s -> s.lastTurnOutcome() == TurnOutcome.COMPLETED)
                        .orElse(false));
    }

    private void stubToolThenFinal(String scenario, String callId, String tool, String argumentsJson) {
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(toolCallChunk(callId, tool, argumentsJson), usageChunk()))
                .willSetStateTo("final"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario(scenario)
                .whenScenarioStateIs("final")
                .willReturn(sse(textChunk("готово"), usageChunk())));
    }

    private String journalField(UUID sessionId, String kind, String field) {
        return jdbcTemplate.queryForObject(
                "SELECT payload_jsonb ->> ? FROM session_message WHERE session_id = ? AND kind = ?"
                        + " ORDER BY seq DESC LIMIT 1",
                String.class, field, sessionId, kind);
    }

    private String keycloakToken(String username, String password) throws Exception {
        String form = "grant_type=password&client_id=harness-cli"
                + "&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(KeycloakContextInitializer.authServerUrl()
                        + "/realms/harness/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Keycloak не выдал токен: " + response.statusCode());
        }
        String body = response.body();
        int start = body.indexOf("\"access_token\":\"") + "\"access_token\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }

    private static ResponseDefinitionBuilder sse(String... events) {
        StringBuilder body = new StringBuilder();
        for (String event : events) {
            body.append("data: ").append(event).append("\n\n");
        }
        body.append("data: [DONE]\n\n");
        return aResponse().withStatus(200)
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

    private static String usageChunk() {
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    }
}
