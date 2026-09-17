package se.rocketscien.harness.execution;

/**
 * Контейнер сессии недоступен: не удалось создать/запустить (в т.ч. ошибка монтирования workspace)
 * или он умер во время вызова. Транслируется инструментом в {@code ERROR} (старт) или {@code LOST}
 * (смерть во время исполнения).
 */
public class WorkspaceContainerException extends RuntimeException {

    private final boolean duringExecution;

    public WorkspaceContainerException(String message, boolean duringExecution, Throwable cause) {
        super(message, cause);
        this.duringExecution = duringExecution;
    }

    public boolean isDuringExecution() {
        return duringExecution;
    }
}
