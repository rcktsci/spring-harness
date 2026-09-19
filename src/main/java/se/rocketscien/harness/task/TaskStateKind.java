package se.rocketscien.harness.task;

/**
 * Тип состояния задачи — денормализация {@code task.current_state_kind} (data-model §4);
 * значения — типы состояний ревизии (workflow-domain §2).
 */
public enum TaskStateKind {
    AGENT,
    BASH_SCRIPT,
    WAIT_WEBHOOK,
    WAIT_TASKS,
    TERMINAL
}
