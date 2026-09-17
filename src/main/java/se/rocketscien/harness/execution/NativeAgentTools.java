package se.rocketscien.harness.execution;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.session.SessionStore;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Нативные workspace-инструменты как декларации для модели и диспетчер исполнения
 * (agent-tools §1). M1 — все sync; async-окно {@code bash} — M3. Декларации фильтруются
 * {@code permissions_jsonb.allowedTools} (allowlist; отсутствие ключа — все нативные).
 * Внутреннее исполнение Spring AI отключено: write-ahead и журналирование — на Turn'е
 * (execution-model §1), поэтому callbacks используются только как схемы для провайдера.
 */
@Component
public class NativeAgentTools {

    static final Set<String> NATIVE_TOOL_NAMES =
            Set.of("read_file", "write_file", "edit_file", "bash", "glob", "grep");

    private final WorkspaceTools workspaceTools;

    public NativeAgentTools(WorkspaceTools workspaceTools) {
        this.workspaceTools = workspaceTools;
    }

    public List<ToolCallback> declarations(SessionStore.AgentRuntime agent) {
        Set<String> allowed = allowedTools(agent);
        List<ToolCallback> callbacks = new ArrayList<>();
        if (allowed.contains("read_file")) {
            callbacks.add(FunctionToolCallback.builder("read_file", (ReadFileArgs unused) -> "")
                    .description("Read a UTF-8 text file from the workspace; offset/limit are in bytes.")
                    .inputType(ReadFileArgs.class)
                    .build());
        }
        if (allowed.contains("write_file")) {
            callbacks.add(FunctionToolCallback.builder("write_file", (WriteFileArgs unused) -> "")
                    .description("Create or overwrite a file in the workspace with the given content.")
                    .inputType(WriteFileArgs.class)
                    .build());
        }
        if (allowed.contains("edit_file")) {
            callbacks.add(FunctionToolCallback.builder("edit_file", (EditFileArgs unused) -> "")
                    .description("Replace an exact string in a file; fails 'not-found' or 'ambiguous' without replaceAll.")
                    .inputType(EditFileArgs.class)
                    .build());
        }
        if (allowed.contains("bash")) {
            callbacks.add(FunctionToolCallback.builder("bash", (BashArgs unused) -> "")
                    .description("Run a shell command in the workspace (synchronous); timeout in seconds.")
                    .inputType(BashArgs.class)
                    .build());
        }
        if (allowed.contains("glob")) {
            callbacks.add(FunctionToolCallback.builder("glob", (GlobArgs unused) -> "")
                    .description("List workspace files matching a glob pattern.")
                    .inputType(GlobArgs.class)
                    .build());
        }
        if (allowed.contains("grep")) {
            callbacks.add(FunctionToolCallback.builder("grep", (GrepArgs unused) -> "")
                    .description("Search file contents with a regex; optional include glob filter.")
                    .inputType(GrepArgs.class)
                    .build());
        }
        return callbacks;
    }

    public ToolResult execute(UUID sessionId, String tool, Map<String, Object> args, TurnCancellation cancellation) {
        return switch (tool == null ? "" : tool) {
            case "read_file" -> workspaceTools.readFile(
                    sessionId, text(args, "path"), intArg(args, "offset"), intArg(args, "limit"));
            case "write_file" -> workspaceTools.writeFile(
                    sessionId, text(args, "path"), text(args, "content"));
            case "edit_file" -> workspaceTools.editFile(
                    sessionId,
                    text(args, "path"),
                    text(args, "oldString"),
                    text(args, "newString"),
                    boolArg(args, "replaceAll"));
            case "bash" -> workspaceTools.bash(
                    sessionId, text(args, "command"), secondsArg(args, "timeout"), text(args, "cwd"), cancellation);
            case "glob" -> workspaceTools.glob(sessionId, text(args, "pattern"));
            case "grep" -> workspaceTools.grep(sessionId, text(args, "pattern"), text(args, "include"));
            default -> ToolResult.error("unknown", tool, "unknown tool: " + tool);
        };
    }

    private Set<String> allowedTools(SessionStore.AgentRuntime agent) {
        Object allowlist = agent.permissions() == null ? null : agent.permissions().get("allowedTools");
        if (allowlist instanceof List<?> list && !list.isEmpty()) {
            Set<String> allowed = new LinkedHashSet<>();
            for (Object item : list) {
                if (item instanceof String name && NATIVE_TOOL_NAMES.contains(name)) {
                    allowed.add(name);
                }
            }
            return allowed;
        }
        return NATIVE_TOOL_NAMES;
    }

    private static String text(Map<String, Object> args, String key) {
        return args != null && args.get(key) instanceof String value ? value : null;
    }

    private static Integer intArg(Map<String, Object> args, String key) {
        return args != null && args.get(key) instanceof Number number ? number.intValue() : null;
    }

    private static boolean boolArg(Map<String, Object> args, String key) {
        return args != null && Boolean.TRUE.equals(args.get(key));
    }

    private static Duration secondsArg(Map<String, Object> args, String key) {
        if (args != null && args.get(key) instanceof Number number && number.doubleValue() > 0) {
            return Duration.ofMillis((long) (number.doubleValue() * 1000));
        }
        return null;
    }

    public record ReadFileArgs(String path, Integer offset, Integer limit) {
    }

    public record WriteFileArgs(String path, String content) {
    }

    public record EditFileArgs(String path, String oldString, String newString, Boolean replaceAll) {
    }

    public record BashArgs(String command, Double timeout, String cwd) {
    }

    public record GlobArgs(String pattern) {
    }

    public record GrepArgs(String pattern, String include) {
    }
}
