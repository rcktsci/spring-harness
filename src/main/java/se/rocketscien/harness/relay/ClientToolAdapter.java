package se.rocketscien.harness.relay;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.common.jsonschema.JsonSchemaError;
import se.rocketscien.harness.common.jsonschema.LimitedJsonSchemaValidator;
import se.rocketscien.harness.execution.ToolDescriptor;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Адаптер клиентского инструмента (M4, D-81/D-82): декларация {@link ToolCallback} для манифеста
 * модели, валидация {@code args} против {@code inputSchema} ограниченным JSON-Schema-профилем
 * (D-58) и сборка кадра {@code tool.call}. Исполнение (send + ожидание future) — на
 * {@link ClientToolRegistry}; внутреннее исполнение Spring AI не используется — журнал пишет
 * Turn-поток под sess-локом (D-81).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClientToolAdapter {

    private final ObjectMapper objectMapper;

    /** Декларация инструмента для манифеста модели (имя/описание/inputSchema). */
    public ToolCallback declaration(ToolDescriptor descriptor) {
        return new Declaration(descriptor, objectMapper);
    }

    /** Валидация args клиентского вызова против inputSchema (D-58) — пусто, если валидно. */
    public List<JsonSchemaError> validate(ToolDescriptor descriptor, Map<String, Object> args) {
        return LimitedJsonSchemaValidator.validate(args, descriptor.inputSchema(), "/args");
    }

    /** Кадр {@code tool.call { callId, sessionId, tool, args }} (api-contracts §5.3). */
    public String toolCallFrame(String callId, UUID sessionId, String tool, Map<String, Object> args) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "tool.call");
        frame.put("callId", callId);
        frame.put("sessionId", sessionId.toString());
        frame.put("tool", tool);
        frame.put("args", args == null ? Map.of() : args);
        return objectMapper.writeValueAsString(frame);
    }

    /** Кадр {@code tool.cancel { callId }} — отмена in-flight вызова на стороне клиента (§5.3). */
    public String toolCancelFrame(String callId) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "tool.cancel");
        frame.put("callId", callId);
        return objectMapper.writeValueAsString(frame);
    }

    private static final class Declaration implements ToolCallback {

        private final ToolDescriptor descriptor;
        private final ObjectMapper objectMapper;

        private Declaration(ToolDescriptor descriptor, ObjectMapper objectMapper) {
            this.descriptor = descriptor;
            this.objectMapper = objectMapper;
        }

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
                    .name(descriptor.name())
                    .description(descriptor.description() == null
                            ? "Client tool " + descriptor.name()
                            : descriptor.description())
                    .inputSchema(schema)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            // Исполнение маршрутизируется Turn-движком через ClientToolBridge.invoke: сюда
            // не попадает (write-ahead/журнал — Turn-поток, D-81).
            return "client tool '" + descriptor.name() + "' is executed via the relay";
        }
    }
}
