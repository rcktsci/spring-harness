package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Страховочная компакция: доля окна модели, при превышении которой контекст сворачивается.
 * {@code readMaxBytes} — лимит одной записи ответа {@code read_compacted} (M3 O.4, D-67):
 * оригинал больше лимита — усечённый с маркером {@code truncated}.
 */
@ConfigurationProperties(prefix = "harness.compact")
public record CompactProperties(double threshold, DataSize readMaxBytes) {
}
