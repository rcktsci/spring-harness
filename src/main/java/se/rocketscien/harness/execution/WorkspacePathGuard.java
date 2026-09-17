package se.rocketscien.harness.execution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Containment-гварды workspace (specs/workspace-tools, agent-tools §4): только относительные пути,
 * резолв от корня workspace сессии; {@code ..} и абсолютные пути запрещены; если путь (или его
 * существующий предок) разрешается через symlink наружу — ошибка. Хост-ФС не затрагивается.
 */
public final class WorkspacePathGuard {

    private WorkspacePathGuard() {
    }

    public static Path resolveHostPath(Path workspaceDir, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new WorkspacePathException("path is required");
        }
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            throw new WorkspacePathException("absolute paths are not allowed: " + relativePath);
        }
        Path requested;
        try {
            requested = Paths.get(relativePath);
        } catch (InvalidPathException e) {
            // например, NUL-байт: необработанный runtime падал бы из Turn'а вместо ToolResult.error
            // (C-J-6 #5).
            throw new WorkspacePathException("invalid path: " + e.getMessage());
        }
        if (requested.isAbsolute()) {
            throw new WorkspacePathException("absolute paths are not allowed: " + relativePath);
        }
        Path root = workspaceDir.toAbsolutePath().normalize();
        Path resolved = root.resolve(requested).normalize();
        if (!resolved.startsWith(root)) {
            throw new WorkspacePathException("path escapes workspace: " + relativePath);
        }
        guardSymlinks(root, resolved, relativePath);
        return resolved;
    }

    public static String toContainerPath(Path workspaceDir, Path hostPath) {
        return "/workspace/" + workspaceDir.toAbsolutePath().normalize()
                .relativize(hostPath.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private static void guardSymlinks(Path root, Path resolved, String relativePath) {
        try {
            Path realRoot = realPath(root);
            Path realResolved = realPath(resolved);
            if (!realResolved.startsWith(realRoot)) {
                throw new WorkspacePathException("path escapes workspace via symlink: " + relativePath);
            }
        } catch (IOException e) {
            throw new WorkspacePathException("cannot resolve path: " + relativePath);
        }
    }

    /**
     * Канонизирует путь даже когда он ещё не существует: ближайший существующий предок
     * разрешается ({@code toRealPath}, снимает symlink и 8.3-алиасы), несуществующий остаток
     * приклеивается обратно. Так сравнение root/resolved корректно до создания файлов.
     */
    private static Path realPath(Path path) throws IOException {
        Path existing = path;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return path.toAbsolutePath().normalize();
        }
        return existing.toRealPath().resolve(existing.relativize(path)).normalize();
    }
}
