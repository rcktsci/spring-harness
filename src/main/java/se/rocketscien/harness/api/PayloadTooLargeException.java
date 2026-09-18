package se.rocketscien.harness.api;

/**
 * Тело запроса превышает {@code harness.limits.body} → {@code 413 payload-too-large}
 * (api-contracts §0). Бросается {@link PayloadSizeFilter}-ом при чтении потоковой передачи.
 */
public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(String message) {
        super(message);
    }
}
