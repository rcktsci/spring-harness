package se.rocketscien.harness.execution;

import java.util.HashMap;
import java.util.Map;

/**
 * Схемы payload-ов событий журнала, которые пишет/читает исполняющий контур (D-M1-1: форматы —
 * достояние обоих сторон, зафиксированы в одном месте):
 * <ul>
 *   <li>{@code USER/ASSISTANT/SYSTEM}: {@code {"text": ...}} (SYSTEM — причина сбоя тем же ключом);</li>
 *   <li>{@code TOOL_CALL}: {@code {"callId": <внутренний ULID>, "toolCallId": <id провайдера>,
 *       "tool": <имя>, "arguments": {...}}};</li>
 *   <li>{@code TOOL_RESULT}: {@code {"callId": ..., "tool": ..., "status": OK|ERROR|CANCELLED|LOST,
 *       "output"?: ..., "exitCode"?: ..., "truncated"?: ..., "timedOut"?: ..., "late"?: true}};</li>
 *   <li>{@code ASYNC_ACCEPTED} (M3, D-60/D-65): {@code {"callId": ..., "tool": ...}} —
 *       плейсхолдер «принято, в полёте»; поздний TOOL_RESULT того же callId несёт
 *       {@code late=true}.</li>
 * </ul>
 */
public final class TurnPayloads {

    public static final String TEXT = "text";
    public static final String CALL_ID = "callId";
    public static final String TOOL_CALL_ID = "toolCallId";
    public static final String TOOL = "tool";
    public static final String ARGUMENTS = "arguments";
    public static final String STATUS = "status";
    public static final String OUTPUT = "output";
    public static final String EXIT_CODE = "exitCode";
    public static final String TRUNCATED = "truncated";
    public static final String TIMED_OUT = "timedOut";
    public static final String LATE = "late";

    /**
     * Суффикс провайдерского id позднего результата (D-65): OpenAI-протокол запрещает дубль
     * {@code tool_call_id}, поэтому ASYNC_ACCEPTED идёт с id = callId, а поздний
     * TOOL_RESULT — с id = {@code callId + "-late"}.
     */
    public static final String LATE_ID_SUFFIX = "-late";

    private TurnPayloads() {
    }

    public static Map<String, Object> assistant(String text) {
        return Map.of(TEXT, text);
    }

    public static Map<String, Object> systemFailure(Throwable error) {
        return Map.of(TEXT, "Turn прерван ошибкой: " + rootMessage(error));
    }

    public static Map<String, Object> toolCall(String callId, String toolCallId, String tool,
                                        Map<String, Object> arguments) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(CALL_ID, callId);
        if (toolCallId != null) {
            payload.put(TOOL_CALL_ID, toolCallId);
        }
        payload.put(TOOL, tool);
        payload.put(ARGUMENTS, arguments);
        return payload;
    }

    public static Map<String, Object> toolResult(String callId, String tool, ToolResult result) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(CALL_ID, callId);
        payload.put(TOOL, tool);
        payload.put(STATUS, result.status().name());
        if (result.output() != null) {
            payload.put(OUTPUT, result.output());
        }
        if (result.exitCode() != null) {
            payload.put(EXIT_CODE, result.exitCode());
        }
        if (result.truncated() != null) {
            payload.put(TRUNCATED, result.truncated());
        }
        if (result.timedOut() != null) {
            payload.put(TIMED_OUT, result.timedOut());
        }
        if (Boolean.TRUE.equals(result.late())) {
            payload.put(LATE, true);
        }
        return payload;
    }

    /** Плейсхолдер async-инструмента «принято, в полёте» (D-60/D-65); финальным не считается. */
    public static Map<String, Object> asyncAccepted(String callId, String tool) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(CALL_ID, callId);
        payload.put(TOOL, tool);
        return payload;
    }

    public static Map<String, Object> toolResultSynthetic(String callId, String tool, ToolStatus status, String output) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(CALL_ID, callId);
        payload.put(TOOL, tool);
        payload.put(STATUS, status.name());
        if (output != null) {
            payload.put(OUTPUT, output);
        }
        return payload;
    }

    public static String text(Map<String, Object> payload) {
        if (payload == null) {
            return "";
        }
        return payload.get(TEXT) instanceof String text ? text : "";
    }

    public static String summary(Map<String, Object> payload) {
        if (payload == null) {
            return "";
        }
        return payload.get("summary") instanceof String summary ? summary : "";
    }

    public static String callId(Map<String, Object> payload) {
        return payload == null ? null : asString(payload.get(CALL_ID));
    }

    public static String toolCallId(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        String providerId = asString(payload.get(TOOL_CALL_ID));
        return providerId != null ? providerId : asString(payload.get(CALL_ID));
    }

    public static String tool(Map<String, Object> payload) {
        return payload == null ? null : asString(payload.get(TOOL));
    }

    /** Маркер позднего результата async-инструмента ({@code late=true}, M3 D-60). */
    public static boolean late(Map<String, Object> payload) {
        return payload != null && Boolean.TRUE.equals(payload.get(LATE));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> arguments(Map<String, Object> payload) {
        if (payload != null && payload.get(ARGUMENTS) instanceof Map<?, ?> arguments) {
            return (Map<String, Object>) arguments;
        }
        return Map.of();
    }

    private static String asString(Object value) {
        return value instanceof String text ? text : null;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : error.getClass().getSimpleName();
    }
}
