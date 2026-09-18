package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Параметры SSE-потока: интервал ping-комментариев и таймаут соединения эмиттера
 * (0 — без таймаута, реконнект на клиенте).
 */
@ConfigurationProperties(prefix = "harness.sse")
public record SseProperties(Duration pingInterval, Duration timeout) {
}
