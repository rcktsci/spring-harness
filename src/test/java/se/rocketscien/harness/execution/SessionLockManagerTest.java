package se.rocketscien.harness.execution;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.config.LockProperties;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Task 7.1: программный лок {@code sess-{id}} через ShedLock {@code LockProvider}:
 * конкурентный tryStart — ровно один победитель; heartbeat продлевает TTL; unlock освобождает.
 */
class SessionLockManagerTest extends BaseApplicationTest {

    private static final int COMPETITORS = 4;

    @Autowired
    private SessionLockManager sessionLocks;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private se.rocketscien.harness.config.LockProperties lockProperties;

    @Test
    void concurrentTryAcquireYieldsExactlyOneWinner() throws Exception {
        UUID sessionId = UUID.randomUUID();

        CountDownLatch startGate = new CountDownLatch(1);
        CopyOnWriteArrayList<SessionLockManager.HeldLock> winners = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(COMPETITORS);
        try {
            List<Future<?>> attempts = new ArrayList<>();
            for (int i = 0; i < COMPETITORS; i++) {
                attempts.add(pool.submit(() -> {
                    startGate.await();
                    sessionLocks.tryAcquire(sessionId).ifPresent(winners::add);
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> attempt : attempts) {
                attempt.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(winners).hasSize(1);
        assertThat(shedlockRow(sessionId)).isPresent();

        winners.getFirst().close();

        SessionLockManager.HeldLock reacquired = null;
        try {
            Optional<SessionLockManager.HeldLock> second = sessionLocks.tryAcquire(sessionId);
            assertThat(second).as("лок должен быть доступен повторно после unlock").isPresent();
            reacquired = second.orElse(null);
        } finally {
            if (reacquired != null) {
                reacquired.close();
            }
        }
    }

    @Test
    void busyLockIsNotAcquirable() {
        UUID sessionId = UUID.randomUUID();

        Optional<SessionLockManager.HeldLock> held = sessionLocks.tryAcquire(sessionId);
        assertThat(held).isPresent();
        try {
            assertThat(sessionLocks.tryAcquire(sessionId)).as("занятый лок не берётся повторно").isEmpty();
        } finally {
            held.get().close();
        }
    }

    @Test
    void heartbeatExtendsLockUntil() throws Exception {
        UUID sessionId = UUID.randomUUID();

        try (SessionLockManager.HeldLock held = sessionLocks.tryAcquire(sessionId).orElseThrow()) {
            Timestamp before = lockUntil(sessionId);

            Thread.sleep(lockProperties.heartbeatInterval().toMillis() * 5);

            Timestamp after = lockUntil(sessionId);
            assertThat(after).as("heartbeat обязан продлевать lock_until").isAfter(before);
        }
    }

    @Test
    void unlockMakesLockImmediatelyExpired() {
        UUID sessionId = UUID.randomUUID();

        SessionLockManager.HeldLock held = sessionLocks.tryAcquire(sessionId).orElseThrow();
        held.close();

        Integer expired = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shedlock WHERE name = ? AND lock_until <= now()",
                Integer.class,
                lockName(sessionId)
        );
        assertThat(expired).isEqualTo(1);
    }

    @Test
    void heartbeatExtendFailureMarksLockLostAndCloseKeepsForeignLock() {
        UUID sessionId = UUID.randomUUID();

        SessionLockManager.HeldLock held = sessionLocks.tryAcquire(sessionId).orElseThrow();

        // Имитация истёкшего TTL: ShedLock пишет lock_until в UTC — пишем так же (D-J-6);
        // extend требует lock_until > now и того же locked_by — по истёкшему TTL вернёт empty
        jdbcTemplate.update(
                "UPDATE shedlock SET lock_until = timezone('utc', now()) - interval '1 second' WHERE name = ?",
                lockName(sessionId));

        held.heartbeat();

        assertThat(held.isLost()).as("неудачный extend помечает лок потерянным").isTrue();

        // Чужой владелец перехватывает истёкший лок
        Optional<SessionLockManager.HeldLock> thief = sessionLocks.tryAcquire(sessionId);
        assertThat(thief).as("перехват истёкшего лока другим владельцем").isPresent();
        Timestamp foreignUntil = lockUntil(sessionId);

        held.close();

        assertThat(lockUntil(sessionId)).as("close() при lost не снимает чужой лок").isEqualTo(foreignUntil);
        assertThat(sessionLocks.tryAcquire(sessionId)).as("перехватчик всё ещё держит лок").isEmpty();

        thief.get().close();
    }

    @Test
    void heartbeatThrowableDoesNotKillSubsequentTicks() {
        AtomicBoolean unlockCalled = new AtomicBoolean(false);
        AtomicInteger extendCalls = new AtomicInteger();
        SimpleLock stub = new SimpleLock() {
            @Override
            public void unlock() {
                unlockCalled.set(true);
            }

            @Override
            public Optional<SimpleLock> extend(Duration lockAtMostFor, Duration lockAtLeastFor) {
                if (extendCalls.incrementAndGet() == 1) {
                    throw new RuntimeException("транзиентный сбой БД");
                }
                return Optional.of(this);
            }
        };
        LockProvider provider = configuration -> Optional.of(stub);
        SessionLockManager manager = new SessionLockManager(provider,
                new LockProperties(Duration.ofHours(1), Duration.ofMillis(50), Duration.ofSeconds(5)));

        SessionLockManager.HeldLock held = manager.tryAcquire(UUID.randomUUID()).orElseThrow();
        try {
            assertThatCode(held::heartbeat).doesNotThrowAnyException();
            held.heartbeat();

            assertThat(held.isLost())
                    .as("транзиентный сбой не помечает лок потерянным — тики продолжаются")
                    .isFalse();
        } finally {
            held.close();
        }
        assertThat(unlockCalled).as("живой (не потерянный) лок освобождается при close").isTrue();
    }

    private String lockName(UUID sessionId) {
        return "sess-" + sessionId;
    }

    private Optional<String> shedlockRow(UUID sessionId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT name FROM shedlock WHERE name = ?",
                (rs, rowNum) -> rs.getString(1),
                lockName(sessionId)
        );
        return rows.stream().findFirst();
    }

    private Timestamp lockUntil(UUID sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT lock_until FROM shedlock WHERE name = ?",
                Timestamp.class,
                lockName(sessionId)
        );
    }
}
