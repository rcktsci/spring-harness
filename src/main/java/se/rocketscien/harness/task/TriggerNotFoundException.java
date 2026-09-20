package se.rocketscien.harness.task;

/**
 * Триггер не найден (api-contracts §6: 404 trigger-not-found) — DELETE несуществующего
 * либо lookup по capability-URL с валидным токеном отсутствующей строки.
 */
public class TriggerNotFoundException extends RuntimeException {

    public TriggerNotFoundException(String message) {
        super(message);
    }
}
