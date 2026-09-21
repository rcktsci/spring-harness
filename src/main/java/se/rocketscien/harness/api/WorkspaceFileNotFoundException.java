package se.rocketscien.harness.api;

/**
 * Файл назначения отсутствует в серверном workspace сессии (api-contracts §8) →
 * {@code 404 file-not-found}.
 */
public class WorkspaceFileNotFoundException extends RuntimeException {

    public WorkspaceFileNotFoundException(String message) {
        super(message);
    }
}
