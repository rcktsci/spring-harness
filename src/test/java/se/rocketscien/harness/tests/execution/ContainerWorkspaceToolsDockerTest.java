package se.rocketscien.harness.tests.execution;

import lombok.SneakyThrows;
import java.nio.charset.StandardCharsets;


import se.rocketscien.harness.execution.ContainerWorkspaceTools;
import se.rocketscien.harness.execution.ToolResult;
import se.rocketscien.harness.execution.ToolStatus;
import se.rocketscien.harness.execution.WorkspaceContainerManager;
import se.rocketscien.harness.execution.WorkspaceTools;
import se.rocketscien.harness.session.Session;

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

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 6.3: нативные инструменты в реальном per-session Docker-контейнере (read/write/edit/glob/grep/bash),
 * containment-гварды, лимит вывода и единый отчёт исполнения.
 */
class ContainerWorkspaceToolsDockerTest {

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
    @SneakyThrows
    static void tearDownClass() {
        dockerClient.close();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.removeContainer(sessionId);
        }
    }

    @Test
    void writeThenOverwriteThenReadRoundtrip() {
        WorkspaceTools tools = tools(DataSize.ofMegabytes(1));

        assertThat(tools.writeFile(sessionId, "dir/note.txt", "hello").output()).isEqualTo("created");
        assertThat(tools.writeFile(sessionId, "dir/note.txt", "bye").output()).isEqualTo("overwritten");

        ToolResult read = tools.readFile(sessionId, "dir/note.txt", null, null);
        assertThat(read.status()).isEqualTo(ToolStatus.OK);
        assertThat(read.output()).isEqualTo("bye");
    }

    @Test
    void readFileAppliesOffsetAndLimit() {
        WorkspaceTools tools = tools(DataSize.ofMegabytes(1));
        tools.writeFile(sessionId, "slice.txt", "abcdefghij");

        ToolResult read = tools.readFile(sessionId, "slice.txt", 2, 4);

        assertThat(read.status()).isEqualTo(ToolStatus.OK);
        assertThat(read.output()).isEqualTo("cdef");
    }

    @Test
    void editFileRequiresSingleOccurrence() {
        WorkspaceTools tools = tools(DataSize.ofMegabytes(1));
        tools.writeFile(sessionId, "edit.txt", "foo foo");

        ToolResult ambiguous = tools.editFile(sessionId, "edit.txt", "foo", "bar", false);
        assertThat(ambiguous.status()).isEqualTo(ToolStatus.ERROR);
        assertThat(ambiguous.output()).isEqualTo("ambiguous");
        assertThat(tools.readFile(sessionId, "edit.txt", null, null).output()).isEqualTo("foo foo");

        ToolResult notFound = tools.editFile(sessionId, "edit.txt", "zzz", "bar", false);
        assertThat(notFound.output()).isEqualTo("not-found");

        ToolResult replaced = tools.editFile(sessionId, "edit.txt", "foo", "bar", true);
        assertThat(replaced.status()).isEqualTo(ToolStatus.OK);
        assertThat(tools.readFile(sessionId, "edit.txt", null, null).output()).isEqualTo("bar bar");
    }

    @Test
    void globListsMatchingPaths() {
        WorkspaceTools tools = tools(DataSize.ofMegabytes(1));
        tools.writeFile(sessionId, "src/a.py", "print(1)");
        tools.writeFile(sessionId, "src/b.txt", "text");

        ToolResult result = tools.glob(sessionId, "**/*.py");

        assertThat(result.status()).isEqualTo(ToolStatus.OK);
        assertThat(result.output()).contains("src/a.py").doesNotContain("src/b.txt");
    }

    @Test
    void grepFindsPatternRespectingInclude() {
        WorkspaceTools tools = tools(DataSize.ofMegabytes(1));
        tools.writeFile(sessionId, "a.java", "needle in java");
        tools.writeFile(sessionId, "b.txt", "needle in text");

        ToolResult result = tools.grep(sessionId, "needle", "*.java");

        assertThat(result.status()).isEqualTo(ToolStatus.OK);
        assertThat(result.output()).contains("a.java").doesNotContain("b.txt");
    }

    @Test
    void bashNonZeroExitIsNotToolError() {
        WorkspaceTools tools = tools(DataSize.ofMegabytes(1));

        ToolResult result = tools.bash(sessionId, "exit 3", null, null);

        assertThat(result.status()).isEqualTo(ToolStatus.OK);
        assertThat(result.exitCode()).isEqualTo(3);
    }

    @Test
    void bashTimeoutIsReported() {
        WorkspaceTools tools = tools(DataSize.ofMegabytes(1));

        ToolResult result = tools.bash(sessionId, "sleep 5", Duration.ofSeconds(1), null);

        assertThat(result.status()).isEqualTo(ToolStatus.OK);
        assertThat(result.timedOut()).isTrue();
    }

    @Test
    void bashRunsInRequestedWorkingDirectory() {
        WorkspaceTools tools = tools(DataSize.ofMegabytes(1));
        tools.writeFile(sessionId, "sub/marker.txt", "x");

        ToolResult result = tools.bash(sessionId, "ls", null, "sub");

        assertThat(result.status()).isEqualTo(ToolStatus.OK);
        assertThat(result.output()).contains("marker.txt");
    }

    @Test
    void outputOverLimitIsTruncated() {
        WorkspaceTools tools = tools(DataSize.ofBytes(16));
        tools.writeFile(sessionId, "big.txt", "0123456789abcdefghijklmnop");

        ToolResult result = tools.readFile(sessionId, "big.txt", null, null);

        assertThat(result.truncated()).isTrue();
        assertThat(result.output()).endsWith("[truncated]");
    }

    @Test
    void signalExitCodeOfLiveContainerIsNotReportedAsLost() {
        WorkspaceTools tools = tools(DataSize.ofMegabytes(1));

        ToolResult result = tools.bash(sessionId, "exit 137", null, null);

        assertThat(result.status()).isEqualTo(ToolStatus.OK);
        assertThat(result.exitCode()).isEqualTo(137);
    }

    @Test
    void bashHugeOutputIsBoundedNotAccumulated() {
        DataSize outputLimit = DataSize.ofKilobytes(8);
        WorkspaceTools tools = tools(outputLimit);

        ToolResult result = tools.bash(sessionId, "seq 1 1000000", null, null);

        assertThat(result.status()).isEqualTo(ToolStatus.OK);
        assertThat(result.truncated()).isTrue();
        assertThat(result.output().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo((int) outputLimit.toBytes() + 16);
    }

    @Test
    void largeUnicodeContentRoundtripsAcrossChunks() {
        WorkspaceTools tools = tools(DataSize.ofMegabytes(1));
        String content = "привет-😀-100%-\\t\\n".repeat(8000);

        assertThat(tools.writeFile(sessionId, "big/unicode.txt", content).output()).isEqualTo("created");

        ToolResult read = tools.readFile(sessionId, "big/unicode.txt", null, null);
        assertThat(read.status()).isEqualTo(ToolStatus.OK);
        assertThat(read.output()).isEqualTo(content);
    }

    @Test
    void invalidGlobPatternYieldsError() {
        WorkspaceTools tools = tools(DataSize.ofMegabytes(1));

        ToolResult result = tools.glob(sessionId, "[");

        assertThat(result.status()).isEqualTo(ToolStatus.ERROR);
        assertThat(result.output()).contains("invalid pattern");
    }

    @Test
    void invalidIncludePatternYieldsError() {
        WorkspaceTools tools = tools(DataSize.ofMegabytes(1));

        ToolResult result = tools.grep(sessionId, "needle", "[");

        assertThat(result.status()).isEqualTo(ToolStatus.ERROR);
        assertThat(result.output()).contains("invalid include pattern");
    }

    private WorkspaceTools tools(DataSize outputLimit) {
        DockerProperties docker = new DockerProperties(
                helperImage,
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
                DataSize.ofMegabytes(1), outputLimit, DataSize.ofKilobytes(4),
                Duration.ofSeconds(2), Duration.ofSeconds(5), 100);
        manager = new WorkspaceContainerManager(dockerClient, docker, limits);
        return new ContainerWorkspaceTools(manager, docker, limits, new IdGenerator());
    }
}
