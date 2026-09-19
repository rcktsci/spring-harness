package se.rocketscien.harness.tests.execution.task;

import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.execution.ToolResult;
import se.rocketscien.harness.execution.ToolStatus;
import se.rocketscien.harness.execution.WorkspaceContainerManager;
import se.rocketscien.harness.execution.WorkspaceTools;
import se.rocketscien.harness.execution.impl.BashStateExecutor;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.TransitionKind;
import se.rocketscien.harness.tests.execution.DockerTestSupport;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * BASH-состояние в реальном task-контейнере (D-50, specs/workspace-tools «Task-контейнер»):
 * исполнение через {@code WorkspaceTools.executeBash} в {@code harness-task-<taskId>} с
 * workspace {@code task-<taskId>} (изоляция от сессий), парсинг исходов — write/bash/read
 * ({@code wc}), exit≠0 → ERROR, таймаут состояния → TIMEOUT (процесс убит). Контейнер
 * одноразовый: после любого исхода его нет (M-2); kill контейнера посреди исполнения —
 * ERROR-переход с {@code exitCode=null} в истории (без NPE).
 */
class BashStateExecutorTaskContainerTest extends BaseApplicationTest {

    @Autowired
    private BashStateExecutor bashExecutor;

    @Autowired
    private WorkspaceTools workspaceTools;

    @Autowired
    private WorkspaceContainerManager containers;

    @Autowired
    private TaskRegistry taskRegistry;

    @Autowired
    private se.rocketscien.harness.execution.impl.TaskEngine taskEngine;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private se.rocketscien.harness.common.IdGenerator idGenerator;

    @BeforeAll
    static void ensureHelperImage() {
        DockerTestSupport.helperImage();
    }

    @Test
    void successScriptWritesIntoTaskWorkspaceAndReturnsNext() throws IOException {
        Task task = newBashTask();
        try {
            BashStateExecutor.Outcome outcome = bashExecutor.execute(
                    task.id(), "run", "printf hello > out.txt && wc -c out.txt", null,
                    task.stateAttempt());

            assertThat(outcome.kind()).isEqualTo(TransitionKind.NEXT);
            assertThat(outcome.reason())
                    .containsEntry("exitCode", 0)
                    .containsEntry("attempt", 1)
                    .containsEntry("kind", "bash");
            assertThat(String.valueOf(outcome.reason().get("output"))).contains("5 out.txt");
            // bind-mount workspace задачи: файл на хосте в task-namespace
            Path hostFile = workspaceRoot().resolve("task-" + task.id()).resolve("out.txt");
            assertThat(hostFile).exists();
            assertThat(Files.readString(hostFile)).isEqualTo("hello");
            // M-2: task-контейнер одноразовый — после исхода контейнера нет
            assertThat(containers.isRunning(WorkspaceContainerManager.TASK_NAMESPACE, task.id())).isFalse();
        } finally {
            removeTaskContainer(task.id());
        }
    }

    @Test
    void readAfterWriteRoundtripInTaskContainer() {
        Task task = newBashTask();
        try {
            bashExecutor.execute(task.id(), "run", "printf abcdefghij > data.txt", null, 1);
            ToolResult read = workspaceTools.executeBash(task.id(), "cat data.txt", null, null);
            assertThat(read.status()).isEqualTo(ToolStatus.OK);
            assertThat(read.output()).contains("abcdefghij");
            // второй прогон того же состояния — новая попытка поверх существующего файла
            BashStateExecutor.Outcome rerun = bashExecutor.execute(
                    task.id(), "run", "printf XY > data.txt && cat data.txt", null, 2);
            assertThat(rerun.kind()).isEqualTo(TransitionKind.NEXT);
            assertThat(rerun.reason()).containsEntry("attempt", 2);
            assertThat(String.valueOf(rerun.reason().get("output"))).contains("XY");
        } finally {
            removeTaskContainer(task.id());
        }
    }

    @Test
    void nonZeroExitMeansErrorOutcome() {
        Task task = newBashTask();
        try {
            BashStateExecutor.Outcome outcome = bashExecutor.execute(
                    task.id(), "run", "echo отказ >&2; exit 3", null, task.stateAttempt());

            assertThat(outcome.kind()).isEqualTo(TransitionKind.ERROR);
            assertThat(outcome.reason()).containsEntry("exitCode", 3);
            assertThat(String.valueOf(outcome.reason().get("output"))).contains("отказ");
        } finally {
            removeTaskContainer(task.id());
        }
    }

    @Test
    void stateTimeoutMeansTimeoutOutcomeAndKillsProcess() {
        Task task = TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.bashGraph("PT1S"), "run");
        try {
            long started = System.nanoTime();
            BashStateExecutor.Outcome outcome = bashExecutor.execute(
                    task.id(), "run", "sleep 10 && echo never", null, task.stateAttempt());
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertThat(outcome.kind()).isEqualTo(TransitionKind.TIMEOUT);
            assertThat(elapsedMs).isLessThan(9_000);
        } finally {
            removeTaskContainer(task.id());
        }
    }

    @Test
    void containerKillMidRunYieldsErrorTransitionWithNullExitCode() throws Exception {
        Task task = newBashTask();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<BashStateExecutor.Outcome> execution = pool.submit(() ->
                    bashExecutor.execute(task.id(), "run", "sleep 30", null, task.stateAttempt()));
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    containers.isRunning(WorkspaceContainerManager.TASK_NAMESPACE, task.id()));
            // kill контейнера посреди исполнения: exec умирает без exit-кода (LOST)
            containers.removeContainer(WorkspaceContainerManager.TASK_NAMESPACE, task.id());

            BashStateExecutor.Outcome outcome = execution.get(30, TimeUnit.SECONDS);
            assertThat(outcome.kind()).isEqualTo(TransitionKind.ERROR);
            assertThat(outcome.reason()).containsEntry("exitCode", null);

            // ERROR-переход с null в reason не падает (Map.copyOf-безопасность истории)
            taskEngine.processTaskTransition(task.id(), "run", "failed",
                    TransitionKind.ERROR, outcome.reason());
            Map<String, Object> reason = taskRegistry
                    .getHistory(task.id(), null, null).items().getFirst().reason();
            assertThat(reason).containsEntry("exitCode", null);
        } finally {
            pool.shutdownNow();
            removeTaskContainer(task.id());
        }
    }

    private Task newBashTask() {
        return TaskEngineTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                TaskEngineTestFixtures.bashGraph(null), "run");
    }

    private Path workspaceRoot() {
        return Path.of(System.getProperty("java.io.tmpdir"), "harness-it-workspaces");
    }

    private void removeTaskContainer(UUID taskId) {
        containers.removeContainer(WorkspaceContainerManager.TASK_NAMESPACE, taskId);
    }
}
