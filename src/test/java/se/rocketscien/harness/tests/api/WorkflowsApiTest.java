package se.rocketscien.harness.tests.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.testclient.ApiClient;
import se.rocketscien.harness.testclient.ApiException;
import se.rocketscien.harness.testclient.api.WorkflowsApi;
import se.rocketscien.harness.testclient.model.CreateWorkflowRequest;
import se.rocketscien.harness.testclient.model.CreateWorkflowRevisionRequest;
import se.rocketscien.harness.testclient.model.WorkflowDto;
import se.rocketscien.harness.testclient.model.WorkflowGraph;
import se.rocketscien.harness.testclient.model.WorkflowPage;
import se.rocketscien.harness.testclient.model.WorkflowState;
import se.rocketscien.harness.testclient.model.WorkflowTransition;
import se.rocketscien.harness.tests.workflow.WorkflowTestFixtures;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static se.rocketscien.harness.tests.api.ApiFixtures.apiClient;
import static se.rocketscien.harness.tests.api.ApiFixtures.insertAgentChain;
import static se.rocketscien.harness.tests.api.ApiFixtures.keycloakToken;

/**
 * REST workflow и ревизий (api-contracts §4.2; спека workflow-engine): создание (201,
 * rev=1, дубликат key → 422 rule=key-unique, битый граф → 422 graph-invalid с errors[]),
 * метаданные со списком ревизий, список с пагинацией, новая иммутабельная ревизия
 * (rev = prev + 1), граф конкретной ревизии, 404 на неизвестных key/rev. Позитивный
 * путь — сгенерированный тест-клиент.
 */
class WorkflowsApiTest extends BaseApplicationTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ProblemReader problem = new ProblemReader();

    private String aliceToken;
    private WorkflowsApi workflowsApi;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IdGenerator idGenerator;
    @Autowired
    private Environment environment;

    @BeforeEach
    void setUpClients() {
        aliceToken = keycloakToken(http, "alice", "alice-password");
        ApiClient aliceClient = apiClient(localServerUrl(), aliceToken);
        workflowsApi = new WorkflowsApi(aliceClient);
        insertAgentChain(jdbcTemplate, idGenerator, environment);
    }

    @Test
    void createWorkflowReturns201FirstRevisionWithGraph() throws Exception {
        WorkflowDto created = workflowsApi.createWorkflow(new CreateWorkflowRequest()
                .key("review-" + System.nanoTime())
                .name("Двухфазное ревью")
                .graph(toClientGraph(graph(Map.of("agent_key", "orchestrator"))))
                .startState("plan"));

        assertThat(created.getKey()).startsWith("review-");
        assertThat(created.getName()).isEqualTo("Двухфазное ревью");
        assertThat(created.getLatestRev()).isEqualTo(1);
        assertThat(created.getRevisions()).hasSize(1);
        assertThat(created.getRevisions().getFirst().getRev()).isEqualTo(1);
        assertThat(created.getRevisions().getFirst().getCreatedAt()).isNotNull();

        WorkflowDto fetched = workflowsApi.getWorkflow(created.getKey());
        assertThat(fetched.getLatestRev()).isEqualTo(1);
        assertThat(fetched.getRevisions()).hasSize(1);

        // граф ревизии читается обратно (round-trip: code/type/agent_key/script/timeout)
        var revision = workflowsApi.getWorkflowRevision(created.getKey(), 1);
        assertThat(revision.getWorkflowKey()).isEqualTo(created.getKey());
        assertThat(revision.getRev()).isEqualTo(1);
        assertThat(revision.getStartState()).isEqualTo("plan");
        assertThat(revision.getGraph().getStates()).hasSize(4);
        assertThat(revision.getGraph().getStates().stream()
                .filter(s -> "plan".equals(s.getCode())).findFirst().orElseThrow())
                .satisfies(plan -> {
                    assertThat(plan.getType().getValue()).isEqualTo("AGENT");
                    assertThat(plan.getAgentKey()).isEqualTo("orchestrator");
                });
        assertThat(revision.getGraph().getTransitions()).hasSize(5);
    }

    @Test
    void createDuplicateKeyReturns422KeyUnique() throws Exception {
        String key = "dup-" + System.nanoTime();
        workflowsApi.createWorkflow(new CreateWorkflowRequest()
                .key(key).name("Первый").graph(toClientGraph(graph(Map.of("agent_key", "orchestrator"))))
                .startState("plan"));

        ApiException exception = assertThrows(ApiException.class, () -> workflowsApi.createWorkflow(
                new CreateWorkflowRequest().key(key).name("Второй")
                        .graph(toClientGraph(graph(Map.of("agent_key", "orchestrator")))).startState("plan")));

        assertThat(exception.getCode()).isEqualTo(422);
        JsonNode body = problem.read(exception.getResponseBody());
        assertThat(body.get("code").asText()).isEqualTo("validation-failed");
        assertThat(body.get("errors").get(0).get("rule").asText()).isEqualTo("key-unique");
        assertThat(body.get("errors").get(0).get("pointer").asText()).isEqualTo("/key");
    }

    @Test
    void createWorkflowWithInvalidGraphReturns422GraphInvalid() {
        // WAIT_TASKS без ERROR-ребра — нарушение правил §2 workflow-domain
        Map<String, Object> broken = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("wait", "WAIT_TASKS",
                                Map.of("scope", "ALL_CHILDREN", "condition", "ALL_TERMINAL")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS"))
                ),
                List.of(WorkflowTestFixtures.transition("wait", "done", "NEXT")));

        ApiException exception = assertThrows(ApiException.class, () -> workflowsApi.createWorkflow(
                new CreateWorkflowRequest().key("broken-" + System.nanoTime()).name("Битый")
                        .graph(toClientGraph(broken)).startState("wait")));

        assertThat(exception.getCode()).isEqualTo(422);
        JsonNode body = problem.read(exception.getResponseBody());
        assertThat(body.get("code").asText()).isEqualTo("graph-invalid");
        assertThat(body.get("errors")).isNotEmpty();
    }

    @Test
    void unknownWorkflowOrRevisionReturns404() {
        ApiException unknownKey = assertThrows(ApiException.class, () -> workflowsApi.getWorkflow("ghost"));
        assertThat(unknownKey.getCode()).isEqualTo(404);
        assertThat(problem.codeOf(unknownKey)).isEqualTo("workflow-not-found");

        ApiException unknownRevision = assertThrows(ApiException.class, () ->
                workflowsApi.getWorkflowRevision("ghost", 7));
        assertThat(unknownRevision.getCode()).isEqualTo(404);
    }

    @Test
    void newRevisionIncrementsAndListPaginates() throws Exception {
        String key = "rev-" + System.nanoTime();
        workflowsApi.createWorkflow(new CreateWorkflowRequest()
                .key(key).name("С ревизиями").graph(toClientGraph(graph(Map.of("agent_key", "orchestrator"))))
                .startState("plan"));

        var second = workflowsApi.createWorkflowRevision(key, new CreateWorkflowRevisionRequest()
                .graph(toClientGraph(graph(Map.of("agent_key", "orchestrator"))))
                .startState("plan"));
        assertThat(second.getRev()).isEqualTo(2);
        assertThat(second.getWorkflowKey()).isEqualTo(key);

        WorkflowDto withRevisions = workflowsApi.getWorkflow(key);
        assertThat(withRevisions.getLatestRev()).isEqualTo(2);
        assertThat(withRevisions.getRevisions()).hasSize(2);

        // идущие задачи остаются на старой ревизии — здесь проверяем только иммутабельность:
        // ревизия 1 читается в исходном виде
        assertThat(workflowsApi.getWorkflowRevision(key, 1).getRev()).isEqualTo(1);

        WorkflowPage page = workflowsApi.listWorkflows(null, null);
        assertThat(page.getItems().stream().map(WorkflowDto::getKey)).contains(key);
    }

    // --- вспомогательное ------------------------------------------------------

    /** Граф «двухфазного ревью» в raw-виде (эталон пачки H). */
    private static Map<String, Object> graph(Map<String, Object> agentExtra) {
        var plan = new java.util.LinkedHashMap<>(WorkflowTestFixtures.state("plan", "AGENT", agentExtra));
        return WorkflowTestFixtures.graph(
                List.of(
                        java.util.Collections.unmodifiableMap(plan),
                        WorkflowTestFixtures.state("checks", "BASH_SCRIPT",
                                Map.of("script", "make test", "timeout", "PT1M")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("plan", "checks", "NEXT"),
                        WorkflowTestFixtures.transition("plan", "failed", "ERROR"),
                        WorkflowTestFixtures.transition("checks", "done", "NEXT"),
                        WorkflowTestFixtures.transition("checks", "failed", "ERROR"),
                        WorkflowTestFixtures.transition("checks", "done", "TIMEOUT")
                ));
    }

    /** Raw-граф → сгенерированная клиентская модель (Jackson 2 в test-классpath). */
    private static WorkflowGraph toClientGraph(Map<String, Object> raw) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .convertValue(raw, WorkflowGraph.class);
        } catch (Exception e) {
            throw new IllegalStateException("Граф не конвертируется в клиентскую модель", e);
        }
    }

    /** Разбор problem+json (Jackson 2 в test-классе). */
    private static final class ProblemReader {
        private final com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();

        JsonNode read(String body) {
            try {
                return mapper.readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException("Не удалось разобрать problem JSON: " + body, e);
            }
        }

        String codeOf(ApiException exception) {
            return read(exception.getResponseBody()).get("code").asText();
        }
    }
}
