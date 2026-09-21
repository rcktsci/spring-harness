package se.rocketscien.harness.execution;

import tools.jackson.databind.JsonNode;

/**
 * Дескриптор клиентского инструмента из декларации {@code register} (M4, D-84):
 * {@code name} — имя в манифесте и журнале ({@code TOOL_CALL}/{@code TOOL_RESULT}),
 * {@code description} — для рендера модели, {@code inputSchema} — JSON Schema для
 * валидации {@code args} на сервере перед {@code tool.call} (D-82: {@code source}
 * информативен и на сервере не интерпретируется).
 */
public record ToolDescriptor(String name, String description, JsonNode inputSchema) {
}
