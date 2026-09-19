package se.rocketscien.harness.task;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Запись истории переходов ({@code task_transition_history}, data-model §4): append-only;
 * {@code reason} — обоснование перехода (bash: exit/stdout/stderr; агент: текст; вебхук:
 * source+сводка; WAIT_TASKS: закрывшие задачи; stop: actor). Маппинг username'ов — API-слой.
 */
public record Transition(UUID id, UUID taskId, String fromState, String toState, TransitionKind kind,
                         Map<String, Object> reason, Instant createdAt) {
}
