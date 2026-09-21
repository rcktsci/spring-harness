package se.rocketscien.harness.mcp;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Декларация MCP-инструмента для манифеста модели (Q.3): имя — namespace
 * {@code server.tool}, inputSchema — как объявил сервер. Внутреннее исполнение Spring AI
 * отключено (write-ahead и исполнение — на Turn'е, execution-model §1): {@link #call}
 * при прямом вызове честно исполняет инструмент через реестр.
 */
@RequiredArgsConstructor
public class McpToolCallback implements ToolCallback {

    private final McpClientRegistry clients;
    private final McpToolAdapter adapter;
    private final McpToolDescriptor descriptor;
    private final ObjectMapper objectMapper;

    @Override
    public ToolDefinition getToolDefinition() {
        String schema;
        try {
            schema = descriptor.inputSchema() == null
                    ? "{\"type\":\"object\"}"
                    : objectMapper.writeValueAsString(descriptor.inputSchema());
        } catch (Exception e) {
            schema = "{\"type\":\"object\"}";
        }
        return ToolDefinition.builder()
                .name(descriptor.namespacedName())
                .description(descriptor.description() == null
                        ? "MCP tool " + descriptor.namespacedName()
                        : descriptor.description())
                .inputSchema(schema)
                .build();
    }

    @Override
    public String call(String toolInput) {
        Map<String, Object> arguments;
        try {
            arguments = toolInput == null || toolInput.isBlank()
                    ? Map.of()
                    : objectMapper.readValue(toolInput,
                            new TypeReference<Map<String, Object>>() {
                            });
        } catch (Exception e) {
            return "error: arguments не распознаны: " + e.getMessage();
        }
        McpToolResult result = adapter.callTool(descriptor.namespacedName(), arguments);
        return result.ok() ? result.output()
                : "error (" + (result.errorCode() == null ? "tool-error" : result.errorCode())
                + "): " + result.output();
    }
}
