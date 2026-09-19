package se.rocketscien.harness.tests.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.config.RecordingTaskWakeListener;
import se.rocketscien.harness.execution.impl.TaskEngine;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.TransitionKind;
import se.rocketscien.harness.testclient.ApiClient;
import se.rocketscien.harness.testclient.ApiException;
import se.rocketscien.harness.testclient.api.TasksApi;
import se.rocketscien.harness.testclient.model.AddTaskCommentRequest;
import se.rocketscien.harness.testclient.model.AddTaskDependenciesRequest;
import se.rocketscien.harness.testclient.model.CommentDto;
import se.rocketscien.harness.testclient.model.CreateTaskRequest;
import se.rocketscien.harness.testclient.model.TaskDto;
import se.rocketscien.harness.testclient.model.TaskPage;
import se.rocketscien.harness.testclient.model.TaskStatusProjection;
import se.rocketscien.harness.testclient.model.UpdateTaskRequest;
import se.rocketscien.harness.tests.task.TaskTestFixtures;
import se.rocketscien.harness.tests.workflow.WorkflowTestFixtures;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static se.rocketscien.harness.tests.api.ApiFixtures.apiClient;
import static se.rocketscien.harness.tests.api.ApiFixtures.insertAgentChain;
import static se.rocketscien.harness.tests.api.ApiFixtures.insertAppUser;
import static se.rocketscien.harness.tests.api.ApiFixtures.keycloakToken;
import static se.rocketscien.harness.tests.api.ApiFixtures.sendRaw;

/**
 * REST задач (пачка K.1/K.3, api-contracts §4.1): создание с пином ревизии (404
 * workflow-not-found, 422 params-schema), merge-patch (params иммутабельны → 422
 * rule=immutable; null-семантика RFC 7396), список с фильтрами parent/status/mine/tags/q
 * и конверт-пагинацией, подзадачи, дерево, история (курсор-пара (created_at, id)),
 * комментарии (append-only, author из JWT / NULL для агентских), зависимости (self-loop/
 * цикл/неизвестный blocker → 422 dependency-invalid; после ребра — «blocked-changed» wake).
 * Позитивный путь — сгенерированный тест-клиент.
 */
class TasksApiTest extends BaseApplicationTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ProblemReader problem = new ProblemReader();

    private String aliceToken;
    private ApiClient aliceClient;
    private TasksApi tasksApi;

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

    @BeforeEach
    void setUpClients() {
        aliceToken = keycloakToken(http, "alice", "alice-password");
        aliceClient = apiClient(localServerUrl(), aliceToken);
        tasksApi = new TasksApi(aliceClient);
        insertAgentChain(jdbcTemplate, idGenerator, environment);
    }

    @Test
    void createTaskReturns201WithLocationAndFullDto() throws Exception {
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");

        TaskDto created = tasksApi.createTask(new CreateTaskRequest()
                .title("Разобрать пайплайн")
                .description("Описание задачи")
                .workflowKey(wf.workflowKey())
                .rev(1)
                .params(Map.of("region", "eu"))
                .tags(List.of("alpha", "beta")));

        assertThat(created.getId()).isNotNull();
        assertThat(created.getTitle()).isEqualTo("Разобрать пайплайн");
        assertThat(created.getDescription()).isEqualTo("Описание задачи");
        assertThat(created.getOwner()).isEqualTo("alice");
        assertThat(created.getAuthor()).isEqualTo("alice");
        assertThat(created.getWorkflow().getKey()).isEqualTo(wf.workflowKey());
        assertThat(created.getWorkflow().getRev()).isEqualTo(1);
        assertThat(created.getCurrentState()).isEqualTo("wait");
        assertThat(created.getStatusProjection().getValue()).isEqualTo("WAITING");
        assertThat(created.getSuspended()).isFalse();
        assertThat(created.getParentTaskId()).isNull();
        assertThat(created.getParams()).containsEntry("region", "eu");
        assertThat(created.getTags()).containsExactly("alpha", "beta");
        // задача стартует в WAIT_WEBHOOK — capability-URL вебхука сформирoван (api-contracts §4.4)
        assertThat(created.getWebhookUrl()).isNotNull();
        assertThat(created.getWebhookUrl().toString())
                .startsWith("http://webhook-test/api/webhooks/tasks/" + created.getId() + "/");
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getUpdatedAt()).isNotNull();

        HttpResponse<String> raw = sendRaw(http, localServerUrl(), "POST", "/api/v1/tasks",
                aliceToken, "application/json", null,
                "{\"title\":\"с Location\",\"description\":\"d\",\"workflowKey\":\""
                        + wf.workflowKey() + "\"}");
        assertThat(raw.statusCode()).isEqualTo(201);
        assertThat(raw.headers().firstValue("Location").orElseThrow())
                .isEqualTo("/api/v1/tasks/" + problem.read(raw.body()).get("id").asText());
    }

    @Test
    void createTaskPinsLatestRevisionWhenRevOmitted() throws Exception {
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");

        TaskDto created = tasksApi.createTask(new CreateTaskRequest()
                .title("Без rev").description("d").workflowKey(wf.workflowKey()));

        assertThat(created.getWorkflow().getRev()).isEqualTo(1);
        assertThat(created.getCurrentState()).isEqualTo("wait");
    }

    @Test
    void createTaskUnknownWorkflowOrRevisionReturns404WorkflowNotFound() {
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");

        ApiException unknownKey = assertThrows(ApiException.class, () -> tasksApi.createTask(
                new CreateTaskRequest().title("t").description("d").workflowKey("no-such-workflow")));
        assertThat(unknownKey.getCode()).isEqualTo(404);
        assertThat(problem.codeOf(unknownKey)).isEqualTo("workflow-not-found");

        ApiException unknownRev = assertThrows(ApiException.class, () -> tasksApi.createTask(
                new CreateTaskRequest().title("t").description("d")
                        .workflowKey(wf.workflowKey()).rev(99)));
        assertThat(unknownRev.getCode()).isEqualTo(404);
        assertThat(problem.codeOf(unknownRev)).isEqualTo("workflow-not-found");
    }

    @Test
    void createTaskParamsAgainstSchemaReturn422ParamsSchema() {
        Map<String, Object> paramsSchema = Map.of(
                "type", "object",
                "required", List.of("module"),
                "properties", Map.of("module", Map.of("type", "string")));
        Map<String, Object> waitWithSchema = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("wait", "WAIT_WEBHOOK",
                                Map.of("paramsSchema", paramsSchema)),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS"))),
                List.of(WorkflowTestFixtures.transition("wait", "done", "NEXT")));
        TaskTestFixtures.WfRevision wf = seedWorkflow(waitWithSchema, "wait");

        ApiException failure = assertThrows(ApiException.class, () -> tasksApi.createTask(
                new CreateTaskRequest().title("t").description("d")
                        .workflowKey(wf.workflowKey()).params(Map.of("unrelated", 1))));

        assertThat(failure.getCode()).isEqualTo(422);
        assertThat(problem.codeOf(failure)).isEqualTo("params-schema");
        assertThat(problem.read(failure.getResponseBody()).get("errors")).isNotEmpty();
    }

    @Test
    void patchTaskMergePatchSemantics() throws Exception {
        TaskDto created = createTaskViaApi();

        TaskDto patched = tasksApi.patchTask(created.getId(),
                new UpdateTaskRequest().title("Новое имя").tags(List.of("renamed")));

        assertThat(patched.getTitle()).isEqualTo("Новое имя");
        assertThat(patched.getTags()).containsExactly("renamed");
        assertThat(patched.getDescription()).isEqualTo("Описание задачи");

        // явный null у title → 422 (title удалить нельзя)
        HttpResponse<String> nullTitle = sendRaw(http, localServerUrl(), "PATCH",
                "/api/v1/tasks/" + created.getId(), aliceToken,
                "application/merge-patch+json", null, "{\"title\":null}");
        assertThat(nullTitle.statusCode()).isEqualTo(422);

        // description: null — отклонено (NOT NULL в схеме); tags: null — очистка тегов
        HttpResponse<String> nullDescription = sendRaw(http, localServerUrl(), "PATCH",
                "/api/v1/tasks/" + created.getId(), aliceToken,
                "application/merge-patch+json", null, "{\"description\":null}");
        assertThat(nullDescription.statusCode()).isEqualTo(422);

        HttpResponse<String> clearedTags = sendRaw(http, localServerUrl(), "PATCH",
                "/api/v1/tasks/" + created.getId(), aliceToken,
                "application/merge-patch+json", null, "{\"tags\":null}");
        assertThat(clearedTags.statusCode()).isEqualTo(200);
        TaskDto afterClear = tasksApi.getTask(created.getId());
        assertThat(afterClear.getDescription()).isEqualTo("Описание задачи");
        assertThat(afterClear.getTags()).isEmpty();

        // неизвестные члены патча игнорируются
        HttpResponse<String> unknownMember = sendRaw(http, localServerUrl(), "PATCH",
                "/api/v1/tasks/" + created.getId(), aliceToken,
                "application/merge-patch+json", null, "{\"title\":\"ok\",\"unknown\":\"ignored\"}");
        assertThat(unknownMember.statusCode()).isEqualTo(200);
        assertThat(problem.read(unknownMember.body()).get("title").asText()).isEqualTo("ok");
    }

    @Test
    void patchWithParamsReturns422ValidationFailedRuleImmutable() throws Exception {
        TaskDto created = createTaskViaApi();

        HttpResponse<String> response = sendRaw(http, localServerUrl(), "PATCH",
                "/api/v1/tasks/" + created.getId(), aliceToken,
                "application/merge-patch+json", null, "{\"params\":{\"region\":\"us\"}}");

        assertThat(response.statusCode()).isEqualTo(422);
        JsonNode body = problem.read(response.body());
        assertThat(body.get("code").asText()).isEqualTo("validation-failed");
        assertThat(body.get("errors").get(0).get("rule").asText()).isEqualTo("immutable");
        assertThat(body.get("errors").get(0).get("pointer").asText()).isEqualTo("/params");
    }

    @Test
    void listTasksAppliesFiltersAndCursorPagination() throws Exception {
        TaskDto viaApi = createTaskViaApi();
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");
        TaskDto letter = createTaskViaRegistry(wf, null, List.of("batch"), "Письмо клиенту");
        TaskDto other = createTaskViaRegistry(wf, null, List.of("other"), "Позвонить домой");
        TaskDto child = createTaskViaRegistry(wf, letter.getId(), List.of("batch"), "Черновик ответа");

        List<UUID> mine = ids(tasksApi.listTasks(null, null, true, null, null, null, null));
        assertThat(mine).containsExactly(viaApi.getId());

        List<UUID> parentChildren = ids(tasksApi.listTasks(letter.getId(), null, null, null, null, null, null));
        assertThat(parentChildren).containsExactly(child.getId());

        List<UUID> tagged = ids(tasksApi.listTasks(null, null, null, List.of("batch"), null, null, null));
        assertThat(tagged).containsExactlyInAnyOrder(letter.getId(), child.getId());

        List<UUID> byQ = ids(tasksApi.listTasks(null, null, null, null, "письмо", null, null));
        assertThat(byQ).containsExactly(letter.getId());

        assertThat(tasksApi.listTasks(null, TaskStatusProjection.WAITING, null, null, null, null, null)
                .getItems()).hasSize(4);
        assertThat(tasksApi.listTasks(null, TaskStatusProjection.RUNNING, null, null, null, null, null)
                .getItems()).isEmpty();

        TaskPage firstPage = tasksApi.listTasks(null, null, null, null, null, null, 2);
        assertThat(firstPage.getItems()).hasSize(2);
        assertThat(firstPage.getNextCursor()).isNotNull();
        TaskPage secondPage = tasksApi.listTasks(null, null, null, null, null,
                firstPage.getNextCursor(), 2);
        assertThat(secondPage.getItems()).hasSize(2);
        assertThat(secondPage.getNextCursor()).isNull();

        assertThat(other.getId()).isNotNull();
    }

    @Test
    void createSubtaskSetsParentAndOwnerFromCaller() throws Exception {
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");
        TaskDto parent = tasksApi.createTask(new CreateTaskRequest()
                .title("Родитель").description("d").workflowKey(wf.workflowKey()));

        TaskDto subtask = tasksApi.createSubtask(parent.getId(), new CreateTaskRequest()
                .title("Подзадача").description("d").workflowKey(wf.workflowKey()));

        assertThat(subtask.getParentTaskId()).isEqualTo(parent.getId());
        assertThat(subtask.getOwner()).isEqualTo("alice");
        assertThat(subtask.getAuthor()).isEqualTo("alice");
    }

    @Test
    void subtaskOfUnknownParentReturns404TaskNotFound() {
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");

        ApiException failure = assertThrows(ApiException.class, () -> tasksApi.createSubtask(
                UUID.randomUUID(), new CreateTaskRequest()
                        .title("s").description("d").workflowKey(wf.workflowKey())));

        assertThat(failure.getCode()).isEqualTo(404);
        assertThat(problem.codeOf(failure)).isEqualTo("task-not-found");
    }

    @Test
    void taskTreeReturnsNestedSubtreeWithDepthLimit() throws Exception {
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");
        TaskDto root = createTaskViaRegistry(wf, null, List.of(), "корень");
        TaskDto child = createTaskViaRegistry(wf, root.getId(), List.of(), "ребёнок");
        createTaskViaRegistry(wf, child.getId(), List.of(), "внук");

        var full = tasksApi.getTaskTree(root.getId(), null).getItems();
        assertThat(full).hasSize(1);
        assertThat(full.getFirst().getId()).isEqualTo(root.getId());
        assertThat(full.getFirst().getChildren()).hasSize(1);
        assertThat(full.getFirst().getChildren().getFirst().getId()).isEqualTo(child.getId());
        assertThat(full.getFirst().getChildren().getFirst().getChildren()).hasSize(1);

        var oneLevel = tasksApi.getTaskTree(root.getId(), 1).getItems();
        assertThat(oneLevel.getFirst().getChildren()).hasSize(1);
        assertThat(oneLevel.getFirst().getChildren().getFirst().getChildren()).isEmpty();
    }

    @Test
    void historyUsesCursorPairPagination() throws Exception {
        TaskTestFixtures.WfRevision wf = seedWorkflow(chainGraph(), "wait1");
        UUID owner = insertAppUser(jdbcTemplate);
        UUID taskId = taskRegistry.createTask(new TaskRegistry.CreateTaskCommand(
                wf.revisionId(), "Цепочка", "d", null, owner, null, Map.of(), List.of())).id();

        taskEngine.processTaskTransition(taskId, "wait1", "wait2", TransitionKind.NEXT,
                Map.of("text", "первый"));
        taskEngine.processTaskTransition(taskId, "wait2", "done", TransitionKind.NEXT,
                Map.of("text", "второй"));

        var firstPage = tasksApi.listTaskHistory(taskId, null, 1);
        assertThat(firstPage.getItems()).hasSize(1);
        assertThat(firstPage.getItems().getFirst().getFromState()).isEqualTo("wait1");
        assertThat(firstPage.getItems().getFirst().getToState()).isEqualTo("wait2");
        assertThat(firstPage.getNextCursor()).isNotNull();

        var secondPage = tasksApi.listTaskHistory(taskId, firstPage.getNextCursor(), 10);
        assertThat(secondPage.getItems()).hasSize(1);
        assertThat(secondPage.getItems().getFirst().getToState()).isEqualTo("done");
        assertThat(secondPage.getNextCursor()).isNull();
    }

    @Test
    void commentsAppendOnlyWithAuthorResolution() throws Exception {
        TaskDto task = createTaskViaApi();

        CommentDto created = tasksApi.addTaskComment(task.getId(),
                new AddTaskCommentRequest().body("первый комментарий"));

        assertThat(created.getId()).isNotNull();
        assertThat(created.getTaskId()).isEqualTo(task.getId());
        assertThat(created.getBody()).isEqualTo("первый комментарий");
        assertThat(created.getAuthor()).isEqualTo("alice");

        // агентский комментарий (author NULL) — напрямую через реестр
        taskRegistry.addComment(task.getId(), null, "агентский комментарий");

        var page = tasksApi.listTaskComments(task.getId(), null, 10);
        assertThat(page.getItems()).hasSize(2);
        assertThat(page.getItems().get(0).getAuthor()).isEqualTo("alice");
        assertThat(page.getItems().get(1).getAuthor()).isNull();
        assertThat(page.getItems().get(1).getBody()).isEqualTo("агентский комментарий");
        assertThat(page.getNextCursor()).isNull();
    }

    @Test
    void dependenciesValidateCyclesAndPublishBlockedChanged() throws Exception {
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");
        TaskDto a = createTaskViaRegistry(wf, null, List.of(), "A");
        TaskDto b = createTaskViaRegistry(wf, null, List.of(), "B");
        TaskDto c = createTaskViaRegistry(wf, null, List.of(), "C");
        wakeListener.clear();

        tasksApi.addTaskDependencies(a.getId(), new AddTaskDependenciesRequest().blockedBy(Set.of(b.getId())));
        tasksApi.addTaskDependencies(b.getId(), new AddTaskDependenciesRequest().blockedBy(Set.of(c.getId())));
        // blocked-changed: wake блокируемой задачи (id в пути) после добавления ребра
        assertThat(wakeListener.wakes()).contains(a.getId(), b.getId());

        // цикл: B блокирует A, C блокирует B ⇒ ребро «A блокирует C» замыкает цикл
        ApiException cycle = assertThrows(ApiException.class, () -> tasksApi.addTaskDependencies(
                c.getId(), new AddTaskDependenciesRequest().blockedBy(Set.of(a.getId()))));
        assertThat(cycle.getCode()).isEqualTo(422);
        assertThat(problem.codeOf(cycle)).isEqualTo("dependency-invalid");

        // self-loop
        ApiException selfLoop = assertThrows(ApiException.class, () -> tasksApi.addTaskDependencies(
                b.getId(), new AddTaskDependenciesRequest().blockedBy(Set.of(b.getId()))));
        assertThat(selfLoop.getCode()).isEqualTo(422);

        // неизвестный blocker → 422 dependency-invalid (спека §4.1)
        ApiException unknownBlocker = assertThrows(ApiException.class, () -> tasksApi.addTaskDependencies(
                a.getId(), new AddTaskDependenciesRequest().blockedBy(Set.of(UUID.randomUUID()))));
        assertThat(unknownBlocker.getCode()).isEqualTo(422);
        assertThat(problem.codeOf(unknownBlocker)).isEqualTo("dependency-invalid");

        // снятие ребра — идемпотентный 204
        tasksApi.removeTaskDependency(a.getId(), b.getId());
        tasksApi.removeTaskDependency(a.getId(), b.getId());

        // неизвестная блокируемая задача {id} → 404 task-not-found
        ApiException unknownBlocked = assertThrows(ApiException.class, () -> tasksApi.addTaskDependencies(
                UUID.randomUUID(), new AddTaskDependenciesRequest().blockedBy(Set.of(b.getId()))));
        assertThat(unknownBlocked.getCode()).isEqualTo(404);
    }

    @Test
    void unknownTaskReturns404OnAllEndpoints() throws Exception {
        UUID absent = UUID.randomUUID();

        assertThat(assertThrows(ApiException.class, () -> tasksApi.getTask(absent)).getCode()).isEqualTo(404);
        assertThat(assertThrows(ApiException.class,
                () -> tasksApi.patchTask(absent, new UpdateTaskRequest().title("x"))).getCode()).isEqualTo(404);
        assertThat(assertThrows(ApiException.class,
                () -> tasksApi.listTaskHistory(absent, null, null)).getCode()).isEqualTo(404);
        assertThat(assertThrows(ApiException.class,
                () -> tasksApi.listTaskComments(absent, null, null)).getCode()).isEqualTo(404);
        assertThat(assertThrows(ApiException.class,
                () -> tasksApi.addTaskComment(absent, new AddTaskCommentRequest().body("x")))
                .getCode()).isEqualTo(404);
        assertThat(assertThrows(ApiException.class,
                () -> tasksApi.getTaskTree(absent, null)).getCode()).isEqualTo(404);
        assertThat(assertThrows(ApiException.class,
                () -> tasksApi.removeTaskDependency(absent, UUID.randomUUID())).getCode()).isEqualTo(404);
    }

    @Test
    void dependencyBatchIsAtomicNoPartialCommit() throws Exception {
        // K-1: пачка [b, unknown] — 422 dependency-invalid и НОЛЬ закоммиченных рёбер
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");
        TaskDto a = createTaskViaRegistry(wf, null, List.of(), "A");
        TaskDto b = createTaskViaRegistry(wf, null, List.of(), "B");

        ApiException failure = assertThrows(ApiException.class, () -> tasksApi.addTaskDependencies(
                a.getId(),
                new AddTaskDependenciesRequest().blockedBy(Set.of(b.getId(), UUID.randomUUID()))));

        assertThat(failure.getCode()).isEqualTo(422);
        assertThat(problem.codeOf(failure)).isEqualTo("dependency-invalid");
        Integer edges = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_dependency WHERE blocked_task_id = ?", Integer.class, a.getId());
        assertThat(edges).as("частичный коммит пачки запрещён").isZero();
    }

    @Test
    void patchWithNonArrayTagsReturns422ArrayRequired() throws Exception {
        // K-5: tags не-массивом — явный 422 validation-failed rule=array-required
        TaskDto created = createTaskViaApi();

        HttpResponse<String> response = sendRaw(http, localServerUrl(), "PATCH",
                "/api/v1/tasks/" + created.getId(), aliceToken,
                "application/merge-patch+json", null, "{\"tags\":\"foo\"}");

        assertThat(response.statusCode()).isEqualTo(422);
        JsonNode body = problem.read(response.body());
        assertThat(body.get("code").asText()).isEqualTo("validation-failed");
        assertThat(body.get("errors").get(0).get("rule").asText()).isEqualTo("array-required");
        assertThat(body.get("errors").get(0).get("pointer").asText()).isEqualTo("/tags");
    }

    @Test
    void listTasksResolvesUsernamesWithSingleBatchQuery() throws Exception {
        TaskDto viaApi = createTaskViaApi();
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");
        createTaskViaRegistry(wf, null, List.of(), "вторая");
        createTaskViaRegistry(wf, null, List.of(), "третья");
        assertThat(viaApi.getId()).isNotNull();

        ch.qos.logback.classic.Logger jdbcLogger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(org.springframework.jdbc.core.JdbcTemplate.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        ch.qos.logback.classic.Level previous = jdbcLogger.getLevel();
        jdbcLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        appender.start();
        jdbcLogger.addAppender(appender);
        try {
            tasksApi.listTasks(null, null, null, null, null, null, null);
        } finally {
            jdbcLogger.detachAppender(appender);
            jdbcLogger.setLevel(previous);
        }

        long usernameQueries = appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("username FROM app_user"))
                .count();
        assertThat(usernameQueries)
                .as("batch-резолв usernames: ровно один SQL на запрос")
                .isEqualTo(1);
    }

    // --- фикстуры ------------------------------------------------------------

    private TaskTestFixtures.WfRevision seedWorkflow(Map<String, Object> graph, String startState) {
        return TaskTestFixtures.insertRevisionWithKey(jdbcTemplate, idGenerator, graph, startState);
    }

    /** Цепочка wait1 → wait2 → done/failed (история из двух переходов руками теста). */
    private Map<String, Object> chainGraph() {
        return WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("wait1", "WAIT_WEBHOOK", null),
                        WorkflowTestFixtures.state("wait2", "WAIT_WEBHOOK", null),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))),
                List.of(
                        WorkflowTestFixtures.transition("wait1", "wait2", "NEXT"),
                        WorkflowTestFixtures.transition("wait2", "done", "NEXT"),
                        WorkflowTestFixtures.transition("wait2", "failed", "ERROR"),
                        WorkflowTestFixtures.transition("wait2", "done", "TIMEOUT")));
    }

    /** Задача через REST (owner/author — alice). */
    private TaskDto createTaskViaApi() throws Exception {
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");
        return tasksApi.createTask(new CreateTaskRequest()
                .title("Задача " + UUID.randomUUID())
                .description("Описание задачи")
                .workflowKey(wf.workflowKey()));
    }

    /** Задача напрямую через реестр (управляемые теги/заголовок/родитель). */
    private TaskDto createTaskViaRegistry(TaskTestFixtures.WfRevision wf, UUID parent,
                                          List<String> tags, String title) throws Exception {
        UUID owner = insertAppUser(jdbcTemplate);
        var created = taskRegistry.createTask(new TaskRegistry.CreateTaskCommand(
                wf.revisionId(), title, "Описание задачи", null, owner, parent, Map.of(), tags));
        return tasksApi.getTask(created.id());
    }

    private static List<UUID> ids(TaskPage page) {
        return page.getItems().stream().map(TaskDto::getId).toList();
    }

    /** Мелкий ридер problem+json (Jackson 2 в тест-класспассе). */
    private static final class ProblemReader {
        private final com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();

        JsonNode read(String body) {
            try {
                return mapper.readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException("Не удалось разобрать JSON: " + body, e);
            }
        }

        String codeOf(ApiException exception) {
            return read(exception.getResponseBody()).get("code").asText();
        }
    }
}
