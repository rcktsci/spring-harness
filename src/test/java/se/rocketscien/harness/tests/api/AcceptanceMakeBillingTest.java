package se.rocketscien.harness.tests.api;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.AesGcmEncryption;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.session.SessionEventBroadcaster;
import se.rocketscien.harness.session.SessionRuntimeStatus;
import se.rocketscien.harness.tests.execution.DockerTestSupport;
import se.rocketscien.harness.tests.workflow.WorkflowTestFixtures;
import se.rocketscien.harness.testclient.ApiClient;
import se.rocketscien.harness.testclient.ApiException;
import se.rocketscien.harness.testclient.api.SessionMessagesApi;
import se.rocketscien.harness.testclient.api.SessionsApi;
import se.rocketscien.harness.testclient.api.TasksApi;
import se.rocketscien.harness.testclient.model.CreateSessionRequest;
import se.rocketscien.harness.testclient.model.SendMessageAccepted;
import se.rocketscien.harness.testclient.model.SendMessageRequest;
import se.rocketscien.harness.testclient.model.SessionDto;
import se.rocketscien.harness.testclient.model.TransitionDto;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Приёмочный e2e M3 (roadmap «Сделай биллинг»; tasks.md §S.2): оркестратор-агент
 * {@code make-billing} (FREE-сессия alice, {@code permissions_jsonb.metaTools=true})
 * по USER-сообщению программно создаёт workflow и дерево задач через metaTools
 * ({@code create_workflow} → {@code create_task} → 6×{@code create_subtask} →
 * {@code set_dependency}, плюс {@code spawn_subagent}), подзадачи проходят через
 * AGENT-состояния (STATE-сессии), родитель собирает стейджи {@code WAIT_TASKS}
 * (TAGGED(stage1)/TAGGED(stage2)), упавшая подзадача {@code impl-2} закрывает
 * {@code ALL_SUCCESS} по ERROR и ведёт в AGENT-сессию разборщика, который завершает
 * задачу SUCCESS. Одна из подзадач ({@code tests}) прогоняет реальный async-bash в
 * helper-контейнере с превышением окна (поздний {@code TOOL_RESULT late=true}).
 *
 * <p>Живой Keycloak (alice), WireMock-LLM (последовательные сценарии per-agent),
 * реальный helper-образ. Скрипты агентов задаются стабами WireMock; между ходами тест
 * дописывает USER-сообщения в STATE-сессии (source=USER — гейт {@code transition},
 * D-59), как в M2-приёмке. Проверки: 9 переходов (root 3 + 6 подзадач), SSE-снапшот
 * событий корневой задачи, терминалы подзадач, ребро {@code set_dependency}, поздний
 * результат async-bash и артефакт в workspace, финал root — SUCCESS.</p>
 */
class AcceptanceMakeBillingTest extends BaseApplicationTest {

    static {
        DockerTestSupport.helperImage();
    }

    private static final String PATH = "/v1/chat/completions";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] LLM_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final Duration AWAIT = Duration.ofSeconds(60);

    private static final String ORCHESTRATOR = "make-billing";
    private static final String ANALYST = "billing-analyst";
    private static final String PARSER = "billing-parser";
    private static final String ROOT_WORKFLOW = "billing-root";
    private static final String ROOT_TITLE = "Биллинг";

    /** Подзадачи: title → (agentKey/workflowKey, тег стейджа, исход). */
    private record Child(String title, String agentKey, String tag, boolean failed) {
    }

    private static final List<Child> CHILDREN = List.of(
            new Child("analytics", "billing-analytics", "stage1", false),
            new Child("contract-first", "billing-contract", "stage1", false),
            new Child("impl-1", "billing-impl-1", "stage2", false),
            new Child("impl-2", "billing-impl-2", "stage2", true),
            new Child("tests", "billing-tests", "stage2", false),
            new Child("e2e", "billing-e2e", "stage2", false));

    private static final Child ASYNC_CHILD = CHILDREN.get(4);

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IdGenerator idGenerator;
    @Autowired
    private Environment environment;
    @Autowired
    private SessionEventBroadcaster broadcaster;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<CompletableFuture<?>> pendingReads = new ArrayList<>();

    private String aliceToken;
    private SessionsApi sessionsApi;
    private SessionMessagesApi messagesApi;
    private TasksApi tasksApi;

    @BeforeEach
    void setUp() {
        aliceToken = ApiFixtures.keycloakToken(http, "alice", "alice-password");
        ApiClient aliceClient = ApiFixtures.apiClient(localServerUrl(), aliceToken);
        sessionsApi = new SessionsApi(aliceClient);
        messagesApi = new SessionMessagesApi(aliceClient);
        tasksApi = new TasksApi(aliceClient);
        llmWireMock.resetAll();
        seedEnvironment();
        stubWorkerScenarios();
    }

    @AfterEach
    void drainPendingReads() {
        pendingReads.forEach(future -> future.cancel(true));
        pendingReads.clear();
    }

    @Test
    void acceptanceMakeBillingScenario() throws Exception {
        SessionDto orchestrator = sessionsApi.createSession(new CreateSessionRequest().agentKey(ORCHESTRATOR));
        assertThat(orchestrator.getKind().getValue()).as("сессия FREE").isEqualTo("FREE");

        // --- фаза 1: оркестратор создаёт workflow и корневую задачу
        stubOrchestratorPlan();
        raiseUser(orchestrator.getId(), "Сделай биллинг");
        awaitTurnConsumed(orchestrator.getId());

        UUID rootTask = taskByTitle(ROOT_TITLE);
        assertThat(currentState(rootTask)).isEqualTo("stage1");

        CompletableFuture<List<String>> rootEvents = readRootEventsUntilDone(rootTask);

        // --- фаза 2: оркестратор создаёт 6 подзадач (create_subtask через metaTools)
        stubOrchestratorSubtasks(rootTask);
        raiseUser(orchestrator.getId(), "разбей на подзадачи");
        awaitTurnConsumed(orchestrator.getId());

        Map<String, UUID> children = new LinkedHashMap<>();
        for (Child child : CHILDREN) {
            children.put(child.title(), taskByTitle(child.title()));
        }

        // --- фаза 3: set_dependency (батч) + spawn_subagent
        stubOrchestratorDependenciesAndAnalyst(children.get("tests"), children.get("impl-1"));
        raiseUser(orchestrator.getId(), "выставь зависимости и позови аналитика");
        awaitTurnConsumed(orchestrator.getId());

        // --- подзадачи: AGENT-состояния (STATE-сессии), упавшая impl-2 → failed
        for (Child child : CHILDREN) {
            UUID taskId = children.get(child.title());
            if (child.title().equals(ASYNC_CHILD.title())) {
                driveAsyncChild(taskId);
            } else {
                driveChild(child, taskId);
            }
        }

        // --- разборщик: root после ERROR стейджа-2 входит в AGENT review → SUCCESS
        UUID parserSession = awaitStateSession(rootTask, "review");
        awaitTurnConsumed(parserSession);
        raiseUser(parserSession, "разбери провал и заверши задачу");
        awaitTurnConsumed(parserSession);
        awaitTerminal(rootTask, "done", "SUCCEEDED");

        // --- история: 9 переходов (root 3 + подзадачи 6), ERROR-ребро несёт failed-ids
        List<TransitionDto> rootHistory = tasksApi.listTaskHistory(rootTask, null, null).getItems();
        assertThat(rootHistory).extracting(AcceptanceMakeBillingTest::edge)
                .containsExactly(
                        "stage1->stage2:NEXT",
                        "stage2->review:ERROR",
                        "review->done:NEXT");
        assertThat(String.valueOf(rootHistory.get(1).getReason().get("failed")))
                .as("ERROR-переход несёт id упавшей подзадачи")
                .contains(children.get("impl-2").toString());
        assertThat(rootHistory.get(2).getReason().get("text")).isEqualTo("разбор завершён");

        int childTransitions = 0;
        for (Child child : CHILDREN) {
            List<TransitionDto> history =
                    tasksApi.listTaskHistory(children.get(child.title()), null, null).getItems();
            assertThat(history).as("подзадача " + child.title()).hasSize(1);
            assertThat(history.getFirst().getFromState()).isEqualTo("work");
            assertThat(history.getFirst().getToState()).isEqualTo(child.failed() ? "failed" : "done");
            childTransitions += history.size();
        }
        assertThat(rootHistory.size() + childTransitions).as("всего переходов M3-сценария").isEqualTo(9);

        // --- SSE-снапшот: последовательность переходов корневой задачи
        List<String> frames = rootEvents.get(AWAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(rootTransitionStates(frames)).containsExactly("stage2", "review", "done");
        assertThat(frames).as("снапшот task.status первым кадром")
                .anyMatch(line -> line.startsWith("data:") && line.contains("\"currentState\":\"stage1\""));

        // --- set_dependency зафиксировано ребром
        Integer edges = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_dependency WHERE blocker_task_id = ? AND blocked_task_id = ?",
                Integer.class, children.get("impl-1"), children.get("tests"));
        assertThat(edges).isEqualTo(1);

        // --- async-bash реально исполнялся в helper-контейнере: поздний результат + артефакт
        UUID asyncSession = stateSession(children.get(ASYNC_CHILD.title()), "work");
        assertThat(lateBashField(asyncSession, "late")).isEqualTo("true");
        assertThat(lateBashField(asyncSession, "status")).isEqualTo("OK");
        assertThat(lateBashField(asyncSession, "output")).contains("tests-ok");
        Path artifact = Path.of(System.getProperty("java.io.tmpdir"), "harness-it-workspaces",
                asyncSession.toString(), "tests.txt");
        assertThat(artifact).as("артефакт async-bash в workspace STATE-сессии").exists();
        assertThat(Files.readString(artifact)).isEqualTo("tests-ok\n");

        // --- spawn_subagent: дочерняя сессия аналитика с owner-наследованием (depth=1)
        UUID analystSession = jdbcTemplate.queryForObject(
                "SELECT id FROM session WHERE parent_session_id = ?", UUID.class, orchestrator.getId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT depth FROM session WHERE id = ?", Integer.class, analystSession)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT owner_user_id FROM session WHERE id = ?", UUID.class, analystSession))
                .isEqualTo(jdbcTemplate.queryForObject(
                        "SELECT owner_user_id FROM session WHERE id = ?", UUID.class, orchestrator.getId()));
    }

    // ---------------------------------------------------------------- драйверы подзадач

    /** Обычная подзадача: seed-SYSTEM → USER → transition → финальный ASSISTANT → терминал. */
    private void driveChild(Child child, UUID taskId) throws ApiException {
        UUID session = awaitStateSession(taskId, "work");
        awaitTurnConsumed(session);
        raiseUser(session, "приступай: " + child.title());
        awaitTurnConsumed(session);
        awaitTerminal(taskId, child.failed() ? "failed" : "done",
                child.failed() ? "FAILED" : "SUCCEEDED");
    }

    /** Асинхронная подзадача: USER → bash превышает окно (PARKED_ASYNC) → поздний результат → USER2. */
    private void driveAsyncChild(UUID taskId) throws ApiException {
        UUID session = awaitStateSession(taskId, "work");
        awaitTurnConsumed(session);
        raiseUser(session, "прогони тесты");
        await().atMost(AWAIT).pollInterval(Duration.ofMillis(100))
                .until(() -> broadcaster.statusSnapshot(session).runtimeStatus() == SessionRuntimeStatus.PARKED_ASYNC);
        awaitLateResultTurn(session);
        raiseUser(session, "тесты прошли, завершай");
        awaitTerminal(taskId, "done", "SUCCEEDED");
    }

    private void awaitLateResultTurn(UUID sessionId) {
        await().atMost(AWAIT).pollInterval(Duration.ofMillis(100)).until(() -> {
            Long lateSeq = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(seq), 0) FROM session_message WHERE session_id = ?"
                            + " AND kind = 'TOOL_RESULT' AND payload_jsonb ->> 'late' = 'true'",
                    Long.class, sessionId);
            Long assistantSeq = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(seq), 0) FROM session_message WHERE session_id = ? AND kind = 'ASSISTANT'",
                    Long.class, sessionId);
            return lateSeq != null && lateSeq > 0 && assistantSeq != null && assistantSeq > lateSeq;
        });
    }

    // ---------------------------------------------------------------- WireMock-сценарии

    /** Фаза 1: create_workflow(root) → create_task(root) → текст. */
    private void stubOrchestratorPlan() {
        String scenario = "orch-plan";
        llmWireMock.stubFor(post(urlEqualTo(PATH)).withRequestBody(containing("role:" + ORCHESTRATOR))
                .inScenario(scenario).whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(toolCallChunk("call-wf", "create_workflow", args(Map.of(
                        "key", ROOT_WORKFLOW,
                        "name", "Биллинг",
                        "start_state", "stage1",
                        "graph", rootGraph())))))
                .willSetStateTo("task"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).withRequestBody(containing("role:" + ORCHESTRATOR))
                .inScenario(scenario).whenScenarioStateIs("task")
                .willReturn(sse(toolCallChunk("call-task", "create_task", args(Map.of(
                        "title", ROOT_TITLE,
                        "description", "Оркестрация биллинга",
                        "workflow_key", ROOT_WORKFLOW)))))
                .willSetStateTo("final"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).withRequestBody(containing("role:" + ORCHESTRATOR))
                .inScenario(scenario).whenScenarioStateIs("final")
                .willReturn(sse(textChunk("план биллинга создан"))));
    }

    /** Фаза 2: одна модель-реплика с 6 create_subtask (родитель — реальный id root). */
    private void stubOrchestratorSubtasks(UUID rootTask) {
        List<ToolCall> calls = new ArrayList<>();
        int index = 0;
        for (Child child : CHILDREN) {
            calls.add(new ToolCall("call-sub-" + (++index), "create_subtask", args(Map.of(
                    "parent_task_id", rootTask.toString(),
                    "title", child.title(),
                    "description", "Подзадача " + child.title(),
                    "workflow_key", child.agentKey(),
                    "tags", List.of(child.tag())))));
        }
        String scenario = "orch-subtasks";
        llmWireMock.stubFor(post(urlEqualTo(PATH)).withRequestBody(containing("role:" + ORCHESTRATOR))
                .inScenario(scenario).whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(toolCallsChunk(calls)))
                .willSetStateTo("final"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).withRequestBody(containing("role:" + ORCHESTRATOR))
                .inScenario(scenario).whenScenarioStateIs("final")
                .willReturn(sse(textChunk("подзадачи созданы"))));
    }

    /** Фаза 3: set_dependency(tests ← impl-1) + spawn_subagent(analyst) → текст. */
    private void stubOrchestratorDependenciesAndAnalyst(UUID blockedTask, UUID blocker) {
        String scenario = "orch-deps";
        List<ToolCall> calls = List.of(
                new ToolCall("call-dep", "set_dependency", args(Map.of(
                        "blocked_task_id", blockedTask.toString(),
                        "blocked_by", List.of(blocker.toString())))),
                new ToolCall("call-spawn", "spawn_subagent", args(Map.of(
                        "agentKey", ANALYST,
                        "prompt", "Оцени объём работ по биллингу"))));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).withRequestBody(containing("role:" + ORCHESTRATOR))
                .inScenario(scenario).whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(toolCallsChunk(calls)))
                .willSetStateTo("final"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).withRequestBody(containing("role:" + ORCHESTRATOR))
                .inScenario(scenario).whenScenarioStateIs("final")
                .willReturn(sse(textChunk("зависимости выставлены, аналитик опрошен"))));

        llmWireMock.stubFor(post(urlEqualTo(PATH)).withRequestBody(containing("role:" + ANALYST))
                .willReturn(sse(textChunk("объём: 6 подзадач"))));
    }

    /**
     * Сценарии всех рабочих/разборного агентов (per-agent, порядок вызовов детерминирован):
     * seed-SYSTEM → текст; USER → transition; после tool-результата — финальный ASSISTANT.
     * У async-подзадачи {@code tests}: USER → bash (окно превышено) → поздний Turn → USER2.
     */
    private void stubWorkerScenarios() {
        for (Child child : CHILDREN) {
            if (child.title().equals(ASYNC_CHILD.title())) {
                stubAsyncWorker();
            } else {
                stubPlainWorker(child.agentKey(), child.failed());
            }
        }
        stubParser();
    }

    private void stubPlainWorker(String agentKey, boolean failed) {
        String scenario = "worker-" + agentKey;
        String toState = failed ? "failed" : "done";
        Map<String, Object> transition = failed
                ? Map.of("toState", toState, "kind", "ERROR", "reason", "подзадача провалена")
                : Map.of("toState", toState, "reason", "подзадача завершена");
        llmWireMock.stubFor(post(urlEqualTo(PATH)).withRequestBody(containing("role:" + agentKey))
                .inScenario(scenario).whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(textChunk("принял подзадачу")))
                .willSetStateTo("u"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).withRequestBody(containing("role:" + agentKey))
                .inScenario(scenario).whenScenarioStateIs("u")
                .willReturn(sse(toolCallChunk("call-t-" + agentKey, "transition", args(transition))))
                .willSetStateTo("f"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).withRequestBody(containing("role:" + agentKey))
                .inScenario(scenario).whenScenarioStateIs("f")
                .willReturn(sse(textChunk("готово"))));
    }

    private void stubAsyncWorker() {
        String agentKey = ASYNC_CHILD.agentKey();
        String scenario = "worker-" + agentKey;
        llmWireMock.stubFor(post(urlEqualTo(PATH)).withRequestBody(containing("role:" + agentKey))
                .inScenario(scenario).whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(textChunk("принял подзадачу")))
                .willSetStateTo("u1"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).withRequestBody(containing("role:" + agentKey))
                .inScenario(scenario).whenScenarioStateIs("u1")
                .willReturn(sse(toolCallChunk("call-bash-" + agentKey, "bash", args(Map.of(
                        "command", "sleep 12; echo tests-ok > tests.txt; cat tests.txt",
                        "timeout", 60)))))
                .willSetStateTo("late"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).withRequestBody(containing("role:" + agentKey))
                .inScenario(scenario).whenScenarioStateIs("late")
                .willReturn(sse(textChunk("тесты завершились")))
                .willSetStateTo("u2"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).withRequestBody(containing("role:" + agentKey))
                .inScenario(scenario).whenScenarioStateIs("u2")
                .willReturn(sse(toolCallChunk("call-t-" + agentKey, "transition", args(
                        Map.of("toState", "done", "reason", "тесты пройдены")))))
                .willSetStateTo("f"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).withRequestBody(containing("role:" + agentKey))
                .inScenario(scenario).whenScenarioStateIs("f")
                .willReturn(sse(textChunk("готово"))));
    }

    private void stubParser() {
        String scenario = "worker-" + PARSER;
        llmWireMock.stubFor(post(urlEqualTo(PATH)).withRequestBody(containing("role:" + PARSER))
                .inScenario(scenario).whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(textChunk("разбираю результат")))
                .willSetStateTo("u"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).withRequestBody(containing("role:" + PARSER))
                .inScenario(scenario).whenScenarioStateIs("u")
                .willReturn(sse(toolCallChunk("call-t-" + PARSER, "transition", args(
                        Map.of("toState", "done", "reason", "разбор завершён")))))
                .willSetStateTo("f"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).withRequestBody(containing("role:" + PARSER))
                .inScenario(scenario).whenScenarioStateIs("f")
                .willReturn(sse(textChunk("готово"))));
    }

    // ---------------------------------------------------------------- фикстуры среды

    /** Credentials (WireMock-LLM) → модель → агенты → workflow-ревизии подзадач. */
    private void seedEnvironment() {
        UUID credentialsId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                "INSERT INTO llm_credentials (id, name, base_url, api_key_encrypted, key_version, created_at)"
                        + " VALUES (?, ?, ?, ?, 1, now())",
                credentialsId, "creds-" + credentialsId,
                environment.getRequiredProperty("wiremock.llm.url") + "/v1", encrypt("sk-test"));
        UUID modelId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                "INSERT INTO llm_model (id, credentials_id, model_id, created_at) VALUES (?, ?, ?, now())",
                modelId, credentialsId, "gpt-test");

        insertAgent(modelId, ORCHESTRATOR, "{\"metaTools\": true}");
        insertAgent(modelId, ANALYST, null);
        insertAgent(modelId, PARSER, null);
        for (Child child : CHILDREN) {
            insertAgent(modelId, child.agentKey(), null);
            insertWorkflow(child.agentKey(), workerGraph(child.agentKey()), "work");
        }
    }

    private void insertAgent(UUID modelId, String key, String permissionsJson) {
        jdbcTemplate.update(
                "INSERT INTO agent (id, key, name, description, rev, role_prompt, llm_model_id,"
                        + " permissions_jsonb, created_at) VALUES (?, ?, ?, ?, 1, ?, ?, ?::jsonb, now())",
                idGenerator.newUuidV7(), key, "Агент " + key, "M3-приёмочный агент",
                "role:" + key + ". Ты помощник harness.", modelId, permissionsJson);
    }

    private void insertWorkflow(String key, Map<String, Object> graph, String startState) {
        UUID owner = WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        UUID workflowId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                "INSERT INTO workflow (id, key, name, owner_user_id, created_at) VALUES (?, ?, ?, ?, now())",
                workflowId, key, "Workflow " + key, owner);
        jdbcTemplate.update(
                "INSERT INTO workflow_revision (id, workflow_id, rev, graph_jsonb, start_state, created_at)"
                        + " VALUES (?, ?, 1, ?::jsonb, ?, now())",
                idGenerator.newUuidV7(), workflowId, rawJson(graph), startState);
    }

    /** Корневой граф: два WAIT_TASKS-стейджа (TAGGED) → AGENT-разборщик → SUCCESS/FAILED. */
    private static Map<String, Object> rootGraph() {
        return WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("stage1", "WAIT_TASKS", Map.of(
                                "scope", "TAGGED(stage1)", "condition", "ALL_SUCCESS", "timeout", "PT5M")),
                        WorkflowTestFixtures.state("stage2", "WAIT_TASKS", Map.of(
                                "scope", "TAGGED(stage2)", "condition", "ALL_SUCCESS", "timeout", "PT5M")),
                        WorkflowTestFixtures.state("review", "AGENT", Map.of("agent_key", PARSER)),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))),
                List.of(
                        WorkflowTestFixtures.transition("stage1", "stage2", "NEXT"),
                        WorkflowTestFixtures.transition("stage1", "review", "ERROR"),
                        WorkflowTestFixtures.transition("stage1", "failed", "TIMEOUT"),
                        WorkflowTestFixtures.transition("stage2", "review", "NEXT"),
                        WorkflowTestFixtures.transition("stage2", "review", "ERROR"),
                        WorkflowTestFixtures.transition("stage2", "failed", "TIMEOUT"),
                        WorkflowTestFixtures.transition("review", "done", "NEXT"),
                        WorkflowTestFixtures.transition("review", "failed", "ERROR")));
    }

    /** Граф подзадачи: AGENT work → done/failed. */
    private static Map<String, Object> workerGraph(String agentKey) {
        return WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("work", "AGENT", Map.of("agent_key", agentKey)),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))),
                List.of(
                        WorkflowTestFixtures.transition("work", "done", "NEXT"),
                        WorkflowTestFixtures.transition("work", "failed", "ERROR")));
    }

    // ---------------------------------------------------------------- каркасы ответов LLM

    private static String textChunk(String content) {
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"},\"finish_reason\":null}]}";
    }

    private record ToolCall(String id, String name, String argumentsJson) {
    }

    private static String args(Map<String, Object> arguments) {
        return json(arguments);
    }

    /** Экранированный JSON для встраивания в строку SSE-кадра (arguments tool-call). */
    private static String json(Object value) {
        return rawJson(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Сырой компактный JSON (вставка в БД/лог). */
    private static String rawJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toolCallChunk(String id, String tool, String argumentsJson) {
        return toolCallsChunk(List.of(new ToolCall(id, tool, argumentsJson)));
    }

    private static String toolCallsChunk(List<ToolCall> calls) {
        StringBuilder toolCalls = new StringBuilder();
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            if (i > 0) {
                toolCalls.append(',');
            }
            toolCalls.append("{\"index\":").append(i).append(",\"id\":\"").append(call.id())
                    .append("\",\"type\":\"function\",\"function\":{\"name\":\"").append(call.name())
                    .append("\",\"arguments\":\"").append(call.argumentsJson()).append("\"}}");
        }
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[" + toolCalls
                + "]},\"finish_reason\":\"tool_calls\"}]}";
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

    // ---------------------------------------------------------------- чтение SSE задачи

    private CompletableFuture<List<String>> readRootEventsUntilDone(UUID taskId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        localServerUrl() + "/api/v1/tasks/" + taskId + "/events"))
                .header("Authorization", "Bearer " + aliceToken)
                .GET()
                .build();
        HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        CompletableFuture<List<String>> future = CompletableFuture.supplyAsync(() -> {
            List<String> lines = new ArrayList<>();
            Iterator<String> iterator = response.body().iterator();
            while (iterator.hasNext()) {
                lines.add(iterator.next());
                if (lines.stream().anyMatch(line -> line.startsWith("data:")
                        && line.contains("\"toState\":\"done\""))) {
                    break;
                }
            }
            return lines;
        }, sseExecutor);
        pendingReads.add(future);
        return future;
    }

    private static List<String> rootTransitionStates(List<String> lines) {
        List<String> states = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("event:") && lines.get(i).contains("task.transition")) {
                for (int j = i + 1; j < lines.size() && !lines.get(j).isEmpty(); j++) {
                    if (lines.get(j).startsWith("data:")) {
                        states.add(JSON.readTree(lines.get(j).substring("data:".length()).trim())
                                .get("toState").asString());
                        break;
                    }
                }
            }
        }
        return states;
    }

    // ---------------------------------------------------------------- шаги и ожидания

    private void raiseUser(UUID sessionId, String text) throws ApiException {
        SendMessageAccepted accepted = messagesApi.sendMessage(sessionId, new SendMessageRequest().text(text));
        assertThat(accepted.getSeq()).as("USER дописан в журнал").isPositive();
    }

    private UUID awaitStateSession(UUID taskId, String stateCode) {
        await().atMost(AWAIT).pollInterval(Duration.ofMillis(100))
                .until(() -> !stateSessions(taskId, stateCode).isEmpty());
        return stateSessions(taskId, stateCode).getFirst();
    }

    private List<UUID> stateSessions(UUID taskId, String stateCode) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM session WHERE task_id = ? AND state_code = ? AND kind = 'STATE' ORDER BY created_at",
                UUID.class, taskId, stateCode);
    }

    private UUID stateSession(UUID taskId, String stateCode) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM session WHERE task_id = ? AND state_code = ? AND kind = 'STATE'",
                UUID.class, taskId, stateCode);
    }

    private void awaitTurnConsumed(UUID sessionId) {
        await().atMost(AWAIT).pollInterval(Duration.ofMillis(100)).until(() -> {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT last_turn_outcome, last_consumed_seq, last_seq FROM session WHERE id = ?",
                    sessionId);
            return "COMPLETED".equals(row.get("last_turn_outcome"))
                    && ((Number) row.get("last_consumed_seq")).longValue()
                    >= ((Number) row.get("last_seq")).longValue();
        });
    }

    private void awaitTerminal(UUID taskId, String stateCode, String projection) {
        await().atMost(AWAIT).pollInterval(Duration.ofMillis(100)).until(() -> {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT current_state, status_projection FROM task WHERE id = ?", taskId);
            return stateCode.equals(row.get("current_state"))
                    && projection.equals(row.get("status_projection"));
        });
    }

    private String currentState(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT current_state FROM task WHERE id = ?", String.class, taskId);
    }

    private UUID taskByTitle(String title) {
        await().atMost(AWAIT).pollInterval(Duration.ofMillis(100)).until(() ->
                !jdbcTemplate.queryForList("SELECT id FROM task WHERE title = ?", UUID.class, title).isEmpty());
        return jdbcTemplate.queryForObject("SELECT id FROM task WHERE title = ?", UUID.class, title);
    }

    /** Поле позднего (late=true) результата bash в журнале сессии. */
    private String lateBashField(UUID sessionId, String field) {
        return jdbcTemplate.queryForObject(
                "SELECT payload_jsonb ->> ? FROM session_message WHERE session_id = ? AND kind = 'TOOL_RESULT'"
                        + " AND payload_jsonb ->> 'tool' = 'bash' AND payload_jsonb ->> 'late' = 'true'"
                        + " ORDER BY seq DESC LIMIT 1",
                String.class, field, sessionId);
    }

    private static String edge(TransitionDto transition) {
        return transition.getFromState() + "->" + transition.getToState() + ":" + transition.getKind();
    }

    private static String encrypt(String value) {
        try {
            return AesGcmEncryption.encrypt(value, LLM_KEY);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
