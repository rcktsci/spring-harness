package se.rocketscien.harness.task;

/**
 * Курсор страницы не декодируется — ошибка клиента (422; стиль session.InvalidCursorException,
 * свой экземпляр — модуль task не зависит от session).
 */
public class InvalidCursorException extends Exception {

    public InvalidCursorException(String message, Throwable cause) {
        super(message, cause);
    }
}
