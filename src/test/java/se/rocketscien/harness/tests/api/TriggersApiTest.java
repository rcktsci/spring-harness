package se.rocketscien.harness.tests.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.testclient.ApiClient;
import se.rocketscien.harness.testclient.ApiException;
import se.rocketscien.harness.testclient.api.TriggersApi;
import se.rocketscien.harness.testclient.model.CreateTriggerRequest;
import se.rocketscien.harness.testclient.model.TriggerDto;
import se.rocketscien.harness.testclient.model.TriggerPage;
import se.rocketscien.harness.tests.task.TaskTestFixtures;
import se.rocketscien.harness.tests.workflow.WorkflowTestFixtures;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static se.rocketscien.harness.tests.api.ApiFixtures.apiClient;
import static se.rocketscien.harness.tests.api.ApiFixtures.insertAgentChain;
import static se.rocketscien.harness.tests.api.ApiFixtures.keycloakToken;
import static se.rocketscien.harness.tests.api.ApiFixtures.sendRaw;

/**
 * REST триггеров (пачка L.4, api-contracts §4.3; спека inbound-triggers «CRUD триггеров»):
 * создание (201 + Location = capability-URL, пин ревизии latest/явный, owner = JWT),
 * 404 workflow-not-found, 422 params-schema, список с mine/курсором (createdAt desc),
 * DELETE = revoke (204; повторный revoke → 404 trigger-not-found, ревью L-3). Позитивный
 * путь — сгенерированный тест-клиент.
 */
class TriggersApiTest extends BaseApplicationTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ProblemReader problem = new ProblemReader();

    private String aliceToken;
    private TriggersApi triggersApi;

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
        triggersApi = new TriggersApi(aliceClient);
        insertAgentChain(jdbcTemplate, idGenerator, environment);
    }

    @Test
    void createTriggerReturns201WithLocationCapabilityUrlAndOwner() throws Exception {
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");

        TriggerDto created = triggersApi.createTrigger(new CreateTriggerRequest()
                .name("Сборка по вебхуку")
                .workflowKey(wf.workflowKey())
                .params(Map.of("branch", "main"))
                .tags(List.of("ci")));

        assertThat(created.getId()).isNotNull();
        assertThat(created.getName()).isEqualTo("Сборка по вебхуку");
        assertThat(created.getWorkflowKey()).isEqualTo(wf.workflowKey());
        assertThat(created.getRev()).isEqualTo(1);
        assertThat(created.getOwner()).isEqualTo("alice");
        assertThat(created.getParams()).containsEntry("branch", "main");
        assertThat(created.getTags()).containsExactly("ci");
        assertThat(created.getRevokedAt()).isNull();
        assertThat(created.getCreatedAt()).isNotNull();
        // url — рабочий capability-URL: base + /api/webhooks/triggers/{id}/{token}
        assertThat(created.getUrl().toString())
                .startsWith("http://webhook-test/api/webhooks/triggers/" + created.getId() + "/");
        assertThat(created.getUrl().toString()).hasSize(
                ("http://webhook-test/api/webhooks/triggers/" + created.getId() + "/").length() + 64);

        HttpResponse<String> raw = sendRaw(http, localServerUrl(), "POST", "/api/v1/triggers",
                aliceToken, "application/json", null,
                "{\"name\":\"с Location\",\"workflowKey\":\"" + wf.workflowKey() + "\"}");
        assertThat(raw.statusCode()).isEqualTo(201);
        // ревью L-4: Location — capability-URL (рабочий эндпоинт), а не DELETE-only ресурс
        String location = raw.headers().firstValue("Location").orElseThrow();
        assertThat(location).isEqualTo(problem.read(raw.body()).get("url").asText());
        assertThat(location).startsWith("http://webhook-test/api/webhooks/triggers/");
    }

    @Test
    void createTriggerPinsExplicitRevision() throws Exception {
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");

        TriggerDto created = triggersApi.createTrigger(new CreateTriggerRequest()
                .name("Явный пин").workflowKey(wf.workflowKey()).rev(1));

        assertThat(created.getRev()).isEqualTo(1);
    }

    @Test
    void createTriggerUnknownWorkflowReturns404WorkflowNotFound() throws Exception {
        ApiException exception = assertThrows(ApiException.class, () -> triggersApi.createTrigger(
                new CreateTriggerRequest().name("t").workflowKey("no-such-workflow")));

        assertThat(exception.getCode()).isEqualTo(404);
        assertThat(problem.codeOf(exception)).isEqualTo("workflow-not-found");
    }

    @Test
    void createTriggerInvalidParamsReturns422ParamsSchema() throws Exception {
        TaskTestFixtures.WfRevision wf = seedWorkflow(TaskTestFixtures.paramsSchemaGraph(), "start");

        ApiException exception = assertThrows(ApiException.class, () -> triggersApi.createTrigger(
                new CreateTriggerRequest().name("t").workflowKey(wf.workflowKey())
                        .params(Map.of("module", 42))));

        assertThat(exception.getCode()).isEqualTo(422);
        assertThat(problem.codeOf(exception)).isEqualTo("params-schema");
    }

    @Test
    void listTriggersSupportsMineAndCursorPagination() throws Exception {
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");
        triggersApi.createTrigger(new CreateTriggerRequest().name("первый").workflowKey(wf.workflowKey()));
        TriggerDto second = triggersApi.createTrigger(
                new CreateTriggerRequest().name("второй").workflowKey(wf.workflowKey()));

        // другой пользователь — в «мои» не попадает (carol — второй валидный SSO-аккаунт)
        String carolToken = keycloakToken(http, "carol", "carol-password");
        new TriggersApi(apiClient(localServerUrl(), carolToken)).createTrigger(
                new CreateTriggerRequest().name("чужой").workflowKey(wf.workflowKey()));

        TriggerPage mineAll = triggersApi.listTriggers(true, null, null);
        assertThat(names(mineAll)).containsExactly("второй", "первый");

        TriggerPage page1 = triggersApi.listTriggers(null, null, 2);
        assertThat(page1.getItems()).hasSize(2);
        assertThat(page1.getNextCursor()).isNotNull();

        TriggerPage page2 = triggersApi.listTriggers(null, page1.getNextCursor(), 2);
        assertThat(page2.getNextCursor()).isNull();
        assertThat(names(page2)).contains("первый");
    }

    @Test
    void revokeReturns204AndRepeatedRevokeIs404() throws Exception {
        TaskTestFixtures.WfRevision wf = seedWorkflow(WorkflowTestFixtures.waitWebhookGraph(), "wait");
        TriggerDto created = triggersApi.createTrigger(
                new CreateTriggerRequest().name("на отзыв").workflowKey(wf.workflowKey()));

        triggersApi.revokeTrigger(created.getId());
        // ревью L-3: повторный revoke → 404 trigger-not-found (нечего ревоукить)
        ApiException repeated = assertThrows(ApiException.class,
                () -> triggersApi.revokeTrigger(created.getId()));
        assertThat(repeated.getCode()).isEqualTo(404);
        assertThat(problem.codeOf(repeated)).isEqualTo("trigger-not-found");

        TriggerDto revoked = triggersApi.listTriggers(true, null, null).getItems().stream()
                .filter(t -> t.getId().equals(created.getId())).findFirst().orElseThrow();
        assertThat(revoked.getRevokedAt()).isNotNull();

        ApiException unknown = assertThrows(ApiException.class,
                () -> triggersApi.revokeTrigger(UUID.randomUUID()));
        assertThat(unknown.getCode()).isEqualTo(404);
        assertThat(problem.codeOf(unknown)).isEqualTo("trigger-not-found");
    }

    // --- вспомогательное ------------------------------------------------------

    private TaskTestFixtures.WfRevision seedWorkflow(Map<String, Object> graph, String startState) {
        return TaskTestFixtures.insertRevisionWithKey(jdbcTemplate, idGenerator, graph, startState);
    }

    private static List<String> names(TriggerPage page) {
        return page.getItems().stream().map(TriggerDto::getName).toList();
    }

    /** Разбор problem+json (Jackson 2 в test-классе). */
    private static final class ProblemReader {
        private final ObjectMapper mapper =
                new ObjectMapper();

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
