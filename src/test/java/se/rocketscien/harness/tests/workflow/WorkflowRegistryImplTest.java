package se.rocketscien.harness.tests.workflow;

import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.workflow.InvalidCursorException;
import se.rocketscien.harness.workflow.WorkflowGraphInvalidException;
import se.rocketscien.harness.workflow.WorkflowGraphSchemaValidator;
import se.rocketscien.harness.workflow.WorkflowKeyAlreadyExistsException;
import se.rocketscien.harness.workflow.WorkflowNotFoundException;
import se.rocketscien.harness.workflow.WorkflowRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные тесты {@link WorkflowRegistryImpl} на живом Postgres (миграции 010/011):
 * создание rev=1, уникальность key, валидация графа, иммутабельные ревизии, cursor-пагинация.
 */
class WorkflowRegistryImplTest extends BaseApplicationTest {

    @Autowired
    private WorkflowRegistry workflowRegistry;

    @Autowired
    private WorkflowGraphSchemaValidator graphValidator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    @Test
    void createWorkflowReturnsFirstRevision() {
        UUID owner = WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator);

        WorkflowRegistry.WorkflowRevision revision = workflowRegistry.createWorkflow(
                owner, "feature-delivery-" + idGenerator.newUuidV7(), "Доставка фич",
                WorkflowTestFixtures.twoPhaseGraph(), "plan");

        assertThat(revision.rev()).isEqualTo(1);
        assertThat(revision.id()).isNotNull();
        assertThat(revision.graph()).isNotEmpty();
        assertThat(revision.createdAt()).isNotNull();
    }

    @Test
    void createWorkflowDuplicateKeyThrows() {
        UUID owner = WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        String key = "dup-" + idGenerator.newUuidV7();
        workflowRegistry.createWorkflow(owner, key, "Оригинал", WorkflowTestFixtures.twoPhaseGraph(), "plan");

        assertThatThrownBy(() -> workflowRegistry.createWorkflow(
                owner, key, "Дубль", WorkflowTestFixtures.twoPhaseGraph(), "plan"))
                .isInstanceOf(WorkflowKeyAlreadyExistsException.class);
    }

    @Test
    void createWorkflowInvalidGraphThrowsWithErrors() {
        UUID owner = WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        Map<String, Object> broken = WorkflowTestFixtures.graph(
                List.of(WorkflowTestFixtures.state("a", "FORK", Map.of())),
                List.of());

        assertThatThrownBy(() -> workflowRegistry.createWorkflow(owner, "broken", "Сломанный", broken, "plan"))
                .isInstanceOfSatisfying(WorkflowGraphInvalidException.class,
                        exception -> assertThat(exception.getErrors()).isNotEmpty());
    }

    @Test
    void createWorkflowUnknownStartStateThrows() {
        UUID owner = WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator);

        assertThatThrownBy(() -> workflowRegistry.createWorkflow(
                owner, "bad-start-" + idGenerator.newUuidV7(), "Кривой старт",
                WorkflowTestFixtures.twoPhaseGraph(), "nowhere"))
                .isInstanceOf(WorkflowGraphInvalidException.class);
    }

    @Test
    void newRevisionIncrementsAndKeepsOld() {
        UUID owner = WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        String key = "revs-" + idGenerator.newUuidV7();
        UUID rev1Id = workflowRegistry
                .createWorkflow(owner, key, "Ревизии", WorkflowTestFixtures.twoPhaseGraph(), "plan")
                .id();
        UUID rev2Id = workflowRegistry
                .newRevision(key, WorkflowTestFixtures.waitWebhookGraph(), "wait")
                .id();

        WorkflowRegistry.WorkflowRevision revision1 = workflowRegistry.getRevision(key, 1);
        WorkflowRegistry.WorkflowRevision revision2 = workflowRegistry.getRevision(key, 2);
        assertThat(revision1.id()).isEqualTo(rev1Id);
        assertThat(revision2.id()).isEqualTo(rev2Id);
        assertThat(revision1.startState()).isEqualTo("plan");
        assertThat(revision2.startState()).isEqualTo("wait");
        assertThat(workflowRegistry.get(key).latestRev()).isEqualTo(2);
    }

    @Test
    void newRevisionInvalidGraphThrows() {
        UUID owner = WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        String key = "revs-broken-" + idGenerator.newUuidV7();
        workflowRegistry.createWorkflow(owner, key, "Ок", WorkflowTestFixtures.twoPhaseGraph(), "plan");

        Map<String, Object> broken = WorkflowTestFixtures.graph(
                List.of(WorkflowTestFixtures.state("a", "TERMINAL", Map.of())),
                List.of());
        assertThatThrownBy(() -> workflowRegistry.newRevision(key, broken, "plan"))
                .isInstanceOf(WorkflowGraphInvalidException.class);
    }

    /** R-2: конкурентные newRevision одного workflow — обе ревизии создаются с разными rev. */
    @Test
    void concurrentNewRevisionsGetDistinctRevs() throws Exception {
        UUID owner = WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        String key = "race-" + idGenerator.newUuidV7();
        workflowRegistry.createWorkflow(owner, key, "Гонки", WorkflowTestFixtures.twoPhaseGraph(), "plan");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<WorkflowRegistry.WorkflowRevision> first = pool.submit(
                    () -> workflowRegistry.newRevision(key, WorkflowTestFixtures.waitWebhookGraph(), "wait"));
            Future<WorkflowRegistry.WorkflowRevision> second = pool.submit(
                    () -> workflowRegistry.newRevision(key, WorkflowTestFixtures.waitWebhookGraph(), "wait"));

            int rev1 = first.get(30, TimeUnit.SECONDS).rev();
            int rev2 = second.get(30, TimeUnit.SECONDS).rev();
            assertThat(Set.of(rev1, rev2)).containsExactlyInAnyOrder(2, 3);
        } finally {
            pool.shutdownNow();
        }
        assertThat(workflowRegistry.get(key).latestRev()).isEqualTo(3);
    }

    @Test
    void newRevisionUnknownWorkflowThrows() {
        assertThatThrownBy(() -> workflowRegistry.newRevision(
                "no-such-" + idGenerator.newUuidV7(), WorkflowTestFixtures.twoPhaseGraph(), "plan"))
                .isInstanceOf(WorkflowNotFoundException.class);
    }

    @Test
    void getUnknownWorkflowThrows() {
        assertThatThrownBy(() -> workflowRegistry.get("no-such-" + idGenerator.newUuidV7()))
                .isInstanceOf(WorkflowNotFoundException.class);
    }

    @Test
    void getRevisionUnknownRevThrows() {
        UUID owner = WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        String key = "one-rev-" + idGenerator.newUuidV7();
        workflowRegistry.createWorkflow(owner, key, "Одна ревизия", WorkflowTestFixtures.twoPhaseGraph(), "plan");

        assertThatThrownBy(() -> workflowRegistry.getRevision(key, 9))
                .isInstanceOf(WorkflowNotFoundException.class);
    }

    @Test
    void listPaginatesByCreatedAtDesc() throws Exception {
        UUID owner = WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        String prefix = "page-" + idGenerator.newUuidV7() + "-";
        for (int i = 0; i < 3; i++) {
            workflowRegistry.createWorkflow(owner, prefix + i, "Workflow " + i,
                    WorkflowTestFixtures.twoPhaseGraph(), "plan");
        }
        // now() в разных транзакциях близки — выравниваем created_at детерминированно
        for (int i = 0; i < 3; i++) {
            jdbcTemplate.update("UPDATE workflow SET created_at = ? WHERE key = ?",
                    Timestamp.from(Instant.parse("2026-09-01T10:00:0" + i + "Z")), prefix + i);
        }

        WorkflowRegistry.WorkflowSearchResult page1 = workflowRegistry.list(
                new WorkflowRegistry.WorkflowSearchCriteria(null, 2));
        WorkflowRegistry.WorkflowSearchResult page2 = workflowRegistry.list(
                new WorkflowRegistry.WorkflowSearchCriteria(page1.nextCursor(), 2));

        assertThat(page1.items()).hasSize(2);
        assertThat(page1.nextCursor()).isNotNull();
        assertThat(page1.items().get(0).key()).isEqualTo(prefix + "2");
        assertThat(page1.items().get(1).key()).isEqualTo(prefix + "1");
        assertThat(page2.items()).hasSize(1);
        assertThat(page2.items().get(0).key()).isEqualTo(prefix + "0");
        assertThat(page2.nextCursor()).isNull();
        assertThat(page1.items()).allSatisfy(workflow -> assertThat(workflow.latestRev()).isEqualTo(1));
    }

    @Test
    void listWithGarbageCursorThrowsInvalidCursor() {
        assertThatThrownBy(() -> workflowRegistry.list(
                new WorkflowRegistry.WorkflowSearchCriteria("%%%garbage%%%", 10)))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void validatorIsInjectableSingleton() {
        assertThat(graphValidator).isNotNull();
    }
}
