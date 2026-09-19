package se.rocketscien.harness.workflow;

/**
 * Workflow с таким {@code key} уже существует
 * (отклонение dev D-пачки №5: 422 validation-failed, rule=key-unique).
 */
public class WorkflowKeyAlreadyExistsException extends RuntimeException {

    public WorkflowKeyAlreadyExistsException(String key) {
        super("Workflow с key='%s' уже существует".formatted(key));
    }
}
