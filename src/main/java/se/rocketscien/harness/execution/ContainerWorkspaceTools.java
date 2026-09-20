package se.rocketscien.harness.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.config.DockerProperties;
import se.rocketscien.harness.config.LimitsProperties;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Нативные инструменты в per-session контейнере (agent-tools §1, specs/workspace-tools): файловые
 * операции и bash исполняются серверно утилитами helper-образа; хост-ФС не трогается. Пути
 * передаются в контейнер как есть — изоляция обеспечивается самим контейнером; вывод ограничен
 * конфигом с маркером {@code truncated}; non-zero exit для bash — не ошибка инструмента.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContainerWorkspaceTools implements WorkspaceTools {

    private static final String MOUNT = "/workspace";

    /** bash объявлен async-capable (M3, D-60): окно → ASYNC_ACCEPTED → поздний TOOL_RESULT. */
    private static final Set<String> ASYNC_CAPABLE = Set.of("bash");

    private final WorkspaceContainerManager containers;
    private final DockerProperties dockerProperties;
    private final LimitsProperties limits;
    private final IdGenerator idGenerator;

    @Override
    public Set<String> asyncCapabilities() {
        return ASYNC_CAPABLE;
    }

    @Override
    public ToolResult readFile(UUID sessionId, String path, Integer offset, Integer limit) {
        String tool = "read_file";
        try {
            String containerPath = resolve(path);
            ContainerExecResult result = exec(sessionId, "cat -- \"$1\"", List.of(containerPath));
            if (result.exitCode() != 0) {
                return ToolResult.error(callId(), tool, firstLine(result.output()));
            }
            byte[] bytes = result.output().getBytes(StandardCharsets.UTF_8);
            int from = offset == null ? 0 : Math.min(Math.max(offset, 0), bytes.length);
            int to = limit == null ? bytes.length : Math.min(from + Math.max(limit, 0), bytes.length);
            Limited limited = truncate(new String(bytes, from, to - from, StandardCharsets.UTF_8), result.truncated());
            return ToolResult.ok(callId(), tool, limited.text(), null, limited.truncated(), null);        } catch (WorkspaceContainerException e) {
            return containerFailure(tool, e);
        }
    }

    @Override
    public ToolResult writeFile(UUID sessionId, String path, String content) {
        String tool = "write_file";
        try {
            String containerPath = resolve(path);
            ContainerExecResult exists = exec(sessionId, "test -e \"$1\"", List.of(containerPath));
            ContainerExecResult prepared = exec(sessionId,
                    "mkdir -p -- \"$(dirname -- \"$1\")\"", List.of(containerPath));
            if (prepared.exitCode() != 0) {
                return ToolResult.error(callId(), tool, firstLine(prepared.output()));
            }
            ContainerExecResult written = writeContainerFile(sessionId, containerPath, content);
            if (written.exitCode() != 0) {
                return ToolResult.error(callId(), tool, firstLine(written.output()));
            }
            return ToolResult.ok(callId(), tool, exists.exitCode() == 0 ? "overwritten" : "created");        } catch (WorkspaceContainerException e) {
            return containerFailure(tool, e);
        }
    }

    @Override
    public ToolResult editFile(UUID sessionId, String path, String oldString, String newString, boolean replaceAll) {
        String tool = "edit_file";
        try {
            String containerPath = resolve(path);
            ContainerExecResult read = exec(sessionId, "cat -- \"$1\"", List.of(containerPath));
            if (read.exitCode() != 0) {
                return ToolResult.error(callId(), tool, firstLine(read.output()));
            }
            String content = read.output();
            int occurrences = countOccurrences(content, oldString);
            if (occurrences == 0) {
                return ToolResult.error(callId(), tool, "not-found");
            }
            if (!replaceAll && occurrences > 1) {
                return ToolResult.error(callId(), tool, "ambiguous");
            }
            String edited = replaceAll
                    ? content.replace(oldString, newString)
                    : content.replaceFirst(Pattern.quote(oldString),
                            Matcher.quoteReplacement(newString));
            ContainerExecResult written = writeContainerFile(sessionId, containerPath, edited);
            if (written.exitCode() != 0) {
                return ToolResult.error(callId(), tool, firstLine(written.output()));
            }
            return ToolResult.ok(callId(), tool, replaceAll ? "replaced-all" : "replaced");        } catch (WorkspaceContainerException e) {
            return containerFailure(tool, e);
        }
    }

    @Override
    public ToolResult glob(UUID sessionId, String pattern) {
        String tool = "glob";
        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            containers.ensureContainer(sessionId);
            ContainerExecResult result = exec(sessionId, "find " + MOUNT + " -mindepth 1", List.of());
            if (result.exitCode() != 0) {
                return ToolResult.error(callId(), tool, firstLine(result.output()));
            }
            String matches = result.output().lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .map(ContainerWorkspaceTools::relative)
                    .filter(relative -> matcher.matches(Path.of(relative)))
                    .collect(Collectors.joining("\n"));
            return ToolResult.ok(callId(), tool, matches);
        } catch (PatternSyntaxException e) {
            return ToolResult.error(callId(), tool, "invalid pattern: " + pattern);        } catch (WorkspaceContainerException e) {
            return containerFailure(tool, e);
        }
    }

    @Override
    public ToolResult grep(UUID sessionId, String pattern, String include) {
        String tool = "grep";
        try {
            PathMatcher includeMatcher = include == null || include.isBlank()
                    ? null
                    : FileSystems.getDefault().getPathMatcher("glob:" + include);
            containers.ensureContainer(sessionId);
            ContainerExecResult result = exec(sessionId, "grep -rn -e \"$1\" -- " + MOUNT, List.of(pattern));
            if (result.exitCode() != 0 && result.exitCode() != 1) {
                return ToolResult.error(callId(), tool, firstLine(result.output()));
            }
            String output = result.output().lines()
                    .filter(line -> line.startsWith(MOUNT + "/"))
                    .filter(line -> includeMatcher == null || includeMatcher.matches(Path.of(basename(line))))
                    .map(line -> {
                        int colon = line.indexOf(':', MOUNT.length() + 1);
                        return colon < 0 ? relative(line) : relative(line.substring(0, colon)) + line.substring(colon);
                    })
                    .collect(Collectors.joining("\n"));
            Limited limited = truncate(output, result.truncated());
            return ToolResult.ok(callId(), tool, limited.text(), null, limited.truncated(), null);
        } catch (PatternSyntaxException e) {
            return ToolResult.error(callId(), tool, "invalid include pattern: " + include);        } catch (WorkspaceContainerException e) {
            return containerFailure(tool, e);
        }
    }

    @Override
    public ToolResult bash(UUID sessionId, String command, Duration timeout, String cwd) {
        return bash(sessionId, command, timeout, cwd, null);
    }

    /**
     * Отменяемый bash (execution-model §6): команда исполняется в своей сессии процесса
     * ({@code setsid}) с PID-файлом; область отмены регистрирует прерыватель — убийство группы
     * процессов в контейнере, что разблокирует ожидание. Отменённый вызов (до или во время)
     * возвращает синтетический CANCELLED; обычный путь идентичен {@link #bash(UUID, String,
     * Duration, String)}.
     */
    @Override
    public ToolResult bash(UUID sessionId, String command, Duration timeout, String cwd,
                           TurnCancellation cancellation) {
        return bashInContainer("", sessionId, command, timeout, cwd, cancellation);
    }

    /**
     * Bash-состояние задачи (D-50): исполнение в отдельном task-контейнере
     * {@code harness-task-<taskId>} с workspace из {@code workspaceRoot/task-<taskId>} —
     * контейнеры и workspaces сессий не затрагиваются. Скрипт состояния обязан быть
     * идемпотентным (повторный прогон после crash — новая попытка, side-effect возможен дважды).
     */
    @Override
    public ToolResult executeBash(UUID taskId, String script, Duration timeout, String cwd) {
        return bashInContainer(WorkspaceContainerManager.TASK_NAMESPACE, taskId, script, timeout, cwd, null);
    }

    /**
     * Общий контур bash (сессии — namespace "", задачи — {@code task-}): команда исполняется
     * в своей сессии процесса ({@code setsid}) с PID-файлом; {@code timeout -s TERM} режет
     * превышение (exit 124 → {@code timedOut}); область отмены регистрирует прерыватель —
     * убийство группы процессов в контейнере (execution-model §6).
     */
    private ToolResult bashInContainer(String namespace, UUID id, String command, Duration timeout,
                                       String cwd, TurnCancellation cancellation) {
        String tool = "bash";
        if (cancellation != null && cancellation.isCancelled()) {
            return ToolResult.cancelled(callId(), tool, "отменено пользователем");
        }
        String callId = callId();
        try {
            String containerCwd = cwd == null || cwd.isBlank()
                    ? MOUNT
                    : resolve(cwd);
            Duration effective = effectiveBashTimeout(timeout);
            long seconds = Math.max(1, effective.toSeconds());
            Duration execDeadline = effective.plusSeconds(5);
            String pidFile = "/tmp/harness-exec-" + callId + ".pid";
            // Docker exec запускает процесс уже в собственной сессии/группе: PID внешнего sh
            // и есть PGID для убийства дерева (timeout + команда) при отмене.
            List<String> cmd = List.of(
                    "sh", "-c",
                    "echo $$ > \"$4\"; cd -- \"$1\";"
                            + " timeout -s TERM \"$2\" sh -c \"$3\"; rc=$?; rm -f -- \"$4\"; exit $rc",
                    "harness", containerCwd, Long.toString(seconds), command, pidFile);

            // Прерыватель живёт дольше вызова exec: stop может прийти раньше, чем PID-файл
            // появится — тогда добивание продолжается поллингом (граница — таймаут вызова).
            AutoCloseable interruptor = cancellation == null
                    ? null
                    : cancellation.registerInterrupt(() -> killUntilDead(namespace, id, pidFile, execDeadline));
            ContainerExecResult result;
            try {
                result = containers.exec(namespace, id, cmd, null, execDeadline, cancellation);
            } finally {
                if (interruptor != null) {
                    try {
                        interruptor.close();
                    } catch (Exception e) {
                        log.debug("Снятие прерывателя не удалось: {}", e.getMessage());
                    }
                }
            }
            if (cancellation != null && cancellation.isCancelled()) {
                return ToolResult.cancelled(callId, tool, "отменено пользователем");
            }
            Limited limited = truncate(result.output(), result.truncated());
            return ToolResult.ok(callId, tool, limited.text(), result.exitCode(),
                    limited.truncated(), result.timedOut());
        } catch (WorkspaceContainerException e) {
            if (cancellation != null && cancellation.isCancelled()) {
                return ToolResult.cancelled(callId, tool, "отменено пользователем");
            }
            return containerFailure(tool, e);
        }
    }

    /**
     * Прерыватель: SIGTERM всей группе процессов команды (PID-файл пишет сам exec-процесс —
     * лидер группы: docker exec даёт каждому процессу свою сессию); синтаксис
     * {@code kill -TERM -<pgid>} — busybox kill не принимает {@code --} перед отрицательным
     * PID. Повторяется с интервалом конфига: PID-файл может появиться позже команды stop;
     * граница повторов — таймаут вызова.
     */
    private void killUntilDead(String namespace, UUID id, String pidFile, Duration deadline) {
        long end = System.nanoTime() + deadline.toNanos();
        while (System.nanoTime() < end) {
            if (tryKillProcessGroup(namespace, id, pidFile)) {
                return;
            }
            try {
                Thread.sleep(dockerProperties.statePollInterval().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Прерывание bash-процесса в {} не успело за {}", containers.containerName(namespace, id), deadline);
    }

    private boolean tryKillProcessGroup(String namespace, UUID id, String pidFile) {
        try {
            ContainerExecResult result = containers.exec(namespace, id,
                    List.of("sh", "-c",
                            "pid=$(cat \"$1\" 2>/dev/null) || exit 1;"
                                    + " [ -n \"$pid\" ] || exit 1;"
                                    + " kill -TERM -\"$pid\" 2>/dev/null; kill -TERM \"$pid\" 2>/dev/null; exit 0",
                            "harness", pidFile),
                    null, dockerProperties.execTimeout(), null);
            return result.exitCode() == 0;
        } catch (Exception e) {
            log.debug("Попытка прерывания bash-процесса в {} не удалась: {}",
                    containers.containerName(namespace, id), e.getMessage());
            return false;
        }
    }

    private ContainerExecResult exec(UUID sessionId, String script, List<String> args) {
        return exec(sessionId, script, args, dockerProperties.execTimeout());
    }

    private ContainerExecResult exec(UUID sessionId, String script, List<String> args, Duration timeout) {
        List<String> command = new ArrayList<>();
        command.add("sh");
        command.add("-c");
        command.add(script);
        command.add("harness");
        command.addAll(args);
        return containers.exec(sessionId, command, null, timeout);
    }

    /**
     * Пишет файл без stdin (docker-java exec со stdin не завершается сигналом completed):
     * содержимое чанкуется и передаётся позиционным аргументом {@code printf %s}; чанки пишутся
     * во временный файл и одним {@code mv -f} атомарно заменяют целевой (C-4). Размер чанка —
     * конфиг {@code harness.docker.write-chunk-bytes} (C-J-5), ниже Linux {@code MAX_ARG_STRLEN}.
     */
    private ContainerExecResult writeContainerFile(UUID sessionId, String containerPath, String content) {
        String tempPath = containerPath + ".harness-tmp";
        List<String> chunks = splitByUtf8Bytes(content);
        ContainerExecResult last;
        if (chunks.isEmpty()) {
            last = exec(sessionId, "printf %s '' > \"$1\"", List.of(tempPath));
        } else {
            last = null;
            for (int i = 0; i < chunks.size(); i++) {
                String redirect = i == 0 ? ">" : ">>";
                last = exec(sessionId, "printf %s \"$2\" " + redirect + " \"$1\"",
                        List.of(tempPath, chunks.get(i)));
                if (last.exitCode() != 0) {
                    discardTemp(sessionId, tempPath);
                    return last;
                }
            }
        }
        if (last.exitCode() != 0) {
            discardTemp(sessionId, tempPath);
            return last;
        }
        ContainerExecResult moved = exec(sessionId, "mv -f -- \"$2\" \"$1\"",
                List.of(containerPath, tempPath));
        if (moved.exitCode() != 0) {
            discardTemp(sessionId, tempPath);
        }
        return moved;
    }

    private void discardTemp(UUID sessionId, String tempPath) {
        try {
            exec(sessionId, "rm -f -- \"$1\"", List.of(tempPath));
        } catch (RuntimeException e) {
            log.debug("Could not remove temp file {}: {}", tempPath, e.getMessage());
        }
    }

    private List<String> splitByUtf8Bytes(String content) {
        int limit = dockerProperties.writeChunkBytes();
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentBytes = 0;
        for (int i = 0; i < content.length(); ) {
            int codePoint = content.codePointAt(i);
            int bytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (currentBytes + bytes > limit && current.length() > 0) {
                chunks.add(current.toString());
                current.setLength(0);
                currentBytes = 0;
            }
            current.appendCodePoint(codePoint);
            currentBytes += bytes;
            i += Character.charCount(codePoint);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /**
     * Путь передаётся в контейнер как есть (директива владельца: изоляция — сам контейнер):
     * относительные пути резолвятся от монтированного workspace ({@code /workspace}),
     * абсолютные — уходят в контейнер без изменений (файловая система контейнера).
     */
    private String resolve(String path) {
        if (path == null || path.isBlank()) {
            return MOUNT;
        }
        String trimmed = path.trim();
        return trimmed.startsWith("/") ? trimmed : MOUNT + "/" + trimmed;
    }

    private Duration effectiveBashTimeout(Duration timeout) {
        Duration requested = timeout == null ? limits.bashTimeout() : timeout;
        if (requested.isNegative() || requested.isZero()) {
            requested = limits.bashTimeout();
        }
        return requested.compareTo(limits.bashTimeoutCap()) > 0 ? limits.bashTimeoutCap() : requested;
    }

    private Limited truncate(String output, boolean alreadyTruncated) {
        int limit = (int) limits.toolOutput().toBytes();
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= limit) {
            return new Limited(output, alreadyTruncated);
        }
        return new Limited(new String(bytes, 0, limit, StandardCharsets.UTF_8) + "\n[truncated]", true);
    }

    private ToolResult containerFailure(String tool, WorkspaceContainerException e) {
        if (e.isDuringExecution()) {
            return ToolResult.lost(callId(), tool, e.getMessage());
        }
        return ToolResult.error(callId(), tool, e.getMessage());
    }

    private String callId() {
        return idGenerator.newUlid();
    }

    private static int countOccurrences(String content, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = content.indexOf(needle);
        while (index >= 0) {
            count++;
            index = content.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String basename(String line) {
        int colon = line.indexOf(':', MOUNT.length() + 1);
        String file = colon < 0 ? line : line.substring(0, colon);
        return file.substring(file.lastIndexOf('/') + 1);
    }

    private static String relative(String containerPath) {
        String trimmed = containerPath.startsWith(MOUNT + "/")
                ? containerPath.substring((MOUNT + "/").length())
                : containerPath;
        return trimmed;
    }

    private static String firstLine(String output) {
        if (output == null || output.isBlank()) {
            return "command failed";
        }
        return output.lines().findFirst().orElse("command failed").trim();
    }

    private record Limited(String text, boolean truncated) {
    }
}
