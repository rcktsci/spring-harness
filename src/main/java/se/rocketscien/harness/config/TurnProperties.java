package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Wake POLL и политика LLM-ретраев: экспоненциальный backoff с базой {@code backoffBase},
 * до {@code llmRetries} попыток (D-M1-3).
 */
@ConfigurationProperties(prefix = "harness.turn")
public record TurnProperties(Duration pollInterval, int llmRetries, Duration backoffBase) {
}
