package se.rocketscien.harness.task;

/**
 * Задача не существует либо вне WAIT_WEBHOOK — вебхук задачи неприменим
 * (api-contracts §6: 409 task-not-waiting-webhook; идемпотентность вебхука по построению:
 * повторная доставка после перехода — тоже 409; несуществующая задача — тоже 409,
 * отклонение dev D-пачки №6 — без отдельного 404).
 */
public class TaskNotWaitingWebhookException extends RuntimeException {

    public TaskNotWaitingWebhookException(String message) {
        super(message);
    }
}
