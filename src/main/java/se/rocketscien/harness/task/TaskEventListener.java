package se.rocketscien.harness.task;

/**
 * Подписчик SSE-событий задачи ({@link TaskEvent}); реализация — {@code execution.impl.TaskWakeBroadcaster}
 * (in-memory доставка, границы процесса). Внедряется списком в реестр/движок (стиль {@link TaskWakeListener});
 * реализации не должны бросать — публикация после коммита не должна падать.
 */
public interface TaskEventListener {

    /** Событие задачи после коммита транзакции-эмиттера. */
    void onTaskEvent(TaskEvent event);
}
