package se.rocketscien.harness.task;

import java.util.UUID;

/**
 * Слушатель task-wake — публикация после коммита (аналог {@code SessionEventListener};
 * реализация — {@code InProcessTaskWakeBus} в execution, пачка I.5: subscribeTaskWake(handler)).
 * Модуль task не зависит от execution — шину инъектируют слушателем (D-33 EVENT+POLL).
 */
public interface TaskWakeListener {

    /**
     * Задача требует wake: resume (пачка H), переоценка WAIT_TASKS «blocked-changed» (пачка K.3)
     * и пр. Обработчик не должен бросать — публикация асинхронная по природе.
     */
    void onTaskWake(UUID taskId);
}
