package se.rocketscien.harness.api;

import java.util.List;

/**
 * Нарушение правил валидации тела/параметров (api-contracts §0.2): {@code 422 validation-failed}
 * с {@code errors[]} {pointer, rule, message}.
 */
public class ApiValidationException extends RuntimeException {

    private final List<ValidationError> errors;

    public ApiValidationException(List<ValidationError> errors) {
        super("Тело/параметры запроса невалидны");
        this.errors = List.copyOf(errors);
    }

    public List<ValidationError> errors() {
        return errors;
    }

    /** {pointer, rule, message} одного невалидного места (RFC 6901 pointer, машинное имя правила). */
    public record ValidationError(String pointer, String rule, String message) {
    }
}
