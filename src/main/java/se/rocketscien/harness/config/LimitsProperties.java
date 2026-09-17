package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Эксплуатационные лимиты: тело запроса, вывод инструмента (с маркером truncated),
 * верхняя граница таймаута bash.
 */
@ConfigurationProperties(prefix = "harness.limits")
public record LimitsProperties(DataSize body, DataSize toolOutput, Duration bashTimeoutCap) {
}
