package se.rocketscien.harness.session;

/**
 * Opaque-курсор страницы не декодируется (битый/подделанный) — ошибка клиента
 * (api-contracts §0: пагинация). Checked: PersistenceExceptionTranslation оборачивает
 * любые RuntimeException транзакционного прокси и не должен маскировать семантику.
 */
public class InvalidCursorException extends Exception {

    public InvalidCursorException(String message, Throwable cause) {
        super(message, cause);
    }
}
