package se.rocketscien.harness.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.config.LimitsProperties;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Адаптер MCP-вызова в стандартный контракт инструмента (Q.2, agent-tools §5):
 * {@code tool, status(ok), output?, truncated?} — callId/late добавляет исполняющий контур
 * (движок). Нестандартные формы результата оборачиваются: текстовые content — конкатенация
 * текстов; без content, но со {@code structuredContent} — сериализация JSON (Jackson 3);
 * {@code isError} → неудача. Лимит вывода — {@code harness.limits.tool-output} с маркером
 * {@code truncated} (как у нативных).
 */
@Component
@RequiredArgsConstructor
public class McpToolAdapter {

    private static final String TRUNCATED_MARKER = "\n[truncated]";

    private final McpClientRegistry clients;
    private final LimitsProperties limits;
    private final ObjectMapper objectMapper;

    /** Синхронный вызов MCP-инструмента; ошибки реестра (в т.ч. auth-refresh-failed) — FAIL. */
    public McpToolResult callTool(String namespacedTool, Map<String, Object> arguments) {
        String server = namespacedTool.substring(0, namespacedTool.indexOf('.'));
        String tool = namespacedTool.substring(namespacedTool.indexOf('.') + 1);
        try {
            McpSchema.CallToolResult result = clients.callTool(server, tool, arguments);
            return toResult(namespacedTool, result);
        } catch (McpAuthRefresher.McpAuthException e) {
            return McpToolResult.error(namespacedTool, e.getMessage(), e.errorCode());
        } catch (Exception e) {
            return McpToolResult.error(namespacedTool, String.valueOf(e.getMessage()), null);
        }
    }

    /** Стандартная форма из результата MCP: тексты / structuredContent / isError. */
    private McpToolResult toResult(String namespacedTool, McpSchema.CallToolResult result) {
        boolean failed = Boolean.TRUE.equals(result.isError());
        String output = extractOutput(result);
        boolean truncated = false;
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        int limit = (int) limits.toolOutput().toBytes();
        if (bytes.length > limit) {
            output = new String(bytes, 0, limit, StandardCharsets.UTF_8) + TRUNCATED_MARKER;
            truncated = true;
        }
        return failed
                ? McpToolResult.error(namespacedTool, output, null)
                : McpToolResult.ok(namespacedTool, output, truncated);
    }

    private String extractOutput(McpSchema.CallToolResult result) {
        List<McpSchema.Content> content = result.content();
        if (content != null && !content.isEmpty()) {
            StringBuilder text = new StringBuilder();
            for (McpSchema.Content part : content) {
                if (part instanceof McpSchema.TextContent textContent) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(textContent.text());
                }
            }
            if (!text.isEmpty()) {
                return text.toString();
            }
        }
        // Нестандартная форма: без текстового content — отдаём structuredContent как JSON
        if (result.structuredContent() != null) {
            try {
                return objectMapper.writeValueAsString(result.structuredContent());
            } catch (Exception e) {
                return String.valueOf(result.structuredContent());
            }
        }
        return "";
    }
}
