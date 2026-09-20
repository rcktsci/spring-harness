package se.rocketscien.harness.session;

/**
 * Вид события журнала сессии (data-model §5). {@code ASYNC_ACCEPTED} — плейсхолдер «принято,
 * в полёте» для async-capable инструмента (M3, D-60/D-65); финальным результатом не считается.
 */
public enum MessageKind {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL_CALL,
    TOOL_RESULT,
    COMPACT,
    ASYNC_ACCEPTED
}
