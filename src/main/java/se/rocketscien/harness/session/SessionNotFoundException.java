package se.rocketscien.harness.session;

import java.util.UUID;

/**
 * Сессия не найдена — сервисная ошибка {@code 404 session-not-found} (api-contracts §6).
 */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(String message) {
        super(message);
    }
}
