package se.rocketscien.harness.intelligence;

/**
 * Конфигурация LLM-клиента не может быть собрана: нет записи модели, нет связанных учётных
 * данных или отсутствует ключ нужной версии. На старте процесса не влияет; Turn, обратившийся
 * к такой модели, завершается FAILED с SYSTEM-событием (specs/llm-gateway).
 */
public class LlmConfigurationException extends RuntimeException {

    public LlmConfigurationException(String message) {
        super(message);
    }

    public LlmConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
