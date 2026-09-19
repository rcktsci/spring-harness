package se.rocketscien.harness.tests.execution.task;

import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.config.RecordingTaskWakeListener;
import se.rocketscien.harness.execution.impl.InProcessTaskWakeBus;
import se.rocketscien.harness.execution.impl.TaskEngine;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskAlreadyTerminalException;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.TaskStateKind;
import se.rocketscien.harness.task.TaskStatus;
import se.rocketscien.harness.task.Transition;
import se.rocketscien.harness.task.TransitionKind;
import se.rocketscien.harness.tests.workflow.WorkflowTestFixtures;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Переходы задачного движка (спека task-engine «Атомарность переходов», data-model §7.2):
 * CAS-атомарность (двойной transition — один), гвард suspended, валидация ребра графа,
 * денормализации и дедлайны (явный timeout состояния / kind-дефолт из конфига), инкремент
 * state_attempt для входа в BASH и task_event_seq, stop-vs-transition (stop выигрывает).
 */
class TaskEngineTransitionTest extends BaseApplicationTest {

    @Autowired
    private TaskRegistry taskRegistry;

    @Autowired
    private TaskEngine taskEngine;

    @Autowired
    private InProcessTaskWakeBus wakeBus;

    @Autowired
    private RecordingTaskWakeListener wakeListener;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    private final List<AutoCloseable> subscriptions = new java.util.ArrayList<>();

    @AfterEach
    void clearSignals() throws Exception {
        wakeListener.clear();
        for (AutoCloseable subscription : subscriptions) {
            subscription.close();
        }
        subscriptions.clear();
    }

    @Test
    void doubleTransitionHasSingleWinner() throws Exception {
        Task task = newTwoPhaseTask();
        CountDownLatch ready = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Transition> attempt = () -> {
                ready.countDown();
                ready.await(5, TimeUnit.SECONDS);
                return taskEngine.processTaskTransition(task.id(), "plan", "checks",
                        TransitionKind.NEXT, Map.of("reason", "кто первый"));
            };
            Future<Transition> first = pool.submit(attempt);
            Future<Transition> second = pool.submit(attempt);

            long winners = Stream.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                    .filter(transition -> transition != null)
                    .count();

            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        Task after = taskRegistry.get(task.id());
        assertThat(after.currentState()).isEqualTo("checks");
        assertThat(after.currentStateKind()).isEqualTo(TaskStateKind.BASH_SCRIPT);
        assertThat(after.statusProjection()).isEqualTo(TaskStatus.RUNNING);
        // вход в BASH_SCRIPT — state_attempt инкремент транзакционно с переходом (спека BASH_SCRIPT)
        assertThat(after.stateAttempt()).isEqualTo(1);
        assertThat(after.taskEventSeq()).isEqualTo(task.taskEventSeq() + 1);

        List<Transition> history = historyOf(task.id());
        assertThat(history).hasSize(1);
        Transition written = history.getFirst();
        assertThat(written.fromState()).isEqualTo("plan");
        assertThat(written.toState()).isEqualTo("checks");
        assertThat(written.kind()).isEqualTo(TransitionKind.NEXT);
        assertThat(written.reason()).containsEntry("reason", "кто первый");
    }

    @Test
    void suspendedTaskRejectsTransitionByCasGuard() {
        Task task = newTwoPhaseTask();
        taskRegistry.suspend(task.id(), false);

        Transition applied = taskEngine.processTaskTransition(task.id(), "plan", "checks",
                TransitionKind.NEXT, Map.of("reason", "приостановлена"));

        assertThat(applied).isNull();
        Task after = taskRegistry.get(task.id());
        assertThat(after.currentState()).isEqualTo("plan");
        assertThat(after.taskEventSeq()).isEqualTo(task.taskEventSeq());
        assertThat(historyOf(task.id())).isEmpty();
    }

    @Test
    void transitionWithoutGraphEdgeIsRejected() {
        Task task = newTwoPhaseTask();

        assertThatThrownBy(() -> taskEngine.processTaskTransition(task.id(), "plan", "done",
                TransitionKind.NEXT, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plan → done");
        assertThatThrownBy(() -> taskEngine.processTaskTransition(task.id(), "plan", "failed",
                TransitionKind.TIMEOUT, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> taskEngine.processTaskTransition(task.id(), "plan", "checks",
                TransitionKind.CANCEL, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TaskRegistry.stop");

        assertThat(taskRegistry.get(task.id()).currentState()).isEqualTo("plan");
    }

    @Test
    void terminalTargetWritesOutcomeProjectionAndClearsDeadline() {
        Task task = TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                WorkflowTestFixtures.waitWebhookGraph(), "wait");
        assertThat(task.deadlineAt()).isNotNull();

        Transition applied = taskEngine.processTaskTransition(task.id(), "wait", "failed",
                TransitionKind.ERROR, Map.of("reason", "плохой payload"));

        assertThat(applied).isNotNull();
        Task after = taskRegistry.get(task.id());
        assertThat(after.currentState()).isEqualTo("failed");
        assertThat(after.currentStateKind()).isEqualTo(TaskStateKind.TERMINAL);
        assertThat(after.statusProjection()).isEqualTo(TaskStatus.FAILED);
        assertThat(after.deadlineAt()).isNull();
    }

    @Test
    void kindDefaultDeadlineAppliedWhenStateHasNoExplicitTimeout() {
        Task task = TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.initToWaitNoTimeoutGraph(), "init");
        assertThat(task.deadlineAt()).isNull();

        taskEngine.processTaskTransition(task.id(), "init", "wait", TransitionKind.NEXT, Map.of());

        Instant deadline = taskRegistry.get(task.id()).deadlineAt();
        // kind-дефолт wait-tasks: 24h (application.yml) — дедлайн появился после перехода
        assertThat(deadline).isNotNull();
        assertThat(Duration.between(Instant.now(), deadline))
                .isBetween(Duration.ofHours(23).plusMinutes(55), Duration.ofHours(24).plusMinutes(5));
    }

    @Test
    void terminalTransitionPublishesTerminalEvent() {
        Task task = newTwoPhaseTask();
        List<UUID> terminals = new java.util.concurrent.CopyOnWriteArrayList<>();
        subscriptions.add(wakeBus.subscribeTaskWake(new se.rocketscien.harness.execution.impl.TaskWakeHandler() {
            @Override
            public void onTaskWake(UUID id) {
            }

            @Override
            public void onTaskTerminal(UUID id) {
                terminals.add(id);
            }
        }));

        taskEngine.processTaskTransition(task.id(), "plan", "failed", TransitionKind.ERROR, Map.of());

        await().atMost(Duration.ofSeconds(5))
                .until(() -> terminals.contains(task.id()));
    }

    @Test
    void wakePublishedAfterCommit() throws Exception {
        Task task = newTwoPhaseTask();
        List<UUID> wakes = new java.util.concurrent.CopyOnWriteArrayList<>();
        subscriptions.add(wakeBus.subscribeTaskWake(wakes::add));

        taskEngine.processTaskTransition(task.id(), "plan", "checks", TransitionKind.NEXT, Map.of());

        assertThat(wakes).contains(task.id());
    }

    @Test
    void stopWinsAgainstConcurrentTransition() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            Task task = newTwoPhaseTask();
            CountDownLatch ready = new CountDownLatch(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> transition = pool.submit(() -> {
                    ready.countDown();
                    ready.await(5, TimeUnit.SECONDS);
                    taskEngine.processTaskTransition(task.id(), "plan", "checks",
                            TransitionKind.NEXT, Map.of());
                    return null;
                });
                Future<?> stop = pool.submit(() -> {
                    ready.countDown();
                    ready.await(5, TimeUnit.SECONDS);
                    try {
                        taskRegistry.stop(task.id());
                    } catch (TaskAlreadyTerminalException ignored) {
                        // переход успел завершить задачу раньше — гонка законна
                    }
                    return null;
                });
                transition.get(10, TimeUnit.SECONDS);
                stop.get(10, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            // stop выигрывает у любых переходов: финал — всегда CANCELLED ('data-model §7.2')
            Task after = taskRegistry.get(task.id());
            assertThat(after.currentState()).isEqualTo(TaskRegistry.CANCELLED_STATE);
            assertThat(after.statusProjection()).isEqualTo(TaskStatus.CANCELLED);
            List<Transition> history = historyOf(task.id());
            assertThat(history).extracting(Transition::kind).contains(TransitionKind.CANCEL);
            wakeListener.clear();
        }
    }

    private Task newTwoPhaseTask() {
        return TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                WorkflowTestFixtures.twoPhaseGraph(), "plan");
    }

    private List<Transition> historyOf(UUID taskId) {
        try {
            return taskRegistry.getHistory(taskId, null, null).items();
        } catch (se.rocketscien.harness.task.InvalidCursorException e) {
            throw new IllegalStateException(e);
        }
    }
}
