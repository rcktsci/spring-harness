package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Параметры async-инструментов (M3, D-60): окно синхронного ожидания async-capable
 * инструмента (превышение → {@code ASYNC_ACCEPTED} + фон) и интервал повторной попытки
 * взятия sess-лока при публикации позднего {@code TOOL_RESULT} (D-64).
 */
@ConfigurationProperties(prefix = "harness.async")
public record AsyncProperties(Window window, Duration latePublishRetry) {

    /** Окно синхронного ожидания (дефолт в yml — 30 с); превышение — парковка вызова. */
    public record Window(Duration defaultMs) {
    }
}
