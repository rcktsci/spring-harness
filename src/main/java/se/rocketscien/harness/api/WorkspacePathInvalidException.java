package se.rocketscien.harness.api;

/**
 * Путь скачивания не прошёл canonical-гвард (api-contracts §8, D-72): абсолютный путь,
 * {@code ..}-эскейп, нулевой сегмент, symlink (в т.ч. ведущий внутрь корня) или каталог →
 * {@code 422 path-invalid}.
 */
public class WorkspacePathInvalidException extends RuntimeException {

    public WorkspacePathInvalidException(String message) {
        super(message);
    }
}
