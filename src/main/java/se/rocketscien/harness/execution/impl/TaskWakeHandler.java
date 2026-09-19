package se.rocketscien.harness.execution.impl;

import java.util.UUID;

/**
 * Подписчик {@link InProcessTaskWakeBus} — внутренняя раскачка движка состояний
 * (bootstrap AGENT, исполнение BASH, переоценка WAIT_TASKS). Реализации не должны бросать
 * и не должны блокать поток публикации надолго — диспетчер исполняет работу асинхронно.
 */
public interface TaskWakeHandler {

    /** Задача требует внимания движка: wake после перехода/резюма, изменение blocked_by/тегов. */
    void onTaskWake(UUID taskId);

    /** Задача пришла в терминал (включая {@code '$CANCELLED'}) — наблюдателям WAIT_TASKS. */
    default void onTaskTerminal(UUID taskId) {
    }
}
