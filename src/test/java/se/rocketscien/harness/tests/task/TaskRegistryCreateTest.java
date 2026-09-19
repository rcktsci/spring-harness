package se.rocketscien.harness.tests.task;

import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.tests.workflow.WorkflowTestFixtures;
import se.rocketscien.harness.common.IdGenerator;

import se.rocketscien.harness.task.ParamsSchemaInvalidException;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskNotFoundException;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.TaskStateKind;
import se.rocketscien.harness.task.TaskStatus;
import se.rocketscien.harness.task.WorkflowRevisionNotFoundException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные тесты создания задачи (миграция 012, живой Postgres): пин начального состояния,
 * проекции kind/status, deadline из timeout, paramsSchema (D-58), подзадачи, ошибки.
 */
class TaskRegistryCreateTest extends BaseApplicationTest {

    @Autowired
    private TaskRegistry taskRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    @Test
    void createPinsInitialStateWithAgentRunningProjection() {
        Task task = TaskTestFixtures.createTask(
                taskRegistry, jdbcTemplate, idGenerator, WorkflowTestFixtures.twoPhaseGraph(), "plan");

        assertThat(task.currentState()).isEqualTo("plan");
        assertThat(task.currentStateKind()).isEqualTo(TaskStateKind.AGENT);
        assertThat(task.statusProjection()).isEqualTo(TaskStatus.RUNNING);
        assertThat(task.stateAttempt()).isZero();
        assertThat(task.taskEventSeq()).isZero();
        assertThat(task.deadlineAt()).isNull();
        assertThat(task.suspended()).isFalse();
        assertThat(task.parentTaskId()).isNull();
        assertThat(task.authorUserId()).isNull();
        assertThat(task.params()).isEmpty();
        assertThat(task.tags()).isEmpty();
    }

    /**
     * H-1 регресс: стартовое состояние берётся из явного {@code start_state} ревизии, а не из
     * эвристики «первый source / первый в JSON»: у графа два source'а, первый в JSON — не старт.
     */
    @Test
    void createUsesExplicitStartStateNotFirstStateInJson() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("plan", "AGENT", Map.of("agent_key", "orchestrator"))
                ),
                List.of()
        );

        Task task = TaskTestFixtures.createTask(
                taskRegistry, jdbcTemplate, idGenerator, graph, "plan");

        assertThat(task.currentState()).isEqualTo("plan");
        assertThat(task.currentStateKind()).isEqualTo(TaskStateKind.AGENT);
        assertThat(task.statusProjection()).isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    void createWaitWebhookSetsWaitingAndDeadlineFromTimeout() {
        Task task = TaskTestFixtures.createTask(
                taskRegistry, jdbcTemplate, idGenerator, WorkflowTestFixtures.waitWebhookGraph(), "wait");

        assertThat(task.currentState()).isEqualTo("wait");
        assertThat(task.currentStateKind()).isEqualTo(TaskStateKind.WAIT_WEBHOOK);
        assertThat(task.statusProjection()).isEqualTo(TaskStatus.WAITING);
        assertThat(task.deadlineAt()).isNotNull();
        long deltaMs = Duration.between(Instant.now(), task.deadlineAt()).toMillis();
        // ~PT2M от создания; допускаем секунды расхождения между dbNow() и JVM-часами
        assertThat(deltaMs).isBetween(Duration.ofMinutes(1).toMillis(), Duration.ofMinutes(3).toMillis());
    }

    @Test
    void createSubtaskSetsParentAndInheritsNothingSilently() {
        Task parent = TaskTestFixtures.createTask(
                taskRegistry, jdbcTemplate, idGenerator, WorkflowTestFixtures.twoPhaseGraph(), "plan");
        UUID revisionId = TaskTestFixtures.insertRevision(
                jdbcTemplate, idGenerator, WorkflowTestFixtures.twoPhaseGraph(), "plan");
        UUID owner = TaskTestFixtures.insertAppUser(jdbcTemplate, idGenerator);

        Task subtask = taskRegistry.createTask(
                TaskTestFixtures.command(revisionId, owner, parent.id(), Map.of()));

        assertThat(subtask.parentTaskId()).isEqualTo(parent.id());
    }

    @Test
    void createWithUnknownParentThrows() {
        UUID revisionId = TaskTestFixtures.insertRevision(
                jdbcTemplate, idGenerator, WorkflowTestFixtures.twoPhaseGraph(), "plan");
        UUID owner = TaskTestFixtures.insertAppUser(jdbcTemplate, idGenerator);

        assertThatThrownBy(() -> taskRegistry.createTask(
                TaskTestFixtures.command(revisionId, owner, UUID.randomUUID(), Map.of())))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void createWithUnknownRevisionThrows() {
        UUID owner = TaskTestFixtures.insertAppUser(jdbcTemplate, idGenerator);

        assertThatThrownBy(() -> taskRegistry.createTask(
                TaskTestFixtures.command(UUID.randomUUID(), owner, null, Map.of())))
                .isInstanceOf(WorkflowRevisionNotFoundException.class);
    }

    @Test
    void paramsValidAgainstSchemaPass() {
        UUID revisionId = TaskTestFixtures.insertRevision(
                jdbcTemplate, idGenerator, TaskTestFixtures.paramsSchemaGraph(), "start");
        UUID owner = TaskTestFixtures.insertAppUser(jdbcTemplate, idGenerator);

        Task task = taskRegistry.createTask(
                TaskTestFixtures.command(revisionId, owner, null, Map.of("module", "billing")));

        assertThat(task.params()).containsEntry("module", "billing");
    }

    @Test
    void paramsWrongTypeReportedWithPointer() {
        UUID revisionId = TaskTestFixtures.insertRevision(
                jdbcTemplate, idGenerator, TaskTestFixtures.paramsSchemaGraph(), "start");
        UUID owner = TaskTestFixtures.insertAppUser(jdbcTemplate, idGenerator);

        assertThatThrownBy(() -> taskRegistry.createTask(
                TaskTestFixtures.command(revisionId, owner, null, Map.of("module", 42))))
                .isInstanceOfSatisfying(ParamsSchemaInvalidException.class, exception -> {
                    assertThat(exception.getErrors()).anySatisfy(error -> {
                        assertThat(error.rule()).isEqualTo("type");
                        assertThat(error.pointer()).isEqualTo("/params/module");
                    });
                });
    }

    @Test
    void paramsMissingRequiredReported() {
        UUID revisionId = TaskTestFixtures.insertRevision(
                jdbcTemplate, idGenerator, TaskTestFixtures.paramsSchemaGraph(), "start");
        UUID owner = TaskTestFixtures.insertAppUser(jdbcTemplate, idGenerator);

        assertThatThrownBy(() -> taskRegistry.createTask(
                TaskTestFixtures.command(revisionId, owner, null, Map.of("other", "x"))))
                .isInstanceOfSatisfying(ParamsSchemaInvalidException.class, exception ->
                        assertThat(exception.getErrors()).anySatisfy(error -> {
                            assertThat(error.rule()).isEqualTo("required");
                            assertThat(error.pointer()).isEqualTo("/params/module");
                        }));
    }

    @Test
    void createStoresTagsAndParamsRoundtrip() {
        UUID revisionId = TaskTestFixtures.insertRevision(
                jdbcTemplate, idGenerator, WorkflowTestFixtures.twoPhaseGraph(), "plan");
        UUID owner = TaskTestFixtures.insertAppUser(jdbcTemplate, idGenerator);

        TaskRegistry.CreateTaskCommand cmd = new TaskRegistry.CreateTaskCommand(
                revisionId, "Теги и params", "Описание", null, owner, null,
                Map.of("module", "billing", "level", 2), List.of("backend", "m2"));
        Task task = taskRegistry.createTask(cmd);

        Task reloaded = taskRegistry.get(task.id());
        assertThat(reloaded.tags()).containsExactly("backend", "m2");
        assertThat(reloaded.params()).containsEntry("module", "billing").containsEntry("level", 2);
    }
}
