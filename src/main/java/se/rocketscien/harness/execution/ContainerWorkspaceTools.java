package se.rocketscien.harness.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.UUID;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Нативные инструменты в per-session контейнере (agent-tools §1, specs/workspace-tools): файловые
 * операции и bash исполняются серверно утилитами helper-образа; хост-ФС не трогается. Все пути
 * относительные и проходят containment-гвард; вывод ограничен конфигом с маркером {@code truncated};
 * non-zero exit для bash — не ошибка инструмента.
 */
@Service
public class ContainerWorkspaceTools implements WorkspaceTools {

    private static final Logger log = LoggerFactory.getLogger(ContainerWorkspaceTools.class);
    private static final String MOUNT = "/workspace";

    private final WorkspaceContainerManager containers;
    private final DockerProperties dockerProperties;
    private final LimitsProperties limits;
    private final IdGenerator idGenerator;

    public ContainerWorkspaceTools(WorkspaceContainerManager containers,
                                   DockerProperties dockerProperties,
                                   LimitsProperties limits,
                                   IdGenerator idGenerator) {
        this.containers = containers;
        this.dockerProperties = dockerProperties;
        this.limits = limits;
        this.idGenerator = idGenerator;
    }

    @Override
    public ToolResult readFile(UUID sessionId, String path, Integer offset, Integer limit) {
        String tool = "read_file";
        try {
            String containerPath = resolve(sessionId, path);
            ContainerExecResult result = exec(sessionId, "cat -- \"$1\"", List.of(containerPath));
            if (result.exitCode() != 0) {
                return ToolResult.error(callId(), tool, firstLine(result.output()));
            }
            byte[] bytes = result.output().getBytes(StandardCharsets.UTF_8);
            int from = offset == null ? 0 : Math.min(Math.max(offset, 0), bytes.length);
            int to = limit == null ? bytes.length : Math.min(from + Math.max(limit, 0), bytes.length);
            Limited limited = truncate(new String(bytes, from, to - from, StandardCharsets.UTF_8), result.truncated());
            return ToolResult.ok(callId(), tool, limited.text(), null, limited.truncated(), null);
        } catch (WorkspacePathException e) {
            return ToolResult.error(callId(), tool, e.getMessage());
        } catch (WorkspaceContainerException e) {
            return containerFailure(tool, e);
        }
    }

    @Override
    public ToolResult writeFile(UUID sessionId, String path, String content) {
        String tool = "write_file";
        try {
            String containerPath = resolve(sessionId, path);
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
            return ToolResult.ok(callId(), tool, exists.exitCode() == 0 ? "overwritten" : "created");
        } catch (WorkspacePathException e) {
            return ToolResult.error(callId(), tool, e.getMessage());
        } catch (WorkspaceContainerException e) {
            return containerFailure(tool, e);
        }
    }

    @Override
    public ToolResult editFile(UUID sessionId, String path, String oldString, String newString, boolean replaceAll) {
        String tool = "edit_file";
        try {
            String containerPath = resolve(sessionId, path);
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
                    : content.replaceFirst(java.util.regex.Pattern.quote(oldString),
                            java.util.regex.Matcher.quoteReplacement(newString));
            ContainerExecResult written = writeContainerFile(sessionId, containerPath, edited);
            if (written.exitCode() != 0) {
                return ToolResult.error(callId(), tool, firstLine(written.output()));
            }
            return ToolResult.ok(callId(), tool, replaceAll ? "replaced-all" : "replaced");
        } catch (WorkspacePathException e) {
            return ToolResult.error(callId(), tool, e.getMessage());
        } catch (WorkspaceContainerException e) {
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
            return ToolResult.error(callId(), tool, "invalid pattern: " + pattern);
        } catch (WorkspacePathException e) {
            return ToolResult.error(callId(), tool, e.getMessage());
        } catch (WorkspaceContainerException e) {
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
            return ToolResult.error(callId(), tool, "invalid include pattern: " + include);
        } catch (WorkspacePathException e) {
            return ToolResult.error(callId(), tool, e.getMessage());
        } catch (WorkspaceContainerException e) {
            return containerFailure(tool, e);
        }
    }

    @Override
    public ToolResult bash(UUID sessionId, String command, Duration timeout, String cwd) {
        String tool = "bash";
        try {
            String containerCwd = cwd == null || cwd.isBlank()
                    ? MOUNT
                    : resolve(sessionId, cwd);
            Duration effective = effectiveBashTimeout(timeout);
            long seconds = Math.max(1, effective.toSeconds());
            ContainerExecResult result = exec(sessionId,
                    "cd -- \"$1\" && timeout -s TERM \"$2\" sh -c \"$3\"",
                    List.of(containerCwd, Long.toString(seconds), command),
                    effective.plusSeconds(5));
            Limited limited = truncate(result.output(), result.truncated());
            return ToolResult.ok(callId(), tool, limited.text(), result.exitCode(),
                    limited.truncated(), result.timedOut());
        } catch (WorkspacePathException e) {
            return ToolResult.error(callId(), tool, e.getMessage());
        } catch (WorkspaceContainerException e) {
            return containerFailure(tool, e);
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

    private String resolve(UUID sessionId, String path) {
        Path workspaceDir = containers.workspaceDir(sessionId);
        Path hostPath = WorkspacePathGuard.resolveHostPath(workspaceDir, path);
        return WorkspacePathGuard.toContainerPath(workspaceDir, hostPath);
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
