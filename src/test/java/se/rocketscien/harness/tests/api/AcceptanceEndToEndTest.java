package se.rocketscien.harness.tests.api;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.testclient.ApiClient;
import se.rocketscien.harness.testclient.ApiException;
import se.rocketscien.harness.testclient.api.SessionMessagesApi;
import se.rocketscien.harness.testclient.api.SessionsApi;
import se.rocketscien.harness.testclient.model.CreateSessionRequest;
import se.rocketscien.harness.testclient.model.MessageDto;
import se.rocketscien.harness.testclient.model.SendMessageRequest;
import se.rocketscien.harness.testclient.model.SessionDto;
import se.rocketscien.harness.tests.execution.DockerTestSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static se.rocketscien.harness.tests.api.ApiFixtures.apiClient;
import static se.rocketscien.harness.tests.api.ApiFixtures.insertAgentChain;
import static se.rocketscien.harness.tests.api.ApiFixtures.keycloakToken;

/**
 * Задача 10.2 — приёмочный критерий M1 (roadmap.md): «FREE-сессия через минимальный attach
 * работает end-to-end: сообщение → модель → инструменты в helper-контейнере → стриминг».
 *
 * <p>Живой Keycloak-юзер (alice) создаёт FREE-сессию по реальному агенту, шлёт сообщение
 * через сгенерированный java-клиент; WireMock-LLM ведёт агента четырьмя раундами
 * write_file → bash → read_file → финальный ответ в реальном helper-контейнере
 * (Docker обязателен, helper-образ собирается статически); клиент наблюдает ход Turn'а
 * по SSE (переходы IDLE → TURN_RUNNING → IDLE), после завершения читает журнал
 * сгенерированным клиентом: seq без дыр, результаты инструментов видимы, текст финального
 * ASSISTANT непустой; файл физически лежит в host-workspace сессии (bind-mount).</p>
 */
class AcceptanceEndToEndTest extends BaseApplicationTest {

    static {
        DockerTestSupport.helperImage();
    }

    private static final ObjectMapper SSE_JSON = new ObjectMapper();
    private static final Duration TURN_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration SSE_TIMEOUT = Duration.ofMinutes(3);

    private static final String LLM_PATH = "/v1/chat/completions";
    private static final String FILE_NAME = "notes.txt";
    private static final String FILE_CONTENT = "alpha\nbeta\ngamma\n";
    private static final String USER_TEXT = "Создай файл notes.txt со строками alpha, beta, gamma; "
            + "посчитай число строк и прочитай файл.";
    private static final String FINAL_TEXT = "Файл notes.txt создан: 3 строки, первая — alpha.";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<CompletableFuture<?>> pendingReads = new ArrayList<>();

    private String aliceToken;
    private ApiClient aliceClient;
    private String agentKey;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IdGenerator idGenerator;
    @Autowired
    private Environment environment;

    @BeforeEach
    void setUp() {
        aliceToken = keycloakToken(http, "alice", "alice-password");
        aliceClient = apiClient(localServerUrl(), aliceToken);
        agentKey = insertAgentChain(jdbcTemplate, idGenerator, environment).agentKey();
        llmWireMock.resetAll();
    }

    @AfterEach
    void drainPendingReads() {
        pendingReads.forEach(future -> future.cancel(true));
        pendingReads.clear();
    }

    @Test
    void acceptanceFreeSessionMessageModelToolsInHelperContainerAndStreaming() throws Exception {
        SessionsApi sessionsApi = new SessionsApi(aliceClient);
        SessionMessagesApi messagesApi = new SessionMessagesApi(aliceClient);

        SessionDto created = sessionsApi.createSession(new CreateSessionRequest().agentKey(agentKey));
        UUID sessionId = created.getId();
        assertThat(created.getKind().getValue()).as("сессия FREE").isEqualTo("FREE");
        assertThat(created.getRuntimeStatus().getValue()).as("статус до Turn'а").isEqualTo("IDLE");

        // attach: SSE-поток открыт до сообщения — живые кадры статуса и событий
        CompletableFuture<StreamCapture> frames =
                readUntilTurnFinishes(openStream(sessionId));
        pendingReads.add(frames);

        stubWriteCountReadScenario();

        var accepted = messagesApi.sendMessage(sessionId, new SendMessageRequest().text(USER_TEXT));
        assertThat(accepted.getSeq()).isEqualTo(1L);

        await().atMost(TURN_TIMEOUT).pollInterval(Duration.ofMillis(200))
                .until(() -> completed(sessionsApi, sessionId));
        SessionDto finished = sessionsApi.getSession(sessionId);
        assertThat(finished.getRuntimeStatus().getValue()).isEqualTo("IDLE");
        assertThat(finished.getLastTurnOutcome().getValue()).isEqualTo("COMPLETED");

        // статусные переходы по живому SSE-потоку: снапшот IDLE → TURN_RUNNING → IDLE
        StreamCapture capture = frames.get(SSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(capture.statuses())
                .as("переходы session.status по SSE")
                .containsExactly("IDLE", "TURN_RUNNING", "IDLE");

        // журнал по сгенерированному клиенту: полный порядок seq без дыр
        List<MessageDto> messages = messagesApi.listMessages(sessionId, 0L, null).getItems();
        assertThat(messages).extracting(MessageDto::getSeq)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L);
        assertThat(messages).extracting(message -> message.getKind().getValue()).containsExactly(
                "USER", "ASSISTANT",
                "TOOL_CALL", "TOOL_RESULT",
                "ASSISTANT", "TOOL_CALL", "TOOL_RESULT",
                "ASSISTANT", "TOOL_CALL", "TOOL_RESULT",
                "ASSISTANT");
        assertThat(messages.getFirst().getAuthor()).as("автор USER из живого Keycloak JWT").isEqualTo("alice");
        assertThat(messages.getFirst().getPayload().get("text")).isEqualTo(USER_TEXT);

        // результаты инструментов агента видимы клиенту
        MessageDto writeCall = toolCall(messages, "write_file");
        MessageDto writeResult = toolResult(messages, "write_file");
        assertThat(writeResult.getPayload()).as("write_file payload").containsEntry("status", "OK");
        assertThat(writeResult.getPayload().get("output")).as("write_file output").isEqualTo("created");
        assertThat(writeResult.getPayload().get("callId")).isEqualTo(writeCall.getPayload().get("callId"));
        assertThat(argumentsOf(writeCall).get("path")).isEqualTo(FILE_NAME);

        MessageDto bashResult = toolResult(messages, "bash");
        assertThat(bashResult.getPayload()).as("bash payload").containsEntry("status", "OK");
        assertThat(((Number) bashResult.getPayload().get("exitCode")).intValue()).isZero();
        assertThat(String.valueOf(bashResult.getPayload().get("output")).trim())
                .as("bash посчитал строки файла в контейнере").isEqualTo("3");

        MessageDto readResult = toolResult(messages, "read_file");
        assertThat(readResult.getPayload()).as("read_file payload").containsEntry("status", "OK");
        assertThat(readResult.getPayload().get("output")).isEqualTo(FILE_CONTENT);

        // финальный ASSISTANT — непустой текст из стрима
        String finalText = String.valueOf(messages.getLast().getPayload().get("text"));
        assertThat(finalText).as("текст финального ASSISTANT").isNotBlank().isEqualTo(FINAL_TEXT);

        // доказательство реального контейнера: файл в host-workspace сессии (bind-mount)
        Path hostFile = Path.of(System.getProperty("java.io.tmpdir"), "harness-it-workspaces",
                sessionId.toString(), FILE_NAME);
        assertThat(hostFile).as("workspace-файл сессии на хосте").exists();
        assertThat(Files.readString(hostFile)).isEqualTo(FILE_CONTENT);

        // модель: 4 раунда (write_file, bash, read_file, финал)
        llmWireMock.verify(4, postRequestedFor(urlEqualTo(LLM_PATH)));
    }

    private record StreamCapture(List<String> lines, List<String> statuses) {
    }

    private boolean completed(SessionsApi sessionsApi, UUID sessionId) {
        try {
            SessionDto session = sessionsApi.getSession(sessionId);
            return session.getLastTurnOutcome() != null
                    && "COMPLETED".equals(session.getLastTurnOutcome().getValue());
        } catch (ApiException e) {
            return false;
        }
    }

    /** Живой attach-стрим: читает кадры, пока не придёт финальный IDLE после TURN_RUNNING. */
    private CompletableFuture<StreamCapture> readUntilTurnFinishes(HttpResponse<Stream<String>> response) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> lines = new ArrayList<>();
            List<String> statuses = new ArrayList<>();
            Iterator<String> iterator = response.body().iterator();
            boolean inStatusFrame = false;
            while (iterator.hasNext()) {
                String line = iterator.next();
                lines.add(line);
                if (line.startsWith("event:")) {
                    inStatusFrame = line.contains("session.status");
                } else if (line.startsWith("data:") && inStatusFrame) {
                    inStatusFrame = false;
                    JsonNode data = SSE_JSON.readTree(line.substring("data:".length()).trim());
                    statuses.add(data.get("runtimeStatus").asString());
                    if (statuses.size() == 3 && "IDLE".equals(statuses.getLast())) {
                        return new StreamCapture(lines, statuses);
                    }
                }
            }
            throw new IllegalStateException("SSE-поток закрылся до финального IDLE: " + statuses);
        }, sseExecutor);
    }

    private HttpResponse<Stream<String>> openStream(UUID sessionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        localServerUrl() + "/api/v1/sessions/" + sessionId + "/events"))
                .header("Authorization", "Bearer " + aliceToken)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofLines());
    }

    /** Сценарий WireMock: раунды write_file → bash (wc -l) → read_file → финальный текст. */
    private void stubWriteCountReadScenario() {
        llmWireMock.stubFor(post(urlEqualTo(LLM_PATH)).inScenario("acceptance")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(toolCallChunk("call-write", "write_file",
                        "{\"path\":\"" + FILE_NAME + "\",\"content\":\"alpha\\nbeta\\ngamma\\n\"}")))
                .willSetStateTo("count"));
        llmWireMock.stubFor(post(urlEqualTo(LLM_PATH)).inScenario("acceptance")
                .whenScenarioStateIs("count")
                .willReturn(sse(toolCallChunk("call-bash", "bash",
                        "{\"command\":\"wc -l < " + FILE_NAME + "\"}")))
                .willSetStateTo("read"));
        llmWireMock.stubFor(post(urlEqualTo(LLM_PATH)).inScenario("acceptance")
                .whenScenarioStateIs("read")
                .willReturn(sse(toolCallChunk("call-read", "read_file",
                        "{\"path\":\"" + FILE_NAME + "\"}")))
                .willSetStateTo("final"));
        llmWireMock.stubFor(post(urlEqualTo(LLM_PATH)).inScenario("acceptance")
                .whenScenarioStateIs("final")
                .willReturn(sse(textChunk(FINAL_TEXT))));
    }

    private static MessageDto toolCall(List<MessageDto> messages, String tool) {
        return messages.stream()
                .filter(message -> "TOOL_CALL".equals(message.getKind().getValue())
                        && tool.equals(message.getPayload().get("tool")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Нет TOOL_CALL " + tool + " в журнале"));
    }

    private static MessageDto toolResult(List<MessageDto> messages, String tool) {
        return messages.stream()
                .filter(message -> "TOOL_RESULT".equals(message.getKind().getValue())
                        && tool.equals(message.getPayload().get("tool")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Нет TOOL_RESULT " + tool + " в журнале"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> argumentsOf(MessageDto toolCall) {
        Object arguments = toolCall.getPayload().get("arguments");
        return arguments instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
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
        // argumentsJson — вложенный JSON: экранируются и бэкслеши (\n должен дожить до
        // внутреннего парсинга аргументов), и кавычки
        String embedded = argumentsJson.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"" + id
                + "\",\"type\":\"function\",\"function\":{\"name\":\"" + tool + "\",\"arguments\":\""
                + embedded + "\"}}]},\"finish_reason\":\"tool_calls\"}]}";
    }
}
