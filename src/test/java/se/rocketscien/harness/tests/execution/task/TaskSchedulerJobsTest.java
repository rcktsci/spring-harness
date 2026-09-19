package se.rocketscien.harness.tests.execution.task;

import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.execution.impl.InProcessTaskWakeBus;
import se.rocketscien.harness.execution.impl.TaskSchedulerJob;
import se.rocketscien.harness.execution.impl.TaskTimeoutScannerJob;
import se.rocketscien.harness.execution.impl.TaskWakeHandler;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.TaskStatus;
import se.rocketscien.harness.task.TransitionKind;
import se.rocketscien.harness.tests.workflow.WorkflowTestFixtures;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Джобы задачного слоя (спека task-engine): task-scheduler — POLL-страховка (AGENT без
 * STATE-сессии → wake; WAIT_TASKS → переоценка) и task-timeout-scanner — просроченный
 * {@code deadline_at} → TIMEOUT (дедлайн снят, история записана). Джобы вызываются напрямую —
 * в тестах их интервалы не тикают (application-test.yml).
 */
class TaskSchedulerJobsTest extends BaseApplicationTest {

    @Autowired
    private TaskSchedulerJob schedulerJob;

    @Autowired
    private TaskTimeoutScannerJob timeoutScanner;

    @Autowired
    private TaskRegistry taskRegistry;

    @Autowired
    private InProcessTaskWakeBus wakeBus;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    private final List<AutoCloseable> subscriptions = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        for (AutoCloseable subscription : subscriptions) {
            subscription.close();
        }
        subscriptions.clear();
    }

    @Test
    void timeoutScanMovesExpiredTaskByTimeoutEdge() throws Exception {
        Task task = TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                WorkflowTestFixtures.waitWebhookGraph(), "wait");
        jdbcTemplate.update("UPDATE task SET deadline_at = now() - interval '1 minute' WHERE id = ?",
                task.id());

        timeoutScanner.scan();

        Task after = taskRegistry.get(task.id());
        assertThat(after.currentState()).isEqualTo("done");
        assertThat(after.statusProjection()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(after.deadlineAt()).isNull();

        var transitions = taskRegistry.getHistory(task.id(), null, null).items();
        assertThat(transitions).hasSize(1);
        assertThat(transitions.getFirst().kind()).isEqualTo(TransitionKind.TIMEOUT);
        assertThat(transitions.getFirst().reason()).containsEntry("kind", "timeout");
    }

    @Test
    void timeoutScanIgnoresFreshDeadlines() throws Exception {
        Task task = TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                WorkflowTestFixtures.waitWebhookGraph(), "wait");

        timeoutScanner.scan();

        Task after = taskRegistry.get(task.id());
        assertThat(after.currentState()).isEqualTo("wait");
        assertThat(after.statusProjection()).isEqualTo(TaskStatus.WAITING);
        assertThat(taskRegistry.getHistory(task.id(), null, null).items()).isEmpty();
    }

    @Test
    void pollReevaluatesWaitTasksMissedByEventWake() {
        // обе задачи raw (мимо реестра): EVENT-wake не было вовсе — имитация краха до wake
        UUID barrier = TaskEngineTestFixtures.insertTaskRaw(jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.blockedByScopeGraph(), "wait",
                "wait", "WAIT_TASKS", "WAITING", false);
        UUID blocker = TaskEngineTestFixtures.insertTaskRaw(jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.successTerminalGraph(), "done",
                "done", "TERMINAL", "SUCCEEDED", false);
        jdbcTemplate.update(
                "INSERT INTO task_dependency (blocker_task_id, blocked_task_id) VALUES (?, ?)",
                blocker, barrier);

        schedulerJob.poll();

        Task after = taskRegistry.get(barrier);
        assertThat(after.currentState()).isEqualTo("done");
        assertThat(after.statusProjection()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    @Test
    void pollWakesAgentTaskWithoutStateSession() {
        UUID agentTask = TaskEngineTestFixtures.insertTaskRaw(jdbcTemplate, idGenerator,
                WorkflowTestFixtures.twoPhaseGraph(), "plan",
                "plan", "AGENT", "RUNNING", false);
        List<UUID> wakes = new CopyOnWriteArrayList<>();
        subscriptions.add(wakeBus.subscribeTaskWake(new TaskWakeHandler() {
            @Override
            public void onTaskWake(UUID taskId) {
                wakes.add(taskId);
            }
        }));

        schedulerJob.poll();

        assertThat(wakes).contains(agentTask);
    }

    @Test
    void pollSkipsSuspendedWaitTasks() {
        UUID barrier = TaskEngineTestFixtures.insertTaskRaw(jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.blockedByScopeGraph(), "wait",
                "wait", "WAIT_TASKS", "WAITING", true);
        UUID blocker = TaskEngineTestFixtures.insertTaskRaw(jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.successTerminalGraph(), "done",
                "done", "TERMINAL", "SUCCEEDED", false);
        jdbcTemplate.update(
                "INSERT INTO task_dependency (blocker_task_id, blocked_task_id) VALUES (?, ?)",
                blocker, barrier);

        schedulerJob.poll();

        Task after = taskRegistry.get(barrier);
        assertThat(after.currentState()).isEqualTo("wait");
        assertThat(after.statusProjection()).isEqualTo(TaskStatus.WAITING);
    }
}
