package se.rocketscien.harness.task;

import se.rocketscien.harness.common.jsonschema.JsonSchemaError;

import java.util.List;

/**
 * Ребро зависимости не прошло валидацию (api-contracts §6: 422 dependency-invalid):
 * self-loop, цикл (включая транзитивный), неизвестная задача в ребре.
 */
public class DependencyInvalidException extends RuntimeException {

    private final List<JsonSchemaError> errors;

    public DependencyInvalidException(List<JsonSchemaError> errors) {
        super("Зависимость не прошла валидацию: %d нарушений".formatted(errors.size()));
        this.errors = List.copyOf(errors);
    }

    public List<JsonSchemaError> getErrors() {
        return errors;
    }
}
