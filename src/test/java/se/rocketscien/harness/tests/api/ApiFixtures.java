package se.rocketscien.harness.tests.api;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.SneakyThrows;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.common.AesGcmEncryption;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.testclient.ApiClient;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Общие фикстуры API-тестов (tests/api): сиды БД (пользователь → credentials WireMock-LLM →
 * модель → агент), STATE-строка напрямую в БД (8.4), токены живого Keycloak (password grant),
 * негативный самоподписанный JWT, сгенерированный тест-клиент (java/native), сырой HTTP-клиент.
 */
final class ApiFixtures {

    /** Тот же ключ, что harness.llm.encryption-keys.1 в application-test.yml. */
    private static final byte[] LLM_KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private ApiFixtures() {
    }

    record AgentSeed(String agentKey, UUID revisionId) {
    }

    /** Пользователь → credentials (WireMock-LLM) → модель → агент; возвращает seed агента. */
    static AgentSeed insertAgentChain(JdbcTemplate jdbcTemplate, IdGenerator idGenerator, Environment environment) {
        UUID ownerId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                "INSERT INTO app_user (id, keycloak_subject, username, display_name, created_at)"
                        + " VALUES (?, ?, ?, ?, now())",
                ownerId, "subject-" + ownerId, "user-" + ownerId, "Пользователь");

        UUID credentialsId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                "INSERT INTO llm_credentials (id, name, base_url, api_key_encrypted, key_version, created_at)"
                        + " VALUES (?, ?, ?, ?, 1, now())",
                credentialsId, "creds-" + credentialsId,
                environment.getRequiredProperty("wiremock.llm.url") + "/v1",
                encrypt("sk-test"));

        UUID modelId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                "INSERT INTO llm_model (id, credentials_id, model_id, created_at) VALUES (?, ?, ?, now())",
                modelId, credentialsId, "gpt-test");

        UUID revisionId = idGenerator.newUuidV7();
        String agentKey = "agent-" + revisionId;
        jdbcTemplate.update(
                "INSERT INTO agent (id, key, name, description, rev, role_prompt, llm_model_id, created_at)"
                        + " VALUES (?, ?, ?, ?, 1, ?, ?, now())",
                revisionId, agentKey, "Агент API", "Описание агента", "Ты исполнитель.", modelId);
        return new AgentSeed(agentKey, revisionId);
    }

    /** Пользователь app_user напрямую (STATE-фикстуре нужен владелец). */
    static UUID insertAppUser(JdbcTemplate jdbcTemplate) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO app_user (id, keycloak_subject, username, display_name, created_at)"
                        + " VALUES (?, ?, ?, ?, now())",
                userId, "subject-" + userId, "user-" + userId, "Пользователь");
        return userId;
    }

    /** STATE-строка напрямую в БД (8.4: STATE создаёт движок задач, M2 — API его не создаёт). */
    static UUID insertStateSession(JdbcTemplate jdbcTemplate, UUID ownerUserId, UUID agentRevisionId) {
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO session (id, title, owner_user_id, kind, task_id, state_code, agent_revision_id,
                                     cancel_requested, last_seq, last_consumed_seq, last_activity_at, created_at)
                VALUES (?, 'STATE-сессия', ?, 'STATE', ?, 'REVIEW', ?, false, 0, 0, now(), now())
                """,
                sessionId, ownerUserId, UUID.randomUUID(), agentRevisionId);
        return sessionId;
    }

    /** Токен живого Keycloak (password grant); alice несёт группу harness-users. */
    @SneakyThrows
    static String keycloakToken(HttpClient httpClient, String username, String password) {
        String form = "grant_type=password"
                + "&client_id=harness-cli"
                + "&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        se.rocketscien.harness.config.KeycloakContextInitializer.authServerUrl()
                                + "/realms/harness/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Keycloak не выдал токен: " + response.statusCode());
        }
        String body = response.body();
        int start = body.indexOf("\"access_token\":\"") + "\"access_token\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }

    /** Самоподписанный JWT посторонним ключом (негативный кейс подписи, директива владельца D-2). */
    @SneakyThrows
    static String selfSignedForeignToken() {
        RSAKey foreignKey = new RSAKeyGenerator(2048)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .keyID("foreign-" + Instant.now().toEpochMilli())
                .generate();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(se.rocketscien.harness.config.KeycloakContextInitializer.authServerUrl() + "/realms/harness")
                .subject("intruder")
                .audience(List.of("harness"))
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("groups", List.of("harness-users"))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims);
        jwt.sign(new RSASSASigner(foreignKey));
        return jwt.serialize();
    }

    /** Сгенерированный тест-клиент (java/native) поверх живого приложения с bearer-токеном. */
    static ApiClient apiClient(String baseUrl, String bearerToken) {
        ApiClient client = new ApiClient();
        client.updateBaseUri(baseUrl + "/api/v1");
        return client.setRequestInterceptor(builder -> builder.header("Authorization", "Bearer " + bearerToken));
    }

    /** Сырой запрос — полный контроль заголовков (405/406/415/413, PATCH с чужим Content-Type). */
    @SneakyThrows
    static HttpResponse<String> sendRaw(HttpClient httpClient, String baseUrl, String method, String pathAndQuery,
                                        String bearerToken, String contentType, String accept, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery))
                .header("Accept", accept == null ? "application/json" : accept);
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        if (body != null) {
            if (contentType != null) {
                builder.header("Content-Type", contentType);
            }
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** OpenAI-совместимый стрим-ответ WireMock: текстовый ответ + usage (без tool-calls). */
    static String textCompletionChunks(String text) {
        return "data: " + textChunk(text) + "\n\n"
                + "data: " + usageChunk() + "\n\n"
                + "data: [DONE]\n\n";
    }

    static String textChunk(String content) {
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"},\"finish_reason\":null}]}";
    }

    private static String usageChunk() {
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    }

    @SneakyThrows
    private static String encrypt(String value) {
        return AesGcmEncryption.encrypt(value, LLM_KEY);
    }
}
