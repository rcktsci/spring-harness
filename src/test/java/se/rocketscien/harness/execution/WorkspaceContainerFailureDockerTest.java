package se.rocketscien.harness.execution;

import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.config.DockerProperties;
import se.rocketscien.harness.config.LimitsProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 6.4: отказы контейнера — смерть во время вызова даёт {@code LOST} с причиной,
 * ошибка подготовки/монтирования workspace даёт {@code ERROR} с причиной (реальный Docker).
 */
class WorkspaceContainerFailureDockerTest {

    private static DockerClient dockerClient;
    private static String helperImage;

    @TempDir
    static Path workspaceRoot;

    private final UUID sessionId = UUID.randomUUID();
    private WorkspaceContainerManager manager;

    @BeforeAll
    static void setUpClass() {
        dockerClient = DockerTestSupport.dockerClient();
        helperImage = DockerTestSupport.helperImage();
    }

    @AfterAll
    static void tearDownClass() throws Exception {
        dockerClient.close();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.removeContainer(sessionId);
        }
    }

    @Test
    void containerDeathDuringBashYieldsLost() throws Exception {
        WorkspaceTools tools = tools(workspaceRoot.resolve("ws"));
        String containerId = manager.ensureContainer(sessionId);

        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Future<ToolResult> running =
                    pool.submit(() -> tools.bash(sessionId, "sleep 30", Duration.ofSeconds(25), null));
            Thread.sleep(1000);
            dockerClient.killContainerCmd(containerId).exec();

            ToolResult result = running.get(30, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(result.status()).as("result=%s", result).isEqualTo(ToolStatus.LOST);
            assertThat(result.output()).contains("died");
        }
    }

    @Test
    void deadContainerIsRecreatedAndCallCompletes() {
        WorkspaceTools tools = tools(workspaceRoot.resolve("ws"));
        tools.writeFile(sessionId, "kept.txt", "value");
        String dead = manager.ensureContainer(sessionId);
        dockerClient.killContainerCmd(dead).exec();

        ToolResult result = tools.readFile(sessionId, "kept.txt", null, null);

        // C-J-3: остановленный контейнер удаляется и пересоздаётся, а не кэшируется как вечный LOST.
        assertThat(result.status()).isEqualTo(ToolStatus.OK);
        assertThat(result.output()).isEqualTo("value");
        assertThat(manager.ensureContainer(sessionId)).isNotEqualTo(dead);
    }

    @Test
    void workspaceMountFailureYieldsError() throws Exception {
        Path notADirectory = Files.createFile(workspaceRoot.resolve("blocked"));
        WorkspaceTools tools = tools(notADirectory);

        ToolResult result = tools.writeFile(sessionId, "file.txt", "x");

        assertThat(result.status()).isEqualTo(ToolStatus.ERROR);
        assertThat(result.output()).containsIgnoringCase("workspace");
    }

    private WorkspaceTools tools(Path root) {
        DockerProperties docker = new DockerProperties(
                helperImage,
                root.toString(),
                500_000_000L,
                DataSize.ofMegabytes(64),
                "none",
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                Duration.ofMillis(50),
                Duration.ofSeconds(3),
                60_000,
                3,
                Duration.ofMillis(50));
        LimitsProperties limits = new LimitsProperties(
                DataSize.ofMegabytes(1), DataSize.ofMegabytes(1), DataSize.ofKilobytes(4),
                Duration.ofSeconds(2), Duration.ofSeconds(5));
        manager = new WorkspaceContainerManager(dockerClient, docker, limits);
        return new ContainerWorkspaceTools(manager, docker, limits, new IdGenerator());
    }
}
