package se.rocketscien.harness.task;

import se.rocketscien.harness.common.jsonschema.JsonSchemaError;

import java.util.List;

/**
 * params задачи не прошли {@code paramsSchema} ревизии — ограниченный профиль D-58
 * (api-contracts §6: 422 params-schema, errors[] по схеме).
 */
public class ParamsSchemaInvalidException extends RuntimeException {

    private final List<JsonSchemaError> errors;

    public ParamsSchemaInvalidException(List<JsonSchemaError> errors) {
        super("params не прошли paramsSchema ревизии: %d нарушений".formatted(errors.size()));
        this.errors = List.copyOf(errors);
    }

    public List<JsonSchemaError> getErrors() {
        return errors;
    }
}
