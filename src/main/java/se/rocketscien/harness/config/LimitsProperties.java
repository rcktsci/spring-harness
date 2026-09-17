package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Эксплуатационные лимиты: тело запроса, вывод инструмента (с маркером truncated), запас к пределу
 * bounded-захвата вывода exec (C-J-1), таймаут bash по умолчанию и верхняя граница таймаута bash.
 */
@ConfigurationProperties(prefix = "harness.limits")
public record LimitsProperties(
        DataSize body,
        DataSize toolOutput,
        DataSize toolCaptureMargin,
        Duration bashTimeout,
        Duration bashTimeoutCap
) {
}
