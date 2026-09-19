package se.rocketscien.harness.execution.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.task.TaskWakeListener;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-process шина wake задачного движка (аналог {@code InMemorySessionEventBroadcaster};
 * D-33 EVENT+POLL): EVENT-канал публикаций после коммита (переходы, resume, «blocked-changed»)
 * подписчикам — диспетчеру движка. <b>Это НЕ SSE</b>: SSE-канал события задачи —
 * {@code TaskWakeBroadcaster} (пачка J.4) с durable-курсором {@code task_event_seq}; события
 * этой шины после рестарта процесса не доставляются — страховка POLL ({@code task-scheduler}).
 */
@Component
@Slf4j
public class InProcessTaskWakeBus implements TaskWakeListener {

    private final List<TaskWakeHandler> handlers = new CopyOnWriteArrayList<>();

    /** Wake-событие: задача требует внимания движка (переход, resume, blocked/tags changed). */
    public void publishTaskWake(UUID taskId) {
        dispatch(taskId, "wake", handler -> handler.onTaskWake(taskId));
    }

    /** Терминал задачи (SUCCESS/FAILED/CANCELLED/'$CANCELLED') — переоценка наблюдателей. */
    public void publishTaskTerminal(UUID taskId) {
        dispatch(taskId, "terminal", handler -> handler.onTaskTerminal(taskId));
    }

    /** Подписка на события шины; возврат — отписка (идемпотентна). */
    public AutoCloseable subscribeTaskWake(TaskWakeHandler handler) {
        handlers.add(handler);
        return () -> handlers.remove(handler);
    }

    /** Точка входа от реестра задач (публикация после коммита TaskRegistry). */
    @Override
    public void onTaskWake(UUID taskId) {
        publishTaskWake(taskId);
    }

    private void dispatch(UUID taskId, String kind, Consumer<TaskWakeHandler> action) {
        for (TaskWakeHandler handler : handlers) {
            try {
                action.accept(handler);
            } catch (Exception e) {
                log.warn("TaskWake-обработчик {} упал на {} {}: {}",
                        handler.getClass().getSimpleName(), kind, taskId, e.getMessage());
            }
        }
    }
}
