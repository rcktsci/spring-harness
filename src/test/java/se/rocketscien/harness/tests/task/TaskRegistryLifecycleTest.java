package se.rocketscien.harness.tests.task;

import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.config.RecordingTaskWakeListener;
import se.rocketscien.harness.task.Comment;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskAlreadyTerminalException;
import se.rocketscien.harness.task.TaskNotFoundException;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные тесты жизненного цикла (живой Postgres): suspend/resume/stop (CAS
 * '$CANCELLED' + история), merge-patch (params иммутабельны), комментарии, task-wake
 * после коммита resume.
 */
class TaskRegistryLifecycleTest extends BaseApplicationTest {

    @Autowired
    private TaskRegistry taskRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    @Autowired
    private RecordingTaskWakeListener wakeListener;

    @AfterEach
    void clearWakes() {
        wakeListener.clear();
    }

    private Task newTask() {
        return TaskTestFixtures.createTask(
                taskRegistry, jdbcTemplate, idGenerator, WorkflowTestFixtures.twoPhaseGraph(), "plan");
    }

    @Test
    void suspendSetsFlagIdempotently() {
        Task task = newTask();

        taskRegistry.suspend(task.id(), false);
        taskRegistry.suspend(task.id(), false);

        assertThat(taskRegistry.get(task.id()).suspended()).isTrue();
    }

    @Test
    void suspendCascadeSuspendsSubtree() {
        Task parent = newTask();
        Task child = newSubtask(parent);
        Task grandchild = newSubtask(child);

        taskRegistry.suspend(parent.id(), true);

        assertThat(taskRegistry.get(parent.id()).suspended()).isTrue();
        assertThat(taskRegistry.get(child.id()).suspended()).isTrue();
        assertThat(taskRegistry.get(grandchild.id()).suspended()).isTrue();
    }

    @Test
    void suspendUnknownTaskThrows() {
        assertThatThrownBy(() -> taskRegistry.suspend(UUID.randomUUID(), false))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void resumeClearsFlagAndPublishesWakeAfterCommit() {
        Task task = newTask();
        taskRegistry.suspend(task.id(), false);
        wakeListener.clear();

        taskRegistry.resume(task.id());

        assertThat(taskRegistry.get(task.id()).suspended()).isFalse();
        assertThat(wakeListener.wakes()).containsExactly(task.id());
    }

    @Test
    void resumeNonSuspendedStillPublishesWake() {
        Task task = newTask();
        wakeListener.clear();

        taskRegistry.resume(task.id());

        assertThat(wakeListener.wakes()).containsExactly(task.id());
    }

    @Test
    void resumeTerminalTaskThrowsAlreadyTerminal() {
        Task task = newTask();
        taskRegistry.stop(task.id());
        wakeListener.clear();

        assertThatThrownBy(() -> taskRegistry.resume(task.id()))
                .isInstanceOf(TaskAlreadyTerminalException.class);
        assertThat(wakeListener.wakes()).isEmpty();
    }

    @Test
    void stopWritesCancelledStateWithHistory() {
        Task task = newTask();

        taskRegistry.stop(task.id());

        Task stopped = taskRegistry.get(task.id());
        assertThat(stopped.currentState()).isEqualTo(TaskRegistry.CANCELLED_STATE);
        assertThat(stopped.currentStateKind()).isEqualTo(TaskStateKind.TERMINAL);
        assertThat(stopped.statusProjection()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(stopped.suspended()).isTrue();
        assertThat(stopped.deadlineAt()).isNull();

        TaskRegistry.HistoryPage history = historyOf(task.id());
        assertThat(history.items()).hasSize(1);
        Transition transition = history.items().getFirst();
        assertThat(transition.kind()).isEqualTo(TransitionKind.CANCEL);
        assertThat(transition.fromState()).isEqualTo("plan");
        assertThat(transition.toState()).isEqualTo(TaskRegistry.CANCELLED_STATE);
        assertThat(transition.reason()).containsEntry("kind", "stop").containsEntry("actor", "user");
    }

    @Test
    void stopCascadesToSubtreeSkippingTerminalChildren() {
        Task parent = newTask();
        Task child = newSubtask(parent);
        taskRegistry.stop(child.id());

        taskRegistry.stop(parent.id());

        assertThat(taskRegistry.get(parent.id()).statusProjection()).isEqualTo(TaskStatus.CANCELLED);
        // уже терминальный ребёнок не трогается повторно — запись истории одна
        assertThat(historyOf(child.id()).items()).hasSize(1);
        assertThat(taskRegistry.get(child.id()).currentState()).isEqualTo(TaskRegistry.CANCELLED_STATE);
    }

    @Test
    void stopTerminalTaskThrowsAlreadyTerminal() {
        Task task = newTask();
        taskRegistry.stop(task.id());

        assertThatThrownBy(() -> taskRegistry.stop(task.id()))
                .isInstanceOf(TaskAlreadyTerminalException.class);
    }

    @Test
    void stopWaitTaskClearsDeadline() {
        Task task = TaskTestFixtures.createTask(
                taskRegistry, jdbcTemplate, idGenerator, WorkflowTestFixtures.waitWebhookGraph(), "wait");
        assertThat(task.deadlineAt()).isNotNull();

        taskRegistry.stop(task.id());

        assertThat(taskRegistry.get(task.id()).deadlineAt()).isNull();
    }

    @Test
    void patchUpdatesTitleDescriptionTagsAndKeepsParams() {
        UUID revisionId = TaskTestFixtures.insertRevision(
                jdbcTemplate, idGenerator, TaskTestFixtures.paramsSchemaGraph(), "start");
        UUID owner = TaskTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        Task task = taskRegistry.createTask(TaskTestFixtures.command(
                revisionId, owner, null, Map.of("module", "billing")));

        Task patched = taskRegistry.patch(task.id(),
                new TaskRegistry.TaskPatch("Новое название", "Новое описание", java.util.List.of("updated")));

        assertThat(patched.title()).isEqualTo("Новое название");
        assertThat(patched.description()).isEqualTo("Новое описание");
        assertThat(patched.tags()).containsExactly("updated");
        // params иммутабельны (api §4.1): контракт не даёт способа их менять
        assertThat(patched.params()).containsEntry("module", "billing");
        assertThat(taskRegistry.get(task.id()).params()).containsEntry("module", "billing");
    }

    @Test
    void patchNullFieldsLeftUntouched() {
        Task task = newTask();

        Task patched = taskRegistry.patch(task.id(), new TaskRegistry.TaskPatch("Только title", null, null));

        assertThat(patched.title()).isEqualTo("Только title");
        assertThat(patched.description()).isEqualTo("Описание задачи");
        assertThat(patched.tags()).isEmpty();
    }

    @Test
    void patchUnknownTaskThrows() {
        assertThatThrownBy(() -> taskRegistry.patch(
                UUID.randomUUID(), new TaskRegistry.TaskPatch("x", null, null)))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void addCommentStoresWithAndWithoutAuthor() {
        Task task = newTask();
        UUID author = TaskTestFixtures.insertAppUser(jdbcTemplate, idGenerator);

        Comment userComment = taskRegistry.addComment(task.id(), author, "Комментарий");
        Comment agentComment = taskRegistry.addComment(task.id(), null, "Агентский");

        assertThat(userComment.authorUserId()).isEqualTo(author);
        assertThat(agentComment.authorUserId()).isNull();
        Integer comments = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_comment WHERE task_id = ?", Integer.class, task.id());
        assertThat(comments).isEqualTo(2);
    }

    @Test
    void addCommentBlankBodyThrows() {
        Task task = newTask();

        // Spring транслирует IllegalArgumentException JPA-слоя — проверяем содержательную часть
        assertThatThrownBy(() -> taskRegistry.addComment(task.id(), null, "  "))
                .hasMessageContaining("Текст комментария обязателен");
    }

    private Task newSubtask(Task parent) {
        UUID revisionId = TaskTestFixtures.insertRevision(
                jdbcTemplate, idGenerator, WorkflowTestFixtures.twoPhaseGraph(), "plan");
        UUID owner = TaskTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        return taskRegistry.createTask(
                TaskTestFixtures.command(revisionId, owner, parent.id(), Map.of()));
    }

    private TaskRegistry.HistoryPage historyOf(UUID taskId) {
        try {
            return taskRegistry.getHistory(taskId, null, null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
