package se.rocketscien.harness.execution.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.execution.ToolResult;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.task.DependencyInvalidException;
import se.rocketscien.harness.task.ParamsSchemaInvalidException;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskNotFoundException;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.Trigger;
import se.rocketscien.harness.task.TriggerRegistry;
import se.rocketscien.harness.task.WorkflowRevisionNotFoundException;
import se.rocketscien.harness.workflow.WorkflowGraphInvalidException;
import se.rocketscien.harness.workflow.WorkflowKeyAlreadyExistsException;
import se.rocketscien.harness.workflow.WorkflowNotFoundException;
import se.rocketscien.harness.workflow.WorkflowRegistry;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Оркестраторские metaTools (M3 пачка P, D-62/D-70): шесть инструментов над существующими
 * контрактами реестров — {@code create_workflow}/{@code edit_workflow} (WorkflowRegistry),
 * {@code create_task}/{@code create_subtask} (TaskRegistry), {@code set_dependency}
 * (атомарная пачка рёбер, K-1), {@code configure_trigger} (TriggerRegistry →
 * {@code {triggerId, url}}). Владелец созданных сущностей — владелец сессии
 * (owner-наследование, D-38 без 4-го множителя); автор задач — агент (NULL).
 *
 * <p>Манифест и гейт — на стороне {@code AgentTurnEngine}: инструменты добавляются только
 * агентам с {@code permissions_jsonb.metaTools = true}; явный вызов без флага —
 * {@code forbidden (no-metaTools)}; D-59 (instructionSource = USER) на них НЕ распространяется
 * (частичный supersession D-41 — D-70). Ошибки реестров маппятся в TOOL_RESULT с
 * машиночитаемым кодом (422 graph-invalid / params-schema / dependency-invalid,
 * 404 workflow-not-found / task-not-found, 409 workflow-key-exists).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrchestratorTools {

    public static final String CREATE_WORKFLOW = "create_workflow";
    public static final String EDIT_WORKFLOW = "edit_workflow";
    public static final String CREATE_TASK = "create_task";
    public static final String CREATE_SUBTASK = "create_subtask";
    public static final String SET_DEPENDENCY = "set_dependency";
    public static final String CONFIGURE_TRIGGER = "configure_trigger";

    /** Orchestrator-список: гейт metaTools и сборка манифеста (P.1/P.3). */
    public static final Set<String> NAMES = Set.of(
            CREATE_WORKFLOW, EDIT_WORKFLOW, CREATE_TASK, CREATE_SUBTASK, SET_DEPENDENCY, CONFIGURE_TRIGGER);

    /** Формат ключа workflow — тот же pattern, что валидирует REST (bean-validation). */
    private static final String KEY_PATTERN = "[a-z][a-z0-9-]*";

    private final WorkflowRegistry workflows;
    private final TaskRegistry tasks;
    private final TriggerRegistry triggers;
    private final ObjectMapper objectMapper;

    /** Декларации шести инструментов (добавляются в манифест только оркестраторам). */
    public List<ToolCallback> declarations() {
        List<ToolCallback> callbacks = new ArrayList<>();
        callbacks.add(FunctionToolCallback.builder(CREATE_WORKFLOW, (CreateWorkflowArgs unused) ->
                        "create_workflow is executed by the turn engine (write-ahead journaling)")
                .description("Create a workflow template with its first revision (rev=1). key must be "
                        + "kebab-case (^[a-z][a-z0-9-]*$). graph is the workflow graph JSON: "
                        + "{states: [{code, type, ...}], transitions: [{from, to, kind}]}; "
                        + "startState defaults to the first state.")
                .inputType(CreateWorkflowArgs.class)
                .build());
        callbacks.add(FunctionToolCallback.builder(EDIT_WORKFLOW, (EditWorkflowArgs unused) ->
                        "edit_workflow is executed by the turn engine (write-ahead journaling)")
                .description("Add a new immutable revision (rev = prev + 1) of an existing workflow; "
                        + "running tasks stay on the old revision. startState defaults to the first state.")
                .inputType(EditWorkflowArgs.class)
                .build());
        callbacks.add(FunctionToolCallback.builder(CREATE_TASK, (CreateTaskArgs unused) ->
                        "create_task is executed by the turn engine (write-ahead journaling)")
                .description("Create a task pinned to the latest revision of the workflow, or to rev "
                        + "when given explicitly. params are validated against the paramsSchema of "
                        + "the start state.")
                .inputType(CreateTaskArgs.class)
                .build());
        callbacks.add(FunctionToolCallback.builder(CREATE_SUBTASK, (CreateSubtaskArgs unused) ->
                        "create_subtask is executed by the turn engine (write-ahead journaling)")
                .description("Create a subtask under an existing parent task, pinned to the latest "
                        + "revision of the given workflow, or to rev when given explicitly.")
                .inputType(CreateSubtaskArgs.class)
                .build());
        callbacks.add(FunctionToolCallback.builder(SET_DEPENDENCY, (SetDependencyArgs unused) ->
                        "set_dependency is executed by the turn engine (write-ahead journaling)")
                .description("Atomically add dependency edges: every blocker task must reach a terminal "
                        + "state before the blocked task proceeds. blocked_by is a list of blocker task ids.")
                .inputType(SetDependencyArgs.class)
                .build());
        callbacks.add(FunctionToolCallback.builder(CONFIGURE_TRIGGER, (ConfigureTriggerArgs unused) ->
                        "configure_trigger is executed by the turn engine (write-ahead journaling)")
                .description("Create an inbound webhook trigger for the workflow and return its "
                        + "capability URL: {triggerId, url}.")
                .inputType(ConfigureTriggerArgs.class)
                .build());
        return callbacks;
    }

    /** Диспетчер metaTools: свитч по имени; ошибки реестров — машиночитаемые коды. */
    public ToolResult execute(Session session, String callId, String tool, Map<String, Object> arguments) {
        try {
            return switch (tool == null ? "" : tool) {
                case CREATE_WORKFLOW -> createWorkflow(session, callId, arguments);
                case EDIT_WORKFLOW -> editWorkflow(session, callId, arguments);
                case CREATE_TASK -> createTask(session, callId, arguments, null);
                case CREATE_SUBTASK -> createSubtask(session, callId, arguments);
                case SET_DEPENDENCY -> setDependency(callId, arguments);
                case CONFIGURE_TRIGGER -> configureTrigger(session, callId, arguments);
                default -> ToolResult.error(callId, tool, "unknown orchestrator tool: " + tool);
            };
        } catch (WorkflowGraphInvalidException e) {
            return ToolResult.error(callId, tool, "422 graph-invalid: " + errorsText(e.getErrors()));
        } catch (ParamsSchemaInvalidException e) {
            return ToolResult.error(callId, tool, "422 params-schema: " + errorsText(e.getErrors()));
        } catch (DependencyInvalidException e) {
            return ToolResult.error(callId, tool, "422 dependency-invalid: " + errorsText(e.getErrors()));
        } catch (WorkflowKeyAlreadyExistsException e) {
            return ToolResult.error(callId, tool, "409 workflow-key-exists");
        } catch (WorkflowRevisionNotFoundException | WorkflowNotFoundException e) {
            return ToolResult.error(callId, tool, "404 workflow-not-found: " + e.getMessage());
        } catch (TaskNotFoundException e) {
            return ToolResult.error(callId, tool, "404 task-not-found: " + e.getMessage());
        } catch (Exception e) {
            log.warn("metaTool {} сессии {} упал: {}", tool, session.id(), e.getMessage());
            return ToolResult.error(callId, tool, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- инструменты

    private ToolResult createWorkflow(Session session, String callId, Map<String, Object> arguments) {
        String key = text(arguments, "key");
        if (key == null || key.isBlank()) {
            return ToolResult.error(callId, CREATE_WORKFLOW, "key обязателен");
        }
        if (!key.strip().matches(KEY_PATTERN)) {
            return ToolResult.error(callId, CREATE_WORKFLOW,
                    "422 validation-failed (rule=kebab-case): key должен быть ^[a-z][a-z0-9-]*$");
        }
        Map<String, Object> graph = mapArg(arguments, "graph");
        if (graph == null || graph.isEmpty()) {
            return ToolResult.error(callId, CREATE_WORKFLOW, "graph обязателен");
        }
        String name = text(arguments, "name");
        String startState = resolveStartState(arguments, graph);
        WorkflowRegistry.WorkflowRevision revision = workflows.createWorkflow(
                session.ownerUserId(), key.strip(), name == null || name.isBlank() ? key.strip() : name,
                graph, startState);
        return ok(callId, CREATE_WORKFLOW, Map.of(
                "workflowKey", key.strip(),
                "rev", revision.rev(),
                "startState", revision.startState()));
    }

    private ToolResult editWorkflow(Session session, String callId, Map<String, Object> arguments) {
        String key = text(arguments, "key");
        if (key == null || key.isBlank()) {
            return ToolResult.error(callId, EDIT_WORKFLOW, "key обязателен");
        }
        if (!key.strip().matches(KEY_PATTERN)) {
            return ToolResult.error(callId, EDIT_WORKFLOW,
                    "422 validation-failed (rule=kebab-case): key должен быть ^[a-z][a-z0-9-]*$");
        }
        Map<String, Object> graph = mapArg(arguments, "graph");
        if (graph == null || graph.isEmpty()) {
            return ToolResult.error(callId, EDIT_WORKFLOW, "graph обязателен");
        }
        String startState = resolveStartState(arguments, graph);
        WorkflowRegistry.WorkflowRevision revision = workflows.newRevision(key.strip(), graph, startState);
        return ok(callId, EDIT_WORKFLOW, Map.of(
                "workflowKey", key.strip(),
                "rev", revision.rev(),
                "startState", revision.startState()));
    }

    private ToolResult createTask(Session session, String callId, Map<String, Object> arguments, UUID parentTaskId) {
        String title = text(arguments, "title");
        if (title == null || title.isBlank()) {
            return ToolResult.error(callId, CREATE_TASK, "title обязателен");
        }
        String workflowKey = text(arguments, "workflow_key");
        if (workflowKey == null || workflowKey.isBlank()) {
            return ToolResult.error(callId, CREATE_TASK, "workflow_key обязателен");
        }
        WorkflowRegistry.Workflow workflow = workflows.get(workflowKey.strip());
        // Пин последней ревизии; agent-tools §2b — «или явной» (rev?)
        Integer requestedRev = arguments != null && arguments.get("rev") instanceof Number number
                ? number.intValue() : null;
        WorkflowRegistry.WorkflowRevision revision = workflows.getRevision(
                workflow.key(), requestedRev == null ? workflow.latestRev() : requestedRev);
        // TaskRegistry требует непустой description — по умолчанию берём title
        String description = text(arguments, "description");
        if (description == null || description.isBlank()) {
            description = title.strip();
        }
        Task task = tasks.createTask(new TaskRegistry.CreateTaskCommand(
                revision.id(),
                title.strip(),
                description,
                null,
                session.ownerUserId(),
                parentTaskId,
                mapArg(arguments, "params"),
                tags(arguments)));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("taskId", task.id());
        output.put("title", task.title());
        output.put("workflowKey", workflow.key());
        output.put("rev", revision.rev());
        output.put("state", task.currentState());
        if (task.parentTaskId() != null) {
            output.put("parentTaskId", task.parentTaskId());
        }
        return ok(callId, CREATE_TASK, output);
    }

    private ToolResult createSubtask(Session session, String callId, Map<String, Object> arguments) {
        UUID parentTaskId = uuid(arguments, "parent_task_id");
        if (parentTaskId == null) {
            return ToolResult.error(callId, CREATE_SUBTASK, "parent_task_id обязателен");
        }
        return createTask(session, callId, arguments, parentTaskId);
    }

    private ToolResult setDependency(String callId, Map<String, Object> arguments) {
        UUID blockedTaskId = uuid(arguments, "blocked_task_id");
        if (blockedTaskId == null) {
            return ToolResult.error(callId, SET_DEPENDENCY, "blocked_task_id обязателен");
        }
        List<UUID> blockers = uuidList(arguments, "blocked_by");
        if (blockers.isEmpty()) {
            return ToolResult.error(callId, SET_DEPENDENCY, "blocked_by обязателен (список task_id)");
        }
        tasks.addDependencies(blockedTaskId, blockers);
        return ok(callId, SET_DEPENDENCY, Map.of("blockedTaskId", blockedTaskId, "added", blockers.size()));
    }

    private ToolResult configureTrigger(Session session, String callId, Map<String, Object> arguments) {
        String name = text(arguments, "name");
        if (name == null || name.isBlank()) {
            return ToolResult.error(callId, CONFIGURE_TRIGGER, "name обязателен");
        }
        String workflowKey = text(arguments, "workflow_key");
        if (workflowKey == null || workflowKey.isBlank()) {
            return ToolResult.error(callId, CONFIGURE_TRIGGER, "workflow_key обязателен");
        }
        Trigger trigger = triggers.create(new TriggerRegistry.CreateTriggerCommand(
                session.ownerUserId(),
                name.strip(),
                workflowKey.strip(),
                null,
                mapArg(arguments, "params"),
                tags(arguments)));
        return ok(callId, CONFIGURE_TRIGGER,
                Map.of("triggerId", trigger.id(), "url", String.valueOf(trigger.url())));
    }

    // ---------------------------------------------------------------- хелперы

    /**
     * Явный {@code start_state} из аргументов; иначе код первого состояния графа
     * (реестр требует старт ∈ codes; единственный source — забота валидатора).
     */
    private String resolveStartState(Map<String, Object> arguments, Map<String, Object> graph) {
        String explicit = text(arguments, "start_state");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        if (graph.get("states") instanceof List<?> states && !states.isEmpty()
                && states.getFirst() instanceof Map<?, ?> first
                && first.get("code") instanceof String code) {
            return code;
        }
        return null;
    }

    private ToolResult ok(String callId, String tool, Map<String, Object> output) {
        try {
            return ToolResult.ok(callId, tool, objectMapper.writeValueAsString(output));
        } catch (Exception e) {
            return ToolResult.ok(callId, tool, String.valueOf(output));
        }
    }

    private String errorsText(List<?> errors) {
        return errors == null ? ""
                : errors.stream().map(String::valueOf).collect(Collectors.joining("; "));
    }

    private static List<String> tags(Map<String, Object> arguments) {
        if (arguments == null || !(arguments.get("tags") instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private static String text(Map<String, Object> arguments, String key) {
        return arguments != null && arguments.get(key) instanceof String value ? value : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapArg(Map<String, Object> arguments, String key) {
        return arguments != null && arguments.get(key) instanceof Map<?, ?> map
                ? (Map<String, Object>) map : null;
    }

    private static UUID uuid(Map<String, Object> arguments, String key) {
        if (arguments == null || !(arguments.get(key) instanceof String value) || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<UUID> uuidList(Map<String, Object> arguments, String key) {
        if (arguments == null || !(arguments.get(key) instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(value -> {
                    try {
                        return UUID.fromString(value.strip());
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    // ---------------------------------------------------------------- аргументы

    public record CreateWorkflowArgs(String key, String name, Map<String, Object> graph, String start_state) {
    }

    public record EditWorkflowArgs(String key, Map<String, Object> graph, String start_state) {
    }

    public record CreateTaskArgs(String title, String description, String workflow_key, Integer rev,
                                 Map<String, Object> params, List<String> tags) {
    }

    public record CreateSubtaskArgs(String parent_task_id, String title, String description,
                                    String workflow_key, Integer rev,
                                    Map<String, Object> params, List<String> tags) {
    }

    public record SetDependencyArgs(String blocked_task_id, List<String> blocked_by) {
    }

    public record ConfigureTriggerArgs(String name, String workflow_key,
                                       Map<String, Object> params, List<String> tags) {
    }
}
