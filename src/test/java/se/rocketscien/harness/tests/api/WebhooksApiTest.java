package se.rocketscien.harness.tests.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.AesGcmEncryption;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.common.security.WebhookSignatureVerifier;
import se.rocketscien.harness.config.RecordingTaskWakeListener;
import se.rocketscien.harness.config.WebhookProperties;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.TaskStatus;
import se.rocketscien.harness.task.Trigger;
import se.rocketscien.harness.task.TriggerRegistry;
import se.rocketscien.harness.tests.execution.task.TaskEngineTestFixtures;
import se.rocketscien.harness.tests.task.TaskTestFixtures;
import se.rocketscien.harness.tests.workflow.WorkflowTestFixtures;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Входящие вебхуки (пачка L.3, api-contracts §4.4; спека inbound-triggers): webhook задачи —
 * валидный payload → 202 + переход NEXT (reason: kind/source/payloadSummary), payload вне
 * payloadSchema → 202 + ERROR (validationErrors), повторная доставка → 409
 * task-not-waiting-webhook; webhook триггера → 202 {taskId} (задача на пиннутой ревизии,
 * params/tags/owner триггера, author NULL), отозванный триггер → 410 trigger-revoked;
 * AGENT-старт — EVENT-wake → bootstrap STATE-сессии → Turn на WireMock-LLM.
 */
class WebhooksApiTest extends BaseApplicationTest {

    private static final String LLM_PATH = "/v1/chat/completions";

    /** Тот же ключ, что harness.llm.encryption-keys.1 в application-test.yml. */
    private static final byte[] LLM_KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private WebhookSignatureVerifier signatureVerifier;
    @Autowired
    private WebhookProperties webhookProperties;
    @Autowired
    private TaskRegistry taskRegistry;
    @Autowired
    private TriggerRegistry triggerRegistry;
    @Autowired
    private RecordingTaskWakeListener wakeListener;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IdGenerator idGenerator;
    @Autowired
    private Environment environment;

    private UUID ownerId;

    @BeforeEach
    void setUp() {
        llmWireMock.resetAll();
        wakeListener.clear();
        ownerId = WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
    }

    @Test
    void validTaskWebhookMovesWaitWebhookTaskNextWithReason() throws Exception {
        Task task = newWaitTask(WorkflowTestFixtures.waitWebhookGraph());

        HttpResponse<String> response = postJson(taskWebhookUrl(task.id(), "github"),
                "{\"status\":\"готово\"}");

        assertThat(response.statusCode()).isEqualTo(202);
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
                        InstanceOfAssertFactories.MAP)
                .containsEntry("topKeys", List.of("status"));
    }

    @Test
    void invalidPayloadReturns202WithErrorTransition() throws Exception {
        Task task = newWaitTask(TaskEngineTestFixtures.waitWebhookSchemaGraph());

        HttpResponse<String> response = postJson(taskWebhookUrl(task.id(), "ci-bot"),
                "{\"wrong\":true}");

        assertThat(response.statusCode()).isEqualTo(202);
        Task after = taskRegistry.get(task.id());
        assertThat(after.currentState()).isEqualTo("failed");
        assertThat(after.statusProjection()).isEqualTo(TaskStatus.FAILED);

        Map<String, Object> reason = taskRegistry.getHistory(task.id(), null, null)
                .items().getFirst().reason();
        assertThat(reason.get("validationErrors")).asInstanceOf(
                        InstanceOfAssertFactories.LIST)
                .isNotEmpty();
    }

    @Test
    void taskWebhookOutsideWaitWebhookReturns409() throws Exception {
        // raw-вставка мимо реестра: AGENT-state без агента, EVENT-wake не публикуется
        UUID taskId = TaskEngineTestFixtures.insertTaskRaw(jdbcTemplate, idGenerator,
                WorkflowTestFixtures.twoPhaseGraph(), "plan", "plan", "AGENT", "RUNNING", false);

        HttpResponse<String> response = postJson(taskWebhookUrl(taskId, "github"),
                "{\"status\":\"ok\"}");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("\"code\":\"task-not-waiting-webhook\"");
    }

    @Test
    void taskWebhookRedeliveryAfterTransitionIs409Noop() throws Exception {
        Task task = newWaitTask(WorkflowTestFixtures.waitWebhookGraph());
        assertThat(postJson(taskWebhookUrl(task.id(), "github"), "{\"status\":\"ok\"}")
                .statusCode()).isEqualTo(202);

        // идемпотентность по построению: повторная доставка — 409, история не задваивается
        HttpResponse<String> retry = postJson(taskWebhookUrl(task.id(), "github"), "{\"status\":\"ok\"}");
        assertThat(retry.statusCode()).isEqualTo(409);
        assertThat(retry.body()).contains("\"code\":\"task-not-waiting-webhook\"");
        Integer transitions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM task_transition_history WHERE task_id = ?", Integer.class, task.id());
        assertThat(transitions).isEqualTo(1);
    }

    @Test
    void triggerWebhookCreatesTaskOnPinnedRevision() throws Exception {
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");
        Trigger trigger = triggerRegistry.create(new TriggerRegistry.CreateTriggerCommand(
                ownerId, "Сборка", wf.workflowKey(), 1, Map.of("branch", "main"), List.of("ci")));

        HttpResponse<String> response = postTriggerWebhook(triggerWebhookUrl(trigger));

        assertThat(response.statusCode()).isEqualTo(202);
        UUID createdTaskId = taskIdOf(response.body());
        Task created = taskRegistry.get(createdTaskId);
        assertThat(created.workflowRevisionId()).isEqualTo(wf.revisionId());
        assertThat(created.params()).containsEntry("branch", "main");
        assertThat(created.tags()).containsExactly("ci");
        assertThat(created.ownerUserId()).isEqualTo(ownerId);
        assertThat(created.authorUserId()).as("агент не указан — author NULL").isNull();
        assertThat(created.currentState()).isEqualTo("wait");
        // задача раскачена EVENT-wake'ом из создания (после коммита HTTP-транзакции)
        assertThat(wakeListener.wakes()).contains(createdTaskId);
    }

    @Test
    void revokedTriggerWebhookReturns410WithoutTaskCreation() throws Exception {
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");
        Trigger trigger = triggerRegistry.create(new TriggerRegistry.CreateTriggerCommand(
                ownerId, "Отзываемый", wf.workflowKey(), null, Map.of(), List.of()));
        triggerRegistry.revoke(trigger.id());
        long tasksBefore = tasksCount();

        HttpResponse<String> response = postTriggerWebhook(triggerWebhookUrl(trigger));

        assertThat(response.statusCode()).isEqualTo(410);
        assertThat(response.body()).contains("\"code\":\"trigger-revoked\"");
        assertThat(tasksCount()).isEqualTo(tasksBefore);
    }

    @Test
    void triggerWebhookWithAgentStartBootstrapsStateSessionAndRunsTurn() throws Exception {
        String agentKey = "orchestrator-" + UUID.randomUUID();
        seedAgent(agentKey);
        // AGENT-старт: задача триггера раскачивается wake'ом → bootstrap STATE-сессии → Turn
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("plan", "AGENT", Map.of("agent_key", agentKey)),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("plan", "done", "NEXT"),
                        WorkflowTestFixtures.transition("plan", "failed", "ERROR")
                ));
        TaskTestFixtures.WfRevision wf = seedWorkflow(graph, "plan");
        Trigger trigger = triggerRegistry.create(new TriggerRegistry.CreateTriggerCommand(
                ownerId, "Агентский", wf.workflowKey(), null, Map.of(), List.of()));
        llmWireMock.stubFor(post(urlEqualTo(LLM_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("data: " + textChunk("принял задачу") + "\n\n"
                                + "data: " + usageChunk() + "\n\ndata: [DONE]\n\n")));

        HttpResponse<String> response = postTriggerWebhook(triggerWebhookUrl(trigger));

        assertThat(response.statusCode()).isEqualTo(202);
        UUID createdTaskId = taskIdOf(response.body());
        Awaitility.await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> "COMPLETED".equals(turnOutcome(createdTaskId)));
        Map<String, Object> session = jdbcTemplate.queryForMap(
                "SELECT kind, state_code FROM session WHERE task_id = ? AND kind = 'STATE'", createdTaskId);
        assertThat(session.get("state_code")).isEqualTo("plan");
    }

    // --- вспомогательное ------------------------------------------------------

    private Task newWaitTask(Map<String, Object> graph) {
        return TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator, graph, "wait");
    }

    private TaskTestFixtures.WfRevision seedWorkflow(Map<String, Object> graph, String startState) {
        return TaskTestFixtures.insertRevisionWithKey(jdbcTemplate, idGenerator, graph, startState);
    }

    private long tasksCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM task", Long.class);
    }

    private String taskWebhookUrl(UUID taskId, String source) {
        String token = signatureVerifier.expectedToken("task", taskId);
        String url = "%s/api/webhooks/tasks/%s/%s".formatted(localServerUrl(), taskId, token);
        return source == null ? url : url + "?source=" + source;
    }

    /** Реальный capability-URL триггера, перебазированный на живой сервер тестов. */
    private String triggerWebhookUrl(Trigger trigger) {
        return "%s/api/webhooks/triggers/%s/%s"
                .formatted(localServerUrl(), trigger.id(),
                        signatureVerifier.expectedToken("trigger", trigger.id()));
    }

    private HttpResponse<String> postJson(String url, String body) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postTriggerWebhook(String url) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private UUID taskIdOf(String body) {
        try {
            return UUID.fromString(new ObjectMapper()
                    .readTree(body).get("taskId").asText());
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось разобрать ответ вебхука: " + body, e);
        }
    }

    private String turnOutcome(UUID taskId) {
        UUID sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM session WHERE task_id = ? AND kind = 'STATE'", UUID.class, taskId);
        return sessionId == null ? null : jdbcTemplate.queryForObject(
                "SELECT last_turn_outcome FROM session WHERE id = ?", String.class, sessionId);
    }

    /** Цепочка user → credentials (WireMock-LLM) → модель → агент с ключом graph'а. */
    private void seedAgent(String agentKey) {
        UUID credentialsId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                """
                INSERT INTO llm_credentials (id, name, base_url, api_key_encrypted, key_version, created_at)
                VALUES (?, ?, ?, ?, 1, now())
                """,
                credentialsId, "creds-" + credentialsId,
                environment.getRequiredProperty("wiremock.llm.url") + "/v1",
                AesGcmEncryption.encrypt("sk-test", LLM_KEY));
        UUID modelId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                "INSERT INTO llm_model (id, credentials_id, model_id, created_at) VALUES (?, ?, ?, now())",
                modelId, credentialsId, "gpt-test");
        jdbcTemplate.update(
                """
                INSERT INTO agent (id, key, name, rev, role_prompt, llm_model_id, created_at)
                VALUES (?, ?, 'Агент триггера', 1, 'Ты исполнитель.', ?, now())
                """,
                idGenerator.newUuidV7(), agentKey, modelId);
    }

    private static String textChunk(String content) {
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"},\"finish_reason\":null}]}";
    }

    private static String usageChunk() {
        return "{\"id\":\"2\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    }
}
