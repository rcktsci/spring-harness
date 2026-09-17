package se.rocketscien.harness.execution;

import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import se.rocketscien.harness.config.DockerProperties;
import se.rocketscien.harness.config.LimitsProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 6.2: lifecycle per-session контейнера (ленивое создание, bind-mount, лимиты, сеть off,
 * pull-политика «локальный образ приоритетен») на реальном Docker.
 */
class WorkspaceContainerManagerDockerTest {

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
    void createsContainerLazilyOnFirstUse() {
        manager = manager(helperImage);

        assertThat(manager.isRunning(sessionId)).isFalse();

        String containerId = manager.ensureContainer(sessionId);

        assertThat(containerId).isNotBlank();
        assertThat(manager.isRunning(sessionId)).isTrue();
        assertThat(containerId).isEqualTo(manager.ensureContainer(sessionId));
    }

    @Test
    void mountsWorkspaceHostDirectoryAtWorkspace() throws Exception {
        manager = manager(helperImage);
        Path sessionDir = manager.workspaceDir(sessionId).toAbsolutePath();
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolve("from-host.txt"), "host-content");

        manager.ensureContainer(sessionId);

        ContainerExecResult result = manager.exec(sessionId,
                java.util.List.of("cat", "/workspace/from-host.txt"), null, Duration.ofSeconds(15));

        assertThat(result.exitCode()).isZero();
        assertThat(result.output().trim()).isEqualTo("host-content");
    }

    @Test
    void appliesResourceLimitsAndDisablesNetwork() {
        manager = manager(helperImage);
        manager.ensureContainer(sessionId);

        var inspect = dockerClient.inspectContainerCmd(containerName()).exec();

        assertThat(inspect.getHostConfig().getMemory()).isEqualTo(64L * 1024 * 1024);
        assertThat(inspect.getHostConfig().getNanoCPUs()).isEqualTo(500_000_000L);
        assertThat(inspect.getHostConfig().getNetworkMode()).isEqualTo("none");
    }

    @Test
    void prefersLocalImageWhenRegistryIsUnreachable() {
        String unreachable = "127.0.0.1:1/harness-helper";
        dockerClient.tagImageCmd(helperImage, unreachable, "test").exec();
        manager = manager(unreachable + ":test");

        String containerId = manager.ensureContainer(sessionId);

        assertThat(containerId).isNotBlank();
        assertThat(manager.isRunning(sessionId)).isTrue();
    }

    @Test
    void recreatesStoppedContainerInsteadOfKeepingLost() {
        manager = manager(helperImage);
        String first = manager.ensureContainer(sessionId);
        dockerClient.killContainerCmd(first).exec();

        String recreated = manager.ensureContainer(sessionId);

        assertThat(recreated).isNotEqualTo(first);
        assertThat(manager.isRunning(sessionId)).isTrue();
    }

    @Test
    void removesContainerOnCleanup() {
        manager = manager(helperImage);
        manager.ensureContainer(sessionId);

        manager.removeContainer(sessionId);

        assertThat(manager.isRunning(sessionId)).isFalse();
    }

    private WorkspaceContainerManager manager(String image) {
        DockerProperties docker = new DockerProperties(
                image,
                workspaceRoot.resolve("ws").toString(),
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
        return new WorkspaceContainerManager(dockerClient, docker, limits);
    }

    private String containerName() {
        return "harness-" + sessionId;
    }
}
