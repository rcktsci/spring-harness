package se.rocketscien.harness.tests.identity;

import lombok.SneakyThrows;
import java.util.HashMap;
import java.util.Map;


import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.config.KeycloakContextInitializer;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JWT-гейт (specs/sso-gate) на живом Keycloak: позитивный путь и groups-гейт — реальные
 * токены (password grant); негативные кейсы подписи/issuer/срока — самоподписанные токены
 * (допустимы по директиве владельца, пачка D-2).
 */
class JwtSecurityTest extends BaseApplicationTest {

    private static final String ALLOWED_GROUP = "harness-users";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @AfterEach
    void restoreBobGroups() {
        try {
            KeycloakContextInitializer.adminClient().realm("harness").users()
                    .search("bob").stream()
                    .findFirst()
                    .ifPresent(user -> {
                        if (user.getAttributes() != null && user.getAttributes().containsKey("groups")) {
                            user.getAttributes().remove("groups");
                            try {
                                KeycloakContextInitializer.adminClient().realm("harness")
                                        .users().get(user.getId()).update(user);
                            } catch (Exception ignore) { }
                        }
                    });
        } catch (Exception ignore) { }
    }

    @Test
    @SneakyThrows
    void noTokenReturns401ProblemDetailsWithoutChallenge() {
        Response response = get("/api/v1/ping", null);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.body()).contains("\"code\":\"unauthenticated\"");
        assertThat(response.wwwAuthenticate()).isNull();
    }

    @Test
    @SneakyThrows
    void garbageTokenReturns401() {
        Response response = get("/api/v1/ping", "garbage-not-a-jwt");

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"unauthenticated\"");
    }

    @Test
    @SneakyThrows
    void expiredTokenReturns401() {
        String token = selfSigned(claims -> claims
                .expirationTime(Date.from(Instant.now().minusSeconds(60)))
                .claim("groups", List.of(ALLOWED_GROUP)));

        Response response = get("/api/v1/ping", token);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"unauthenticated\"");
    }

    @Test
    @SneakyThrows
    void foreignIssuerTokenReturns401() {
        String token = selfSigned(claims -> claims
                .issuer("https://foreign.issuer")
                .claim("groups", List.of(ALLOWED_GROUP)));

        Response response = get("/api/v1/ping", token);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"unauthenticated\"");
    }

    @Test
    @SneakyThrows
    void tokenWithoutGroupsClaimReturns401() {
        // bob в realm без атрибута groups — реальный Keycloak-токен без claim groups
        Response response = get("/api/v1/ping", keycloakToken("bob", "bob-password"));

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"unauthenticated\"");
    }

    @Test
    @SneakyThrows
    void tokenOutsideAllowedGroupsReturns401() {
        // bob'у выдаётся ЧУЖАЯ группа через Keycloak admin API — реальный токен,
        // groups-гейт отклоняет её
        setBobGroups(List.of("some-other-group"));

        Response response = get("/api/v1/ping", keycloakToken("bob", "bob-password"));

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"unauthenticated\"");
    }

    @Test
    @SneakyThrows
    void tokenInAllowedGroupGetsFullAccess() {
        // alice в realm несёт groups=[harness-users] — позитивный путь через живой Keycloak
        Response response = get("/api/v1/ping", keycloakToken("alice", "alice-password"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"ok\"");
    }

    @Test
    @SneakyThrows
    void tokenSignedByUnknownKeyReturns401() {
        RSAKey foreignKey;
        try {
            foreignKey = new RSAKeyGenerator(2048)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID("foreign-key")
                    .generate();
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сгенерировать посторонний ключ", e);
        }
        String token = selfSigned(claims -> claims.claim("groups", List.of(ALLOWED_GROUP)), foreignKey);

        Response response = get("/api/v1/ping", token);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"unauthenticated\"");
    }

    @SneakyThrows
    private Response get(String path, String bearerToken) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(localServerUrl() + path))
                .GET();
        if (bearerToken != null) {
            requestBuilder.header("Authorization", "Bearer " + bearerToken);
        }
        HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(
                response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(null),
                response.headers().firstValue("WWW-Authenticate").orElse(null),
                response.body()
        );
    }

    private void setBobGroups(List<String> groups) {
        KeycloakContextInitializer.adminClient().realm("harness").users()
                .search("bob").stream()
                .findFirst()
                .ifPresent(user -> {
                    Map<String, List<String>> attributes =
                            new HashMap<>(user.getAttributes() == null ? Map.of() : user.getAttributes());
                    attributes.put("groups", groups);
                    user.setAttributes(attributes);
                    KeycloakContextInitializer.adminClient().realm("harness")
                            .users().get(user.getId()).update(user);
                });
    }

    @SneakyThrows
    private String keycloakToken(String username, String password) {
        String form = "grant_type=password"
                + "&client_id=harness-cli"
                + "&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(KeycloakContextInitializer.authServerUrl()
                                + "/realms/harness/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return extractAccessToken(response.body());
    }

    private static String extractAccessToken(String tokenResponseBody) {
        int access_tokenIndex = tokenResponseBody.indexOf("\"access_token\":\"");
        int start = access_tokenIndex + "\"access_token\":\"".length();
        int end = tokenResponseBody.indexOf('"', start);
        return tokenResponseBody.substring(start, end);
    }

    private String selfSigned(UnaryOperator<JWTClaimsSet.Builder> customizer) {
        RSAKey key;
        try {
            key = new RSAKeyGenerator(2048)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID("test-key-" + Instant.now().toEpochMilli())
                    .generate();
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сгенерировать тестовый ключ", e);
        }
        return selfSigned(customizer, key);
    }

    private String selfSigned(UnaryOperator<JWTClaimsSet.Builder> customizer, RSAKey signingKey) {
        try {
            JWTClaimsSet.Builder builder = customizer.apply(new JWTClaimsSet.Builder()
                    .issuer(KeycloakContextInitializer.authServerUrl() + "/realms/harness")
                    .subject("test-user")
                    .audience(List.of("harness"))
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300))));
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), builder.build());
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось подписать тестовый JWT", e);
        }
    }

    private record Response(int status, String contentType, String wwwAuthenticate, String body) {
    }
}
