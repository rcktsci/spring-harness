package se.rocketscien.harness.execution.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.config.CompactProperties;
import se.rocketscien.harness.execution.ToolResult;
import se.rocketscien.harness.execution.TurnPayloads;
import se.rocketscien.harness.session.SessionMessageEntity;
import se.rocketscien.harness.session.SessionStore;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Мета-инструмент {@code read_compacted(compactMessageId)} (M3 O.4, D-44/D-67, спека
 * subagent-lifecycle): возвращает оригиналы, скрытые COMPACT-покрытием. ULID резолвится
 * глобально, но читается только в пределах текущей сессии (cross-session — вне M3, R8):
 * чужой/неизвестный id → {@code not-found (no-such-message)}. Указывает можно и на сам
 * COMPACT, и на покрытое им сообщение — берётся последний покрывающий компакт. Записи
 * больше {@code harness.compact.read-max-bytes} усечённо с маркером {@code truncated}.
 * COMPACT не модифицируется — покрытие снимается только для ответа инструмента.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReadCompactedTool {

    /** Имя инструмента в декларациях модели и журнале TOOL_CALL/TOOL_RESULT. */
    public static final String NAME = "read_compacted";

    private static final String TRUNCATED_MARKER = "\n[truncated]";

    private final SessionStore sessionStore;
    private final CompactProperties properties;
    private final ObjectMapper objectMapper;

    /** Декларация для модели — доступна всем агентам в их собственной сессии. */
    public ToolCallback declaration() {
        return FunctionToolCallback.builder(NAME, (ReadCompactedArgs unused) ->
                        "read_compacted is executed by the turn engine (write-ahead journaling)")
                .description("Return original journal messages hidden by a compaction summary. "
                        + "compactMessageId is the ULID of a COMPACT summary message or of any message "
                        + "it covers, from this session only.")
                .inputType(ReadCompactedArgs.class)
                .build();
    }

    public ToolResult execute(UUID sessionId, String callId, Map<String, Object> arguments) {
        String messageId = arguments != null && arguments.get("compactMessageId") instanceof String id
                ? id.strip()
                : null;
        if (messageId == null || messageId.isBlank()) {
            return ToolResult.error(callId, NAME, "compactMessageId обязателен");
        }

        SessionStore.MessageRef ref = sessionStore.findMessageRef(messageId).orElse(null);
        if (ref == null || !ref.sessionId().equals(sessionId)) {
            return ToolResult.error(callId, NAME, "not-found (no-such-message)");
        }

        List<SessionMessageEntity> originals = sessionStore.findCompactedOriginals(sessionId, ref.seq());
        if (originals.isEmpty()) {
            return ToolResult.error(callId, NAME, "not-found (no-such-message)");
        }

        long limitBytes = properties.readMaxBytes() == null
                ? 16384
                : Math.max(1, properties.readMaxBytes().toBytes());
        StringBuilder output = new StringBuilder();
        for (SessionMessageEntity original : originals) {
            output.append(recordJson(original, limitBytes)).append('\n');
        }
        return ToolResult.ok(callId, NAME, output.toString().stripTrailing());
    }

    /**
     * JSON одной скрытой записи. Лимит (D-67 «на одну запись») применяется к содержимому
     * {@code payload.text}: больше лимита — усечение + маркер {@code truncated} (как у
     * нативных инструментов); конверт записи (seq/id/kind) сохраняется целиком.
     */
    private String recordJson(SessionMessageEntity original, long limitBytes) {
        Map<String, Object> sourcePayload = original.getPayloadJsonb() == null
                ? Map.of()
                : original.getPayloadJsonb();
        Map<String, Object> payload = new LinkedHashMap<>(sourcePayload);
        boolean truncated = false;
        if (payload.get(TurnPayloads.TEXT) instanceof String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > limitBytes) {
                String cut = new String(bytes, 0, (int) limitBytes, StandardCharsets.UTF_8)
                        + TRUNCATED_MARKER;
                payload.put(TurnPayloads.TEXT, cut);
                truncated = true;
            }
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("seq", original.getId().seq());
        record.put("id", original.getUlid());
        record.put("kind", original.getKind().name());
        record.put("payload", payload);
        if (truncated) {
            record.put("truncated", true);
        }
        try {
            return objectMapper.writeValueAsString(record);
        } catch (Exception e) {
            return String.valueOf(record);
        }
    }

    public record ReadCompactedArgs(String compactMessageId) {
    }
}
