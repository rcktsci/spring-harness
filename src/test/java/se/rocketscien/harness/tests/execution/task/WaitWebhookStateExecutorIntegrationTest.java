package se.rocketscien.harness.tests.execution.task;

import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.execution.impl.WaitWebhookStateExecutor;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.TaskStatus;
import se.rocketscien.harness.task.TransitionKind;
import se.rocketscien.harness.tests.workflow.WorkflowTestFixtures;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WAIT_WEBHOOK (спека task-engine + inbound-triggers): валидный payload → NEXT (reason:
 * kind/source/payloadSummary — полное тело в истории не живёт, D-29), payload вне
 * payloadSchema (ограниченный профиль D-58) → ERROR с validationErrors, вне WAIT_WEBHOOK —
 * NOT_WAITING (HTTP-слой ответит 409), повторная доставка — no-op, nullable source (без
 * {@code ?source=}) — без NPE, payload сверх byte-size-limit — summary {byteSize, truncated}.
 */
class WaitWebhookStateExecutorIntegrationTest extends BaseApplicationTest {

    @Autowired
    private WaitWebhookStateExecutor webhookExecutor;

    @Autowired
    private TaskRegistry taskRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    @Test
    void validPayloadMovesTaskNextWithReason() throws Exception {
        Task task = newWaitTask(WorkflowTestFixtures.waitWebhookGraph());

        WaitWebhookStateExecutor.WebhookOutcome outcome = webhookExecutor.onWebhookArrived(
                task.id(), Map.of("status", "готово"), "github");

        assertThat(outcome).isEqualTo(WaitWebhookStateExecutor.WebhookOutcome.ACCEPTED_NEXT);
        Task after = taskRegistry.get(task.id());
        assertThat(after.currentState()).isEqualTo("done");
        assertThat(after.statusProjection()).isEqualTo(TaskStatus.SUCCEEDED);

        TaskRegistry.HistoryPage history = taskRegistry.getHistory(task.id(), null, null);
        Map<String, Object> reason = history.items().getFirst().reason();
        assertThat(reason)
                .containsEntry("kind", "webhook")
                .containsEntry("source", "github")
                .doesNotContainKey("payload");
        assertThat(reason.get("payloadSummary")).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("topKeys", List.of("status"))
                .doesNotContainKey("truncated");
        assertThat((Integer) ((Map<?, ?>) reason.get("payloadSummary")).get("byteSize")).isPositive();
    }

    @Test
    void webhookWithoutSourceIsAcceptedWithoutNpe() throws Exception {
        Task task = newWaitTask(WorkflowTestFixtures.waitWebhookGraph());

        WaitWebhookStateExecutor.WebhookOutcome outcome = webhookExecutor.onWebhookArrived(
                task.id(), Map.of("status", "готово"), null);

        assertThat(outcome).isEqualTo(WaitWebhookStateExecutor.WebhookOutcome.ACCEPTED_NEXT);
        Map<String, Object> reason = taskRegistry.getHistory(task.id(), null, null)
                .items().getFirst().reason();
        assertThat(reason).containsEntry("kind", "webhook").doesNotContainKey("source");
    }

    @Test
    void payloadSummaryTruncatedBeyondByteSizeLimit() throws Exception {
        Task task = newWaitTask(WorkflowTestFixtures.waitWebhookGraph());
        Map<String, Object> bigPayload = Map.of("status", "ok", "filler", "x".repeat(200));

        WaitWebhookStateExecutor.WebhookOutcome outcome = webhookExecutor.onWebhookArrived(
                task.id(), bigPayload, "bulk");

        assertThat(outcome).isEqualTo(WaitWebhookStateExecutor.WebhookOutcome.ACCEPTED_NEXT);
        Map<String, Object> reason = taskRegistry.getHistory(task.id(), null, null)
                .items().getFirst().reason();
        assertThat(reason.get("payloadSummary")).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .doesNotContainKey("topKeys")
                .containsEntry("truncated", true);
        assertThat((Integer) ((Map<?, ?>) reason.get("payloadSummary")).get("byteSize"))
                .isGreaterThan(64);
    }

    @Test
    void payloadViolatingSchemaMovesTaskError() throws Exception {
        Task task = newWaitTask(TaskEngineTestFixtures.waitWebhookSchemaGraph());

        WaitWebhookStateExecutor.WebhookOutcome outcome = webhookExecutor.onWebhookArrived(
                task.id(), Map.of("wrong", true), "ci-bot");

        assertThat(outcome).isEqualTo(WaitWebhookStateExecutor.WebhookOutcome.ACCEPTED_ERROR);
        Task after = taskRegistry.get(task.id());
        assertThat(after.currentState()).isEqualTo("failed");
        assertThat(after.statusProjection()).isEqualTo(TaskStatus.FAILED);

        Map<String, Object> reason = taskRegistry.getHistory(task.id(), null, null).items().getFirst().reason();
        assertThat(reason).containsEntry("kind", "webhook").containsEntry("source", "ci-bot");
        assertThat(reason.get("validationErrors")).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .isNotEmpty();
    }

    @Test
    void notWaitingWhenTaskIsInOtherState() {
        Task task = TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                WorkflowTestFixtures.twoPhaseGraph(), "plan");

        assertThat(webhookExecutor.onWebhookArrived(task.id(), Map.of("status", "ok"), "github"))
                .isEqualTo(WaitWebhookStateExecutor.WebhookOutcome.NOT_WAITING);
    }

    @Test
    void redeliveryAfterTransitionIsNoop() throws Exception {
        Task task = newWaitTask(WorkflowTestFixtures.waitWebhookGraph());
        webhookExecutor.onWebhookArrived(task.id(), Map.of("status", "ok"), "github");

        // ретрай после перехода: задача более не в WAIT_WEBHOOK — 409-семантика
        assertThat(webhookExecutor.onWebhookArrived(task.id(), Map.of("status", "ok"), "github"))
                .isEqualTo(WaitWebhookStateExecutor.WebhookOutcome.NOT_WAITING);
        // история не задвоилась
        List<Map<String, Object>> transitions = jdbcTemplate.queryForList(
                "SELECT to_state FROM task_transition_history WHERE task_id = ?", task.id());
        assertThat(transitions).hasSize(1);
    }

    private Task newWaitTask(Map<String, Object> graph) {
        return TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator, graph, "wait");
    }
}
