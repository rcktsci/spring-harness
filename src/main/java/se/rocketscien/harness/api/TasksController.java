package se.rocketscien.harness.api;

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

import java.util.List;
import java.util.UUID;

/**
 * Задачи (api-contracts §4.1) — wiring D.2. Реализация — пачки H/I/K
 * (TaskRegistry, движок, REST-логика).
 */
@RestController
public class TasksController implements TasksApi {

    private static final String STUB = "D.2: stub — реализация в пачках H/I/J/K/L";

    @Override
    public ResponseEntity<TaskDto> createTask(CreateTaskRequest createTaskRequest) {
        throw new ApiNotImplementedException(STUB);
    }

    @Override
    public ResponseEntity<TaskPage> listTasks(UUID parent, TaskStatusProjection status,
                                              Boolean mine, List<String> tags,
                                              String q, String cursor, Integer limit) {
        throw new ApiNotImplementedException(STUB);
    }

    @Override
    public ResponseEntity<TaskDto> getTask(UUID id) {
        throw new ApiNotImplementedException(STUB);
    }

    @Override
    public ResponseEntity<TaskDto> patchTask(UUID id, UpdateTaskRequest updateTaskRequest) {
        throw new ApiNotImplementedException(STUB);
    }

    @Override
    public ResponseEntity<TaskDto> createSubtask(UUID id, CreateTaskRequest createTaskRequest) {
        throw new ApiNotImplementedException(STUB);
    }

    @Override
    public ResponseEntity<Void> addTaskDependencies(UUID id,
                                                    AddTaskDependenciesRequest addTaskDependenciesRequest) {
        throw new ApiNotImplementedException(STUB);
    }

    @Override
    public ResponseEntity<Void> removeTaskDependency(UUID id, UUID blockerId) {
        throw new ApiNotImplementedException(STUB);
    }

    @Override
    public ResponseEntity<TransitionPage> listTaskHistory(UUID id, String since, Integer limit) {
        throw new ApiNotImplementedException(STUB);
    }

    @Override
    public ResponseEntity<CommentDto> addTaskComment(UUID id, AddTaskCommentRequest addTaskCommentRequest) {
        throw new ApiNotImplementedException(STUB);
    }

    @Override
    public ResponseEntity<CommentPage> listTaskComments(UUID id, String cursor, Integer limit) {
        throw new ApiNotImplementedException(STUB);
    }

    @Override
    public ResponseEntity<TaskTreePage> getTaskTree(UUID id, Integer depth) {
        throw new ApiNotImplementedException(STUB);
    }
}
