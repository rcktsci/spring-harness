package se.rocketscien.harness.session;

/**
 * Команда неприменима к роду сессии (api-contracts §6): compact — только FREE, STATE →
 * {@code 409 wrong-session-kind}.
 */
public class WrongSessionKindException extends RuntimeException {

    public WrongSessionKindException(String message) {
        super(message);
    }
}
