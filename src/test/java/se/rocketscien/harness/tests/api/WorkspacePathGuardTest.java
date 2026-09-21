package se.rocketscien.harness.tests.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import se.rocketscien.harness.api.ExtensionNotAllowedException;
import se.rocketscien.harness.api.PayloadTooLargeException;
import se.rocketscien.harness.api.WorkspaceFileNotFoundException;
import se.rocketscien.harness.api.WorkspacePathGuard;
import se.rocketscien.harness.api.WorkspacePathInvalidException;
import se.rocketscien.harness.config.DockerProperties;
import se.rocketscien.harness.config.WorkspaceDownloadProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M4 T.3: unit canonical-path-гварда (api-contracts §8, D-72) — {@code ..}-эскейп, symlink
 * наружу/внутрь (NOFOLLOW), каталог, абсолютный путь, нулевые сегменты, недопустимые символы,
 * отсутствие файла, case-insensitive safe-лист и pre-stat 413.
 *
 * <p>Symlink-кейсы идут под {@code assumeTrue(symlinksSupported())}: на Linux-рантайме/CI
 * выполняются, на Windows-dev без привилегий (нет Developer Mode) — скипаются; отдельный
 * {@code @EnabledOnOs} не нужен, т.к. на Windows с Developer Mode symlink'и создаются и тесты
 * исполняются. Ключевая NOFOLLOW-проверка обязана гоняться на Linux (приёмка/CI).</p>
 */
class WorkspacePathGuardTest {

    private static final List<String> ALLOWED = List.of("txt", "md", "json", "java", "toml");

    @TempDir
    Path root;

    private UUID sessionId;
    private Path sessionDir;
    private WorkspacePathGuard guard;

    @BeforeEach
    void setUp() throws IOException {
        sessionId = UUID.randomUUID();
        sessionDir = root.resolve(sessionId.toString());
        Files.createDirectories(sessionDir);
        guard = new WorkspacePathGuard(docker(root), new WorkspaceDownloadProperties(DataSize.ofKilobytes(1), ALLOWED));
    }

    @Test
    void resolvesExistingFileInsideWorkspace() throws IOException {
        Files.writeString(sessionDir.resolve("note.txt"), "hello");

        Path resolved = guard.resolve(sessionId, "note.txt");

        assertThat(resolved).isEqualTo(sessionDir.resolve("note.txt").toRealPath());
        assertThat(Files.readString(resolved)).isEqualTo("hello");
    }

    @Test
    void resolvesNestedFile() throws IOException {
        Files.createDirectories(sessionDir.resolve("a/b"));
        Files.writeString(sessionDir.resolve("a/b/deep.md"), "x");

        assertThat(guard.resolve(sessionId, "a/b/deep.md").getFileName().toString()).isEqualTo("deep.md");
    }

    @Test
    void rejectsParentEscape() {
        assertThatThrownBy(() -> guard.resolve(sessionId, "../outside.txt"))
                .isInstanceOf(WorkspacePathInvalidException.class);
        assertThatThrownBy(() -> guard.resolve(sessionId, "a/../../outside.txt"))
                .isInstanceOf(WorkspacePathInvalidException.class);
    }

    @Test
    void rejectsAbsolutePath() {
        assertThatThrownBy(() -> guard.resolve(sessionId, "/etc/passwd"))
                .isInstanceOf(WorkspacePathInvalidException.class);
    }

    @Test
    void rejectsEmptySegment() {
        assertThatThrownBy(() -> guard.resolve(sessionId, "a//b.txt"))
                .isInstanceOf(WorkspacePathInvalidException.class);
        assertThatThrownBy(() -> guard.resolve(sessionId, "a/"))
                .isInstanceOf(WorkspacePathInvalidException.class);
    }

    @Test
    void rejectsInvalidPathCharacters() {
        // NUL недопустим в имени на всех платформах → InvalidPathException → 422 path-invalid (U-2).
        assertThatThrownBy(() -> guard.resolve(sessionId, "bad\u0000name.txt"))
                .isInstanceOf(WorkspacePathInvalidException.class);
    }

    @Test
    void rejectsDirectory() throws IOException {
        Files.createDirectories(sessionDir.resolve("sub"));

        assertThatThrownBy(() -> guard.resolve(sessionId, "sub"))
                .isInstanceOf(WorkspacePathInvalidException.class);
    }

    @Test
    void rejectsMissingFile() {
        assertThatThrownBy(() -> guard.resolve(sessionId, "ghost.txt"))
                .isInstanceOf(WorkspaceFileNotFoundException.class);
    }

    @Test
    void rejectsMissingSessionWorkspace() {
        assertThatThrownBy(() -> guard.resolve(UUID.randomUUID(), "note.txt"))
                .isInstanceOf(WorkspaceFileNotFoundException.class);
    }

    @Test
    void rejectsSymlinkEscapingWorkspace() throws IOException {
        assumeTrue(symlinksSupported(), "symlink не поддержан ФС/привилегиями");
        Path outside = Files.writeString(root.resolve("outside.txt"), "secret");
        Files.createSymbolicLink(sessionDir.resolve("escape.txt"), outside);

        assertThatThrownBy(() -> guard.resolve(sessionId, "escape.txt"))
                .isInstanceOf(WorkspacePathInvalidException.class);
    }

    @Test
    void rejectsSymlinkInsideWorkspace() throws IOException {
        assumeTrue(symlinksSupported(), "symlink не поддержан ФС/привилегиями");
        Files.writeString(sessionDir.resolve("note.txt"), "hello");
        Files.createSymbolicLink(sessionDir.resolve("alias.txt"), sessionDir.resolve("note.txt"));

        assertThatThrownBy(() -> guard.resolve(sessionId, "alias.txt"))
                .isInstanceOf(WorkspacePathInvalidException.class);
    }

    @Test
    void rejectsSymlinkedSessionRoot() throws IOException {
        assumeTrue(symlinksSupported(), "symlink не поддержан ФС/привилегиями");
        Path otherSessionDir = Files.createDirectories(root.resolve(UUID.randomUUID().toString()));
        Files.writeString(otherSessionDir.resolve("note.txt"), "hello");
        Files.delete(sessionDir);
        Files.createSymbolicLink(sessionDir, otherSessionDir);

        assertThatThrownBy(() -> guard.resolve(sessionId, "note.txt"))
                .isInstanceOf(WorkspacePathInvalidException.class);
    }

    @Test
    void extensionCheckIsCaseInsensitive() {
        guard.checkExtension(sessionDir.resolve("Report.MD"));
        guard.checkExtension(sessionDir.resolve("data.TOML"));

        assertThatThrownBy(() -> guard.checkExtension(sessionDir.resolve("data.bin")))
                .isInstanceOf(ExtensionNotAllowedException.class);
        assertThatThrownBy(() -> guard.checkExtension(sessionDir.resolve("README")))
                .isInstanceOf(ExtensionNotAllowedException.class);
    }

    @Test
    void preStatRejectsOversizeFile() throws IOException {
        Files.write(sessionDir.resolve("small.txt"), "ok".getBytes(StandardCharsets.UTF_8));
        Files.write(sessionDir.resolve("big.txt"), new byte[2048]);

        Path small = guard.resolve(sessionId, "small.txt");
        Path big = guard.resolve(sessionId, "big.txt");

        assertThat(guard.ensureWithinLimit(small)).isEqualTo(2L);
        assertThatThrownBy(() -> guard.ensureWithinLimit(big)).isInstanceOf(PayloadTooLargeException.class);
    }

    private boolean symlinksSupported() {
        Path target = root.resolve("probe-target.txt");
        Path link = root.resolve("probe-link.txt");
        try {
            Files.writeString(target, "x");
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    private static DockerProperties docker(Path workspaceRoot) {
        return new DockerProperties(
                "harness-helper:test",
                workspaceRoot.toString(),
                1_000_000_000L,
                DataSize.ofMegabytes(512),
                "none",
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                Duration.ofMillis(50),
                Duration.ofSeconds(3),
                60_000,
                3,
                Duration.ofSeconds(2));
    }
}
