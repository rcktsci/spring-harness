package se.rocketscien.harness.execution.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.config.TaskProperties;
import se.rocketscien.harness.execution.WorkspaceContainerManager;
import se.rocketscien.harness.execution.impl.TaskGraphReader.GraphEdge;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.TransitionKind;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Таймаут-скан задач (D-55, спека task-engine «Таймаут-скан (deadline_at)»): ShedLock-джоба
 * {@code task-timeout-scanner} сканирует {@code deadline_at IS NOT NULL AND deadline_at <= now()
 * AND status_projection IN ('RUNNING','WAITING')} и переводит просроченные по TIMEOUT-ребру
 * графа (дедлайн снимается сам — CAS ставит дедлайн целевого состояния, для TERMINAL — null).
 * BASH-исполнение, живое в этом процессе, пропускается — его доведёт до TIMEOUT сам
 * {@link BashStateExecutor}; остатки после рестарта добиваются здесь (task-контейнер удаляется).
 * WAIT_WEBHOOK пассивен — только этот скан и страхует его таймаут.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskTimeoutScannerJob {

    private final TaskRegistry taskRegistry;
    private final TaskEngine taskEngine;
    private final BashStateExecutor bashExecutor;
    private final TaskGraphReader graphs;
    private final WorkspaceContainerManager containers;
    private final JdbcTemplate jdbcTemplate;
    private final TaskProperties properties;

    @Scheduled(fixedDelayString = "${harness.task.timeout.scan-interval}",
            initialDelayString = "${harness.task.timeout.scan-interval}")
    @SchedulerLock(name = "task-timeout-scanner", lockAtMostFor = "${harness.task.scheduler.ttl}")
    public void scan() {
        List<UUID> expired = jdbcTemplate.queryForList("""
                SELECT id FROM task
                WHERE deadline_at IS NOT NULL AND deadline_at <= now()
                  AND status_projection IN ('RUNNING', 'WAITING')
                ORDER BY id
                LIMIT ?
                """, UUID.class, batchSize());
        for (UUID taskId : expired) {
            try {
                timeout(taskId);
            } catch (Exception e) {
                log.warn("Таймаут-скан: задача {} не переведена: {}", taskId, e.getMessage());
            }
        }
        if (!expired.isEmpty()) {
            log.info("Таймаут-скан: просроченных {}", expired.size());
        }
    }

    private void timeout(UUID taskId) {
        if (bashExecutor.isInFlight(taskId)) {
            log.debug("Таймаут-скан {}: BASH в полёте — доведёт executor", taskId);
            return;
        }
        Task task = taskRegistry.get(taskId);
        Optional<GraphEdge> edge = graphs.edgeByKind(
                graphs.loadForTask(task), task.currentState(), TransitionKind.TIMEOUT);
        if (edge.isEmpty()) {
            log.error("Таймаут-скан {}: TIMEOUT-ребро из '{}' отсутствует — пропуск",
                    taskId, task.currentState());
            return;
        }
        // Остатки после рестарта: живой task-контейнер просроченной bash-задачи добивается.
        containers.removeContainer(WorkspaceContainerManager.TASK_NAMESPACE, taskId);
        taskEngine.processTaskTransition(taskId, task.currentState(), edge.get().to(),
                TransitionKind.TIMEOUT, Map.of("kind", "timeout",
                        "deadline", String.valueOf(task.deadlineAt())));
    }

    private int batchSize() {
        return Math.max(1, properties.scheduler() != null ? properties.scheduler().batchSize() : 50);
    }
}
