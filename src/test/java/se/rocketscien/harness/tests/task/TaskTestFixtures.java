package se.rocketscien.harness.tests.task;

import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.tests.workflow.WorkflowTestFixtures;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Общая фикстура task-тестов: пользователь + workflow-ревизия (мимо реестра, raw SQL),
 * эталонные графы, создание задач с дефолтами. Публична — используется и тестами
 * execution/task (движок состояний, пачка I).
 */
public final class TaskTestFixtures {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TaskTestFixtures() {
    }

    public static UUID insertAppUser(JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
        UUID userId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                "INSERT INTO app_user (id, keycloak_subject, username, display_name, created_at) VALUES (?, ?, ?, ?, now())",
                userId,
                "subject-" + userId,
                "user-" + userId,
                "Пользователь"
        );
        return userId;
    }

    /**
     * Вставка workflow + ревизии (rev=1) мимо реестра — для пина задач; возвращает revisionId.
     * {@code startState} — явное стартовое состояние ревизии (H-1).
     */
    public static UUID insertRevision(JdbcTemplate jdbcTemplate, IdGenerator idGenerator,
                                      Map<String, Object> graph, String startState) {
        return insertRevisionWithKey(jdbcTemplate, idGenerator, graph, startState).revisionId();
    }

    /** Выжимка сеянного workflow: id ревизии + ключ (пин ревизии через REST — пачка K). */
    public record WfRevision(UUID revisionId, String workflowKey, int rev) {
    }

    /** Вставка workflow + ревизии с известным ключом (REST-создание задачи по workflowKey+rev). */
    public static WfRevision insertRevisionWithKey(JdbcTemplate jdbcTemplate, IdGenerator idGenerator,
                                                   Map<String, Object> graph, String startState) {
        UUID owner = insertAppUser(jdbcTemplate, idGenerator);
        UUID workflowId = idGenerator.newUuidV7();
        String workflowKey = "wf-" + workflowId;
        jdbcTemplate.update(
                "INSERT INTO workflow (id, key, name, owner_user_id, created_at) VALUES (?, ?, ?, ?, now())",
                workflowId,
                workflowKey,
                "Workflow " + workflowId,
                owner
        );
        UUID revisionId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                "INSERT INTO workflow_revision (id, workflow_id, rev, graph_jsonb, start_state, created_at) "
                        + "VALUES (?, ?, 1, ?::jsonb, ?, now())",
                revisionId,
                workflowId,
                JSON.writeValueAsString(graph),
                startState
        );
        return new WfRevision(revisionId, workflowKey, 1);
    }

    /** WAIT_TASKS-граф (ALL_CHILDREN/ALL_TERMINAL); старт — gather. */
    public static Map<String, Object> waitTasksGraph() {
        return WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("gather", "WAIT_TASKS",
                                Map.of("scope", "ALL_CHILDREN", "condition", "ALL_TERMINAL",
                                        "timeout", "PT10M")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("gather", "done", "NEXT"),
                        WorkflowTestFixtures.transition("gather", "failed", "ERROR"),
                        WorkflowTestFixtures.transition("gather", "done", "TIMEOUT")
                )
        );
    }

    /** Граф с paramsSchema на начальном состоянии (restricted-профиль D-58); старт — start. */
    public static Map<String, Object> paramsSchemaGraph() {
        Map<String, Object> paramsSchema = Map.of(
                "type", "object",
                "required", List.of("module"),
                "properties", Map.of("module", Map.of("type", "string"))
        );
        var start = new HashMap<>(WorkflowTestFixtures.state("start", "AGENT", Map.of("agent_key", "orchestrator")));
        start.put("paramsSchema", paramsSchema);
        return WorkflowTestFixtures.graph(
                List.of(
                        java.util.Collections.unmodifiableMap(start),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS"))
                ),
                List.of(WorkflowTestFixtures.transition("start", "done", "NEXT"))
        );
    }

    public static TaskRegistry.CreateTaskCommand command(UUID revisionId, UUID owner, UUID parent,
                                                         Map<String, Object> params) {
        return new TaskRegistry.CreateTaskCommand(
                revisionId,
                "Задача " + UUID.randomUUID().toString().substring(0, 8),
                "Описание задачи",
                null,
                owner,
                parent,
                params == null ? Map.of() : params,
                List.of()
        );
    }

    public static Task createTask(TaskRegistry registry, JdbcTemplate jdbcTemplate, IdGenerator idGenerator,
                                  Map<String, Object> graph, String startState) {
        UUID revisionId = insertRevision(jdbcTemplate, idGenerator, graph, startState);
        UUID owner = insertAppUser(jdbcTemplate, idGenerator);
        return registry.createTask(command(revisionId, owner, null, Map.of()));
    }
}
