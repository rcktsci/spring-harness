package se.rocketscien.harness.task;

/**
 * Триггер отозван ({@code revoked_at IS NOT NULL}) — capability-URL мёртв
 * (api-contracts §6: 410 trigger-revoked, обратного включения нет).
 */
public class TriggerRevokedException extends RuntimeException {

    public TriggerRevokedException(String message) {
        super(message);
    }
}
