package se.rocketscien.harness.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import se.rocketscien.harness.api.gen.WorkflowsApi;
import se.rocketscien.harness.api.gen.model.CreateWorkflowRequest;
import se.rocketscien.harness.api.gen.model.CreateWorkflowRevisionRequest;
import se.rocketscien.harness.api.gen.model.WorkflowDto;
import se.rocketscien.harness.api.gen.model.WorkflowPage;
import se.rocketscien.harness.api.gen.model.WorkflowRevisionDto;

/**
 * Workflow и ревизии (api-contracts §4.2) — wiring D.2. Реализация — пачка H
 * (WorkflowRegistry, валидатор графа).
 */
@RestController
public class WorkflowsController implements WorkflowsApi {

    private static final String STUB = "D.2: stub — реализация в пачках H/I/J/K/L";

    @Override
    public ResponseEntity<WorkflowPage> listWorkflows(String cursor, Integer limit) {
        throw new ApiNotImplementedException(STUB);
    }

    @Override
    public ResponseEntity<WorkflowDto> createWorkflow(CreateWorkflowRequest createWorkflowRequest) {
        throw new ApiNotImplementedException(STUB);
    }

    @Override
    public ResponseEntity<WorkflowDto> getWorkflow(String key) {
        throw new ApiNotImplementedException(STUB);
    }

    @Override
    public ResponseEntity<WorkflowRevisionDto> createWorkflowRevision(
            String key, CreateWorkflowRevisionRequest createWorkflowRevisionRequest) {
        throw new ApiNotImplementedException(STUB);
    }

    @Override
    public ResponseEntity<WorkflowRevisionDto> getWorkflowRevision(String key, Integer rev) {
        throw new ApiNotImplementedException(STUB);
    }
}
