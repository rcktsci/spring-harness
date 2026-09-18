package se.rocketscien.harness.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import se.rocketscien.harness.api.gen.TaskCommandsApi;
import se.rocketscien.harness.api.gen.model.SuspendTaskRequest;

import java.util.UUID;

/**
 * Команды задачи — suspend/resume/stop (api-contracts §4.1, RPC-стиль) — wiring D.2.
 * Реализация — пачки I/K (движок, каскадный stop, resume-wake).
 */
@RestController
public class TaskCommandsController implements TaskCommandsApi {

    private static final String STUB = "D.2: stub — реализация в пачках H/I/J/K/L";

    @Override
    public ResponseEntity<Void> suspendTask(UUID id, SuspendTaskRequest suspendTaskRequest) {
        throw new ApiNotImplementedException(STUB);
    }

    @Override
    public ResponseEntity<Void> resumeTask(UUID id) {
        throw new ApiNotImplementedException(STUB);
    }

    @Override
    public ResponseEntity<Object> stopTask(UUID id) {
        throw new ApiNotImplementedException(STUB);
    }
}
