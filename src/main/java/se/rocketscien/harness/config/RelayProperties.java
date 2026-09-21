package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

/**
 * Параметры WS-релея (M4, api-contracts §5, D-83): интервал серверного heartbeat
 * ({@code ping} инициирует сервер, разрыв по {@code 2 × heartbeat-interval} без pong),
 * верхний таймаут ответа {@code tool.call} (пачка W), пределы декоратора исходящих
 * кадров {@code ConcurrentWebSocketSessionDecorator} (сериализация отправок) и
 * доверенные origin для handshake (Web Desktop-фаза; в M4 — тестовый клиент).
 */
@ConfigurationProperties(prefix = "harness.relay")
public record RelayProperties(
        Duration heartbeatInterval,
        Duration toolCallTimeout,
        Duration sendTimeLimit,
        DataSize bufferSizeLimit,
        List<String> allowedOriginPatterns
) {
}
