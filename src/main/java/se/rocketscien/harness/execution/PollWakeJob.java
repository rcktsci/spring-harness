package se.rocketscien.harness.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.session.SessionStore;

import java.util.UUID;

/**
 * Wake-контур POLL (execution-model §1, D-M1-4, D-46): ShedLock-джоба (единственность — лок
 * {@code poll-wake}, {@code lockAtMostFor} = {@code harness.lock.job-ttl}) раз в
 * {@code harness.turn.poll-interval} подбирает eligible-сессии ({@code last_seq >
 * last_consumed_seq}, partial index), пропущенные контуром EVENT. Другой работы у джобы нет:
 * чистка shedlock-строк не проводится (D-46) — просроченные {@code sess-*}-строки безвредны
 * (не участвуют ни во взятии лока, ни в продлении) и остаются на месте.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PollWakeJob {


    private final TurnManager turnManager;
    private final SessionStore sessionStore;

    @Scheduled(fixedDelayString = "${harness.turn.poll-interval}",
            initialDelayString = "${harness.turn.poll-interval}")
    @SchedulerLock(name = "poll-wake", lockAtMostFor = "${harness.lock.job-ttl}")
    public void poll() {
        int woken = 0;
        for (UUID sessionId : sessionStore.findEligibleSessionIds()) {
            turnManager.tryStart(sessionId);
            woken++;
        }
        if (woken > 0) {
            log.info("POLL: разбужено сессий {}", woken);
        }
    }
}
