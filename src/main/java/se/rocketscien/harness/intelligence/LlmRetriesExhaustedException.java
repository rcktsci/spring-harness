package se.rocketscien.harness.intelligence;

/**
 * Все попытки вызова LLM исчерпаны временными ошибками (429/5xx). Turn, получивший это исключение,
 * завершается FAILED: SYSTEM-событие причины + {@code last_consumed_seq := last_seq}, автоповтора нет
 * (specs/llm-gateway, specs/agent-turn).
 */
public class LlmRetriesExhaustedException extends RuntimeException {

    public LlmRetriesExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
