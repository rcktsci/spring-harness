package se.rocketscien.harness.tests.execution.task;

import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.tests.task.TaskTestFixtures;
import se.rocketscien.harness.tests.workflow.WorkflowTestFixtures;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Фикстуры пачки I (движок состояний): эталонные графы BASH/WAIT_TASKS-скопов/терминалов,
 * raw-вставки задач мимо реестра (для сценариев «EVENT-wake потерян» — POLL-страховка).
 * Публична — используется и api-тестами вебхуков (пачка L).
 */
public final class TaskEngineTestFixtures {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TaskEngineTestFixtures() {
    }

    /** BASH-граф: run(BASH_SCRIPT, script-заглушка, timeout или null) → done/failed (+TIMEOUT). */
    public static Map<String, Object> bashGraph(String timeout) {
        var run = new java.util.HashMap<>(WorkflowTestFixtures.state(
                "run", "BASH_SCRIPT", Map.of("script", "true")));
        if (timeout != null) {
            run.put("timeout", timeout);
        }
        return WorkflowTestFixtures.graph(
                List.of(
                        java.util.Collections.unmodifiableMap(run),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("run", "done", "NEXT"),
                        WorkflowTestFixtures.transition("run", "failed", "ERROR"),
                        WorkflowTestFixtures.transition("run", "done", "TIMEOUT")
                )
        );
    }

    /** AGENT-старт без явного таймаута у WAIT_TASKS-цели: init(AGENT) → wait → done/failed. */
    public static Map<String, Object> initToWaitNoTimeoutGraph() {
        return WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("init", "AGENT", Map.of("agent_key", "orchestrator")),
                        WorkflowTestFixtures.state("wait", "WAIT_TASKS",
                                Map.of("scope", "ALL_CHILDREN", "condition", "ALL_TERMINAL")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("init", "wait", "NEXT"),
                        WorkflowTestFixtures.transition("wait", "done", "NEXT"),
                        WorkflowTestFixtures.transition("wait", "failed", "ERROR"),
                        WorkflowTestFixtures.transition("wait", "done", "TIMEOUT")
                )
        );
    }

    /** WAIT_TASKS ALL_CHILDREN/ALL_SUCCESS. */
    public static Map<String, Object> waitAllSuccessGraph() {
        return WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("gather", "WAIT_TASKS",
                                Map.of("scope", "ALL_CHILDREN", "condition", "ALL_SUCCESS",
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

    /** WAIT_TASKS BLOCKED_BY/ALL_TERMINAL. */
    public static Map<String, Object> blockedByScopeGraph() {
        return WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("wait", "WAIT_TASKS",
                                Map.of("scope", "BLOCKED_BY", "condition", "ALL_TERMINAL",
                                        "timeout", "PT10M")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("wait", "done", "NEXT"),
                        WorkflowTestFixtures.transition("wait", "failed", "ERROR"),
                        WorkflowTestFixtures.transition("wait", "done", "TIMEOUT")
                )
        );
    }

    /** WAIT_TASKS TAGGED(batch)/ALL_TERMINAL. */
    public static Map<String, Object> taggedScopeGraph() {
        return WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("wait", "WAIT_TASKS",
                                Map.of("scope", "TAGGED(batch)", "condition", "ALL_TERMINAL",
                                        "timeout", "PT10M")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("wait", "done", "NEXT"),
                        WorkflowTestFixtures.transition("wait", "failed", "ERROR"),
                        WorkflowTestFixtures.transition("wait", "done", "TIMEOUT")
                )
        );
    }

    /** WAIT_TASKS EXPLICIT(${task.params.ids})/ALL_TERMINAL. */
    public static Map<String, Object> explicitScopeGraph() {
        return WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("wait", "WAIT_TASKS",
                                Map.of("scope", "EXPLICIT(${task.params.ids})",
                                        "condition", "ALL_TERMINAL", "timeout", "PT10M")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("wait", "done", "NEXT"),
                        WorkflowTestFixtures.transition("wait", "failed", "ERROR"),
                        WorkflowTestFixtures.transition("wait", "done", "TIMEOUT")
                )
        );
    }

    /** Единственное терминальное состояние — старт (мгновенно SUCCESS). */
    public static Map<String, Object> successTerminalGraph() {
        return WorkflowTestFixtures.graph(
                List.of(WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS"))),
                List.of()
        );
    }

    /** Единственное терминальное состояние — старт (мгновенно CANCELLED). */
    public static Map<String, Object> cancelledTerminalGraph() {
        return WorkflowTestFixtures.graph(
                List.of(WorkflowTestFixtures.state("gone", "TERMINAL", Map.of("outcome", "CANCELLED"))),
                List.of()
        );
    }

    /** WAIT_WEBHOOK с payloadSchema (ограниченный профиль D-58): required status:string. */
    public static Map<String, Object> waitWebhookSchemaGraph() {
        Map<String, Object> payloadSchema = Map.of(
                "type", "object",
                "required", List.of("status"),
                "properties", Map.of("status", Map.of("type", "string"))
        );
        return WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("wait", "WAIT_WEBHOOK",
                                Map.of("payloadSchema", payloadSchema, "timeout", "PT2M")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("wait", "done", "NEXT"),
                        WorkflowTestFixtures.transition("wait", "failed", "ERROR"),
                        WorkflowTestFixtures.transition("wait", "done", "TIMEOUT")
                )
        );
    }

    /** Создание задачи по графу (raw-вставка ревизии + TaskRegistry). */
    public static Task createTask(TaskRegistry registry, JdbcTemplate jdbcTemplate,
                                  IdGenerator idGenerator, Map<String, Object> graph, String startState) {
        return createTask(registry, jdbcTemplate, idGenerator, graph, startState, Map.of());
    }

    /** Создание задачи с params (raw-вставка ревизии + TaskRegistry). */
    public static Task createTask(TaskRegistry registry, JdbcTemplate jdbcTemplate,
                                  IdGenerator idGenerator, Map<String, Object> graph, String startState,
                                  Map<String, Object> params) {
        UUID revisionId = TaskTestFixtures.insertRevision(jdbcTemplate, idGenerator, graph, startState);
        UUID owner = WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        return registry.createTask(new TaskRegistry.CreateTaskCommand(
                revisionId, "Задача " + UUID.randomUUID(), "Описание", null, owner,
                null, params, List.of()));
    }

    /** Подзадача родителя по графу (raw-вставка ревизии + TaskRegistry). */
    public static Task createSubtask(TaskRegistry registry, JdbcTemplate jdbcTemplate,
                                     IdGenerator idGenerator, Map<String, Object> graph, String startState,
                                     UUID parentTaskId, List<String> tags, Map<String, Object> params) {
        UUID revisionId = TaskTestFixtures.insertRevision(jdbcTemplate, idGenerator, graph, startState);
        UUID owner = WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        return registry.createTask(new TaskRegistry.CreateTaskCommand(
                revisionId, "Подзадача " + UUID.randomUUID(), "Описание", null, owner,
                parentTaskId, params == null ? Map.of() : params, tags == null ? List.of() : tags));
    }

    /**
     * Raw-вставка задачи мимо реестра (без EVENT-wake) — сценарий «процесс упал между коммитом
     * и wake», POLL-страховка поднимает. Граф — waitTasks по умолчанию.
     */
    public static UUID insertTaskRaw(JdbcTemplate jdbcTemplate, IdGenerator idGenerator,
                                     String state, String kind, String status, boolean suspended) {
        return insertTaskRaw(jdbcTemplate, idGenerator, TaskTestFixtures.waitTasksGraph(),
                "gather", state, kind, status, suspended);
    }

    /** Raw-вставка задачи с явным графом и стартовым состоянием. */
    public static UUID insertTaskRaw(JdbcTemplate jdbcTemplate, IdGenerator idGenerator,
                                     Map<String, Object> graph, String startState,
                                     String state, String kind, String status, boolean suspended) {
        UUID revisionId = TaskTestFixtures.insertRevision(jdbcTemplate, idGenerator, graph, startState);
        UUID owner = WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        UUID taskId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                """
                INSERT INTO task (id, title, description, owner_user_id, workflow_revision_id,
                                  current_state, current_state_kind, state_attempt, task_event_seq,
                                  status_projection, suspended, created_at, updated_at)
                VALUES (?, ?, 'raw', ?, ?, ?, ?, 0, 0, ?, ?, now(), now())
                """,
                taskId, "Raw " + taskId, owner, revisionId, state, kind, status, suspended);
        return taskId;
    }
}
