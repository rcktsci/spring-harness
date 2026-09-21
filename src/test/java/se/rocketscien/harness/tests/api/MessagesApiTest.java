package se.rocketscien.harness.tests.api;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.testclient.ApiClient;
import se.rocketscien.harness.testclient.ApiException;
import se.rocketscien.harness.testclient.api.SessionMessagesApi;
import se.rocketscien.harness.testclient.api.SessionsApi;
import se.rocketscien.harness.testclient.model.CreateSessionRequest;
import se.rocketscien.harness.testclient.model.MessageDto;
import se.rocketscien.harness.testclient.model.MessagePage;
import se.rocketscien.harness.testclient.model.SendMessageRequest;
import se.rocketscien.harness.testclient.model.SessionDto;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.awaitility.Awaitility.await;
import static se.rocketscien.harness.tests.api.ApiFixtures.apiClient;
import static se.rocketscien.harness.tests.api.ApiFixtures.insertAgentChain;
import static se.rocketscien.harness.tests.api.ApiFixtures.keycloakToken;
import static se.rocketscien.harness.tests.api.ApiFixtures.sendRaw;
import static se.rocketscien.harness.tests.api.ApiFixtures.textCompletionChunks;

/**
 * Задача 8.3 (specs/session-api: «Отправка сообщений», «Чтение сообщений») и wake EVENT (7.3):
 * POST messages — 202 {messageId, seq}, атрибуция автора из JWT; wake EVENT — Turn стартует
 * быстрее poll-interval (в тест-профиле poll = 1h); GET messages — интервал (since, …],
 * только видимые, nextCursor = seq последнего события страницы.
 */
class MessagesApiTest extends BaseApplicationTest {

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

    @BeforeEach
    void setUp() {
        aliceToken = keycloakToken(http, "alice", "alice-password");
        aliceClient = apiClient(localServerUrl(), aliceToken);
        agentKey = insertAgentChain(jdbcTemplate, idGenerator, environment).agentKey();
        llmWireMock.resetAll();
    }

    @Test
    void sendMessageReturnsAcceptedIdentifiersWithAuthorFromJwt() throws Exception {
        SessionsApi sessionsApi = new SessionsApi(aliceClient);
        UUID sessionId = sessionsApi.createSession(new CreateSessionRequest().agentKey(agentKey)).getId();

        SessionMessagesApi messagesApi = new SessionMessagesApi(aliceClient);
        var accepted = messagesApi.sendMessage(sessionId, new SendMessageRequest().text("проверь сборку"));

        assertThat(accepted.getMessageId()).hasSize(26);
        assertThat(accepted.getSeq()).isEqualTo(1);

        List<MessageDto> messages = messagesApi.listMessages(sessionId, 0L, null).getItems();
        assertThat(messages).hasSize(1);
        MessageDto user = messages.getFirst();
        assertThat(user.getKind().getValue()).isEqualTo("USER");
        assertThat(user.getAuthor()).isEqualTo("alice");
        assertThat(user.getPayload().get("text")).isEqualTo("проверь сборку");
        assertThat(user.getCreatedAt()).isNotNull();
    }

    @Test
    void sendMessageWakesTurnFasterThanPollInterval() throws Exception {
        SessionsApi sessionsApi = new SessionsApi(aliceClient);
        UUID sessionId = sessionsApi.createSession(new CreateSessionRequest().agentKey(agentKey)).getId();
        llmWireMock.stubFor(post(urlEqualTo(LLM_PATH))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(textCompletionChunks("готово"))));

        long pollIntervalMillis = environment.getRequiredProperty("harness.turn.poll-interval", Duration.class)
                .toMillis();
        assertThat(pollIntervalMillis)
                .as("Тест-профиль обязан отключить POLL-wake (poll = 1h)")
                .isEqualTo(Duration.ofHours(1).toMillis());

        long startNanos = System.nanoTime();
        SessionMessagesApi messagesApi = new SessionMessagesApi(aliceClient);
        var accepted = messagesApi.sendMessage(sessionId, new SendMessageRequest().text("быстрый старт"));

        // EVENT-wake: COMPLETED за время, заведомо меньшее poll-interval (1h)
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    SessionDto session = sessionsApi.getSession(sessionId);
                    return session.getLastTurnOutcome() != null
                            && "COMPLETED".equals(session.getLastTurnOutcome().getValue());
                });
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(elapsedMillis).as("EVENT-старт обязан обгонять интервал опроса").isLessThan(pollIntervalMillis);
        assertThat(accepted.getSeq()).isEqualTo(1);
        llmWireMock.verify(1, postRequestedFor(urlEqualTo(LLM_PATH)));

        List<MessageDto> messages = messagesApi.listMessages(sessionId, 0L, null).getItems();
        assertThat(messages).extracting(dto -> dto.getKind().getValue())
                .containsExactly("USER", "ASSISTANT");
        assertThat(messages.get(0).getPayload().get("text")).isEqualTo("быстрый старт");
        assertThat(messages.get(1).getAuthor()).isNull();
        // E-J-2: текст ASSISTANT доходит из стрима через aggregate(stream, responseRef::set)
        assertThat(messages.get(1).getPayload().get("text")).isEqualTo("готово");
    }

    @Test
    void blankTextReturns422ValidationFailed() throws Exception {
        UUID sessionId = new SessionsApi(aliceClient)
                .createSession(new CreateSessionRequest().agentKey(agentKey)).getId();

        HttpResponse<String> response = sendRaw(http, localServerUrl(), "POST",
                "/api/v1/sessions/" + sessionId + "/messages", aliceToken, "application/json", null,
                "{\"text\":\" \"}");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("\"code\":\"validation-failed\"")
                .contains("\"rule\":\"blank\"");
    }

    @Test
    void sendMessageToUnknownSessionReturns404() {
        assertThrows(
                ApiException.class,
                () -> new SessionMessagesApi(aliceClient)
                        .sendMessage(UUID.randomUUID(), new SendMessageRequest().text("в никуда")));
    }

    @Test
    void listMessagesReturnsIntervalAfterSinceInAscendingOrder() throws Exception {
        UUID sessionId = new SessionsApi(aliceClient)
                .createSession(new CreateSessionRequest().agentKey(agentKey)).getId();
        appendUser(sessionId, "первый");
        appendUser(sessionId, "второй");
        appendUser(sessionId, "третий");

        SessionMessagesApi messagesApi = new SessionMessagesApi(aliceClient);

        // страница целиком (since=1): [2,3] — событий больше нет, nextCursor отсутствует (спека)
        MessagePage afterSince = messagesApi.listMessages(sessionId, 1L, null);
        assertThat(afterSince.getItems()).extracting(MessageDto::getSeq).containsExactly(2L, 3L);
        assertThat(afterSince.getNextCursor()).isNull();

        MessagePage fromStart = messagesApi.listMessages(sessionId, 0L, 2);
        assertThat(fromStart.getItems()).extracting(MessageDto::getSeq).containsExactly(1L, 2L);
        assertThat(fromStart.getNextCursor()).isEqualTo(2L);

        MessagePage lastPage = messagesApi.listMessages(sessionId, 2L, 5);
        assertThat(lastPage.getItems()).extracting(MessageDto::getSeq).containsExactly(3L);
        assertThat(lastPage.getNextCursor()).isNull();
    }

    @Test
    void listMessagesHidesEventsCoveredByCompact() throws Exception {
        UUID sessionId = new SessionsApi(aliceClient)
                .createSession(new CreateSessionRequest().agentKey(agentKey)).getId();
        appendUser(sessionId, "скроется компакцией");
        appendUser(sessionId, "останется виден");
        sessionStore.appendEvent(sessionId, MessageKind.COMPACT, null,
                Map.of("covers", List.of(Map.of("from", 1, "to", 1)), "summary", "сводка"));

        MessagePage page = new SessionMessagesApi(aliceClient).listMessages(sessionId, 0L, null);

        assertThat(page.getItems()).extracting(MessageDto::getSeq).containsExactly(2L, 3L);
        assertThat(page.getItems()).extracting(dto -> dto.getKind().getValue())
                .containsExactly("USER", "COMPACT");
        assertThat(page.getItems().get(1).getPayload().get("summary")).isEqualTo("сводка");
    }

    @Test
    void listMessagesOfUnknownSessionReturns404() {
        assertThrows(
                ApiException.class,
                () -> new SessionMessagesApi(aliceClient).listMessages(UUID.randomUUID(), 0L, null));
    }

    private void appendUser(UUID sessionId, String text) {
        sessionStore.appendEvent(sessionId, MessageKind.USER, null, Map.of("text", text));
    }
}
