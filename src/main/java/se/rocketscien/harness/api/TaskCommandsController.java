package se.rocketscien.harness.api;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import se.rocketscien.harness.api.gen.TaskCommandsApi;
import se.rocketscien.harness.api.gen.model.SuspendTaskRequest;
import se.rocketscien.harness.task.TaskRegistry;

import java.util.List;
import java.util.UUID;

/**
 * Команды задачи — suspend/resume/stop (api-contracts §4.1, RPC-стиль, пачка K.2).
 * Suspend — идемпотентный флаг ({@code cascade} — поддерево), кадры {@code task.status}
 * эмитирует реестр после коммита. Resume — снятие флага нетерминальной задачи +
 * task-wake (переоценка WAIT_TASKS / bootstrap AGENT-state), терминальная → 409
 * {@code task-already-terminal}. Stop — всегда каскадный (параметра cascade нет,
 * D-54): CAS поддерева в {@code '$CANCELLED'} + отмена Turn'ов STATE-сессий
 * ({@code TaskWakeDispatcher.handleStop} по wake после коммита) → 202.
 */
@RestController
@RequiredArgsConstructor
public class TaskCommandsController implements TaskCommandsApi {

    private final TaskRegistry tasks;

    @Override
    public ResponseEntity<Void> suspendTask(UUID id, SuspendTaskRequest suspendTaskRequest) {
        // GLM nit: cascade — required по спеке; отсутствующее тело/поле → явный 422 rule=required
        if (suspendTaskRequest == null || suspendTaskRequest.getCascade() == null) {
            throw new ApiValidationException(List.of(new ApiValidationException.ValidationError(
                    "/cascade", "required", "cascade обязателен")));
        }
        tasks.suspend(id, suspendTaskRequest.getCascade());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> resumeTask(UUID id) {
        tasks.resume(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Object> stopTask(UUID id) {
        tasks.stop(id);
        return ResponseEntity.accepted().build();
    }
}
