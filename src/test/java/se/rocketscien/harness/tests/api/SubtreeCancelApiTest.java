package se.rocketscien.harness.tests.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.execution.PollWakeJob;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.testclient.ApiClient;
import se.rocketscien.harness.testclient.api.SessionMessagesApi;
import se.rocketscien.harness.testclient.api.SessionsApi;
import se.rocketscien.harness.testclient.model.CreateSessionRequest;
import se.rocketscien.harness.testclient.model.SendMessageRequest;
import se.rocketscien.harness.tests.execution.DockerTestSupport;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static se.rocketscien.harness.tests.api.ApiFixtures.apiClient;
import static se.rocketscien.harness.tests.api.ApiFixtures.insertAgentChain;
import static se.rocketscien.harness.tests.api.ApiFixtures.keycloakToken;
import static se.rocketscien.harness.tests.api.ApiFixtures.sendRaw;
import static se.rocketscien.harness.tests.api.ApiFixtures.textCompletionChunks;

/**
 * O.3 (спека subagent-lifecycle «Отмена поддерева сессий»): POST /sessions/{id}/stop
 * каскадирует по parent_session_id — корень, ребёнок и внук. Активные Turn'ы (bash в окне)
 * завершаются CANCELLED с CANCELLED-результатами; незакрытые async parked-сессии закрываются
 * самим {@code SubtreeCanceller}'ом — синтетический {@code TOOL_RESULT CANCELLED
 * «subtree-cancelled»}. Идемпотентность: повторный stop — 202, журнальных эффекта нет.
 */
class SubtreeCancelApiTest extends BaseApplicationTest {

    static {
        DockerTestSupport.helperImage();
    }

    private static final String LLM_PATH = "/v1/chat/completions";

    private final HttpClient http = HttpClient.newHttpClient();
    private String aliceToken;
    private ApiClient aliceClient;
    private String agentKey;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IdGenerator idGenerator;
    @Autowired
    private Environment environment;
    @Autowired
    private SessionStore sessionStore;
    @Autowired
    private PollWakeJob pollWakeJob;

    @BeforeEach
    void setUp() {
        aliceToken = keycloakToken(http, "alice", "alice-password");
        aliceClient = apiClient(localServerUrl(), aliceToken);
        agentKey = insertAgentChain(jdbcTemplate, idGenerator, environment).agentKey();
        llmWireMock.resetAll();
    }

    @Test
    void stopDuringActiveTurnsCancelsWholeSubtree() throws Exception {
        UUID rootId = new SessionsApi(aliceClient)
                .createSession(new CreateSessionRequest().agentKey(agentKey)).getId();
        Session child = sessionStore.createChildSession(rootId, agentKey, null);
        Session grandchild = sessionStore.createChildSession(child.id(), agentKey, null);
        List<UUID> all = List.of(rootId, child.id(), grandchild.id());

        // Все три выполняют долгий bash в активных Turn'ах; stop — в пределах окна
        llmWireMock.stubFor(post(urlEqualTo(LLM_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("data: " + toolCallChunk("call-1", "bash", "{\"command\":\"sleep 15\"}")
                                + "\n\ndata: [DONE]\n\n")));

        SessionMessagesApi messagesApi = new SessionMessagesApi(aliceClient);
        for (UUID id : all) {
            messagesApi.sendMessage(id, new SendMessageRequest().text("работай"));
        }
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
                .until(() -> all.stream().allMatch(this::journalHasPendingToolCall));

        var response = sendRaw(http, localServerUrl(), "POST",
                "/api/v1/sessions/" + rootId + "/stop", aliceToken, null, null, null);
        assertThat(response.statusCode()).isEqualTo(202);

        // Каскад: все три сессии — исход CANCELLED, у bash — CANCELLED-результат
        awaitAllCancelled(all);
        for (UUID id : all) {
            assertThat(journalField(id, "TOOL_RESULT", "status")).isEqualTo("CANCELLED");
        }
        assertThat(countToolResults(all)).isEqualTo(3);
    }

    @Test
    void stopClosesParkedAsyncWithSubtreeCancelled() throws Exception {
        UUID rootId = new SessionsApi(aliceClient)
                .createSession(new CreateSessionRequest().agentKey(agentKey)).getId();
        Session child = sessionStore.createChildSession(rootId, agentKey, null);

        // Парковка без живого Turn'а: TOOL_CALL + ASYNC_ACCEPTED, результата нет
        sessionStore.appendEvent(rootId, MessageKind.TOOL_CALL, null, Map.of(
                "callId", "call-parked-root", "toolCallId", "call-llm-1", "tool", "bash",
                "arguments", Map.of("command", "sleep 60")));
        sessionStore.appendEvent(rootId, MessageKind.ASYNC_ACCEPTED, null,
                Map.of("callId", "call-parked-root", "tool", "bash"));
        sessionStore.appendEvent(child.id(), MessageKind.TOOL_CALL, null, Map.of(
                "callId", "call-parked-child", "toolCallId", "call-llm-2", "tool", "bash",
                "arguments", Map.of("command", "sleep 60")));
        sessionStore.appendEvent(child.id(), MessageKind.ASYNC_ACCEPTED, null,
                Map.of("callId", "call-parked-child", "tool", "bash"));

        var response = sendRaw(http, localServerUrl(), "POST",
                "/api/v1/sessions/" + rootId + "/stop", aliceToken, null, null, null);
        assertThat(response.statusCode()).isEqualTo(202);

        // SubtreeCanceller закрывает parked-вызовы синтетическим CANCELLED (D-64, под локом)
        for (UUID id : List.of(rootId, child.id())) {
            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50))
                    .until(() -> "CANCELLED".equals(journalField(id, "TOOL_RESULT", "status")));
            assertThat(journalField(id, "TOOL_RESULT", "output")).contains("subtree-cancelled");
        }
    }

    @Test
    void stopKeepsSubtreeStoppedUntilExplicitResume() throws Exception {
        UUID rootId = new SessionsApi(aliceClient)
                .createSession(new CreateSessionRequest().agentKey(agentKey)).getId();
        Session child = sessionStore.createChildSession(rootId, agentKey, null);

        // Парковка обеих: TOOL_CALL + ASYNC_ACCEPTED, результата нет
        sessionStore.appendEvent(rootId, MessageKind.TOOL_CALL, null, Map.of(
                "callId", "call-stop-root", "toolCallId", "call-llm-1", "tool", "bash",
                "arguments", Map.of("command", "sleep 60")));
        sessionStore.appendEvent(rootId, MessageKind.ASYNC_ACCEPTED, null,
                Map.of("callId", "call-stop-root", "tool", "bash"));
        sessionStore.appendEvent(child.id(), MessageKind.TOOL_CALL, null, Map.of(
                "callId", "call-stop-child", "toolCallId", "call-llm-2", "tool", "bash",
                "arguments", Map.of("command", "sleep 60")));
        sessionStore.appendEvent(child.id(), MessageKind.ASYNC_ACCEPTED, null,
                Map.of("callId", "call-stop-child", "tool", "bash"));

        var response = sendRaw(http, localServerUrl(), "POST",
                "/api/v1/sessions/" + rootId + "/stop", aliceToken, null, null, null);
        assertThat(response.statusCode()).isEqualTo(202);

        // Cancellер закрыл parked-вызовы; сессии eligible + под флагом
        for (UUID id : List.of(rootId, child.id())) {
            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50))
                    .until(() -> "CANCELLED".equals(journalField(id, "TOOL_RESULT", "status")));
        }

        // O-2: stop персистентен — POLL не поднимает сессии, флаги держатся, исхода нет
        pollWakeJob.poll();
        Thread.sleep(1000);
        for (UUID id : List.of(rootId, child.id())) {
            assertThat(cancelRequested(id)).isTrue();
            assertThat(sessionStore.findSession(id).orElseThrow().lastTurnOutcome()).isNull();
        }

        // Явный resume корня — сообщение пользователя снимает флаг и поднимает Turn;
        // child остаётся остановленным
        llmWireMock.stubFor(post(urlEqualTo(LLM_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(textCompletionChunks("продолжили"))));
        new SessionMessagesApi(aliceClient)
                .sendMessage(rootId, new SendMessageRequest().text("продолжаем"));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .until(() -> sessionStore.findSession(rootId)
                        .map(s -> s.lastTurnOutcome() != null
                                && "COMPLETED".equals(s.lastTurnOutcome().name()))
                        .orElse(false));
        assertThat(cancelRequested(rootId)).isFalse();
        assertThat(cancelRequested(child.id())).isTrue();
        assertThat(sessionStore.findSession(child.id()).orElseThrow().lastTurnOutcome()).isNull();
    }

    private boolean cancelRequested(UUID sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT cancel_requested FROM session WHERE id = ?", Boolean.class, sessionId);
    }

    private boolean journalHasPendingToolCall(UUID sessionId) {
        Integer pending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_message sm WHERE sm.session_id = ? AND sm.kind = 'TOOL_CALL'"
                        + " AND NOT EXISTS (SELECT 1 FROM session_message tr WHERE tr.session_id = sm.session_id"
                        + " AND tr.kind = 'TOOL_RESULT' AND tr.payload_jsonb ->> 'callId' = sm.payload_jsonb ->> 'callId')",
                Integer.class, sessionId);
        return pending != null && pending > 0;
    }

    private void awaitAllCancelled(List<UUID> ids) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100))
                .until(() -> ids.stream().allMatch(id -> sessionStore.findSession(id)
                        .map(s -> s.lastTurnOutcome() != null
                                && "CANCELLED".equals(s.lastTurnOutcome().name()))
                        .orElse(false)));
    }

    private String journalField(UUID sessionId, String kind, String field) {
        return jdbcTemplate.queryForObject(
                "SELECT payload_jsonb ->> ? FROM session_message WHERE session_id = ? AND kind = ?"
                        + " ORDER BY seq DESC LIMIT 1",
                String.class, field, sessionId, kind);
    }

    private int countToolResults(List<UUID> ids) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_message WHERE session_id IN (?, ?, ?) AND kind = 'TOOL_RESULT'",
                Integer.class, ids.get(0), ids.get(1), ids.get(2));
        return count == null ? 0 : count;
    }

    private static String toolCallChunk(String id, String tool, String argumentsJson) {
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"" + id
                + "\",\"type\":\"function\",\"function\":{\"name\":\"" + tool + "\",\"arguments\":\""
                + argumentsJson.replace("\"", "\\\"") + "\"}}]},\"finish_reason\":\"tool_calls\"}]}";
    }
}
