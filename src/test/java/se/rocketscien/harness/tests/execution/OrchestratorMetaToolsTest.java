package se.rocketscien.harness.tests.execution;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.execution.TurnManager;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.session.TurnOutcome;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.TaskTreeNode;
import se.rocketscien.harness.task.TriggerRegistry;
import se.rocketscien.harness.workflow.WorkflowRegistry;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Пачка P (D-62/D-70): оркестраторские metaTools — 6 инструментов над реестрами
 * (create/edit_workflow, create_task/create_subtask, set_dependency, configure_trigger);
 * манифест и исполнение только при permissions_jsonb.metaTools = true, явный вызов без
 * флага — forbidden (no-metaTools); ошибки реестров — машиночитаемые коды в TOOL_RESULT
 * (422 graph-invalid / params-schema / dependency-invalid, 404 workflow-not-found).
 */
class OrchestratorMetaToolsTest extends BaseApplicationTest {

    private static final String PATH = "/v1/chat/completions";

    @Autowired
    private TurnManager turnManager;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    @Autowired
    private Environment environment;

    @Autowired
    private WorkflowRegistry workflows;

    @Autowired
    private TaskRegistry tasks;

    @Autowired
    private TriggerRegistry triggers;

    private final ObjectMapper json =
            JsonMapper.builder().build();

    @BeforeEach
    void resetStubs() {
        llmWireMock.resetAll();
    }

    @Test
    void createWorkflowMakesFirstRevision() {
        Session root = orchestratorSession();
        runTool(root, "create_workflow", Map.of(
                "key", "meta-wf",
                "name", "Мета WF",
                "graph", graph()));

        assertThat(journalField(root.id(), "TOOL_RESULT", "status")).isEqualTo("OK");
        assertThat(journalField(root.id(), "TOOL_RESULT", "output"))
                .contains("\"workflowKey\":\"meta-wf\"").contains("\"rev\":1");

        WorkflowRegistry.Workflow workflow = workflows.get("meta-wf");
        assertThat(workflow.latestRev()).isEqualTo(1);
        assertThat(workflow.name()).isEqualTo("Мета WF");
        assertThat(workflows.getRevision("meta-wf", 1).startState()).isEqualTo("plan");
    }

    @Test
    void editWorkflowAddsSecondRevision() {
        Session root = orchestratorSession();
        workflows.createWorkflow(root.ownerUserId(), "meta-edit", "Мета правка",
                graph(), "plan");

        Map<String, Object> edited = Map.of(
                "states", List.of(
                        Map.of("code", "plan", "type", "AGENT", "agent_key", "ghost"),
                        Map.of("code", "review", "type", "AGENT", "agent_key", "ghost"),
                        Map.of("code", "done", "type", "TERMINAL", "outcome", "SUCCESS")),
                "transitions", List.of(
                        Map.of("from", "plan", "to", "review", "kind", "NEXT"),
                        Map.of("from", "review", "to", "done", "kind", "NEXT")));
        runTool(root, "edit_workflow", Map.of("key", "meta-edit", "graph", edited, "start_state", "review"));

        assertThat(journalField(root.id(), "TOOL_RESULT", "status")).isEqualTo("OK");
        assertThat(journalField(root.id(), "TOOL_RESULT", "output")).contains("\"rev\":2");
        assertThat(workflows.get("meta-edit").latestRev()).isEqualTo(2);
        assertThat(workflows.getRevision("meta-edit", 2).startState()).isEqualTo("review");
    }

    @Test
    void createTaskPinsLatestRevision() {
        Session root = orchestratorSession();
        workflows.createWorkflow(root.ownerUserId(), "meta-task-wf", "Задачный WF",
                graph(), "plan");

        runTool(root, "create_task", Map.of(
                "title", "Мета-задача",
                "description", "создана оркестратором",
                "workflow_key", "meta-task-wf"));

        assertThat(journalField(root.id(), "TOOL_RESULT", "status")).isEqualTo("OK");
        UUID taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM task WHERE title = 'Мета-задача'", UUID.class);
        Task task = tasks.get(taskId);
        assertThat(task.currentState()).isEqualTo("plan");
        assertThat(task.workflowRevisionId()).isEqualTo(
                workflows.getRevision("meta-task-wf", 1).id());
    }

    @Test
    void createSubtaskNestsUnderParent() {
        Session root = orchestratorSession();
        WorkflowRegistry.WorkflowRevision revision = workflows.createWorkflow(
                root.ownerUserId(), "meta-sub-wf", "Субзадачный WF", graph(), "plan");
        Task parent = tasks.createTask(new TaskRegistry.CreateTaskCommand(
                revision.id(), "Родитель", "d", null, root.ownerUserId(), null, Map.of(), List.of()));

        runTool(root, "create_subtask", Map.of(
                "parent_task_id", parent.id().toString(),
                "title", "Ребёнок",
                "workflow_key", "meta-sub-wf"));

        assertThat(journalField(root.id(), "TOOL_RESULT", "status")).isEqualTo("OK");
        UUID childId = jdbcTemplate.queryForObject(
                "SELECT id FROM task WHERE title = 'Ребёнок'", UUID.class);
        assertThat(tasks.get(childId).parentTaskId()).isEqualTo(parent.id());
        TaskTreeNode tree = tasks.getTree(parent.id(), null);
        assertThat(tree.children()).hasSize(1);
    }

    @Test
    void setDependencyAddsBatchAtomically() {
        Session root = orchestratorSession();
        WorkflowRegistry.WorkflowRevision revision = workflows.createWorkflow(
                root.ownerUserId(), "meta-dep-wf", "Зависимый WF", graph(), "plan");
        Task blocker = tasks.createTask(new TaskRegistry.CreateTaskCommand(
                revision.id(), "Блокер", "d", null, root.ownerUserId(), null, Map.of(), List.of()));
        Task blocked = tasks.createTask(new TaskRegistry.CreateTaskCommand(
                revision.id(), "Блокируемый", "d", null, root.ownerUserId(), null, Map.of(), List.of()));

        runTool(root, "set_dependency", Map.of(
                "blocked_task_id", blocked.id().toString(),
                "blocked_by", List.of(blocker.id().toString())));

        assertThat(journalField(root.id(), "TOOL_RESULT", "status")).isEqualTo("OK");
        Integer edges = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_dependency WHERE blocker_task_id = ? AND blocked_task_id = ?",
                Integer.class, blocker.id(), blocked.id());
        assertThat(edges).isEqualTo(1);
    }

    @Test
    void configureTriggerReturnsCapabilityUrl() {
        Session root = orchestratorSession();
        workflows.createWorkflow(root.ownerUserId(), "meta-trg-wf", "Триггерный WF", graph(), "plan");

        runTool(root, "configure_trigger", Map.of(
                "name", "мета-триггер",
                "workflow_key", "meta-trg-wf"));

        assertThat(journalField(root.id(), "TOOL_RESULT", "status")).isEqualTo("OK");
        String output = journalField(root.id(), "TOOL_RESULT", "output");
        assertThat(output).contains("triggerId").contains("url").contains("http");
        UUID triggerId = jdbcTemplate.queryForObject(
                "SELECT id FROM trigger WHERE name = 'мета-триггер'", UUID.class);
        var trigger = triggers.get(triggerId);
        assertThat(trigger.workflowKey()).isEqualTo("meta-trg-wf");
        assertThat(trigger.revoked()).isFalse();
    }

    @Test
    void createTaskWithExplicitRevPinsThatRevision() {
        // P-3: rev? — явный пин вместо последней ревизии (agent-tools §2b)
        Session root = orchestratorSession();
        workflows.createWorkflow(root.ownerUserId(), "meta-rev-wf", "Ревизии", graph(), "plan");
        workflows.newRevision("meta-rev-wf", graph(), "plan");

        runTool(root, "create_task", Map.of(
                "title", "На первой ревизии",
                "workflow_key", "meta-rev-wf",
                "rev", 1));

        assertThat(journalField(root.id(), "TOOL_RESULT", "status")).isEqualTo("OK");
        UUID taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM task WHERE title = 'На первой ревизии'", UUID.class);
        assertThat(tasks.get(taskId).workflowRevisionId())
                .isEqualTo(workflows.getRevision("meta-rev-wf", 1).id());
    }

    @Test
    void createWorkflowRejectsNonKebabKey() {
        // P-4: key валидируется как в REST — 422 validation-failed (rule=kebab-case)
        Session root = orchestratorSession();
        runTool(root, "create_workflow", Map.of("key", "Bad_Key", "graph", graph()));

        assertThat(journalField(root.id(), "TOOL_RESULT", "status")).isEqualTo("ERROR");
        assertThat(journalField(root.id(), "TOOL_RESULT", "output"))
                .contains("422 validation-failed").contains("kebab-case");
        Integer created = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow WHERE key = 'Bad_Key'", Integer.class);
        assertThat(created).isZero();
    }

    @Test
    void orchestratorToolForbiddenForPlainAgent() {
        // P.4: без permissions_jsonb.metaTools манифест не содержит инструменты; явный
        // вызов — forbidden (no-metaTools), сущность не создаётся
        Session plain = ExecutionFixtures.newSession(jdbcTemplate, sessionStore, idGenerator, environment);
        runTool(plain, "create_workflow", Map.of("key", "forbidden-wf", "graph", graph()));

        assertThat(journalField(plain.id(), "TOOL_RESULT", "status")).isEqualTo("ERROR");
        assertThat(journalField(plain.id(), "TOOL_RESULT", "output")).contains("no-metaTools");
        Integer created = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow WHERE key = 'forbidden-wf'", Integer.class);
        assertThat(created).isZero();
    }

    @Test
    void registryErrorsReportedWithCodes() {
        Session root = orchestratorSession();

        // 422 graph-invalid: переход в неизвестное состояние
        runTool(root, "create_workflow", Map.of("key", "broken-wf", "graph", Map.of(
                "states", List.of(Map.of("code", "plan", "type", "AGENT", "agent_key", "ghost")),
                "transitions", List.of(Map.of("from", "plan", "to", "nowhere", "kind", "NEXT")))));
        assertThat(journalField(root.id(), "TOOL_RESULT", "status")).isEqualTo("ERROR");
        assertThat(journalField(root.id(), "TOOL_RESULT", "output")).contains("422 graph-invalid");

        // 404 workflow-not-found
        runTool(root, "edit_workflow", Map.of("key", "no-such-wf", "graph", graph()));
        assertThat(journalField(root.id(), "TOOL_RESULT", "status")).isEqualTo("ERROR");
        assertThat(journalField(root.id(), "TOOL_RESULT", "output")).contains("404 workflow-not-found");
    }

    @Test
    void taskParamsSchemaViolationReported() {
        Session root = orchestratorSession();
        Map<String, Object> schemaGraph = Map.of(
                "states", List.of(Map.of(
                        "code", "plan", "type", "AGENT", "agent_key", "ghost",
                        "paramsSchema", Map.of(
                                "type", "object",
                                "required", List.of("budget"),
                                "properties", Map.of("budget", Map.of("type", "number")))),
                        Map.of("code", "done", "type", "TERMINAL", "outcome", "SUCCESS")),
                "transitions", List.of(Map.of("from", "plan", "to", "done", "kind", "NEXT")));
        workflows.createWorkflow(root.ownerUserId(), "meta-schema-wf", "Схемный WF", schemaGraph, "plan");

        runTool(root, "create_task", Map.of(
                "title", "Без бюджета",
                "workflow_key", "meta-schema-wf",
                "params", Map.of()));

        assertThat(journalField(root.id(), "TOOL_RESULT", "status")).isEqualTo("ERROR");
        assertThat(journalField(root.id(), "TOOL_RESULT", "output")).contains("422 params-schema");
    }

    @Test
    void subagentOfOrchestratorCannotCallOrchestratorTools() {
        // D-69: metaTools НЕ наследуется субагентам — флаг per-agent-revision; child,
        // заспавненный на агента без metaTools, оркестраторские инструменты звать не может
        Session root = orchestratorSession();
        Session coder = ExecutionFixtures.newSession(jdbcTemplate, sessionStore, idGenerator, environment);
        String coderKey = coderAgentKey(coder.id());

        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("d69")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(toolCallChunk("call-1", "spawn_subagent",
                        jsonOf(mapJson("agentKey", coderKey, "prompt", "сделай задачу")))))
                .willSetStateTo("child-round1"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("d69")
                .whenScenarioStateIs("child-round1")
                .willReturn(sse(toolCallChunk("call-2", "create_workflow",
                        jsonOf(mapJson("key", "sub-wf", "graph", graph())))))
                .willSetStateTo("child-final"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("d69")
                .whenScenarioStateIs("child-final")
                .willReturn(sse(TurnEngineWireMockTest.textChunk("не вышло")))
                .willSetStateTo("root-final"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("d69")
                .whenScenarioStateIs("root-final")
                .willReturn(sse(TurnEngineWireMockTest.textChunk("принято"))));

        sessionStore.appendEvent(root.id(), MessageKind.USER, root.ownerUserId(),
                Map.of("text", "спавни кодера"));
        turnManager.tryStart(root.id());
        awaitOutcome(root.id(), TurnOutcome.COMPLETED);

        UUID childId = jdbcTemplate.queryForObject(
                "SELECT id FROM session WHERE parent_session_id = ?", UUID.class, root.id());
        assertThat(journalField(childId, "TOOL_RESULT", "status")).isEqualTo("ERROR");
        assertThat(journalField(childId, "TOOL_RESULT", "output")).contains("no-metaTools");
        Integer created = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow WHERE key = 'sub-wf'", Integer.class);
        assertThat(created).isZero();
        llmWireMock.verify(4, postRequestedFor(urlEqualTo(PATH)));
    }

    private String coderAgentKey(UUID sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT a.key FROM session s JOIN agent a ON a.id = s.agent_revision_id WHERE s.id = ?",
                String.class, sessionId);
    }

    private static Map<String, Object> mapJson(String k1, Object v1, String k2, Object v2) {
        return Map.of(k1, v1, k2, v2);
    }

    private String jsonOf(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- хелперы

    /** Оркестраторская сессия: agent с permissions_jsonb.metaTools = true. */
    private Session orchestratorSession() {
        return ExecutionFixtures.newSessionWithAgent(
                jdbcTemplate, sessionStore, idGenerator, environment, "{\"metaTools\": true}");
    }

    private static Map<String, Object> graph() {
        return Map.of(
                "states", List.of(
                        Map.of("code", "plan", "type", "AGENT", "agent_key", "ghost"),
                        Map.of("code", "done", "type", "TERMINAL", "outcome", "SUCCESS")),
                "transitions", List.of(Map.of("from", "plan", "to", "done", "kind", "NEXT")));
    }

    /** Один раунд с metaTool-вызовом + финальный раунд: детерминированная пара откликов LLM. */
    private void runTool(Session session, String tool, Map<String, Object> arguments) {
        String argumentsJson;
        try {
            argumentsJson = json.writeValueAsString(arguments);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("meta-" + tool + "-" + session.id())
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(toolCallChunk("call-1", tool, argumentsJson)))
                .willSetStateTo("final"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("meta-" + tool + "-" + session.id())
                .whenScenarioStateIs("final")
                .willReturn(sse(TurnEngineWireMockTest.textChunk("готово"))));

        sessionStore.appendEvent(session.id(), MessageKind.USER, session.ownerUserId(),
                Map.of("text", "вызови " + tool));
        turnManager.tryStart(session.id());
        awaitOutcome(session.id(), TurnOutcome.COMPLETED);
    }

    private void awaitOutcome(UUID sessionId, TurnOutcome outcome) {
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> sessionStore.findSession(sessionId)
                        .map(s -> s.lastTurnOutcome() == outcome)
                        .orElse(false));
    }

    private String journalField(UUID sessionId, String kind, String field) {
        return jdbcTemplate.queryForObject(
                "SELECT payload_jsonb ->> ? FROM session_message WHERE session_id = ? AND kind = ?"
                        + " ORDER BY seq DESC LIMIT 1",
                String.class, field, sessionId, kind);
    }

    private static String toolCallChunk(String id, String tool, String argumentsJson) {
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"" + id
                + "\",\"type\":\"function\",\"function\":{\"name\":\"" + tool + "\",\"arguments\":\""
                + argumentsJson.replace("\"", "\\\"") + "\"}}]},\"finish_reason\":\"tool_calls\"}]}";
    }

    private static ResponseDefinitionBuilder sse(String... events) {
        StringBuilder body = new StringBuilder();
        for (String event : events) {
            body.append("data: ").append(event).append("\n\n");
        }
        body.append("data: [DONE]\n\n");
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(body.toString());
    }
}
