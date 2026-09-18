package se.rocketscien.harness.api;

/**
 * Сервисный ответ переходного периода apply-прохода: метод контракта ещё не реализован
 * (stubs пачки D.2) — {@code 501 not-implemented}; не ошибка контракта (api-contracts §6).
 */
public class ApiNotImplementedException extends RuntimeException {

    public ApiNotImplementedException(String message) {
        super(message);
    }
}
