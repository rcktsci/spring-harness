package se.rocketscien.harness.task;

/**
 * Задача уже в терминале ({@code SUCCEEDED|FAILED|CANCELLED}, включая {@code '$CANCELLED'}) —
 * resume/stop недопустимы (api-contracts §6: 409 task-already-terminal).
 */
public class TaskAlreadyTerminalException extends RuntimeException {

    public TaskAlreadyTerminalException(String message) {
        super(message);
    }
}
