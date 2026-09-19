package se.rocketscien.harness.task;

/**
 * Задача не найдена (api-contracts §6: 404 task-not-found).
 */
public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(String message) {
        super(message);
    }
}
