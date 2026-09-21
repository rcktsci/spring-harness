package se.rocketscien.harness.api;

import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import se.rocketscien.harness.api.gen.WorkspaceApi;

import java.util.UUID;

/**
 * GET /sessions/{id}/workspace/files (api-contracts §8) — wiring contract-first (пачка 1).
 * Реализация (canonical-path-гвард, safe-лист расширений, pre-stat 413, streaming) —
 * пачка U; до неё метод отдаёт {@code 501 not-implemented}.
 */
@RestController
public class WorkspaceFilesController implements WorkspaceApi {

    private static final String STUB = "1.1: stub — реализация скачивания workspace-файлов в пачке U";

    @Override
    public ResponseEntity<Resource> downloadWorkspaceFile(String path, UUID id) {
        throw new ApiNotImplementedException(STUB);
    }
}
