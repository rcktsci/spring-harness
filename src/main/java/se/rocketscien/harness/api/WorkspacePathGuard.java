package se.rocketscien.harness.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.config.DockerProperties;
import se.rocketscien.harness.config.WorkspaceDownloadProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.UUID;

/**
 * Точечный canonical-path-гвард скачивания серверного workspace (api-contracts §8, D-72):
 * посегментная проверка symlink'ов ({@link Files#isSymbolicLink}), канонический резолв каждого
 * сегмента, containment результата в корне сессии {@code workspaceRoot/{sessionId}}, запрет
 * {@code .}/{@code ..}/нулевых сегментов и абсолютных путей. Symlink запрещён в любом компоненте,
 * включая ведущий внутрь корня (NOFOLLOW-семантика). Остаточный TOCTOU между проверкой и
 * открытием — принятый риск (потребитель — аутентифицированный SSO-пользователь).
 *
 * <p>Слои безопасности в глубину не плодятся: единственный публичный вход в файлы workspace —
 * этот эндпоинт, внутренние инструменты изолированы контейнером (D-30).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkspacePathGuard {

    private final DockerProperties dockerProperties;
    private final WorkspaceDownloadProperties downloadProperties;

    /**
     * Резолвит относительный POSIX-путь внутри workspace сессии в существующий обычный файл.
     *
     * @throws WorkspacePathInvalidException абсолютный путь, {@code .}/{@code ..}/пустой сегмент,
     *                                       symlink, каталог или выход за корень (422 path-invalid)
     * @throws WorkspaceFileNotFoundException сегмент/файл не существует (404 file-not-found)
     */
    public Path resolve(UUID sessionId, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new WorkspacePathInvalidException("Путь не задан");
        }
        Path root = sessionRoot(sessionId);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceFileNotFoundException("Workspace-каталог сессии не найден");
        }
        Path rootReal = canonical(root);

        String path = rawPath.trim().replace('\\', '/');
        if (path.startsWith("/")) {
            throw new WorkspacePathInvalidException("Абсолютный путь запрещён");
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new WorkspacePathInvalidException("Недопустимый сегмент пути: '" + segment + "'");
            }
        }
        Path current = rootReal;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            Path next = current.resolve(segment);
            if (Files.isSymbolicLink(next)) {
                throw new WorkspacePathInvalidException("Symlink в пути запрещён");
            }
            if (!Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspaceFileNotFoundException("Файл не найден: " + segment);
            }
            Path nextReal = canonical(next);
            if (!nextReal.startsWith(rootReal)) {
                throw new WorkspacePathInvalidException("Путь выходит за пределы workspace");
            }
            if (i == segments.length - 1) {
                if (Files.isDirectory(nextReal, LinkOption.NOFOLLOW_LINKS)) {
                    throw new WorkspacePathInvalidException("Запрошен каталог, не файл");
                }
                if (!Files.isRegularFile(nextReal, LinkOption.NOFOLLOW_LINKS)) {
                    throw new WorkspacePathInvalidException("Не обычный файл");
                }
                return nextReal;
            }
            current = nextReal;
        }
        throw new WorkspacePathInvalidException("Путь не задан");
    }

    /** Safe-лист расширений (сравнение case-insensitive, api-contracts §8) → 422 extension-not-allowed. */
    public void checkExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        boolean allowed = !extension.isEmpty() && downloadProperties.allowExtensions().stream()
                .map(candidate -> candidate.toLowerCase(Locale.ROOT))
                .anyMatch(extension::equals);
        if (!allowed) {
            throw new ExtensionNotAllowedException("Расширение не разрешено: " + name);
        }
    }

    /** Pre-stat до отдачи заголовков (api-contracts §8): превышение лимита → 413 payload-too-large. */
    public long ensureWithinLimit(Path file) {
        long max = downloadProperties.maxBytes().toBytes();
        try {
            long size = Files.size(file);
            if (size > max) {
                throw new PayloadTooLargeException("Файл больше лимита " + max + " байт");
            }
            return size;
        } catch (IOException e) {
            throw new WorkspaceFileNotFoundException("Файл недоступен: " + e.getMessage());
        }
    }

    private Path sessionRoot(UUID sessionId) {
        return Paths.get(dockerProperties.workspaceRoot(), sessionId.toString()).normalize();
    }

    private Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new WorkspaceFileNotFoundException("Путь недоступен: " + e.getMessage());
        }
    }
}
