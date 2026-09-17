package se.rocketscien.harness.execution;

/**
 * Единый контракт результата инструмента (agent-tools §5):
 * {@code { callId, tool, status, output?, exitCode?, truncated?, timedOut? }}.
 * {@code timedOut} — только для {@code bash}; {@code late} (async M3) не заполняется в M1.
 */
public record ToolResult(
        String callId,
        String tool,
        ToolStatus status,
        String output,
        Integer exitCode,
        Boolean truncated,
        Boolean timedOut
) {

    public static ToolResult ok(String callId, String tool, String output) {
        return new ToolResult(callId, tool, ToolStatus.OK, output, null, null, null);
    }

    public static ToolResult ok(String callId, String tool, String output, Integer exitCode, Boolean truncated,
                                Boolean timedOut) {
        return new ToolResult(callId, tool, ToolStatus.OK, output, exitCode, truncated, timedOut);
    }

    public static ToolResult error(String callId, String tool, String reason) {
        return new ToolResult(callId, tool, ToolStatus.ERROR, reason, null, null, null);
    }

    public static ToolResult lost(String callId, String tool, String reason) {
        return new ToolResult(callId, tool, ToolStatus.LOST, reason, null, null, null);
    }

    public static ToolResult cancelled(String callId, String tool, String reason) {
        return new ToolResult(callId, tool, ToolStatus.CANCELLED, reason, null, null, null);
    }
}
