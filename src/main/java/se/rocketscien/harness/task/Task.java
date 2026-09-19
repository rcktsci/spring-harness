package se.rocketscien.harness.task;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Проекция строки {@code task} (data-model §4) для домена и API-слоя: {@code params} —
 * иммутабельная параметр-мапа, {@code currentState} — code состояния или
 * {@link TaskRegistry#CANCELLED_STATE}, {@code statusProjection} — денормализация статуса.
 */
public record Task(UUID id, String title, String description, UUID authorUserId, UUID ownerUserId,
                   UUID workflowRevisionId, String currentState, TaskStateKind currentStateKind,
                   int stateAttempt, long taskEventSeq, Instant deadlineAt, TaskStatus statusProjection,
                   Map<String, Object> params, UUID parentTaskId, List<String> tags, boolean suspended,
                   Instant createdAt, Instant updatedAt) {
}
