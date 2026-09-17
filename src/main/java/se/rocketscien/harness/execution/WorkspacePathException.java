package se.rocketscien.harness.execution;

/**
 * Путь недопустим: абсолютный, выходит за корень workspace, содержит {@code ..} или разрешается
 * через symlink наружу. Файловая система хоста/контейнера не затрагивается.
 */
public class WorkspacePathException extends RuntimeException {

    public WorkspacePathException(String message) {
        super(message);
    }
}
