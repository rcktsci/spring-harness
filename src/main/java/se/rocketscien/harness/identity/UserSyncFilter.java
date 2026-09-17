package se.rocketscien.harness.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import se.rocketscien.harness.common.IdGenerator;

import java.io.IOException;

/**
 * Синхронизация пользователей (спека sso-gate): upsert по {@code keycloak_subject} при каждом
 * запросе, прошедшем SSO-гейт: создаёт {@code app_user} при первом запросе нового subject,
 * обновляет username/display_name при изменении в токене. Пользователи не удаляются.
 * <p>Не является бином: исключается двойная servlet-регистрация, экземпляр собирается
 * в {@link SecurityConfig} внутри security-цепочки.</p>
 */
public class UserSyncFilter extends OncePerRequestFilter {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserSyncFilter.class);

    private final JdbcTemplate jdbcTemplate;
    private final IdGenerator idGenerator;

    public UserSyncFilter(JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
        this.jdbcTemplate = jdbcTemplate;
        this.idGenerator = idGenerator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication && jwtAuthentication.isAuthenticated()) {
            syncUser(jwtAuthentication.getToken());
        }
        filterChain.doFilter(request, response);
    }

    private void syncUser(Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        if (jwt.getSubject() == null || username == null) {
            return;
        }
        log.debug("Синхронизация пользователя: subject={}, username={}", jwt.getSubject(), username);
        String displayName = jwt.getClaimAsString("name");
        jdbcTemplate.update(
                """
                INSERT INTO app_user (id, keycloak_subject, username, display_name, created_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (keycloak_subject) DO UPDATE
                    SET username = EXCLUDED.username, display_name = EXCLUDED.display_name
                    WHERE app_user.username IS DISTINCT FROM EXCLUDED.username
                       OR app_user.display_name IS DISTINCT FROM EXCLUDED.display_name
                """,
                idGenerator.newUuidV7(),
                jwt.getSubject(),
                username,
                displayName != null ? displayName : username
        );
    }
}
