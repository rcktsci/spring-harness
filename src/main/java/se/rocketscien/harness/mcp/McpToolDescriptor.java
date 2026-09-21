package se.rocketscien.harness.mcp;

import java.util.Map;

/**
 * Инструмент MCP-сервера в манифесте (Q.2/Q.3): namespace строится как
 * {@code server + "." + tool} — дедупликация имён между серверами. {@code asyncCapable} —
 * manifest-атрибут сервера ({@code _meta["async-capable"] = true}); такие инструменты идут
 * через то же окно, что нативные (N.1).
 */
public record McpToolDescriptor(String server, String tool, String description,
                                Map<String, Object> inputSchema, boolean asyncCapable) {

    /** Имя в манифесте агента и журнале TOOL_CALL/TOOL_RESULT. */
    public String namespacedName() {
        return server + "." + tool;
    }
}
