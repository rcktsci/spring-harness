package se.rocketscien.harness.execution;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.session.SessionStore;

import java.util.UUID;

/**
 * Wake-контур POLL (execution-model §1, D-M1-4): ShedLock-джоба (единственность — лок
 * {@code poll-wake}, {@code lockAtMostFor} = {@code harness.lock.job-ttl}) раз в
 * {@code harness.turn.poll-interval} подбирает eligible-сессии ({@code last_seq >
 * last_consumed_seq}, partial index), пропущенные контуром EVENT, и чистит просроченные
 * {@code sess-*}-строки ShedLock ({@code lock_until < now()} — не возраст записи: живой
 * продлённый лок не удаляем).
 */
@Component
public class PollWakeJob {

    private static final Logger log = LoggerFactory.getLogger(PollWakeJob.class);

    /**
     * Чистка просроченных сессионных локов (D-M1-4, D-J-6): сравнение строго в БД — ShedLock
     * пишет {@code lock_until} в UTC, поэтому и {@code now()} приводится к UTC
     * ({@code timezone('utc', now())}); ни Java-времени, ни зависимости от TZ сессии БД.
     * Критерий — {@code lock_until}, не возраст записи: живой продлённый лок не удаляем.
     */
    static final String CLEANUP_SQL =
            "DELETE FROM shedlock WHERE name LIKE 'sess-%' AND lock_until < timezone('utc', now())";

    private final TurnManager turnManager;
    private final SessionStore sessionStore;
    private final JdbcTemplate jdbcTemplate;

    public PollWakeJob(TurnManager turnManager, SessionStore sessionStore, JdbcTemplate jdbcTemplate) {
        this.turnManager = turnManager;
        this.sessionStore = sessionStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(fixedDelayString = "${harness.turn.poll-interval}",
            initialDelayString = "${harness.turn.poll-interval}")
    @SchedulerLock(name = "poll-wake", lockAtMostFor = "${harness.lock.job-ttl}")
    public void poll() {
        int woken = 0;
        for (UUID sessionId : sessionStore.findEligibleSessionIds()) {
            turnManager.tryStart(sessionId);
            woken++;
        }
        int cleaned = jdbcTemplate.update(CLEANUP_SQL);
        if (woken > 0 || cleaned > 0) {
            log.info("POLL: разбужено сессий {}, вычищено просроченных sess-локов {}", woken, cleaned);
        }
    }
}
