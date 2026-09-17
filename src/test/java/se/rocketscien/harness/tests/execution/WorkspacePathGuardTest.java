package se.rocketscien.harness.tests.execution;

import se.rocketscien.harness.execution.WorkspacePathException;
import se.rocketscien.harness.execution.WorkspacePathGuard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Containment-гварды (specs/workspace-tools): относительные пути, запрет {@code ..} и абсолютных,
 * symlink-обход за пределы корня — ошибка.
 */
class WorkspacePathGuardTest {

    @TempDir
    Path workspaceDir;

    @Test
    void resolvesRelativePathInsideWorkspace() {
        Path resolved = WorkspacePathGuard.resolveHostPath(workspaceDir, "src/app.py");

        assertThat(resolved).isEqualTo(workspaceDir.resolve("src/app.py").toAbsolutePath().normalize());
        assertThat(WorkspacePathGuard.toContainerPath(workspaceDir, resolved)).isEqualTo("/workspace/src/app.py");
    }

    @Test
    void rejectsParentTraversal() {
        assertThatThrownBy(() -> WorkspacePathGuard.resolveHostPath(workspaceDir, "../secret"))
                .isInstanceOf(WorkspacePathException.class)
                .hasMessageContaining("escapes workspace");
    }

    @Test
    void rejectsAbsolutePath() {
        assertThatThrownBy(() -> WorkspacePathGuard.resolveHostPath(workspaceDir, "/etc/passwd"))
                .isInstanceOf(WorkspacePathException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    void rejectsEmptyPath() {
        assertThatThrownBy(() -> WorkspacePathGuard.resolveHostPath(workspaceDir, " "))
                .isInstanceOf(WorkspacePathException.class);
    }

    @Test
    void rejectsNulByteInsteadOfThrowingRuntime() {
        assertThatThrownBy(() -> WorkspacePathGuard.resolveHostPath(workspaceDir, "bad\u0000name"))
                .isInstanceOf(WorkspacePathException.class)
                .hasMessageContaining("invalid path");
    }

    @Test
    void rejectsSymlinkEscapingWorkspace() throws Exception {
        Path outside = Files.createTempFile("outside", ".txt");
        try {
            Files.createSymbolicLink(workspaceDir.resolve("link.txt"), outside);
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "symlinks are not supported in this environment: " + e.getMessage());
        }

        assertThatThrownBy(() -> WorkspacePathGuard.resolveHostPath(workspaceDir, "link.txt"))
                .isInstanceOf(WorkspacePathException.class)
                .hasMessageContaining("symlink");
    }
}
