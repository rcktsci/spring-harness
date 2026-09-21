package se.rocketscien.harness.api;

/**
 * Расширение файла вне safe-листа {@code harness.workspace.download.allow-extensions}
 * (api-contracts §8) → {@code 422 extension-not-allowed}.
 */
public class ExtensionNotAllowedException extends RuntimeException {

    public ExtensionNotAllowedException(String message) {
        super(message);
    }
}
