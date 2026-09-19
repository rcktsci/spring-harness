package se.rocketscien.harness.task;

import java.time.Instant;
import java.util.UUID;

/**
 * Комментарий задачи (append-only, data-model §4); {@code authorUserId == null} — агентский
 * комментарий (NULL + агент-пометка).
 */
public record Comment(UUID id, UUID taskId, UUID authorUserId, String body, Instant createdAt) {
}
