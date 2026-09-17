package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Локи сессий ShedLock (D-40): {@code sessionTtl} заведомо больше максимального Turn,
 * {@code jobTtl} — {@code lockAtMostFor} POLL-джобы.
 */
@ConfigurationProperties(prefix = "harness.lock")
public record LockProperties(Duration sessionTtl, Duration heartbeatInterval, Duration jobTtl) {
}
