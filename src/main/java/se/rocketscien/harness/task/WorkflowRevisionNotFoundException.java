package se.rocketscien.harness.task;

/**
 * Ревизия workflow, к которой пинится задача, не найдена (api-contracts §6: 404 workflow-not-found —
 * пин происходит в момент создания задачи/триггера).
 */
public class WorkflowRevisionNotFoundException extends RuntimeException {

    public WorkflowRevisionNotFoundException(String message) {
        super(message);
    }
}
