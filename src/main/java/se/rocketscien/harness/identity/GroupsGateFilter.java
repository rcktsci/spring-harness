package se.rocketscien.harness.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import se.rocketscien.harness.config.SecurityProperties;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * SSO-гейт (D-41): аутентифицированный JWT обязан содержать в claim {@code groups} хотя бы одну
 * группу из {@code harness.security.allowed-groups}. Иначе — {@code 401 unauthenticated}
 * (GroupsNotAllowedException — AuthenticationException, обрабатывается entry point-ом).
 * Прошедший гейт получает полный доступ без проверок владения/прав.
 * <p>Не является бином: исключается двойная servlet-регистрация, экземпляр собирается
 * в {@link SecurityConfig} внутри security-цепочки.</p>
 */
@RequiredArgsConstructor
public class GroupsGateFilter extends OncePerRequestFilter {

    private final SecurityProperties securityProperties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication
                && jwtAuthentication.isAuthenticated()
                && !passesGroupsGate(jwtAuthentication.getToken())) {
            throw new GroupsNotAllowedException("JWT не содержит разрешённых групп");
        }
        filterChain.doFilter(request, response);
    }

    private boolean passesGroupsGate(Jwt jwt) {
        List<String> groups = jwt.getClaimAsStringList("groups");
        return groups != null && !Collections.disjoint(groups, securityProperties.allowedGroups());
    }
}
