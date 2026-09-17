package se.rocketscien.harness.session;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Событие сессии для in-memory broadcaster'а (D-M1-5): доставка подписчикам строго по возрастанию
 * seq (сообщения), статусные события — вне последовательности.
 */
public sealed interface SessionEvent {

    /**
     * В журнал сессии дописано событие (эквивалент SSE {@code message.created}, id = seq).
     * Публикуется после коммита транзакции дописи.
     */
    record MessageCreated(
            UUID sessionId,
            long seq,
            String ulid,
            MessageKind kind,
            UUID authorUserId,
            Map<String, Object> payload,
            Integer tokens,
            Instant createdAt
    ) implements SessionEvent {
    }

    /** Изменился рантайм-статус сессии (эквивалент SSE {@code session.status}). */
    record StatusChanged(UUID sessionId, SessionRuntimeStatus runtimeStatus) implements SessionEvent {
    }
}
