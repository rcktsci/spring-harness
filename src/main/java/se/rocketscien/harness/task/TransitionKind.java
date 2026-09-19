package se.rocketscien.harness.task;

/**
 * Вид перехода в истории ({@code task_transition_history.kind}, data-model §4). NEXT/ERROR/TIMEOUT —
 * рёбра графа; CANCEL — движковый резерв принудительной отмены ({@code '$CANCELLED'}, data-model §7.2).
 */
public enum TransitionKind {
    NEXT,
    ERROR,
    TIMEOUT,
    CANCEL
}
