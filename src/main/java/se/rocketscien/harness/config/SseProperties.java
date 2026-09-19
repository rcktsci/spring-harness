package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Параметры SSE-потоков: интервал ping-комментариев, таймаут соединения эмиттера
 * (0 — без таймаута, реконнект на клиенте) и размер backlog событий задачи
 * (бэкфилл реконнекта SSE {@code tasks/{id}/events} за курсором, пачка J.4).
 */
@ConfigurationProperties(prefix = "harness.sse")
public record SseProperties(Duration pingInterval, Duration timeout, Integer taskBacklog) {
}
