package se.rocketscien.harness.api;

/**
 * Ответ переходного периода contract-first (пачка 1): метод контракта сгенерирован, но
 * ещё не реализован — {@code 501 not-implemented}. Не ошибка контракта (api-contracts §6);
 * код и обработчик удаляются вместе со stub-контроллером
 * {@link WorkspaceFilesController} при реализации пачки U.
 */
public class ApiNotImplementedException extends RuntimeException {

    public ApiNotImplementedException(String message) {
        super(message);
    }
}
