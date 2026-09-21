package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Параметры WS-релея (M4, api-contracts §5, D-83): интервал серверного heartbeat
 * ({@code ping} инициирует сервер, разрыв по {@code 2 × heartbeat-interval} без pong),
 * верхний таймаут ответа {@code tool.call} (пачка W) и пределы декоратора исходящих
 * кадров {@code ConcurrentWebSocketSessionDecorator} (сериализация отправок).
 */
@ConfigurationProperties(prefix = "harness.relay")
public record RelayProperties(
        Duration heartbeatInterval,
        Duration toolCallTimeout,
        Duration sendTimeLimit,
        DataSize bufferSizeLimit
) {
}
