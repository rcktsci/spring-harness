package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Явный таймаут LLM-вызовов в каждой сборке options (защита от невидимых дефолтов #6915).
 */
@ConfigurationProperties(prefix = "harness.llm")
public record LlmProperties(Duration timeout) {
}
