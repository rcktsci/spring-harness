package se.rocketscien.harness.api;

/**
 * HMAC capability-токен вебхука не совпал (api-contracts §4.4) — {@code 401 signature-invalid},
 * без challenge (api-contracts §6).
 */
public class SignatureInvalidException extends RuntimeException {

    public SignatureInvalidException(String message) {
        super(message);
    }
}
