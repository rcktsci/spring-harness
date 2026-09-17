package se.rocketscien.harness.identity;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.WireMockJwksInitializer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("jwtmock")
@ContextConfiguration(initializers = WireMockJwksInitializer.class)
class JwtSecurityTest extends BaseApplicationTest {

    private static final String ALLOWED_GROUP = "harness-users";

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void noTokenReturns401ProblemDetailsWithoutChallenge() throws Exception {
        Response response = get("/api/v1/ping", null);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.body()).contains("\"code\":\"unauthenticated\"");
        assertThat(response.wwwAuthenticate()).isNull();
    }

    @Test
    void garbageTokenReturns401() throws Exception {
        Response response = get("/api/v1/ping", "garbage-not-a-jwt");

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"unauthenticated\"");
    }

    @Test
    void expiredTokenReturns401() throws Exception {
        String token = token(claims -> claims
                .expirationTime(Date.from(Instant.now().minusSeconds(60)))
                .claim("groups", List.of(ALLOWED_GROUP)));

        Response response = get("/api/v1/ping", token);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"unauthenticated\"");
    }

    @Test
    void foreignIssuerTokenReturns401() throws Exception {
        String token = token(claims -> claims
                .issuer("https://foreign.issuer")
                .claim("groups", List.of(ALLOWED_GROUP)));

        Response response = get("/api/v1/ping", token);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"unauthenticated\"");
    }

    @Test
    void tokenWithoutGroupsClaimReturns401() throws Exception {
        String token = token(claims -> claims);

        Response response = get("/api/v1/ping", token);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"unauthenticated\"");
    }

    @Test
    void tokenOutsideAllowedGroupsReturns401() throws Exception {
        String token = token(claims -> claims.claim("groups", List.of("some-other-group")));

        Response response = get("/api/v1/ping", token);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"unauthenticated\"");
    }

    @Test
    void tokenInAllowedGroupGetsFullAccess() throws Exception {
        String token = token(claims -> claims.claim("groups", List.of("readers", ALLOWED_GROUP)));

        Response response = get("/api/v1/ping", token);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"ok\"");
    }

    private Response get(String path, String bearerToken) throws IOException, InterruptedException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
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

    @Test
    void tokenSignedByUnknownKeyReturns401() throws Exception {
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
        String token = token(claims -> claims.claim("groups", List.of(ALLOWED_GROUP)), foreignKey);

        Response response = get("/api/v1/ping", token);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"unauthenticated\"");
    }

    private String token(UnaryOperator<JWTClaimsSet.Builder> customizer) {
        return token(customizer, WireMockJwksInitializer.SIGNING_KEY);
    }

    private String token(UnaryOperator<JWTClaimsSet.Builder> customizer, RSAKey signingKey) {
        try {
            JWTClaimsSet.Builder builder = customizer.apply(new JWTClaimsSet.Builder()
                    .issuer(WireMockJwksInitializer.ISSUER)
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
