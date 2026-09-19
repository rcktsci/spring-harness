package se.rocketscien.harness.tests.task;

import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.task.DependencyInvalidException;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.tests.workflow.WorkflowTestFixtures;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные тесты зависимостей (миграция 013): self-loop, прямые и транзитивные циклы
 * (DFS), идемпотентность remove, no-op на дубль ребра (PK-пара).
 */
class TaskRegistryDependencyTest extends BaseApplicationTest {

    @Autowired
    private TaskRegistry taskRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    private Task newTask() {
        return TaskTestFixtures.createTask(
                taskRegistry, jdbcTemplate, idGenerator, WorkflowTestFixtures.twoPhaseGraph(), "plan");
    }

    @Test
    void selfLoopThrowsWithRule() {
        Task task = newTask();

        assertThatThrownBy(() -> taskRegistry.addDependency(task.id(), task.id()))
                .isInstanceOfSatisfying(DependencyInvalidException.class, exception ->
                        assertThat(exception.getErrors()).anySatisfy(error ->
                                assertThat(error.rule()).isEqualTo("self-loop")));
    }

    @Test
    void directCycleThrowsWithRule() {
        Task a = newTask();
        Task b = newTask();
        taskRegistry.addDependency(a.id(), b.id());

        assertThatThrownBy(() -> taskRegistry.addDependency(b.id(), a.id()))
                .isInstanceOfSatisfying(DependencyInvalidException.class, exception ->
                        assertThat(exception.getErrors()).anySatisfy(error ->
                                assertThat(error.rule()).isEqualTo("cycle")));
    }

    @Test
    void transitiveCycleThrows() {
        Task a = newTask();
        Task b = newTask();
        Task c = newTask();
        taskRegistry.addDependency(a.id(), b.id());
        taskRegistry.addDependency(b.id(), c.id());

        // C блокирует A: A→B→C и C→A замыкают цикл (сценарий спеки task-engine)
        assertThatThrownBy(() -> taskRegistry.addDependency(c.id(), a.id()))
                .isInstanceOfSatisfying(DependencyInvalidException.class, exception ->
                        assertThat(exception.getErrors()).anySatisfy(error ->
                                assertThat(error.rule()).isEqualTo("cycle")));
    }

    @Test
    void validChainPasses() {
        Task a = newTask();
        Task b = newTask();
        Task c = newTask();
        taskRegistry.addDependency(a.id(), b.id());
        taskRegistry.addDependency(b.id(), c.id());

        Integer edges = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_dependency WHERE (blocker_task_id, blocked_task_id) IN ((?, ?), (?, ?))",
                Integer.class, a.id(), b.id(), b.id(), c.id());
        assertThat(edges).isEqualTo(2);
    }

    @Test
    void duplicateEdgeIsNoOp() {
        Task a = newTask();
        Task b = newTask();
        taskRegistry.addDependency(a.id(), b.id());

        assertThatCode(() -> taskRegistry.addDependency(a.id(), b.id())).doesNotThrowAnyException();

        Integer edges = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_dependency WHERE blocker_task_id = ? AND blocked_task_id = ?",
                Integer.class, a.id(), b.id());
        assertThat(edges).isEqualTo(1);
    }

    @Test
    void removeDependencyIsIdempotent() {
        Task a = newTask();
        Task b = newTask();
        taskRegistry.addDependency(a.id(), b.id());

        assertThatCode(() -> {
            taskRegistry.removeDependency(a.id(), b.id());
            taskRegistry.removeDependency(a.id(), b.id());
        }).doesNotThrowAnyException();

        Integer edges = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_dependency WHERE blocker_task_id = ? AND blocked_task_id = ?",
                Integer.class, a.id(), b.id());
        assertThat(edges).isZero();
    }

    @Test
    void unknownTaskThrowsNotFound() {
        Task task = newTask();

        assertThatThrownBy(() -> taskRegistry.addDependency(UUID.randomUUID(), task.id()))
                .isInstanceOf(RuntimeException.class);
    }
}
