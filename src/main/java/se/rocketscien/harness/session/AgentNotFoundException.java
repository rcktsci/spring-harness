package se.rocketscien.harness.session;

/**
 * Ревизия агента не найдена (неизвестный {@code agentKey} или несуществующая ревизия) —
 * сервисная ошибка {@code 404 agent-not-found} (api-contracts §6).
 */
public class AgentNotFoundException extends RuntimeException {

    public AgentNotFoundException(String message) {
        super(message);
    }
}
