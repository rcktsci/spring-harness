package se.rocketscien.harness.session;

import java.time.Instant;
import java.util.UUID;

/**
 * Сессия — метаданные журнала (data-model §5). Сущность-проекция строки {@code session}:
 * ревизия агента пинится при создании и не меняется; {@code lastSeq/lastConsumedSeq} —
 * денормализации дописи; {@code lastActivityAt/createdAt} — для SessionDto (api-contracts §2).
 */
public record Session(
        UUID id,
        SessionKind kind,
        String title,
        UUID ownerUserId,
        UUID taskId,
        String stateCode,
        UUID agentRevisionId,
        UUID parentSessionId,
        boolean cancelRequested,
        long lastSeq,
        long lastConsumedSeq,
        TurnOutcome lastTurnOutcome,
        Instant lastActivityAt,
        Instant createdAt
) {
}
