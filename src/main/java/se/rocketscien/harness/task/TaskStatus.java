package se.rocketscien.harness.task;

/**
 * Проекция статуса задачи ({@code task.status_projection}, data-model §4): RUNNING/WAITING —
 * идёт; SUCCEEDED/FAILED/CANCELLED — терминальные.
 */
public enum TaskStatus {
    RUNNING,
    WAITING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
