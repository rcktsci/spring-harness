package se.rocketscien.harness.tests.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static se.rocketscien.harness.tests.api.ApiFixtures.insertAgentChain;
import static se.rocketscien.harness.tests.api.ApiFixtures.keycloakToken;
import static se.rocketscien.harness.tests.api.ApiFixtures.selfSignedForeignToken;
import static se.rocketscien.harness.tests.api.ApiFixtures.sendRaw;

/**
 * Задача 8.1 (specs/session-api, «Единый формат ошибок»): каждая ошибка каталога M1 —
 * RFC 9457 Problem Details с {@code code} (401/405/406/413/415/422); 422 — с errors[].
 * 401 — без WWW-Authenticate-челленджа; 406 — с problem+json телом вопреки Accept.
 */
class ApiErrorHandlingTest extends BaseApplicationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();
    private String aliceToken;
    private String agentKey;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IdGenerator idGenerator;
    @Autowired
    private Environment environment;

    @BeforeEach
    void setUpTokens() {
        aliceToken = keycloakToken(http, "alice", "alice-password");
        agentKey = insertAgentChain(jdbcTemplate, idGenerator, environment).agentKey();
    }

    @Test
    void noTokenReturns401ProblemDetailsWithoutChallenge() throws Exception {
        HttpResponse<String> response = sendRaw(http, localServerUrl(), "GET", "/api/v1/sessions",
                null, null, null, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
        assertThat(code(response)).isEqualTo("unauthenticated");
    }

    @Test
    void foreignSignedTokenReturns401Unauthenticated() throws Exception {
        HttpResponse<String> response = sendRaw(http, localServerUrl(), "GET", "/api/v1/sessions",
                selfSignedForeignToken(), null, null, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(code(response)).isEqualTo("unauthenticated");
    }

    @Test
    void wrongMethodOnExistingPathReturns405MethodNotAllowed() throws Exception {
        HttpResponse<String> response = sendRaw(http, localServerUrl(), "PATCH", "/api/v1/agents",
                aliceToken, "application/json", null, "{}");

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        assertThat(code(response)).isEqualTo("method-not-allowed");
    }

    @Test
    void unacceptableAcceptReturns406WithProblemJsonBody() throws Exception {
        HttpResponse<String> response = sendRaw(http, localServerUrl(), "GET", "/api/v1/agents",
                aliceToken, null, "text/plain", null);

        assertThat(response.statusCode()).isEqualTo(406);
        // 406-ответ обязан нести problem+json вопреки Accept: text/plain (ручная запись)
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        assertThat(code(response)).isEqualTo("not-acceptable");
    }

    @Test
    void foreignContentTypeReturns415UnsupportedMediaType() throws Exception {
        HttpResponse<String> response = sendRaw(http, localServerUrl(), "POST", "/api/v1/sessions",
                aliceToken, "text/plain", null, "{}");

        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        assertThat(code(response)).isEqualTo("unsupported-media-type");
    }

    @Test
    void oversizedBodyReturns413PayloadTooLarge() throws Exception {
        String bigTitle = "x".repeat(10 * 1024);
        HttpResponse<String> response = sendRaw(http, localServerUrl(), "POST", "/api/v1/sessions",
                aliceToken, "application/json", null,
                "{\"title\":\"" + bigTitle + "\",\"agentKey\":\"" + agentKey + "\"}");

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        assertThat(code(response)).isEqualTo("payload-too-large");
    }

    @Test
    void bodyWithinLimitIsNotRejectedAsTooLarge() throws Exception {
        String title = "y".repeat(4 * 1024);
        HttpResponse<String> response = sendRaw(http, localServerUrl(), "POST", "/api/v1/sessions",
                aliceToken, "application/json", null,
                "{\"title\":\"" + title + "\",\"agentKey\":\"" + agentKey + "\"}");

        assertThat(response.statusCode()).isEqualTo(201);
    }

    @Test
    void missingRequiredFieldReturns422WithErrorsPointer() throws Exception {
        HttpResponse<String> response = sendRaw(http, localServerUrl(), "POST", "/api/v1/sessions",
                aliceToken, "application/json", null, "{\"title\":\"Без агента\"}");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(code(response)).isEqualTo("validation-failed");
        JsonNode errors = MAPPER.readTree(response.body()).get("errors");
        assertThat(errors).isNotNull();
        assertThat(errors.size()).isGreaterThan(0);
        assertThat(errors.get(0).get("pointer").asString()).isEqualTo("/agentKey");
        assertThat(errors.get(0).get("rule").asString()).isEqualTo("required");
        assertThat(errors.get(0).get("message").asString()).isNotBlank();
    }

    @Test
    void emptyStringAgentKeyReturns422MinLength() throws Exception {
        HttpResponse<String> response = sendRaw(http, localServerUrl(), "POST", "/api/v1/sessions",
                aliceToken, "application/json", null, "{\"agentKey\":\"\"}");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(code(response)).isEqualTo("validation-failed");
        assertThat(MAPPER.readTree(response.body()).get("errors").get(0).get("rule").asString())
                .isEqualTo("minLength");
    }

    @Test
    void blankTitleReturns422ByServiceRule() throws Exception {
        HttpResponse<String> response = sendRaw(http, localServerUrl(), "POST", "/api/v1/sessions",
                aliceToken, "application/json", null, "{\"title\":\" \",\"agentKey\":\"" + agentKey + "\"}");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(code(response)).isEqualTo("validation-failed");
        assertThat(MAPPER.readTree(response.body()).get("errors").get(0).get("rule").asString())
                .isEqualTo("blank");
    }

    @Test
    void malformedJsonBodyReturns422() throws Exception {
        HttpResponse<String> response = sendRaw(http, localServerUrl(), "POST", "/api/v1/sessions",
                aliceToken, "application/json", null, "{\"agentKey\": ");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(code(response)).isEqualTo("validation-failed");
    }

    @Test
    void limitBelowMinimumReturns422() throws Exception {
        HttpResponse<String> response = sendRaw(http, localServerUrl(), "GET", "/api/v1/sessions?limit=0",
                aliceToken, null, null, null);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(code(response)).isEqualTo("validation-failed");
    }

    private String code(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body()).get("code").asString();
    }
}
