package se.rocketscien.harness.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.config.LateResultProperties;
import se.rocketscien.harness.execution.SessionLockManager;
import se.rocketscien.harness.execution.ToolStatus;
import se.rocketscien.harness.execution.TurnManager;
import se.rocketscien.harness.execution.TurnPayloads;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.SessionStore;

import java.time.Instant;

/**
 * Верхний лимит ожидания позднего результата async-инструмента (M3 N.6, D-64): ShedLock-джоба
 * {@code async-timeout-watcher} раз в {@code harness.late-result.watch-schedule} сканирует
 * {@code session_message.kind = 'ASYNC_ACCEPTED'} без парного финального TOOL_RESULT
 * {@code (OK|ERROR|CANCELLED|LOST)}; плейсхолдер старше {@code harness.late-result.timeout-ms}
 * закрывается синтетическим {@code TOOL_RESULT LOST} «превышен верхний лимит» — под программным
 * локом сессии {@code sess-{id}} с проверкой «первый финальный выигрывает». Залоченные сессии
 * (живой Turn) пропускаются — их доделает следующий скан; на рестарте ту же проверку делает
 * рестарт-скан.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AsyncTimeoutWatcher {

    static final String LOST_REASON = "операция потеряна — превышен верхний лимит ожидания результата";

    private final SessionStore sessionStore;
    private final SessionLockManager sessionLocks;
    private final TurnManager turnManager;
    private final LateResultProperties properties;

    @Scheduled(fixedDelayString = "${harness.late-result.watch-schedule}",
            initialDelayString = "${harness.late-result.watch-schedule}")
    @SchedulerLock(name = "async-timeout-watcher", lockAtMostFor = "${harness.late-result.timeout.ttl}")
    public void scan() {
        Instant createdBefore = Instant.now().minus(properties.timeoutMs());
        int closed = 0;
        for (SessionStore.PendingAsyncCall call : sessionStore.findExpiredAsyncAccepteds(createdBefore)) {
            try {
                if (closeIfLockFree(call)) {
                    closed++;
                }
            } catch (Exception e) {
                log.warn("Async-timeout-watcher: не смог закрыть ASYNC_ACCEPTED {} сессии {}: {}",
                        call.callId(), call.sessionId(), e.getMessage());
            }
        }
        if (closed > 0) {
            log.info("Async-timeout-watcher: закрыто зависших async-вызовов {}", closed);
        }
    }

    /** {@code true} — дописан LOST (нужен wake строго после release: wake берёт тот же sess-лок). */
    private boolean closeIfLockFree(SessionStore.PendingAsyncCall call) {
        SessionLockManager.HeldLock lock = sessionLocks.tryAcquire(call.sessionId()).orElse(null);
        if (lock == null) {
            log.debug("Async-timeout-watcher: сессия {} под живым локом — пропущена", call.sessionId());
            return false;
        }
        boolean appended = false;
        try {
            if (sessionStore.hasToolResultForCall(call.sessionId(), call.callId())) {
                return false;
            }
            sessionStore.appendEvent(call.sessionId(), MessageKind.TOOL_RESULT, null,
                    TurnPayloads.toolResultSynthetic(call.callId(), call.tool(), ToolStatus.LOST, LOST_REASON),
                    null);
            appended = true;
            log.info("Async-timeout-watcher: ASYNC_ACCEPTED {} ({}) сессии {} закрыт LOST, wake",
                    call.callId(), call.tool(), call.sessionId());
        } finally {
            lock.close();
        }
        if (appended) {
            turnManager.tryStart(call.sessionId());
        }
        return appended;
    }
}
