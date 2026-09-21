package se.rocketscien.harness.api;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import se.rocketscien.harness.api.gen.TasksApi;
import se.rocketscien.harness.api.gen.model.AddTaskCommentRequest;
import se.rocketscien.harness.api.gen.model.AddTaskDependenciesRequest;
import se.rocketscien.harness.api.gen.model.CommentDto;
import se.rocketscien.harness.api.gen.model.CommentPage;
import se.rocketscien.harness.api.gen.model.CreateTaskRequest;
import se.rocketscien.harness.api.gen.model.TaskDto;
import se.rocketscien.harness.api.gen.model.TaskPage;
import se.rocketscien.harness.api.gen.model.TaskStatusProjection;
import se.rocketscien.harness.api.gen.model.TaskTreePage;
import se.rocketscien.harness.api.gen.model.TransitionPage;
import se.rocketscien.harness.api.gen.model.UpdateTaskRequest;
import se.rocketscien.harness.api.gen.model.WorkflowRef;
import se.rocketscien.harness.common.jsonschema.JsonSchemaError;
import se.rocketscien.harness.common.security.WebhookSignatureVerifier;
import se.rocketscien.harness.config.LimitsProperties;
import se.rocketscien.harness.config.WebhookProperties;
import se.rocketscien.harness.execution.InstructionSource;
import se.rocketscien.harness.identity.AppUserDirectory;
import se.rocketscien.harness.task.Comment;
import se.rocketscien.harness.task.InvalidCursorException;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.TaskStateKind;
import se.rocketscien.harness.task.TaskStatus;
import se.rocketscien.harness.task.TaskTreeNode;
import se.rocketscien.harness.task.WorkflowRevisionNotFoundException;
import se.rocketscien.harness.workflow.WorkflowRegistry;
import se.rocketscien.harness.workflow.WorkflowRegistry.RevisionSummary;
import se.rocketscien.harness.workflow.WorkflowRegistry.Workflow;
import se.rocketscien.harness.workflow.WorkflowRegistry.WorkflowRevision;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Задачи (api-contracts §4.1, пачка K.1): создание с пином ревизии (201 + Location),
 * чтение, список с фильтрами parent/status/mine/tags/q и конверт-пагинацией, merge-patch
 * (RFC 7396: absent — не менять; title/description NOT NULL — явный null → 422; params
 * иммутабельны → 422 rule=immutable), подзадачи, дерево (BFS-структура реестра), история
 * (курсор-пара {@code (created_at, id)}), комментарии (append-only; author из JWT или
 * NULL + агент-пометка) и зависимости (K.3: DFS-валидация циклов реестром → 422
 * dependency-invalid, после ребра — wake «blocked-changed»).
 *
 * <p>{@code owner} — пользователь JWT (в M2 все входы API пользовательские, D-59; ветка
 * «наследование от родителя» для агентских инициаторов появится с M3-инструментами).
 * {@code webhookUrl} — capability-URL, только в WAIT_WEBHOOK-состоянии. limit без значения
 * — {@code harness.limits.page}.</p>
 */
@RestController
@RequiredArgsConstructor
public class TasksController implements TasksApi {

    private final TaskRegistry tasks;
    private final WorkflowRegistry workflows;
    private final AppUserDirectory users;
    private final Caller caller;
    private final LimitsProperties limits;
    private final WebhookSignatureVerifier signatureVerifier;
    private final WebhookProperties webhookProperties;

    @Override
    public ResponseEntity<TaskDto> createTask(CreateTaskRequest createTaskRequest) {
        return create(null, createTaskRequest);
    }

    @Override
    public ResponseEntity<TaskDto> createSubtask(UUID id, CreateTaskRequest createTaskRequest) {
        tasks.get(id);
        return create(id, createTaskRequest);
    }

    /** Общая ветка создания (POST /tasks и POST /tasks/{id}/subtasks): пин ревизии + 201 + Location. */
    private ResponseEntity<TaskDto> create(UUID parentTaskId, CreateTaskRequest request) {
        rejectBlank("/title", request.getTitle());
        rejectBlank("/workflowKey", request.getWorkflowKey());
        WorkflowRevision revision = resolveRevision(request.getWorkflowKey(), request.getRev());

        UUID userId = caller.userId();
        Task task = tasks.createTask(new TaskRegistry.CreateTaskCommand(
                revision.id(),
                request.getTitle(),
                request.getDescription(),
                userId,
                userId,
                parentTaskId,
                request.getParams(),
                request.getTags()
        ));
        return ResponseEntity
                .created(URI.create("/api/v1/tasks/" + task.id()))
                .body(enricherOf(List.of(task)).toDto(task));
    }

    @Override
    public ResponseEntity<TaskPage> listTasks(UUID parent, TaskStatusProjection status,
                                              Boolean mine, List<String> tags,
                                              String q, String cursor, Integer limit) {
        TaskRegistry.TaskSearchResult result;
        try {
            result = tasks.list(new TaskRegistry.TaskSearchCriteria(
                    parent,
                    status == null ? null : TaskStatus.valueOf(status.name()),
                    Boolean.TRUE.equals(mine) ? caller.userId() : null,
                    tags,
                    q,
                    cursor,
                    clampLimit(limit)
            ));
        } catch (InvalidCursorException e) {
            throw cursorInvalid();
        }
        // K-4: батч-резолв usernames и выжимок ревизий — один SQL на запрос, не N+1
        TaskDtoEnricher enricher = enricherOf(result.items());
        List<TaskDto> items = result.items().stream()
                .map(task -> enricher.toDto(task))
                .toList();
        TaskPage page = new TaskPage(items);
        if (result.nextCursor() != null) {
            page.setNextCursor(result.nextCursor());
        }
        return ResponseEntity.ok(page);
    }

    @Override
    public ResponseEntity<TaskDto> getTask(UUID id) {
        return ResponseEntity.ok(enricherOf(List.of(tasks.get(id))).toDto(tasks.get(id)));
    }

    @Override
    public ResponseEntity<TaskDto> patchTask(UUID id, UpdateTaskRequest updateTaskRequest) {
        tasks.get(id);
        JsonNode patch = MergePatchBodyContext.current();
        if (patch != null && patch.has("params")) {
            throw new ApiValidationException(List.of(new ApiValidationException.ValidationError(
                    "/params", "immutable", "params иммутабельны после создания задачи")));
        }

        String title = null;
        String description = null;
        List<String> tags = null;
        if (patch != null) {
            JsonNode titleNode = patch.get("title");
            if (titleNode != null) {
                if (titleNode.isNull()) {
                    throw new ApiValidationException(List.of(new ApiValidationException.ValidationError(
                            "/title", "required", "title не может быть удалён")));
                }
                if (titleNode.asText().isBlank()) {
                    throw new ApiValidationException(List.of(new ApiValidationException.ValidationError(
                            "/title", "blank", "title не может быть пустой строкой")));
                }
                title = titleNode.asText();
            }
            JsonNode descriptionNode = patch.get("description");
            if (descriptionNode != null) {
                // description NOT NULL (data-model §4): merge-patch null — удаление члена — отклоняемо
                if (descriptionNode.isNull()) {
                    throw new ApiValidationException(List.of(new ApiValidationException.ValidationError(
                            "/description", "required", "description не может быть удалён")));
                }
                description = descriptionNode.asText();
            }
            JsonNode tagsNode = patch.get("tags");
            if (tagsNode != null) {
                if (tagsNode.isNull()) {
                    // null — очистка тегов
                    tags = List.of();
                } else if (!tagsNode.isArray()) {
                    // K-5: не-массивный tags — явный 422 с rule=array-required
                    throw new ApiValidationException(List.of(new ApiValidationException.ValidationError(
                            "/tags", "array-required", "tags должен быть массивом строк")));
                } else {
                    tags = updateTaskRequest.getTags();
                }
            }
        }

        Task patched = tasks.patch(id, new TaskRegistry.TaskPatch(title, description, tags));
        return ResponseEntity.ok(enricherOf(List.of(patched)).toDto(patched));
    }

    @Override
    public ResponseEntity<Void> addTaskDependencies(UUID id,
                                                    AddTaskDependenciesRequest addTaskDependenciesRequest) {
        tasks.get(id);
        // K-1: вся пачка — одна атомарная операция реестра (self-loop/цикл/неизвестный
        // blocker → 422 dependency-invalid без частичного коммита; 404 task-not-found —
        // только для блокируемой задачи {id} пути)
        tasks.addDependencies(id, addTaskDependenciesRequest.getBlockedBy());
        return ResponseEntity
                .created(URI.create("/api/v1/tasks/" + id))
                .build();
    }

    @Override
    public ResponseEntity<Void> removeTaskDependency(UUID id, UUID blockerId) {
        tasks.get(id);
        tasks.removeDependency(blockerId, id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<TransitionPage> listTaskHistory(UUID id, String since, Integer limit) {
        TaskRegistry.HistoryPage result;
        try {
            result = tasks.getHistory(id, since, clampLimit(limit));
        } catch (InvalidCursorException e) {
            throw cursorInvalid();
        }
        TransitionPage page = new TransitionPage(result.items().stream()
                .map(ApiMappers::toDto)
                .toList());
        if (result.nextCursor() != null) {
            page.setNextCursor(result.nextCursor());
        }
        return ResponseEntity.ok(page);
    }

    @Override
    public ResponseEntity<CommentDto> addTaskComment(UUID id, AddTaskCommentRequest addTaskCommentRequest) {
        rejectBlank("/body", addTaskCommentRequest.getBody());
        // author: USER — по JWT; агентский — NULL + агент-пометка (M2: все входы API — USER);
        // один запрос батч-резолва usernames (K-4)
        UUID authorId = caller.instructionSource() == InstructionSource.USER ? caller.userId() : null;
        Comment comment = tasks.addComment(id, authorId, addTaskCommentRequest.getBody());
        String authorUsername = authorId == null ? null
                : users.usernames(List.of(authorId)).get(authorId);
        return ResponseEntity
                .created(URI.create("/api/v1/tasks/" + id + "/comments/" + comment.id()))
                .body(ApiMappers.toDto(comment, authorUsername));
    }

    @Override
    public ResponseEntity<CommentPage> listTaskComments(UUID id, String cursor, Integer limit) {
        TaskRegistry.CommentPage result;
        try {
            result = tasks.listComments(id, cursor, clampLimit(limit));
        } catch (InvalidCursorException e) {
            throw cursorInvalid();
        }
        List<UUID> authorIds = result.items().stream()
                .map(Comment::authorUserId)
                .filter(Objects::nonNull)
                .toList();
        Map<UUID, String> usernames = users.usernames(authorIds);
        CommentPage page = new CommentPage(result.items().stream()
                .map(comment -> ApiMappers.toDto(comment,
                        comment.authorUserId() == null ? null : usernames.get(comment.authorUserId())))
                .toList());
        if (result.nextCursor() != null) {
            page.setNextCursor(result.nextCursor());
        }
        return ResponseEntity.ok(page);
    }

    @Override
    public ResponseEntity<TaskTreePage> getTaskTree(UUID id, Integer depth) {
        TaskTreeNode root = tasks.getTree(id, depth);
        return ResponseEntity.ok(new TaskTreePage(List.of(ApiMappers.toNode(root))));
    }

    // ---------------------------------------------------------------- вспомогательное

    /** Пин ревизии: rev из запроса или latestRev; неизвестный ключ/ревизия → 404 workflow-not-found. */
    private WorkflowRevision resolveRevision(String workflowKey, Integer rev) {
        Workflow workflow = workflows.get(workflowKey);
        int resolved = rev == null ? workflow.latestRev() : rev;
        return workflows.getRevision(workflowKey, resolved);
    }

    /**
     * K-4: пакетное обогащение страницы задач. Usernames (owner+author) резолвятся одним
     * запросом через {@link AppUserDirectory#usernames} ({@code WHERE id IN}), выжимки пиннутых
     * ревизий — одним запросом через {@link WorkflowRegistry#revisionSummaries}; дальше DTO
     * собираются без обращений к БД.
     */
    private TaskDtoEnricher enricherOf(List<Task> tasksPage) {
        List<UUID> userIds = new ArrayList<>();
        List<UUID> revisionIds = new ArrayList<>();
        for (Task task : tasksPage) {
            userIds.add(task.ownerUserId());
            if (task.authorUserId() != null) {
                userIds.add(task.authorUserId());
            }
            revisionIds.add(task.workflowRevisionId());
        }
        Map<UUID, String> usernames = users.usernames(userIds);
        Map<UUID, RevisionSummary> revisions = workflows.revisionSummaries(revisionIds);
        return task -> {
            RevisionSummary revision = revisions.get(task.workflowRevisionId());
            if (revision == null) {
                throw new WorkflowRevisionNotFoundException(
                        "Ревизия workflow %s не найдена".formatted(task.workflowRevisionId()));
            }
            WorkflowRef workflowRef = new WorkflowRef(revision.workflowKey(), revision.rev());
            String authorUsername = task.authorUserId() == null
                    ? null : usernames.get(task.authorUserId());
            return ApiMappers.toDto(task, usernames.get(task.ownerUserId()), authorUsername,
                    workflowRef, webhookUrlOf(task));
        };
    }

    /** Собирает DTO задачи из предвычисленных usernames/выжимок ревизий (+capability-URL вебхука). */
    private interface TaskDtoEnricher {
        TaskDto toDto(Task task);
    }

    /** Capability-URL вебхука — только пока задача в WAIT_WEBHOOK-состоянии (api-contracts §4.4). */
    private URI webhookUrlOf(Task task) {
        if (task.currentStateKind() != TaskStateKind.WAIT_WEBHOOK) {
            return null;
        }
        String token = signatureVerifier.expectedToken("task", task.id());
        return URI.create("%s/api/webhooks/tasks/%s/%s"
                .formatted(webhookProperties.baseUrl(), task.id(), token));
    }

    private int clampLimit(Integer limit) {
        int max = limits.page();
        return limit == null ? max : Math.min(limit, max);
    }

    private static ApiValidationException cursorInvalid() {
        // Битый/подделанный opaque-курсор — ошибка клиента, не сервера (E-J-3)
        return new ApiValidationException(List.of(new ApiValidationException.ValidationError(
                "/cursor", "cursor", "Курсор страницы некорректен")));
    }

    private static void rejectBlank(String pointer, String value) {
        if (value != null && value.isBlank()) {
            throw new ApiValidationException(List.of(new ApiValidationException.ValidationError(
                    pointer, "blank", "Значение не может быть пустой строкой")));
        }
    }
}
