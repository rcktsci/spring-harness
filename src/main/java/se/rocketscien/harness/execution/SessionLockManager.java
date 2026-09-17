package se.rocketscien.harness.execution;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.config.LockProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Программный лок {@code sess-{sessionId}} (D-M1-4, спека agent-turn): TTL —
 * {@code harness.lock.session-ttl}, heartbeat продлевает лок каждые {@code heartbeat-interval}
 * ( ShedLock-механизм {@link SimpleLock#extend} — то же, что делает {@code LockExtender};
 * последний привязан к ThreadLocal регистрации {@code LockingTaskExecutor} и при прямом
 * {@link LockProvider#lock} неприменим), unlock — в {@link HeldLock#close()} (try-with-resources).
 *
 * <p>Блокировка — исключительно mutex Turn'ов; допись в журнал идёт мимо неё (D-M1-4).
 * Fencing-токенов нет (D-40): продление может не успеть за патологически долгим раундом —
 * риск принят, компенсация щедрым TTL.</p>
 */
@Component
@RequiredArgsConstructor
public class SessionLockManager {

    private static final Logger log = LoggerFactory.getLogger(SessionLockManager.class);
    private static final Duration NO_MINIMUM = Duration.ZERO;

    private final LockProvider lockProvider;
    private final LockProperties properties;
    private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(1, task -> {
        Thread thread = new Thread(task, "session-lock-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Попытка взятия лока сессии; занято (активный Turn на любом инстансе) — {@code empty} (no-op
     * для tryStart).
     */
    public Optional<HeldLock> tryAcquire(UUID sessionId) {
        return lockProvider
                .lock(new LockConfiguration(
                        Instant.now(), "sess-" + sessionId, properties.sessionTtl(), NO_MINIMUM))
                .map(lock -> new HeldLock(sessionId, lock));
    }

    @PreDestroy
    void shutdown() {
        heartbeatExecutor.shutdownNow();
    }

    /**
     * Удерживаемый лок: heartbeat продлевает TTL в фоне, {@link #close()} отменяет продление и
     * освобождает лок (unlock в finally — try-with-resources).
     *
     * <p>D-J-1: {@code heartbeat()} гасит {@link Throwable} (необработанное исключение в
     * {@code scheduleAtFixedRate} подавляет все последующие тики — лок молча истёк бы);
     * транзиентный сбой не помечает лок потерянным. Неудачный {@code extend} (TTL истёк, лок
     * перехвачен) — лок помечается {@code lost}, heartbeat прекращается; {@link #close()} при
     * {@code lost} НЕ вызывает {@code unlock()} — ShedLock-unlock матчит строку по имени и
     * снял бы лок легитимного нового владельца («зомби», D-40).</p>
     */
    public final class HeldLock implements AutoCloseable {

        private final UUID sessionId;
        private final ScheduledFuture<?> heartbeatTask;
        private final Object mutex = new Object();
        private SimpleLock lock;
        private boolean lost;

        private HeldLock(UUID sessionId, SimpleLock lock) {
            this.sessionId = sessionId;
            this.lock = lock;
            this.heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(
                    this::heartbeat,
                    properties.heartbeatInterval().toMillis(),
                    properties.heartbeatInterval().toMillis(),
                    TimeUnit.MILLISECONDS);
        }

        public void heartbeat() {
            try {
                synchronized (mutex) {
                    if (lock == null || lost) {
                        return;
                    }
                    Optional<SimpleLock> extended = lock.extend(properties.sessionTtl(), NO_MINIMUM);
                    if (extended.isPresent()) {
                        lock = extended.get();
                    } else {
                        lost = true;
                        log.warn("Лок sess-{} потерян (TTL истёк, лок перехвачен) — heartbeat прекращён,"
                                + " close() не будет снимать чужой лок (D-J-1)", sessionId);
                    }
                }
            } catch (Throwable t) {
                log.warn("Heartbeat sess-{} упал (тики продолжаются): {}", sessionId, t.toString());
            }
        }

        public boolean isLost() {
            synchronized (mutex) {
                return lost;
            }
        }

        @Override
        public void close() {
            synchronized (mutex) {
                heartbeatTask.cancel(false);
                if (lock != null && !lost) {
                    lock.unlock();
                }
                lock = null;
            }
        }
    }
}
