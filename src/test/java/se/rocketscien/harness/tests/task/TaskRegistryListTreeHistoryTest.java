package se.rocketscien.harness.tests.task;

import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.task.InvalidCursorException;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskNotFoundException;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.TaskTreeNode;
import se.rocketscien.harness.task.TaskStatus;
import se.rocketscien.harness.task.TransitionKind;
import se.rocketscien.harness.tests.workflow.WorkflowTestFixtures;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные тесты списка (фильтры + курсор), дерева (BFS/depth) и истории переходов
 * (курсор (created_at, id)) на живом Postgres.
 */
class TaskRegistryListTreeHistoryTest extends BaseApplicationTest {

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

    private Task newSubtask(Task parent) {
        UUID revisionId = TaskTestFixtures.insertRevision(
                jdbcTemplate, idGenerator, WorkflowTestFixtures.twoPhaseGraph(), "plan");
        UUID owner = TaskTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        return taskRegistry.createTask(
                TaskTestFixtures.command(revisionId, owner, parent.id(), Map.of()));
    }

    @Test
    void listFiltersByParentAndStatus() {
        Task parent = newTask();
        Task child = newSubtask(parent);
        newTask();

        TaskRegistry.TaskSearchResult byParent = list(
                new TaskRegistry.TaskSearchCriteria(parent.id(), null, null, null, null, null, 50));
        assertThat(byParent.items()).extracting(Task::id).containsExactly(child.id());

        TaskRegistry.TaskSearchResult running = list(
                new TaskRegistry.TaskSearchCriteria(parent.id(), TaskStatus.RUNNING, null, null, null, null, 50));
        assertThat(running.items()).extracting(Task::id).containsExactly(child.id());

        // stop → CANCELLED: фильтр по RUNNING пустеет, по CANCELLED — находит
        taskRegistry.stop(child.id());
        TaskRegistry.TaskSearchResult afterStop = list(
                new TaskRegistry.TaskSearchCriteria(parent.id(), TaskStatus.RUNNING, null, null, null, null, 50));
        assertThat(afterStop.items()).isEmpty();
        TaskRegistry.TaskSearchResult cancelled = list(
                new TaskRegistry.TaskSearchCriteria(parent.id(), TaskStatus.CANCELLED, null, null, null, null, 50));
        assertThat(cancelled.items()).extracting(Task::id).containsExactly(child.id());
    }

    @Test
    void listFiltersByTagsAndOwnerAndQ() {
        UUID revisionId = TaskTestFixtures.insertRevision(
                jdbcTemplate, idGenerator, WorkflowTestFixtures.twoPhaseGraph(), "plan");
        UUID owner1 = TaskTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        UUID owner2 = TaskTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        Task tagged = taskRegistry.createTask(new TaskRegistry.CreateTaskCommand(
                revisionId, "Биллинг-модуль", "Реализация биллинга", null, owner1, null,
                Map.of(), List.of("backend")));
        taskRegistry.createTask(new TaskRegistry.CreateTaskCommand(
                revisionId, "Прочее", "Другая задача", null, owner2, null, Map.of(), List.of()));

        TaskRegistry.TaskSearchResult byTag = list(new TaskRegistry.TaskSearchCriteria(
                null, null, null, List.of("backend"), null, null, 50));
        assertThat(byTag.items()).extracting(Task::id).containsExactly(tagged.id());

        TaskRegistry.TaskSearchResult mine = list(new TaskRegistry.TaskSearchCriteria(
                null, null, owner2, null, null, null, 50));
        assertThat(mine.items()).hasSize(1);
        assertThat(mine.items().getFirst().ownerUserId()).isEqualTo(owner2);

        TaskRegistry.TaskSearchResult byQ = list(new TaskRegistry.TaskSearchCriteria(
                null, null, null, null, "биллинг", null, 50));
        assertThat(byQ.items()).extracting(Task::id).containsExactly(tagged.id());
    }

    @Test
    void listPaginatesByUpdatedAtDescWithCursor() {
        UUID revisionId = TaskTestFixtures.insertRevision(
                jdbcTemplate, idGenerator, WorkflowTestFixtures.twoPhaseGraph(), "plan");
        UUID owner = TaskTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        for (int i = 0; i < 3; i++) {
            Task task = taskRegistry.createTask(TaskTestFixtures.command(
                    revisionId, owner, null, Map.of()));
            jdbcTemplate.update("UPDATE task SET updated_at = ? WHERE id = ?",
                    Timestamp.from(Instant.parse("2026-09-01T12:00:0" + i + "Z")), task.id());
        }

        TaskRegistry.TaskSearchResult page1 = list(new TaskRegistry.TaskSearchCriteria(
                null, null, null, null, null, null, 2));
        TaskRegistry.TaskSearchResult page2 = list(new TaskRegistry.TaskSearchCriteria(
                null, null, null, null, null, page1.nextCursor(), 2));

        assertThat(page1.items()).hasSize(2);
        assertThat(page1.nextCursor()).isNotNull();
        assertThat(page2.items()).hasSize(1);
        assertThat(page2.nextCursor()).isNull();
    }

    @Test
    void listWithGarbageCursorThrows() {
        assertThatThrownBy(() -> taskRegistry.list(new TaskRegistry.TaskSearchCriteria(
                null, null, null, null, null, "%%%garbage%%%", 10)))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void getTreeReturnsFullSubtreeAndRespectsDepth() {
        Task root = newTask();
        Task child = newSubtask(root);
        Task grandchild = newSubtask(child);

        TaskTreeNode fullTree = taskRegistry.getTree(root.id(), null);
        assertThat(fullTree.task().id()).isEqualTo(root.id());
        assertThat(fullTree.children()).hasSize(1);
        assertThat(fullTree.children().getFirst().task().id()).isEqualTo(child.id());
        assertThat(fullTree.children().getFirst().children())
                .extracting(node -> node.task().id())
                .containsExactly(grandchild.id());

        TaskTreeNode shallow = taskRegistry.getTree(root.id(), 1);
        assertThat(shallow.children()).hasSize(1);
        assertThat(shallow.children().getFirst().children()).isEmpty();
    }

    @Test
    void getTreeUnknownTaskThrows() {
        assertThatThrownBy(() -> taskRegistry.getTree(UUID.randomUUID(), null))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void getHistoryReturnsAllInAscendingOrderWhenNoSince() throws Exception {
        Task task = newTask();
        assertThat(taskRegistry.getHistory(task.id(), null, null).items()).isEmpty();

        taskRegistry.stop(task.id());

        TaskRegistry.HistoryPage history = taskRegistry.getHistory(task.id(), null, null);
        assertThat(history.items()).hasSize(1);
        assertThat(history.nextCursor()).isNull();
        assertThat(history.items().getFirst().kind()).isEqualTo(TransitionKind.CANCEL);
    }

    @Test
    void getHistoryPaginatesByCreatedAtIdCursor() throws Exception {
        Task task = newTask();
        // три записи истории с управляемыми created_at (stop добавит четвёртую «сейчас»)
        for (int i = 0; i < 3; i++) {
            jdbcTemplate.update("""
                    INSERT INTO task_transition_history
                        (id, task_id, from_state, to_state, kind, reason_jsonb, created_at)
                    VALUES (?, ?, 'plan', 'plan', 'NEXT', '{}', ?)
                    """,
                    idGenerator.newUuidV7(), task.id(),
                    Timestamp.from(Instant.parse("2026-09-01T08:00:0" + i + "Z")));
        }

        TaskRegistry.HistoryPage page1 = taskRegistry.getHistory(task.id(), null, 2);
        TaskRegistry.HistoryPage page2 = taskRegistry.getHistory(task.id(), page1.nextCursor(), 2);

        assertThat(page1.items()).hasSize(2);
        assertThat(page1.nextCursor()).isNotNull();
        assertThat(page2.items()).isNotEmpty();
        // строгая прогрессия (created_at, id) между страницами
        assertThat(page1.items().get(1).createdAt())
                .isBeforeOrEqualTo(page2.items().getFirst().createdAt());
        assertThat(page1.items()).noneMatch(first -> page2.items().stream()
                .anyMatch(second -> second.id().equals(first.id())));
    }

    private TaskRegistry.TaskSearchResult list(TaskRegistry.TaskSearchCriteria criteria) {
        try {
            return taskRegistry.list(criteria);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
