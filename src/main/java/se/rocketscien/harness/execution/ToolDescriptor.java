package se.rocketscien.harness.execution;

import java.util.Map;

/**
 * Дескриптор клиентского инструмента из декларации {@code register} (M4, D-84):
 * {@code name} — имя в манифесте и журнале ({@code TOOL_CALL}/{@code TOOL_RESULT}),
 * {@code description} — для рендера модели, {@code inputSchema} — JSON Schema (ограниченный
 * профиль D-58) для валидации {@code args} на сервере перед {@code tool.call}; {@code source}
 * ({@code client} | {@code client.mcp:<server>}) — информативная метка для UI/аудита
 * (D-82: на сервере не интерпретируется, к MCP-серверам клиента сервер не ходит).
 */
public record ToolDescriptor(String name, String description, Map<String, Object> inputSchema, String source) {
}
