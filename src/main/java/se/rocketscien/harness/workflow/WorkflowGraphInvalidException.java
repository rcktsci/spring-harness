package se.rocketscien.harness.workflow;

import se.rocketscien.harness.common.jsonschema.JsonSchemaError;

import java.util.List;

/**
 * Граф не прошёл валидацию правил §2 workflow-domain / контракта graph_jsonb
 * (api-contracts §6: 422 graph-invalid, errors[] { pointer, rule, message }).
 */
public class WorkflowGraphInvalidException extends RuntimeException {

    private final List<JsonSchemaError> errors;

    public WorkflowGraphInvalidException(List<JsonSchemaError> errors) {
        super("Граф не прошёл валидацию: %d нарушений".formatted(errors.size()));
        this.errors = List.copyOf(errors);
    }

    public List<JsonSchemaError> getErrors() {
        return errors;
    }
}
