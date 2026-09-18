package se.rocketscien.harness.session;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * In-memory broadcaster событий сессии (D-M1-5): подписчики SSE (эндпоинт — задача 8.5) получают
 * события через этот контракт; delivery в память — без реактивных цепочек.
 *
 * <p>Статусные события (DS F5, api-contracts §3.1) несут {@code lastTurnOutcome} —
 * снапшот при коннекте и кадр {@code session.status} обязаны его отражать.</p>
 */
public interface SessionEventBroadcaster {

    /** Снапшот статуса сессии (для первого кадра при коннекте/реконнекте). */
    StatusSnapshot statusSnapshot(UUID sessionId);

    /** Публикация статусного события (немедленная доставка подписчикам). */
    void publishStatus(UUID sessionId, SessionRuntimeStatus status, TurnOutcome lastTurnOutcome);

    /** Подписка на события сессии; отписка — {@link Subscription#close()}. */
    Subscription subscribe(UUID sessionId, Consumer<SessionEvent> consumer);

    /** Рантайм-статус и исход последнего Turn'а на момент снапшота. */
    record StatusSnapshot(SessionRuntimeStatus runtimeStatus, TurnOutcome lastTurnOutcome) {
    }

    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
