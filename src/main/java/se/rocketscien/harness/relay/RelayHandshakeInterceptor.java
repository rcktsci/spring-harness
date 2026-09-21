package se.rocketscien.harness.relay;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import se.rocketscien.harness.config.SecurityProperties;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Аутентификация WS-handshake релея (api-contracts §5.1, D-41): Bearer-JWT проверяется тем же
 * {@link JwtDecoder}, что и REST, и проходит тот же SSO-гейт по claim {@code groups}.
 *
 * <p>Апгрейд не блокируется на уровне HTTP-цепочки: handshake всегда пропускается, а результат
 * аутентификации кладётся в атрибуты сессии — хендлер отдаёт клиенту WS-close {@code 4401}
 * вместо HTTP {@code 401} до апгрейда (спека client-relay: «без/с кривым токеном — close 4401»).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RelayHandshakeInterceptor implements HandshakeInterceptor {

    /** Имя аутентифицированного principal (или отсутствует при неудаче). */
    public static final String PRINCIPAL_ATTRIBUTE = "relay.principal";
    /** Признак неудачной аутентификации handshake. */
    public static final String AUTH_FAILED_ATTRIBUTE = "relay.authFailed";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder jwtDecoder;
    private final SecurityProperties securityProperties;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            attributes.put(AUTH_FAILED_ATTRIBUTE, true);
            return true;
        }
        try {
            Jwt jwt = jwtDecoder.decode(header.substring(BEARER_PREFIX.length()));
            if (!passesGroupsGate(jwt)) {
                log.debug("Релей: JWT без разрешённых групп — handshake отклонён (4401)");
                attributes.put(AUTH_FAILED_ATTRIBUTE, true);
                return true;
            }
            attributes.put(PRINCIPAL_ATTRIBUTE, principalName(jwt));
        } catch (Exception e) {
            // T-10: любая аномалия декодера (пустой/битый токен) — тот же close 4401, не 500.
            log.debug("Релей: невалидный JWT — handshake отклонён (4401): {}", e.getMessage());
            attributes.put(AUTH_FAILED_ATTRIBUTE, true);
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // Аутентификация завершена в beforeHandshake — пост-обработка не требуется.
    }

    private boolean passesGroupsGate(Jwt jwt) {
        List<String> groups = jwt.getClaimAsStringList("groups");
        return groups != null && !Collections.disjoint(groups, securityProperties.allowedGroups());
    }

    private String principalName(Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        return username != null ? username : jwt.getSubject();
    }
}
