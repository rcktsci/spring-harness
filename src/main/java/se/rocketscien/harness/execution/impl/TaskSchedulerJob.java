package se.rocketscien.harness.execution.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.config.TaskProperties;

import java.util.List;
import java.util.UUID;

/**
 * POLL-страховка задачного слоя (D-33, спека task-engine «Задачный POLL-страховка»):
 * ShedLock-джоба {@code task-scheduler} (TTL — {@code harness.task.scheduler.ttl}) раз в
 * {@code harness.task.poll-interval} подбирает:
 * (1) AGENT-задачи {@code RUNNING} без STATE-сессии (EVENT-wake потерян — повторный wake;
 * сам bootstrap — {@link AgentStateBootstrapper}, диспетчер исполняет его на любом AGENT-wake);
 * (2) {@code WAIT_TASKS}-барьеры — переоценка (идемпотентна). {@code WAIT_WEBHOOK} в выборку
 * не входит — состояние пассивно, его страхует только {@code task-timeout-scanner}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskSchedulerJob {

    private final WaitTasksStateExecutor waitTasksExecutor;
    private final InProcessTaskWakeBus wakeBus;
    private final JdbcTemplate jdbcTemplate;
    private final TaskProperties properties;

    @Scheduled(fixedDelayString = "${harness.task.poll-interval}",
            initialDelayString = "${harness.task.poll-interval}")
    @SchedulerLock(name = "task-scheduler", lockAtMostFor = "${harness.task.scheduler.ttl}")
    public void poll() {
        bootstrapAgentWithoutSession();
        reevaluateWaitTasks();
    }

    /** AGENT + RUNNING + нет STATE-сессии → EVENT-wake (bootstrap исполняет диспетчер — J.3). */
    private void bootstrapAgentWithoutSession() {
        int batchSize = batchSize();
        List<UUID> ids = jdbcTemplate.queryForList("""
                SELECT t.id FROM task t
                WHERE t.current_state_kind = 'AGENT'
                  AND t.status_projection = 'RUNNING'
                  AND t.suspended = false
                  AND NOT EXISTS (
                        SELECT 1 FROM session s WHERE s.task_id = t.id AND s.kind = 'STATE')
                ORDER BY t.id
                LIMIT ?
                """, UUID.class, batchSize);
        for (UUID taskId : ids) {
            log.info("POLL: AGENT-задача {} без STATE-сессии — повторный EVENT-wake", taskId);
            wakeBus.publishTaskWake(taskId);
        }
    }

    /** WAIT_TASKS-барьеры → переоценка (partial-индекс current_state_kind='WAIT_TASKS'). */
    private void reevaluateWaitTasks() {
        List<UUID> ids = jdbcTemplate.queryForList("""
                SELECT id FROM task
                WHERE current_state_kind = 'WAIT_TASKS' AND status_projection = 'WAITING' AND suspended = false
                ORDER BY id
                LIMIT ?
                """, UUID.class, batchSize());
        int closed = 0;
        for (UUID taskId : ids) {
            try {
                if (waitTasksExecutor.reevaluate(taskId)) {
                    closed++;
                }
            } catch (Exception e) {
                log.warn("POLL: переоценка WAIT_TASKS {} упала: {}", taskId, e.getMessage());
            }
        }
        if (!ids.isEmpty()) {
            log.info("POLL: переоценено WAIT_TASKS {}, закрыто {}", ids.size(), closed);
        }
    }

    private int batchSize() {
        return Math.max(1, properties.scheduler() != null ? properties.scheduler().batchSize() : 50);
    }
}
