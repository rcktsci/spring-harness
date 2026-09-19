package se.rocketscien.harness.workflow;

/**
 * Курсор страницы не декодируется — ошибка клиента (422; стиль session.InvalidCursorException,
 * свой экземпляр — модуль workflow не зависит от session).
 */
public class InvalidCursorException extends Exception {

    public InvalidCursorException(String message, Throwable cause) {
        super(message, cause);
    }
}
