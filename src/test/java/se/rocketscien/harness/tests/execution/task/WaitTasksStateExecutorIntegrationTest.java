package se.rocketscien.harness.tests.execution.task;

import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.execution.impl.TaskEngine;
import se.rocketscien.harness.execution.impl.WaitTasksStateExecutor;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.TaskStateKind;
import se.rocketscien.harness.task.TaskStatus;
import se.rocketscien.harness.task.Transition;
import se.rocketscien.harness.task.TransitionKind;
import se.rocketscien.harness.tests.task.TaskTestFixtures;
import se.rocketscien.harness.tests.workflow.WorkflowTestFixtures;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * WAIT_TASKS end-to-end (спека task-engine): scope ALL_CHILDREN/BLOCKED_BY/TAGGED/EXPLICIT,
 * условия ALL_TERMINAL (CANCELLED — терминал)/ALL_SUCCESS (FAILED/CANCELLED — немедленный
 * ERROR), пустой scope не закрывает барьер; триггер переоценки — терминал подзадачи через
 * InProcessTaskWakeBus (EVENT), изменение тегов и blocked_by.
 */
class WaitTasksStateExecutorIntegrationTest extends BaseApplicationTest {

    @Autowired
    private TaskRegistry taskRegistry;

    @Autowired
    private TaskEngine taskEngine;

    @Autowired
    private WaitTasksStateExecutor waitTasksExecutor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    @Test
    void parentClosesWhenAllChildrenTerminal() throws Exception {
        Task parent = TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                TaskTestFixtures.waitTasksGraph(), "gather");
        // дети создаются под suspended-барьером: иначе переоценка после первого терминального
        // ребёнка законно закроет ALL_CHILDREN до создания второго (переоценка — по текущему
        // состоянию); resume — сам EVENT-триггер переоценки
        taskRegistry.suspend(parent.id(), false);
        Task first = TaskEngineTestFixtures.createSubtask(taskRegistry, jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.successTerminalGraph(), "done", parent.id(), null, null);
        Task second = TaskEngineTestFixtures.createSubtask(taskRegistry, jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.successTerminalGraph(), "done", parent.id(), null, null);
        taskRegistry.resume(parent.id());

        // EVENT-триггер: resume → переоценка без ручного вызова
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Task after = taskRegistry.get(parent.id());
            assertThat(after.currentState()).isEqualTo("done");
            assertThat(after.statusProjection()).isEqualTo(TaskStatus.SUCCEEDED);
        });

        Transition closing = lastTransition(parent.id());
        assertThat(closing.kind()).isEqualTo(TransitionKind.NEXT);
        assertThat(closing.reason().get("closedBy")).asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactlyInAnyOrder(first.id().toString(), second.id().toString());
    }

    @Test
    void reevaluateReturnsFalseWhileChildRunning() {
        Task parent = TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                TaskTestFixtures.waitTasksGraph(), "gather");
        TaskEngineTestFixtures.createSubtask(taskRegistry, jdbcTemplate, idGenerator,
                WorkflowTestFixtures.twoPhaseGraph(), "plan", parent.id(), null, null);

        boolean closed = waitTasksExecutor.reevaluate(parent.id());

        assertThat(closed).isFalse();
        assertThat(taskRegistry.get(parent.id()).statusProjection()).isEqualTo(TaskStatus.WAITING);
    }

    @Test
    void emptyScopeDoesNotSatisfyBarrier() {
        Task parent = TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                TaskTestFixtures.waitTasksGraph(), "gather");

        assertThat(waitTasksExecutor.reevaluate(parent.id())).isFalse();
        assertThat(taskRegistry.get(parent.id()).statusProjection()).isEqualTo(TaskStatus.WAITING);
    }

    @Test
    void allSuccessFailsOnCancelledChild() throws Exception {
        Task parent = TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.waitAllSuccessGraph(), "gather");
        // suspended-барьер на время создания детей — без него одиночный терминальный ребёнок
        // законно закрывает ALL_CHILDREN раньше второго (см. parentClosesWhenAllChildrenTerminal)
        taskRegistry.suspend(parent.id(), false);
        TaskEngineTestFixtures.createSubtask(taskRegistry, jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.successTerminalGraph(), "done", parent.id(), null, null);
        TaskEngineTestFixtures.createSubtask(taskRegistry, jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.cancelledTerminalGraph(), "gone", parent.id(), null, null);
        taskRegistry.resume(parent.id());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Task after = taskRegistry.get(parent.id());
            assertThat(after.currentState()).isEqualTo("failed");
            assertThat(after.statusProjection()).isEqualTo(TaskStatus.FAILED);
        });

        Transition closing = lastTransition(parent.id());
        assertThat(closing.kind()).isEqualTo(TransitionKind.ERROR);
        assertThat(String.valueOf(closing.reason().get("failed"))).isNotBlank();
    }

    @Test
    void allTerminalCountsCancelledChildAsTerminal() {
        Task parent = TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                TaskTestFixtures.waitTasksGraph(), "gather");
        TaskEngineTestFixtures.createSubtask(taskRegistry, jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.cancelledTerminalGraph(), "gone", parent.id(), null, null);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Task after = taskRegistry.get(parent.id());
            assertThat(after.currentState()).isEqualTo("done");
            assertThat(after.statusProjection()).isEqualTo(TaskStatus.SUCCEEDED);
        });
    }

    @Test
    void blockedByScopeClosesOnBlockerTerminal() {
        Task parent = TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.blockedByScopeGraph(), "wait");
        Task blocker = TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.successTerminalGraph(), "done");

        taskRegistry.addDependency(blocker.id(), parent.id());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Task after = taskRegistry.get(parent.id());
            assertThat(after.currentState()).isEqualTo("done");
            assertThat(after.statusProjection()).isEqualTo(TaskStatus.SUCCEEDED);
        });
    }

    @Test
    void tagChangeTriggersTaggedScopeReevaluation() throws Exception {
        Task parent = TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.taggedScopeGraph(), "wait");
        TaskEngineTestFixtures.createSubtask(taskRegistry, jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.successTerminalGraph(), "done", parent.id(), null, null);

        // подзадача терминальна, но тега нет — барьер не закрывается
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(taskRegistry.get(parent.id()).statusProjection()).isEqualTo(TaskStatus.WAITING));

        // change тега подзадачи → EVENT-wake → переоценка TAGGED-барьера родителя
        taskRegistry.patch(childOf(parent.id()).id(), new TaskRegistry.TaskPatch(null, null, List.of("batch")));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Task after = taskRegistry.get(parent.id());
            assertThat(after.currentState()).isEqualTo("done");
            assertThat(after.statusProjection()).isEqualTo(TaskStatus.SUCCEEDED);
        });
    }

    @Test
    void explicitScopeClosesOnListedTaskTerminal() {
        Task listed = TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                WorkflowTestFixtures.twoPhaseGraph(), "plan");
        Task parent = TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.explicitScopeGraph(), "wait",
                Map.of("ids", List.of(listed.id().toString())));
        // план в работе — барьер ждёт
        assertThat(waitTasksExecutor.reevaluate(parent.id())).isFalse();

        // терминал перечисленной задачи (движковый переход) → переоценка → NEXT
        taskEngine.processTaskTransition(listed.id(), "plan", "failed", TransitionKind.ERROR, Map.of());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Task after = taskRegistry.get(parent.id());
            assertThat(after.currentState()).isEqualTo("done");
            assertThat(after.statusProjection()).isEqualTo(TaskStatus.SUCCEEDED);
        });
    }

    @Test
    void taggedScopeExcludesSelfTask() throws Exception {
        Task parent = TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.taggedScopeGraph(), "wait");
        // сама барьер-задача получает тег scope: без self-фильтра WAITING-задача в собственном
        // барьере никогда не даст ALL_TERMINAL
        taskRegistry.patch(parent.id(), new TaskRegistry.TaskPatch(null, null, List.of("batch")));
        TaskEngineTestFixtures.createSubtask(taskRegistry, jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.successTerminalGraph(), "done", parent.id(),
                List.of("batch"), null);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Task after = taskRegistry.get(parent.id());
            assertThat(after.currentState()).isEqualTo("done");
            assertThat(after.statusProjection()).isEqualTo(TaskStatus.SUCCEEDED);
        });
    }

    @Test
    void explicitScopeExcludesSelfTask() {
        Task listed = TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.successTerminalGraph(), "done");
        Task parent = TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.explicitScopeGraph(), "wait",
                Map.of("ids", List.of(listed.id().toString())));
        // params иммутабельны через API — добавляем self-id напрямую (регресс на I-3)
        jdbcTemplate.update(
                "UPDATE task SET params_jsonb = jsonb_set(params_jsonb, '{ids}', "
                        + "(params_jsonb->'ids') || ?::jsonb) WHERE id = ?",
                "[\"" + parent.id() + "\"]", parent.id());

        assertThat(waitTasksExecutor.reevaluate(parent.id())).isTrue();
        assertThat(taskRegistry.get(parent.id()).statusProjection()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    @Test
    void suspendedBarrierIsNotClosed() {
        Task parent = TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                TaskTestFixtures.waitTasksGraph(), "gather");
        TaskEngineTestFixtures.createSubtask(taskRegistry, jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.successTerminalGraph(), "done", parent.id(), null, null);
        taskRegistry.suspend(parent.id(), false);

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4)).untilAsserted(() ->
                assertThat(taskRegistry.get(parent.id()).statusProjection()).isEqualTo(TaskStatus.WAITING));
    }

    private Task childOf(UUID parentId) throws Exception {
        TaskRegistry.TaskSearchResult children =
                taskRegistry.list(new TaskRegistry.TaskSearchCriteria(parentId, null, null, null, null, null, 10));
        assertThat(children.items()).hasSize(1);
        return children.items().getFirst();
    }

    private Transition lastTransition(UUID taskId) throws Exception {
        List<Transition> items = taskRegistry.getHistory(taskId, null, null).items();
        assertThat(items).isNotEmpty();
        return items.getLast();
    }
}
