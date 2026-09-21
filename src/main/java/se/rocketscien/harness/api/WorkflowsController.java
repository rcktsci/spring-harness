package se.rocketscien.harness.api;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import se.rocketscien.harness.api.gen.WorkflowsApi;
import se.rocketscien.harness.api.gen.model.CreateWorkflowRequest;
import se.rocketscien.harness.api.gen.model.CreateWorkflowRevisionRequest;
import se.rocketscien.harness.api.gen.model.WorkflowDto;
import se.rocketscien.harness.api.gen.model.WorkflowPage;
import se.rocketscien.harness.api.gen.model.WorkflowRevisionDto;
import se.rocketscien.harness.config.LimitsProperties;
import se.rocketscien.harness.workflow.InvalidCursorException;
import se.rocketscien.harness.workflow.WorkflowRegistry;
import se.rocketscien.harness.workflow.WorkflowRegistry.WorkflowRevision;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Workflow и ревизии (api-contracts §4.2, спека workflow-engine; реализация — пачка L:
 * закрывает последний стаб переходного кода, план удаления — apply-notes «Пачка D.2»,
 * задача L.5): создание (rev=1, 422 graph-invalid с errors[]; дубликат key — 422
 * validation-failed rule=key-unique, отклонение dev D-пачки №5), чтение метаданных со
 * списком ревизий, новая иммутабельная ревизия (rev = prev + 1; идущие задачи остаются
 * на старой), граф конкретной ревизии. {@code owner} — пользователь JWT.
 */
@RestController
@RequiredArgsConstructor
public class WorkflowsController implements WorkflowsApi {

    private final WorkflowRegistry workflows;
    private final Caller caller;
    private final LimitsProperties limits;

    @Override
    public ResponseEntity<WorkflowPage> listWorkflows(String cursor, Integer limit) {
        WorkflowRegistry.WorkflowSearchResult result;
        try {
            result = workflows.list(new WorkflowRegistry.WorkflowSearchCriteria(
                    cursor, clampLimit(limit)));
        } catch (InvalidCursorException e) {
            throw cursorInvalid();
        }
        WorkflowPage page = new WorkflowPage(result.items().stream()
                .map(ApiMappers::toDto)
                .toList());
        if (result.nextCursor() != null) {
            page.setNextCursor(result.nextCursor());
        }
        return ResponseEntity.ok(page);
    }

    @Override
    public ResponseEntity<WorkflowDto> createWorkflow(CreateWorkflowRequest createWorkflowRequest) {
        rejectBlank("/key", createWorkflowRequest.getKey());
        rejectBlank("/name", createWorkflowRequest.getName());
        WorkflowRevision revision = workflows.createWorkflow(
                caller.userId(),
                createWorkflowRequest.getKey(),
                createWorkflowRequest.getName(),
                ApiMappers.toGraphMap(createWorkflowRequest.getGraph()),
                createWorkflowRequest.getStartState());
        WorkflowDto dto = ApiMappers.toDto(workflows.get(createWorkflowRequest.getKey()));
        dto.addRevisionsItem(ApiMappers.toSummary(revision));
        return ResponseEntity
                .created(URI.create("/api/v1/workflows/" + createWorkflowRequest.getKey()))
                .body(dto);
    }

    @Override
    public ResponseEntity<WorkflowDto> getWorkflow(String key) {
        WorkflowDto dto = ApiMappers.toDto(workflows.get(key));
        workflows.revisions(key).stream()
                .map(ApiMappers::toSummary)
                .forEach(dto::addRevisionsItem);
        return ResponseEntity.ok(dto);
    }

    @Override
    public ResponseEntity<WorkflowRevisionDto> createWorkflowRevision(
            String key, CreateWorkflowRevisionRequest createWorkflowRevisionRequest) {
        rejectBlank("/startState", createWorkflowRevisionRequest.getStartState());
        WorkflowRevision revision = workflows.newRevision(
                key,
                ApiMappers.toGraphMap(createWorkflowRevisionRequest.getGraph()),
                createWorkflowRevisionRequest.getStartState());
        return ResponseEntity
                .created(URI.create("/api/v1/workflows/" + key + "/revisions/" + revision.rev()))
                .body(ApiMappers.toDto(key, revision));
    }

    @Override
    public ResponseEntity<WorkflowRevisionDto> getWorkflowRevision(String key, Integer rev) {
        return ResponseEntity.ok(ApiMappers.toDto(key, workflows.getRevision(key, rev)));
    }

    // ---------------------------------------------------------------- вспомогательное

    private int clampLimit(Integer limit) {
        int max = limits.page();
        return limit == null ? max : Math.min(limit, max);
    }

    private static ApiValidationException cursorInvalid() {
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
