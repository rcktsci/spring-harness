package se.rocketscien.harness.tests.api;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.execution.WorkspaceContainerManager;
import se.rocketscien.harness.tests.execution.DockerTestSupport;
import se.rocketscien.harness.testclient.ApiClient;
import se.rocketscien.harness.testclient.api.SessionMessagesApi;
import se.rocketscien.harness.testclient.api.SessionsApi;
import se.rocketscien.harness.testclient.model.CreateSessionRequest;
import se.rocketscien.harness.testclient.model.MessageDto;
import se.rocketscien.harness.testclient.model.MessagePage;
import se.rocketscien.harness.testclient.model.SendMessageRequest;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static se.rocketscien.harness.tests.api.ApiFixtures.apiClient;
import static se.rocketscien.harness.tests.api.ApiFixtures.insertAgentChain;
import static se.rocketscien.harness.tests.api.ApiFixtures.keycloakToken;

/**
 * N.4 (M3, спека session-api «MessageDto.late реализовано»): поздний TOOL_RESULT
 * завершившегося async-инструмента отдаётся клиенту с {@code late=true}; плейсхолдер
 * ASYNC_ACCEPTED виден в журнале как обычное событие сессии (mini-amendment N-1: значение
 * добавлено в enum MessageKind openapi.yaml) с заполненным callId и без late.
 */
class AsyncLateResultApiTest extends BaseApplicationTest {

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
    private WorkspaceContainerManager containers;

    @BeforeEach
    void setUp() {
        aliceToken = keycloakToken(http, "alice", "alice-password");
        aliceClient = apiClient(localServerUrl(), aliceToken);
        agentKey = insertAgentChain(jdbcTemplate, idGenerator, environment).agentKey();
        llmWireMock.resetAll();
    }

    @Test
    void lateToolResultCarriesLateTrueAndPlaceholderIsVisible() throws Exception {
        UUID sessionId = new SessionsApi(aliceClient)
                .createSession(new CreateSessionRequest().agentKey(agentKey)).getId();
        containers.ensureContainer(sessionId);

        llmWireMock.stubFor(post(urlEqualTo(LLM_PATH)).inScenario("async")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse("data: " + toolCallChunk("call-1", "bash", "{\"command\":\"sleep 15\"}")
                        + "\n\ndata: " + usageChunk() + "\n\ndata: [DONE]\n\n"))
                .willSetStateTo("final"));
        llmWireMock.stubFor(post(urlEqualTo(LLM_PATH)).inScenario("async")
                .whenScenarioStateIs("final")
                .willReturn(sse("data: " + textChunk("готово") + "\n\ndata: " + usageChunk()
                        + "\n\ndata: [DONE]\n\n")));

        SessionMessagesApi messagesApi = new SessionMessagesApi(aliceClient);
        messagesApi.sendMessage(sessionId, new SendMessageRequest().text("запусти долгую"));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .until(() -> visibleKinds(messagesApi, sessionId).contains("TOOL_RESULT"));

        MessagePage page = messagesApi.listMessages(sessionId, 0L, null);
        MessageDto lateResult = page.getItems().stream()
                .filter(dto -> "TOOL_RESULT".equals(dto.getKind().getValue()))
                .findFirst().orElseThrow();
        assertThat(lateResult.getLate()).isTrue();
        assertThat(lateResult.getPayload().get("late")).isEqualTo(true);

        assertThat(page.getItems().stream()
                .filter(dto -> "TOOL_CALL".equals(dto.getKind().getValue()))
                .findFirst().orElseThrow().getLate()).isNull();

        // Wake по позднему результату: новый Turn доводит журнал финальным ASSISTANT
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .until(() -> visibleKinds(messagesApi, sessionId)
                        .equals(List.of("USER", "ASSISTANT", "TOOL_CALL", "ASYNC_ACCEPTED",
                                "TOOL_RESULT", "ASSISTANT")));
        llmWireMock.verify(2, postRequestedFor(urlEqualTo(LLM_PATH)));

        // N-1: плейсхолдер — обычное событие сессии (kind=ASYNC_ACCEPTED, callId заполнен, late нет)
        MessageDto placeholder = page.getItems().stream()
                .filter(dto -> "ASYNC_ACCEPTED".equals(dto.getKind().getValue()))
                .findFirst().orElseThrow();
        assertThat(placeholder.getCallId()).isNotBlank();
        assertThat(placeholder.getLate()).isNull();
        assertThat(placeholder.getPayload().get("callId")).isEqualTo(placeholder.getCallId());
        assertThat(placeholder.getPayload().get("tool")).isEqualTo("bash");

        containers.removeContainer(sessionId);
    }

    private List<String> visibleKinds(SessionMessagesApi messagesApi, UUID sessionId) throws Exception {
        return messagesApi.listMessages(sessionId, 0L, null).getItems().stream()
                .map(dto -> dto.getKind().getValue())
                .toList();
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

    private static String textChunk(String content) {
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"},\"finish_reason\":null}]}";
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder sse(String body) {
        return WireMock.aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(body);
    }
}
