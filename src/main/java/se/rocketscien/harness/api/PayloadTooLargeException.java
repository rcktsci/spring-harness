package se.rocketscien.harness.api;

/**
 * Полезная нагрузка превышает лимит → {@code 413 payload-too-large} (api-contracts §0, §8).
 * Бросается {@link PayloadSizeFilter}-ом (тело запроса > {@code harness.limits.body}) и
 * pre-stat-проверкой размера скачиваемого workspace-файла
 * ({@code harness.workspace.download.max-bytes}, {@link WorkspacePathGuard#ensureWithinLimit}).
 */
public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(String message) {
        super(message);
    }
}
