package se.rocketscien.harness.tests.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.config.WebhookProperties;
import se.rocketscien.harness.testclient.ApiClient;
import se.rocketscien.harness.testclient.ApiException;
import se.rocketscien.harness.testclient.api.WebhooksApi;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring вебхуков D.2 (api-contracts §4.4): маршрутизация без /v1, отдельная
 * security-цепочка без JWT (чужой Bearer не валидируется), HMAC-гейт токена
 * (401 signature-invalid раньше 501-стаба), basePath тест-клиента /api.
 */
class WebhooksRoutingTest extends BaseApplicationTest {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Autowired
    protected WebhookProperties webhookProperties;

    @Test
    void badTokenWithoutJwtReturns401SignatureInvalid() throws IOException, InterruptedException {
        Response response = postWebhook(taskWebhookPath(UUID.randomUUID(), "bad-token"), null);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"signature-invalid\"");
    }

    @Test
    void foreignBearerDoesNotTriggerJwtGate() throws IOException, InterruptedException {
        // GLM M-1: вебхук-цепочка без BearerTokenAuthenticationFilter — мусорный Bearer
        // не превращает ответ в 401 unauthenticated; исход решает HMAC
        Response response = postWebhook(taskWebhookPath(UUID.randomUUID(), "bad-token"), "garbage-not-a-jwt");

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"signature-invalid\"");
        assertThat(response.body()).doesNotContain("unauthenticated");
    }

    @Test
    void validTokenReachesStub501NotImplemented() throws IOException, InterruptedException {
        UUID taskId = UUID.randomUUID();
        Response response = postWebhook(taskWebhookPath(taskId, hmac("task", taskId)), null);

        assertThat(response.status()).isEqualTo(501);
        assertThat(response.body()).contains("\"code\":\"not-implemented\"");
    }

    @Test
    void v1WebhookPathIsNotRegistered() throws IOException, InterruptedException {
        UUID taskId = UUID.randomUUID();

        Response response = post(
                localServerUrl() + "/api/v1/webhooks/tasks/" + taskId + "/" + hmac("task", taskId),
                null);

        // Маппинг есть только на /api/webhooks/**: /api/v1/webhooks/** попадает под
        // JWT-гейт основной цепочки (401 unauthenticated), а не в вебхук-контроллер
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"unauthenticated\"");
    }

    @Test
    void generatedClientResolvesApiBasePath() {
        // F-1: генератор игнорирует path-item servers — клиент вебхуков конструируется
        // с basePath=/api (паттерн для L.3 e2e); проверяем резолв /api/webhooks/...
        ApiClient apiClient = new ApiClient();
        apiClient.updateBaseUri(localServerUrl() + "/api");
        WebhooksApi webhooksApi = new WebhooksApi(apiClient);

        UUID taskId = UUID.randomUUID();
        ApiException exception = catchWebhookCall(webhooksApi, taskId);

        assertThat(exception.getCode()).isEqualTo(401);
        assertThat(exception.getResponseBody()).contains("\"code\":\"signature-invalid\"");
    }

    @Test
    void errorPathIsDeniedWith401Unauthenticated() throws IOException, InterruptedException {
        // N-1: catch-all цепочка — /error (достижим после редиректов) отдаёт 401
        // unauthenticated через общий entry point, а не 200/403
        Response response = get(localServerUrl() + "/error");

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"unauthenticated\"");
    }

    @Test
    void unlistedPathsAreDeniedWith401Unauthenticated() throws IOException, InterruptedException {
        // N-1: всё вне /api/webhooks/** и /api/v1/** закрыто (в т.ч. /actuator/**,
        // когда появится в M): аноним — 401 unauthenticated, без 200-утечек
        Response actuator = get(localServerUrl() + "/actuator/prometheus");
        Response root = get(localServerUrl() + "/");

        assertThat(actuator.status()).isEqualTo(401);
        assertThat(actuator.body()).contains("\"code\":\"unauthenticated\"");
        assertThat(root.status()).isEqualTo(401);
    }

    private ApiException catchWebhookCall(WebhooksApi webhooksApi, UUID taskId) {
        try {
            webhooksApi.handleTaskWebhook(taskId, "bad-token", Map.of(), null);
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("Ожидался ApiException (bad-token должен дать 401)");
    }

    private String taskWebhookPath(UUID taskId, String token) {
        return localServerUrl() + "/api/webhooks/tasks/" + taskId + "/" + token;
    }

    /** POST JSON-тела вебхука с опциональным мусорным Bearer (для проверки JWT-независимости). */
    private Response postWebhook(String url, String bearerToken) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"event\":\"ping\"}"));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return execute(builder);
    }

    private Response post(String url, String bearerToken) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return execute(builder);
    }

    private Response get(String url) throws IOException, InterruptedException {
        return execute(HttpRequest.newBuilder(URI.create(url)).GET());
    }

    private Response execute(HttpRequest.Builder builder) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private String hmac(String kind, UUID entityId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    webhookProperties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal((kind + ":" + entityId).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 недоступен", e);
        }
    }

    private record Response(int status, String body) {
    }
}
