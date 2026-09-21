package se.rocketscien.harness.mcp;

/**
 * Результат MCP-вызова в форме стандартного контракта инструмента (Q.2):
 * {@code tool, status (ok), output?, truncated?}; {@code errorCode} — машиночитаемый код
 * для ошибок уровня клиента (сейчас {@code auth-refresh-failed}). callId/late добавляет
 * исполняющий контур (write-ahead движка / asLate парковки) — как у нативных.
 */
public record McpToolResult(String tool, boolean ok, String output, boolean truncated,
                            String errorCode) {

    public static McpToolResult ok(String tool, String output, boolean truncated) {
        return new McpToolResult(tool, true, output, truncated, null);
    }

    public static McpToolResult error(String tool, String output, String errorCode) {
        return new McpToolResult(tool, false, output, false, errorCode);
    }
}
