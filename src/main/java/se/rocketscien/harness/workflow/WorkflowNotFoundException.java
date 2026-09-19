package se.rocketscien.harness.workflow;

/**
 * Workflow или его ревизия не найдены (api-contracts §6: 404 workflow-not-found).
 */
public class WorkflowNotFoundException extends RuntimeException {

    public WorkflowNotFoundException(String message) {
        super(message);
    }
}
