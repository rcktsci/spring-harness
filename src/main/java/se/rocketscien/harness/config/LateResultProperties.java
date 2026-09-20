package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Параметры поздних результатов async-инструментов (M3 N.6, D-64): верхний лимит ожидания
 * ({@code ASYNC_ACCEPTED} старше — синтетический LOST от {@code AsyncTimeoutWatcher}),
 * расписание скана и TTL ShedLock-лока джобы.
 */
@ConfigurationProperties(prefix = "harness.late-result")
public record LateResultProperties(Duration timeoutMs, Duration watchSchedule, Timeout timeout) {

    /** TTL ShedLock-лока {@code async-timeout-watcher} ({@code lockAtMostFor}); дефолт yml — 10 с. */
    public record Timeout(Duration ttl) {
    }
}
