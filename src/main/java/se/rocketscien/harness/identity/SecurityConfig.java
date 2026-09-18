package se.rocketscien.harness.identity;

import lombok.SneakyThrows;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.config.SecurityProperties;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Resource server: Bearer JWT на всех {@code /api/v1/**} (спека sso-gate). Отказ — всегда
 * {@code 401} Problem Details RFC 9457 с {@code code: unauthenticated}, без WWW-Authenticate
 * challenge. Порядок фильтров: BearerTokenAuthenticationFilter → GroupsGateFilter → UserSyncFilter.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @SneakyThrows
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            AuthenticationEntryPoint unauthenticatedEntryPoint,
            SecurityProperties securityProperties,
            JdbcTemplate jdbcTemplate,
            IdGenerator idGenerator
    ) {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(jwtDecoder))
                        .authenticationEntryPoint(unauthenticatedEntryPoint))
        .exceptionHandling(handling -> handling.authenticationEntryPoint(unauthenticatedEntryPoint))
        // GroupsGateFilter выше ExceptionTranslationFilter: бросая AuthenticationException,
        // он не перехватывается entry point-ом в этом прогоне; при error-dispatch контейнер
        // запускает цепочку заново, entry point обрабатывает Exception → 401 ProblemDetails.
        .addFilterAfter(new GroupsGateFilter(securityProperties), BearerTokenAuthenticationFilter.class)
        .addFilterBefore(new UserSyncFilter(jdbcTemplate, idGenerator), AuthorizationFilter.class);
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder(SecurityProperties securityProperties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(securityProperties.jwkSetUri()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(securityProperties.issuerUri()));
        return decoder;
    }

    @Bean
    public AuthenticationEntryPoint unauthenticatedEntryPoint(ObjectMapper objectMapper) {
        return (HttpServletRequest request, HttpServletResponse response,
                 org.springframework.security.core.AuthenticationException exception) -> writeProblemDetails(
                response, objectMapper, exception);
    }

    @SneakyThrows
    private static void writeProblemDetails(HttpServletResponse response, ObjectMapper objectMapper,
                                            org.springframework.security.core.AuthenticationException exception) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, exception.getMessage());
        problemDetail.setTitle("Unauthorized");
        problemDetail.setProperty("code", "unauthenticated");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        objectMapper.writeValue(response.getOutputStream(), problemDetail);
    }
}
