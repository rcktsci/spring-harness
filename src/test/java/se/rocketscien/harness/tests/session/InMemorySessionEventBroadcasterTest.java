package se.rocketscien.harness.tests.session;
import java.util.Map;


import se.rocketscien.harness.session.impl.InMemorySessionEventBroadcaster;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.SessionEvent;
import se.rocketscien.harness.session.SessionEventBroadcaster;
import se.rocketscien.harness.session.SessionRuntimeStatus;
import se.rocketscien.harness.session.TurnOutcome;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 7.x (broadcaster): in-memory доставка событий сессии строго по возрастанию seq (D-M1-5),
 * снапшот runtime-статуса, изоляция подписчиков.
 */
class InMemorySessionEventBroadcasterTest {

    private final InMemorySessionEventBroadcaster broadcaster = new InMemorySessionEventBroadcaster();
    private final UUID sessionId = UUID.randomUUID();

    @Test
    void deliversOutOfOrderEventsInSeqOrder() {
        List<Long> seen = new ArrayList<>();
        broadcaster.subscribe(sessionId, event -> {
            if (event instanceof SessionEvent.MessageCreated message) {
                seen.add(message.seq());
            }
        });

        broadcaster.onEvent(message(2));
        broadcaster.onEvent(message(1));
        broadcaster.onEvent(message(4));
        broadcaster.onEvent(message(3));

        assertThat(seen).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    void duplicateSeqIsNotDeliveredTwice() {
        List<Long> seen = new ArrayList<>();
        broadcaster.subscribe(sessionId, event -> {
            if (event instanceof SessionEvent.MessageCreated message) {
                seen.add(message.seq());
            }
        });

        broadcaster.onEvent(message(1));
        broadcaster.onEvent(message(1));

        assertThat(seen).containsExactly(1L);
    }

    @Test
    void eventsBeforeSubscriptionAreNotReplayed() {
        broadcaster.onEvent(message(1));

        List<Long> seen = new ArrayList<>();
        broadcaster.subscribe(sessionId, event -> {
            if (event instanceof SessionEvent.MessageCreated message) {
                seen.add(message.seq());
            }
        });
        broadcaster.onEvent(message(2));

        assertThat(seen).containsExactly(2L);
    }

    @Test
    void statusSnapshotDefaultsToIdleAndTracksChanges() {
        assertThat(broadcaster.statusSnapshot(sessionId).runtimeStatus()).isEqualTo(SessionRuntimeStatus.IDLE);
        assertThat(broadcaster.statusSnapshot(sessionId).lastTurnOutcome()).isNull();

        broadcaster.publishStatus(sessionId, SessionRuntimeStatus.TURN_RUNNING, null);
        assertThat(broadcaster.statusSnapshot(sessionId).runtimeStatus()).isEqualTo(SessionRuntimeStatus.TURN_RUNNING);

        List<SessionEvent.StatusChanged> statuses = new ArrayList<>();
        broadcaster.subscribe(sessionId, event -> {
            if (event instanceof SessionEvent.StatusChanged status) {
                statuses.add(status);
            }
        });
        broadcaster.publishStatus(sessionId, SessionRuntimeStatus.IDLE, TurnOutcome.COMPLETED);

        assertThat(statuses).hasSize(1);
        assertThat(statuses.getFirst().runtimeStatus()).isEqualTo(SessionRuntimeStatus.IDLE);
        // DS F5: статусные события несут lastTurnOutcome (api-contracts §3.1)
        assertThat(statuses.getFirst().lastTurnOutcome()).isEqualTo(TurnOutcome.COMPLETED);
        assertThat(broadcaster.statusSnapshot(sessionId).runtimeStatus()).isEqualTo(SessionRuntimeStatus.IDLE);
        assertThat(broadcaster.statusSnapshot(sessionId).lastTurnOutcome()).isEqualTo(TurnOutcome.COMPLETED);
    }

    @Test
    void failingSubscriberDoesNotAffectOthers() {
        AtomicInteger healthy = new AtomicInteger();
        broadcaster.subscribe(sessionId, event -> {
            throw new IllegalStateException("подписчик сломан");
        });
        broadcaster.subscribe(sessionId, event -> healthy.incrementAndGet());

        broadcaster.onEvent(message(1));

        assertThat(healthy.get()).isEqualTo(1);
    }

    @Test
    void closedSubscriptionStopsDelivery() {
        List<Long> seen = new ArrayList<>();
        SessionEventBroadcaster.Subscription subscription = broadcaster.subscribe(sessionId, event -> {
            if (event instanceof SessionEvent.MessageCreated message) {
                seen.add(message.seq());
            }
        });

        broadcaster.onEvent(message(1));
        subscription.close();
        broadcaster.onEvent(message(2));

        assertThat(seen).containsExactly(1L);
    }

    private SessionEvent.MessageCreated message(long seq) {
        return new SessionEvent.MessageCreated(
                sessionId, seq, "ulid-" + seq, MessageKind.USER, null, Map.of("text", "текст"), null, Instant.now());
    }
}
