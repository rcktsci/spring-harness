package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Страховочная компакция: доля окна модели, при превышении которой контекст сворачивается.
 */
@ConfigurationProperties(prefix = "harness.compact")
public record CompactProperties(double threshold) {
}
