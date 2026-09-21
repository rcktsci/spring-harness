package se.rocketscien.harness.relay;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * Обёртка серверного {@link WebSocketSession}: исходящие кадры сериализуются
 * {@link ConcurrentWebSocketSessionDecorator} (D-83) — параллельные отправки из Turn'ов
 * root- и sub-сессий, ping'ов планировщика и регистрационных ответов не перемешиваются.
 */
@Slf4j
public class WebSocketRelayConnection implements RelayConnection {

    private final WebSocketSession session;
    private final String principal;

    public WebSocketRelayConnection(WebSocketSession session, String principal,
                                    int sendTimeLimitMs, int bufferSizeLimitBytes) {
        this.session = new ConcurrentWebSocketSessionDecorator(session, sendTimeLimitMs, bufferSizeLimitBytes);
        this.principal = principal;
    }

    @Override
    public String principal() {
        return principal;
    }

    @Override
    public void sendText(String frame) {
        try {
            session.sendMessage(new TextMessage(frame));
        } catch (Exception e) {
            log.debug("Кадр релея не отправлен (соединение закрыто/переполнено): {}", e.getMessage());
        }
    }

    @Override
    public void close(int statusCode, String reason) {
        try {
            if (session.isOpen()) {
                session.close(new CloseStatus(statusCode, reason));
            }
        } catch (Exception e) {
            log.debug("Закрытие соединения релея не удалось: {}", e.getMessage());
        }
    }
}
