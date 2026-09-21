package se.rocketscien.harness.tests.api;

import org.awaitility.Awaitility;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.config.RecordingTaskWakeListener;
import se.rocketscien.harness.execution.TaskEventBroadcaster;
import se.rocketscien.harness.execution.impl.TaskEngine;
import se.rocketscien.harness.session.StateSessionService;
import se.rocketscien.harness.task.TaskEvent;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.TransitionKind;
import se.rocketscien.harness.testclient.ApiClient;
import se.rocketscien.harness.testclient.ApiException;
import se.rocketscien.harness.testclient.api.TaskCommandsApi;
import se.rocketscien.harness.testclient.api.TasksApi;
import se.rocketscien.harness.testclient.model.CreateTaskRequest;
import se.rocketscien.harness.testclient.model.SuspendTaskRequest;
import se.rocketscien.harness.testclient.model.TaskDto;
import se.rocketscien.harness.tests.task.TaskTestFixtures;
import se.rocketscien.harness.tests.workflow.WorkflowTestFixtures;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static se.rocketscien.harness.tests.api.ApiFixtures.apiClient;
import static se.rocketscien.harness.tests.api.ApiFixtures.insertAgentChain;
import static se.rocketscien.harness.tests.api.ApiFixtures.insertAppUser;
import static se.rocketscien.harness.tests.api.ApiFixtures.keycloakToken;
import static se.rocketscien.harness.tests.api.ApiFixtures.sendRaw;

/**
 * Команды задачи (пачка K.2, api-contracts §4.1 RPC-команды): suspend — идемпотентный флаг
 * (+каскад на поддерево), кадр task.status в SSE-канале задачи; resume — снятие флага +
 * task-wake, терминальная → 409 task-already-terminal; stop — всегда каскадный ('$CANCELLED'
 * поддерева, история kind=CANCEL) → 202 и отмена Turn'ов STATE-сессий поддерева
 * ({@code TaskWakeDispatcher.handleStop}).
 */
class TaskCommandsApiTest extends BaseApplicationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    private String aliceToken;
    private ApiClient aliceClient;
    private TasksApi tasksApi;
    private TaskCommandsApi commandsApi;
    private ApiFixtures.AgentSeed agentSeed;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IdGenerator idGenerator;
    @Autowired
    private Environment environment;
    @Autowired
    private TaskRegistry taskRegistry;
    @Autowired
    private TaskEngine taskEngine;
    @Autowired
    private RecordingTaskWakeListener wakeListener;
    @Autowired
    private TaskEventBroadcaster broadcaster;
    @Autowired
    private StateSessionService stateSessions;

    @BeforeEach
    void setUpClients() {
        aliceToken = keycloakToken(http, "alice", "alice-password");
        aliceClient = apiClient(localServerUrl(), aliceToken);
        tasksApi = new TasksApi(aliceClient);
        commandsApi = new TaskCommandsApi(aliceClient);
        agentSeed = insertAgentChain(jdbcTemplate, idGenerator, environment);
    }

    @Test
    void suspendSetsFlagIdempotently() throws Exception {
        TaskDto task = createWaitTask();

        commandsApi.suspendTask(task.getId(), new SuspendTaskRequest().cascade(false));
        commandsApi.suspendTask(task.getId(), new SuspendTaskRequest().cascade(false));

        assertThat(tasksApi.getTask(task.getId()).getSuspended()).isTrue();
    }

    @Test
    void suspendCascadeSuspendsSubtree() throws Exception {
        TaskDto parent = createWaitTask();
        TaskDto child = createSubtask(parent.getId());
        TaskDto grandChild = createSubtask(child.getId());

        commandsApi.suspendTask(parent.getId(), new SuspendTaskRequest().cascade(true));

        assertThat(tasksApi.getTask(parent.getId()).getSuspended()).isTrue();
        assertThat(tasksApi.getTask(child.getId()).getSuspended()).isTrue();
        assertThat(tasksApi.getTask(grandChild.getId()).getSuspended()).isTrue();
    }

    @Test
    void suspendWithoutCascadeLeavesSubtreeRunning() throws Exception {
        TaskDto parent = createWaitTask();
        TaskDto child = createSubtask(parent.getId());

        commandsApi.suspendTask(parent.getId(), new SuspendTaskRequest().cascade(false));

        assertThat(tasksApi.getTask(parent.getId()).getSuspended()).isTrue();
        assertThat(tasksApi.getTask(child.getId()).getSuspended()).isFalse();
    }

    @Test
    void suspendWithoutCascadeReturns422Required() throws Exception {
        // GLM nit: cascade — required по спеке; пустое тело/отсутствующее поле → 422 rule=required
        TaskDto task = createWaitTask();

        HttpResponse<String> emptyBody = sendRaw(http, localServerUrl(), "POST",
                "/api/v1/tasks/" + task.getId() + "/suspend", aliceToken,
                "application/json", "application/problem+json", "{}");

        assertThat(emptyBody.statusCode()).isEqualTo(422);
        JsonNode body = problemReader().read(emptyBody.body());
        assertThat(body.get("code").asText()).isEqualTo("validation-failed");
        assertThat(body.get("errors").get(0).get("rule").asText()).isEqualTo("required");
        assertThat(body.get("errors").get(0).get("pointer").asText()).isEqualTo("/cascade");
    }

    @Test
    void suspendEmitsTaskStatusFrameIntoSseChannel() throws Exception {
        TaskDto task = createWaitTask();
        BlockingQueue<TaskEvent> events = new LinkedBlockingQueue<>();
        try (TaskEventBroadcaster.Subscription ignored = broadcaster.subscribe(task.getId(), events::add)) {
            commandsApi.suspendTask(task.getId(), new SuspendTaskRequest().cascade(false));

            TaskEvent event = events.poll(5, TimeUnit.SECONDS);
            assertThat(event).isInstanceOf(TaskEvent.Status.class);
            var status = (TaskEvent.Status) event;
            assertThat(status.suspended()).isTrue();
            assertThat(status.statusProjection().name()).isEqualTo("WAITING");
            assertThat(status.seq()).isEqualTo(1);
        }
    }

    @Test
    void resumeClearsFlagAndPublishesWake() throws Exception {
        TaskDto task = createWaitTask();
        commandsApi.suspendTask(task.getId(), new SuspendTaskRequest().cascade(false));
        wakeListener.clear();

        commandsApi.resumeTask(task.getId());

        assertThat(tasksApi.getTask(task.getId()).getSuspended()).isFalse();
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(wakeListener.wakes()).contains(task.getId()));
    }

    @Test
    void resumeTerminalTaskReturns409TaskAlreadyTerminal() throws Exception {
        TaskDto task = createWaitTask();
        taskEngine.processTaskTransition(task.getId(), "wait", "done", TransitionKind.NEXT,
                Map.of("text", "завершено"));

        ApiException failure = assertThrows(ApiException.class,
                () -> commandsApi.resumeTask(task.getId()));

        assertThat(failure.getCode()).isEqualTo(409);
        assertThat(problemCode(failure)).isEqualTo("task-already-terminal");
    }

    @Test
    void stopCascadesSubtreeIntoCancelled() throws Exception {
        TaskDto parent = createWaitTask();
        TaskDto child = createSubtask(parent.getId());

        commandsApi.stopTask(parent.getId());

        TaskDto stoppedParent = tasksApi.getTask(parent.getId());
        assertThat(stoppedParent.getCurrentState()).isEqualTo(TaskRegistry.CANCELLED_STATE);
        assertThat(stoppedParent.getStatusProjection().getValue()).isEqualTo("CANCELLED");
        assertThat(stoppedParent.getSuspended()).isTrue();

        TaskDto stoppedChild = tasksApi.getTask(child.getId());
        assertThat(stoppedChild.getCurrentState()).isEqualTo(TaskRegistry.CANCELLED_STATE);
        assertThat(stoppedChild.getStatusProjection().getValue()).isEqualTo("CANCELLED");

        var history = tasksApi.listTaskHistory(parent.getId(), null, null).getItems();
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().getKind().getValue()).isEqualTo("CANCEL");
        assertThat(history.getFirst().getFromState()).isEqualTo("wait");
        assertThat(history.getFirst().getToState()).isEqualTo(TaskRegistry.CANCELLED_STATE);
        assertThat(history.getFirst().getReason()).containsEntry("kind", "stop");
    }

    @Test
    void stopTerminalTaskReturns409() throws Exception {
        TaskDto task = createWaitTask();
        commandsApi.stopTask(task.getId());

        ApiException failure = assertThrows(ApiException.class,
                () -> commandsApi.stopTask(task.getId()));

        assertThat(failure.getCode()).isEqualTo(409);
        assertThat(problemCode(failure)).isEqualTo("task-already-terminal");
    }

    @Test
    void commandsOnUnknownTaskReturn404() {
        UUID absent = UUID.randomUUID();

        assertThat(assertThrows(ApiException.class,
                () -> commandsApi.suspendTask(absent, new SuspendTaskRequest().cascade(false)))
                .getCode()).isEqualTo(404);
        assertThat(assertThrows(ApiException.class,
                () -> commandsApi.resumeTask(absent)).getCode()).isEqualTo(404);
        assertThat(assertThrows(ApiException.class,
                () -> commandsApi.stopTask(absent)).getCode()).isEqualTo(404);
    }

    @Test
    void stopCancelsTurnOfStateSessionOfStoppedTask() throws Exception {
        // AGENT-старт с несуществующим agent_key: bootstrap диспетчера тихо падает
        // (LLM не зовётся), STATE-сессию создаём напрямую; stop отменяет её Turn.
        TaskTestFixtures.WfRevision wf = seedWorkflow(agentGraph("ghost-agent"), "plan");
        UUID owner = insertAppUser(jdbcTemplate);
        UUID taskId = taskRegistry.createTask(new TaskRegistry.CreateTaskCommand(
                wf.revisionId(), "Останавливаемая", "d", null, owner, null, Map.of(), List.of())).id();

        UUID sessionId = stateSessions.findOrCreate(taskId, "plan", agentSeed.revisionId()).id();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cancel_requested FROM session WHERE id = ?", Boolean.class, sessionId)).isFalse();

        taskRegistry.stop(taskId);

        // handleStop — асинхронно на wake после коммита (диспетчер на виртуальных потоках)
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                        "SELECT cancel_requested FROM session WHERE id = ?",
                        Boolean.class, sessionId)).isTrue());
    }

    // --- фикстуры ------------------------------------------------------------

    private TaskTestFixtures.WfRevision seedWorkflow(Map<String, Object> graph, String startState) {
        return TaskTestFixtures.insertRevisionWithKey(jdbcTemplate, idGenerator, graph, startState);
    }

    private Map<String, Object> agentGraph(String agentKey) {
        return WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("plan", "AGENT", Map.of("agent_key", agentKey)),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))),
                List.of(
                        WorkflowTestFixtures.transition("plan", "done", "NEXT"),
                        WorkflowTestFixtures.transition("plan", "failed", "ERROR")));
    }

    private TaskDto createWaitTask() throws Exception {
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");
        return tasksApi.createTask(new CreateTaskRequest()
                .title("Задача " + UUID.randomUUID())
                .description("Описание задачи")
                .workflowKey(wf.workflowKey()));
    }

    private TaskDto createSubtask(UUID parentId) throws Exception {
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");
        return tasksApi.createSubtask(parentId, new CreateTaskRequest()
                .title("Подзадача " + UUID.randomUUID())
                .description("Описание задачи")
                .workflowKey(wf.workflowKey()));
    }

    private String problemCode(ApiException exception) {
        return problemReader().read(exception.getResponseBody()).get("code").asText();
    }

    private ProblemReader problemReader() {
        return new ProblemReader();
    }

    /** Мелкий ридер problem+json (Jackson 2 в тест-класспассе). */
    private static final class ProblemReader {
        private final ObjectMapper mapper =
                new ObjectMapper();

        JsonNode read(String body) {
            try {
                return mapper.readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException("Не удалось разобрать JSON: " + body, e);
            }
        }
    }
}
