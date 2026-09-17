package se.rocketscien.harness.execution;

/**
 * Результат исполнения команды в контейнере: объединённый stdout/stderr, exit code, признак
 * превышения таймаута (команда обёрнута в {@code timeout}, exit 124 → {@code timedOut}) и признак
 * усечения захвата вывода по пределу из конфига (C-J-1).
 */
public record ContainerExecResult(String output, int exitCode, boolean timedOut, boolean truncated) {
}
