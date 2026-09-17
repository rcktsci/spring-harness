package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Интервал ping-комментариев SSE-потока.
 */
@ConfigurationProperties(prefix = "harness.sse")
public record SseProperties(Duration pingInterval) {
}
