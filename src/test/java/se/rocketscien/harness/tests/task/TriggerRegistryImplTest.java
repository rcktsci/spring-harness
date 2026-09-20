package se.rocketscien.harness.tests.task;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.common.security.WebhookSignatureVerifier;
import se.rocketscien.harness.task.InvalidCursorException;
import se.rocketscien.harness.task.ParamsSchemaInvalidException;
import se.rocketscien.harness.task.Trigger;
import se.rocketscien.harness.task.TriggerNotFoundException;
import se.rocketscien.harness.task.TriggerRegistry;
import se.rocketscien.harness.task.WorkflowRevisionNotFoundException;
import se.rocketscien.harness.tests.workflow.WorkflowTestFixtures;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TriggerRegistry (пачка L.1, живой Postgres): создание с пином ревизии (latest и явный
 * rev), 404 на неизвестном ключе/ревизии, params против paramsSchema стартового состояния
 * (422 params-schema), capability-URL = base + HMAC-токен ({@code trigger:<id>}), revoke
 * (атомарный, ревью L-3: повторный revoke → 404 — ревоукить нечего), список createdAt desc
 * с фильтром mine и конверт-пагинацией, битый курсор → InvalidCursorException.
 */
class TriggerRegistryImplTest extends BaseApplicationTest {

    @Autowired
    private TriggerRegistry triggers;

    @Autowired
    private WebhookSignatureVerifier signatureVerifier;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    @Test
    void createPinsLatestRevisionAndBuildsCapabilityUrl() {
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");
        UUID owner = WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator);

        Trigger trigger = triggers.create(command(owner, wf.workflowKey(), null, Map.of()));

        assertThat(trigger.id()).isNotNull();
        assertThat(trigger.rev()).isEqualTo(1);
        assertThat(trigger.workflowKey()).isEqualTo(wf.workflowKey());
        assertThat(trigger.ownerUserId()).isEqualTo(owner);
        assertThat(trigger.revoked()).isFalse();
        assertThat(trigger.createdAt()).isNotNull();
        // url = base + /api/webhooks/triggers/{id}/{HMAC(secret, "trigger:"+id)}
        assertThat(trigger.url().toString()).isEqualTo(
                "http://webhook-test/api/webhooks/triggers/%s/%s"
                        .formatted(trigger.id(), signatureVerifier.expectedToken("trigger", trigger.id())));
    }

    @Test
    void createPinsExplicitRevision() {
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");
        UUID owner = WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator);

        Trigger trigger = triggers.create(command(owner, wf.workflowKey(), 1, Map.of()));

        assertThat(trigger.rev()).isEqualTo(1);
    }

    @Test
    void createWithUnknownWorkflowOrRevisionThrows404MappedException() {
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");
        UUID owner = WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator);

        assertThatThrownBy(() -> triggers.create(command(owner, "no-such-workflow", null, Map.of())))
                .isInstanceOf(WorkflowRevisionNotFoundException.class);
        assertThatThrownBy(() -> triggers.create(command(owner, wf.workflowKey(), 99, Map.of())))
                .isInstanceOf(WorkflowRevisionNotFoundException.class);
    }

    @Test
    void createValidatesParamsAgainstPinnedRevisionParamsSchema() {
        UUID owner = WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        TaskTestFixtures.WfRevision wf = seedWorkflow(TaskTestFixtures.paramsSchemaGraph(), "start");

        assertThatThrownBy(() -> triggers.create(command(owner, wf.workflowKey(), 1, Map.of("module", 42))))
                .isInstanceOf(ParamsSchemaInvalidException.class);

        Trigger valid = triggers.create(command(owner, wf.workflowKey(), 1, Map.of("module", "core")));
        assertThat(valid.params()).containsEntry("module", "core");
    }

    @Test
    void revokeIsFinalAndRepeatedRevokeIsNotFound() {
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");
        UUID owner = WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        Trigger trigger = triggers.create(command(owner, wf.workflowKey(), null, Map.of()));

        triggers.revoke(trigger.id());
        // ревью L-3: повторный revoke → 404 (нечего ревоукить); revoked_at не перезаписывается
        assertThatThrownBy(() -> triggers.revoke(trigger.id()))
                .isInstanceOf(TriggerNotFoundException.class);
        Trigger revoked = triggers.get(trigger.id());
        assertThat(revoked.revoked()).isTrue();
        assertThat(revoked.revokedAt()).isNotNull();

        assertThatThrownBy(() -> triggers.revoke(UUID.randomUUID()))
                .isInstanceOf(TriggerNotFoundException.class);
        assertThatThrownBy(() -> triggers.get(UUID.randomUUID()))
                .isInstanceOf(TriggerNotFoundException.class);
    }

    @Test
    void listReturnsNewestFirstWithMineFilterAndCursor() throws InvalidCursorException {
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");
        UUID owner = WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        UUID stranger = WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        Trigger first = triggers.create(command(owner, wf.workflowKey(), null, Map.of()));
        Trigger second = triggers.create(command(owner, wf.workflowKey(), null, Map.of()));
        triggers.create(command(stranger, wf.workflowKey(), null, Map.of()));

        // mine: только свои, новые впереди
        TriggerRegistry.TriggerPage minePage = triggers.list(
                new TriggerRegistry.TriggerSearchCriteria(owner, null, 10));
        assertThat(ids(minePage)).containsExactly(second.id(), first.id());

        // конверт-пагинация: страница 1 (limit 2) + nextCursor → страница 2
        TriggerRegistry.TriggerPage page1 = triggers.list(
                new TriggerRegistry.TriggerSearchCriteria(null, null, 2));
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.nextCursor()).isNotNull();

        TriggerRegistry.TriggerPage page2 = triggers.list(
                new TriggerRegistry.TriggerSearchCriteria(null, page1.nextCursor(), 2));
        assertThat(ids(page2)).doesNotContain(ids(page1).toArray(UUID[]::new));
        assertThat(page2.nextCursor()).isNull();
        assertThat(ids(page2)).contains(first.id());
    }

    @Test
    void listWithGarbageCursorThrowsInvalidCursor() {
        assertThatThrownBy(() -> triggers.list(
                new TriggerRegistry.TriggerSearchCriteria(null, "garbage-cursor", 10)))
                .isInstanceOf(InvalidCursorException.class);
    }

    private TriggerRegistry.CreateTriggerCommand command(UUID owner, String workflowKey,
                                                         Integer rev, Map<String, Object> params) {
        return new TriggerRegistry.CreateTriggerCommand(
                owner, "Триггер " + UUID.randomUUID(), workflowKey, rev, params, List.of());
    }

    private static List<UUID> ids(TriggerRegistry.TriggerPage page) {
        return page.items().stream().map(Trigger::id).toList();
    }

    private TaskTestFixtures.WfRevision seedWorkflow(Map<String, Object> graph, String startState) {
        return TaskTestFixtures.insertRevisionWithKey(jdbcTemplate, idGenerator, graph, startState);
    }
}
