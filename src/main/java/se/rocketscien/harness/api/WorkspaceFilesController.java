package se.rocketscien.harness.api;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import se.rocketscien.harness.api.gen.WorkspaceApi;
import se.rocketscien.harness.session.SessionNotFoundException;
import se.rocketscien.harness.session.SessionStore;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;

/**
 * GET /sessions/{id}/workspace/files (api-contracts §8, D-72): скачивание файла из **серверного**
 * workspace сессии {@code workspaceRoot/{sessionId}}. Порядок проверок: существование сессии
 * (404 session-not-found) → canonical-гвард пути (422 path-invalid / 404 file-not-found) →
 * safe-лист расширений (422 extension-not-allowed) → pre-stat размера до отдачи заголовков
 * (413 payload-too-large). Отдача — потоком ({@link InputStreamResource}) с {@code NOFOLLOW_LINKS}
 * на финальный компонент, без загрузки файла в память. Для CLIENT-сессий серверный workspace
 * может быть пуст — файлы у клиента (задокументированное ограничение): такие запросы отдают 404.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class WorkspaceFilesController implements WorkspaceApi {

    private final SessionStore sessionStore;
    private final WorkspacePathGuard guard;

    @Override
    @SneakyThrows
    public ResponseEntity<Resource> downloadWorkspaceFile(String path, UUID id) {
        sessionStore.findSession(id).orElseThrow(() ->
                new SessionNotFoundException("Сессия %s не найдена".formatted(id)));
        Path file = guard.resolve(id, path);
        guard.checkExtension(file);
        long size = guard.ensureWithinLimit(file);
        InputStream stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(size)
                .body(new InputStreamResource(stream));
    }
}
