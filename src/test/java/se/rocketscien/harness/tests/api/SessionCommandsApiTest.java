package se.rocketscien.harness.tests.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.testclient.ApiClient;
import se.rocketscien.harness.testclient.ApiException;
import se.rocketscien.harness.testclient.api.SessionCommandsApi;
import se.rocketscien.harness.testclient.api.SessionsApi;
import se.rocketscien.harness.testclient.model.CreateSessionRequest;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static se.rocketscien.harness.tests.api.ApiFixtures.apiClient;
import static se.rocketscien.harness.tests.api.ApiFixtures.insertAgentChain;
import static se.rocketscien.harness.tests.api.ApiFixtures.insertAppUser;
import static se.rocketscien.harness.tests.api.ApiFixtures.insertStateSession;
import static se.rocketscien.harness.tests.api.ApiFixtures.keycloakToken;
import static se.rocketscien.harness.tests.api.ApiFixtures.sendRaw;

/**
 * Задача 8.4 (specs/session-api: «Команды compact и stop»): compact — 202 на FREE,
 * 409 wrong-session-kind на STATE (STATE-строка кладётся в БД напрямую — движок задач
 * появится в M2), 404 на неизвестную сессию; stop — 202, идемпотентен, 404.
 * Появление COMPACT-события в потоке — задача 9.2.
 */
class SessionCommandsApiTest extends BaseApplicationTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private String aliceToken;
    private ApiClient aliceClient;
    private String agentKey;
    private UUID agentRevisionId;

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
        var seed = insertAgentChain(jdbcTemplate, idGenerator, environment);
        agentKey = seed.agentKey();
        agentRevisionId = seed.revisionId();
    }

    @Test
    void compactOnFreeSessionReturnsAccepted() throws Exception {
        UUID sessionId = new SessionsApi(aliceClient)
                .createSession(new CreateSessionRequest().agentKey(agentKey)).getId();

        new SessionCommandsApi(aliceClient).compactSession(sessionId);

        // 202 без тела: команда принята; COMPACT-событие появится в потоке (задача 9.2).
        // После E-J-4 спека объявляет 202 content application/json {} — дефолтный
        // Accept: application/json совместим, костыль */* снят.
        HttpResponse<String> raw = sendRaw(http, localServerUrl(), "POST",
                "/api/v1/sessions/" + sessionId + "/compact", aliceToken, null, null, null);
        assertThat(raw.statusCode()).isEqualTo(202);
        assertThat(raw.body()).isBlank();
    }

    @Test
    void compactOnStateSessionReturns409WrongSessionKind() throws Exception {
        UUID ownerUserId = insertAppUser(jdbcTemplate);
        UUID stateSessionId = insertStateSession(jdbcTemplate, ownerUserId, agentRevisionId);

        HttpResponse<String> response = sendRaw(http, localServerUrl(), "POST",
                "/api/v1/sessions/" + stateSessionId + "/compact", aliceToken, null, null, null);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("\"code\":\"wrong-session-kind\"");
    }

    @Test
    void compactUnknownSessionReturns404() {
        assertThrows(ApiException.class,
                () -> new SessionCommandsApi(aliceClient).compactSession(UUID.randomUUID()));
    }

    @Test
    void stopReturnsAcceptedAndIsIdempotent() throws Exception {
        UUID sessionId = new SessionsApi(aliceClient)
                .createSession(new CreateSessionRequest().agentKey(agentKey)).getId();

        SessionCommandsApi commandsApi = new SessionCommandsApi(aliceClient);
        commandsApi.stopSession(sessionId);
        commandsApi.stopSession(sessionId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT cancel_requested FROM session WHERE id = ?", Boolean.class, sessionId))
                .as("stop фиксирует cancel_requested в БД")
                .isTrue();
    }

    @Test
    void stopUnknownSessionReturns404() {
        assertThrows(ApiException.class,
                () -> new SessionCommandsApi(aliceClient).stopSession(UUID.randomUUID()));
    }
}
