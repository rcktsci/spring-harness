package se.rocketscien.harness.relay;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Регистрация WS-релея на сервлет-стеке (D-83): {@code @EnableWebSocket} + {@link WebSocketConfigurer}.
 * Путь {@code /api/v1/relay} совпадает с api-contracts §5; аутентификация — на handshake через
 * {@link RelayHandshakeInterceptor} (bearer-JWT), поэтому HTTP-цепочка безопасно пропускает апгрейд
 * (см. relay-цепочку в {@code SecurityConfig}).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@RequiredArgsConstructor
public class RelayWebSocketConfig implements WebSocketConfigurer {

    static final String RELAY_PATH = "/api/v1/relay";

    private final RelayWebSocketHandler relayWebSocketHandler;
    private final RelayHandshakeInterceptor relayHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(relayWebSocketHandler, RELAY_PATH)
                .addInterceptors(relayHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
