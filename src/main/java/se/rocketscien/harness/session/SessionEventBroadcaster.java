package se.rocketscien.harness.session;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * In-memory broadcaster событий сессии (D-M1-5): подписчики SSE (эндпоинт — задача 8.5) получают
 * события через этот контракт; delivery в память — без реактивных цепочек.
 */
public interface SessionEventBroadcaster {

    /** Последний известный рантайм-статус сессии (для снапшота при коннекте). */
    SessionRuntimeStatus runtimeStatus(UUID sessionId);

    /** Публикация статусного события (немедленная доставка подписчикам). */
    void publishStatus(UUID sessionId, SessionRuntimeStatus status);

    /** Подписка на события сессии; отписка — {@link Subscription#close()}. */
    Subscription subscribe(UUID sessionId, Consumer<SessionEvent> consumer);

    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
