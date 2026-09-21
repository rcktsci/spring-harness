package se.rocketscien.harness.tests.relay;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import se.rocketscien.harness.config.SecurityProperties;
import se.rocketscien.harness.relay.RelayHandshakeInterceptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M4 T.3: аутентификация WS-handshake релея (D-41) — отсутствие/битый JWT и провал SSO-гейта
 * дают признак неудачи (хендлер закроет 4401), валидный JWT с разрешённой группой — principal.
 */
class RelayHandshakeInterceptorTest {

    private static final SecurityProperties SECURITY =
            new SecurityProperties(List.of("harness-users"), "issuer", "jwks");

    @Test
    void rejectsMissingAuthorizationHeader() {
        RelayHandshakeInterceptor interceptor = new RelayHandshakeInterceptor(neverDecode(), SECURITY);
        Map<String, Object> attributes = new HashMap<>();

        interceptor.beforeHandshake(request(null), null, null, attributes);

        assertThat(attributes).containsEntry(RelayHandshakeInterceptor.AUTH_FAILED_ATTRIBUTE, true)
                .doesNotContainKey(RelayHandshakeInterceptor.PRINCIPAL_ATTRIBUTE);
    }

    @Test
    void rejectsEmptyBearerToken() {
        RelayHandshakeInterceptor interceptor = new RelayHandshakeInterceptor(
                token -> {
                    throw new JwtException("empty");
                }, SECURITY);
        Map<String, Object> attributes = new HashMap<>();

        interceptor.beforeHandshake(request("Bearer "), null, null, attributes);

        assertThat(attributes).containsEntry(RelayHandshakeInterceptor.AUTH_FAILED_ATTRIBUTE, true);
    }

    @Test
    void rejectsInvalidToken() {
        RelayHandshakeInterceptor interceptor = new RelayHandshakeInterceptor(
                token -> {
                    throw new JwtException("bad signature");
                }, SECURITY);
        Map<String, Object> attributes = new HashMap<>();

        interceptor.beforeHandshake(request("Bearer broken"), null, null, attributes);

        assertThat(attributes).containsEntry(RelayHandshakeInterceptor.AUTH_FAILED_ATTRIBUTE, true)
                .doesNotContainKey(RelayHandshakeInterceptor.PRINCIPAL_ATTRIBUTE);
    }

    @Test
    void rejectsTokenWithoutAllowedGroups() {
        JwtDecoder decoder = token -> token(null, List.of("other-group"));
        RelayHandshakeInterceptor interceptor = new RelayHandshakeInterceptor(decoder, SECURITY);
        Map<String, Object> attributes = new HashMap<>();

        interceptor.beforeHandshake(request("Bearer good"), null, null, attributes);

        assertThat(attributes).containsEntry(RelayHandshakeInterceptor.AUTH_FAILED_ATTRIBUTE, true)
                .doesNotContainKey(RelayHandshakeInterceptor.PRINCIPAL_ATTRIBUTE);
    }

    @Test
    void acceptsTokenWithAllowedGroupAndResolvesUsername() {
        JwtDecoder decoder = token -> token("alice", List.of("harness-users"));
        RelayHandshakeInterceptor interceptor = new RelayHandshakeInterceptor(decoder, SECURITY);
        Map<String, Object> attributes = new HashMap<>();

        interceptor.beforeHandshake(request("Bearer good"), null, null, attributes);

        assertThat(attributes).containsEntry(RelayHandshakeInterceptor.PRINCIPAL_ATTRIBUTE, "alice")
                .doesNotContainKey(RelayHandshakeInterceptor.AUTH_FAILED_ATTRIBUTE);
    }

    private static JwtDecoder neverDecode() {
        return token -> {
            throw new AssertionError("decode не должен вызываться без Authorization");
        };
    }

    private static Jwt token(String username, List<String> groups) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none").subject("subject");
        if (username != null) {
            builder.claim("preferred_username", username);
        }
        if (groups != null) {
            builder.claim("groups", groups);
        }
        return builder.build();
    }

    private static ServerHttpRequest request(String authorization) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        when(request.getHeaders()).thenReturn(headers);
        return request;
    }
}
