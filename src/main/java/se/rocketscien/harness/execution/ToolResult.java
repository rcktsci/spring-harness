package se.rocketscien.harness.execution;

/**
 * Единый контракт результата инструмента (agent-tools §5):
 * {@code { callId, tool, status, output?, exitCode?, truncated?, timedOut?, late? }}.
 * {@code timedOut} — только для {@code bash}; {@code late} — поздний результат
 * async-инструмента (M3, D-60): прибыл после раунда, в котором инструмент был принят.
 */
public record ToolResult(
        String callId,
        String tool,
        ToolStatus status,
        String output,
        Integer exitCode,
        Boolean truncated,
        Boolean timedOut,
        Boolean late
) {

    public static ToolResult ok(String callId, String tool, String output) {
        return new ToolResult(callId, tool, ToolStatus.OK, output, null, null, null, null);
    }

    public static ToolResult ok(String callId, String tool, String output, Integer exitCode, Boolean truncated,
                                Boolean timedOut) {
        return new ToolResult(callId, tool, ToolStatus.OK, output, exitCode, truncated, timedOut, null);
    }

    public static ToolResult error(String callId, String tool, String reason) {
        return new ToolResult(callId, tool, ToolStatus.ERROR, reason, null, null, null, null);
    }

    public static ToolResult lost(String callId, String tool, String reason) {
        return new ToolResult(callId, tool, ToolStatus.LOST, reason, null, null, null, null);
    }

    public static ToolResult cancelled(String callId, String tool, String reason) {
        return new ToolResult(callId, tool, ToolStatus.CANCELLED, reason, null, null, null, null);
    }

    /** Копия с маркером {@code late=true} — публикация завершившегося async-инструмента (D-60). */
    public ToolResult asLate() {
        return new ToolResult(callId, tool, status, output, exitCode, truncated, timedOut, true);
    }
}
