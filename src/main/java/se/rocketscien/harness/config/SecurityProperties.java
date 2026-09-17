package se.rocketscien.harness.config;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Гейт допуска: claim {@code groups} должен пересекаться с {@link #allowedGroups} (D-41);
 * {@code issuerUri}/{@code jwkSetUri} — параметры валидации Keycloak-JWT.
 * {@code allowedGroups} обязателен: пустой список — ошибка старта (fail-closed), не NPE в запросе.
 */
@Validated
@ConfigurationProperties(prefix = "harness.security")
public record SecurityProperties(
        @NotEmpty List<String> allowedGroups,
        String issuerUri,
        String jwkSetUri
) {
}
