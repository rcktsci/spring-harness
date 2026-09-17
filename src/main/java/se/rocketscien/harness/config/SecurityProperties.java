package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Гейт допуска: claim {@code groups} должен пересекаться с {@link #allowedGroups} (D-41).
 */
@ConfigurationProperties(prefix = "harness.security")
public record SecurityProperties(List<String> allowedGroups) {
}
