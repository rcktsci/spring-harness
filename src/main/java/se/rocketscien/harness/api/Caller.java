package se.rocketscien.harness.api;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.identity.AppUserDirectory;

import java.util.UUID;

/**
 * Атрибуция вызывающего (api-contracts §0): JWT из security-контекста, username —
 * {@code preferred_username}, внутренний id — через {@link AppUserDirectory}.
 */
@Component
@RequiredArgsConstructor
public class Caller {

    private final AppUserDirectory users;

    public Jwt jwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication && jwtAuthentication.isAuthenticated()) {
            return jwtAuthentication.getToken();
        }
        throw new IllegalStateException("Вызов вне аутентифицированного запроса");
    }

    public String username() {
        String username = jwt().getClaimAsString("preferred_username");
        if (username == null) {
            throw new IllegalStateException("JWT без preferred_username — гейт токенов пропустил кривой токен");
        }
        return username;
    }

    public UUID userId() {
        return users.idBySubject(jwt().getSubject());
    }
}
