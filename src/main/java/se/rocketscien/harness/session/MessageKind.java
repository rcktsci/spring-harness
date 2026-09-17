package se.rocketscien.harness.session;

/**
 * Вид события журнала сессии (data-model §5).
 */
public enum MessageKind {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL_CALL,
    TOOL_RESULT,
    COMPACT
}
