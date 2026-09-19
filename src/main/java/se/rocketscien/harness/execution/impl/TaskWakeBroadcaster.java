package se.rocketscien.harness.execution.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.config.SseProperties;
import se.rocketscien.harness.execution.TaskEventBroadcaster;
import se.rocketscien.harness.task.TaskEvent;
import se.rocketscien.harness.task.TaskEventListener;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * SSE-широковещатель событий задачи (пачка J.4, аналог M1 {@code InMemorySessionEventBroadcaster};
 * Javadoc контракта — {@link TaskEventBroadcaster}). <b>Это НЕ wake-шина движка состояний</b> —
 * она остаётся в {@link InProcessTaskWakeBus} (пачка I.5): события этой шины не доставляются
 * подписчикам SSE, а раскачивают bootstrap/переоценки.
 *
 * <p>Реализует {@link TaskEventListener}: эмиттеры — {@code TaskEngineImpl} (переходы, статусы,
 * терминалы подзадач) и {@code TaskRegistryImpl} (комментарии, stop) — публикуют строго после
 * коммита. Per-task буфер переупорядочивания по {@code task_event_seq} + ограниченный backlog
 * для бэкфилла реконнекта (размер — только конфиг {@code harness.sse.task-backlog}); backlog —
 * in-memory (D-J-5-стиль), не источник истины: снапшот статуса при реконнекте берётся из строки task.</p>
 *
 * <p>Гигиена памяти (J-4): поток задачи удаляется из карты при уходе последнего подписчика
 * (backlog без подписчиков не нужен — реконнект пересоздаёт поток; добор после ухода последнего
 * подписчика возможен только снапшотом). При переполнении backlog старые кадры выбрасываются,
 * живым подписчикам доставляется служебный кадр {@link TaskEvent.BacklogOverflow} — SSE-кадр
 * {@code notify-dropped-events} (клиент ресинхронизируется снапшотом, реконнект по курсору
 * дыру уже не закроет).</p>
 */
@Component
@Slf4j
public class TaskWakeBroadcaster implements TaskEventBroadcaster, TaskEventListener {

    private final ConcurrentMap<UUID, TaskStream> streams = new ConcurrentHashMap<>();
    private final int backlogSize;

    public TaskWakeBroadcaster(SseProperties sseProperties) {
        // Числовой дефолт — только в application.yml (harness.sse.task-backlog); отсутствие —
        // ошибка конфигурации, а не тихий fallback в коде (правило «числа — конфиг»)
        this.backlogSize = sseProperties.taskBacklog();
    }

    @Override
    public Subscription subscribe(UUID taskId, Consumer<TaskEvent> consumer) {
        TaskStream stream = streams.computeIfAbsent(taskId, id -> new TaskStream(backlogSize));
        stream.subscribers.add(consumer);
        return () -> {
            stream.subscribers.remove(consumer);
            // J-4: поток без подписчиков не держим (eviction); конкурентный computeIfAbsent
            // событий просто создаст свежий поток
            if (stream.subscribers.isEmpty()) {
                streams.remove(taskId, stream);
            }
        };
    }

    @Override
    public List<TaskEvent> backlog(UUID taskId, long afterSeq) {
        TaskStream stream = streams.get(taskId);
        if (stream == null) {
            return List.of();
        }
        synchronized (stream) {
            return stream.backlog.stream()
                    .filter(event -> event.seq() > afterSeq)
                    .toList();
        }
    }

    @Override
    public void onTaskEvent(TaskEvent event) {
        TaskStream stream = streams.computeIfAbsent(event.streamTaskId(), id -> new TaskStream(backlogSize));
        synchronized (stream) {
            if (event.seq() >= 0 && event.seq() < stream.lastDeliveredSeq) {
                log.warn("Опоздавшее событие seq={} задачи {} — пропущено (доставлено до seq={})",
                        event.seq(), event.streamTaskId(), stream.lastDeliveredSeq);
                return;
            }
            if (event.seq() < 0) {
                deliver(stream, event);
                return;
            }
            List<TaskEvent> group = stream.pending.get(event.seq());
            if (group == null && event.seq() <= stream.lastDeliveredSeq) {
                // второй кадр пары, делящей уже доставленный seq (task.status перехода) —
                // доставляется сразу же, порядок эмиссии сохранён
                appendToBacklog(stream, event);
                deliver(stream, event);
                return;
            }
            // несколько кадров могут делить один seq — буфер хранит список на seq,
            // порядок внутри группы — порядок эмиссии
            stream.pending.computeIfAbsent(event.seq(), key -> new ArrayList<>()).add(event);
            drain(stream);
        }
    }

    /** Дренаж по смежности: seq бездырочный (инкремент сериализован row-lock'ом строки задачи). */
    private void drain(TaskStream stream) {
        while (true) {
            List<TaskEvent> group = stream.pending.remove(stream.lastDeliveredSeq + 1);
            if (group == null) {
                return;
            }
            stream.lastDeliveredSeq = group.getFirst().seq();
            for (TaskEvent event : group) {
                appendToBacklog(stream, event);
                deliver(stream, event);
            }
        }
    }

    /** Допись в backlog с вытеснением старых кадров; вытеснение — уведомлением подписчикам (J-4). */
    private void appendToBacklog(TaskStream stream, TaskEvent event) {
        stream.backlog.addLast(event);
        long droppedSeq = -1;
        while (stream.backlog.size() > backlogSize) {
            TaskEvent dropped = stream.backlog.pollFirst();
            if (dropped != null && droppedSeq < 0 && dropped.seq() >= 0) {
                droppedSeq = dropped.seq();
            }
        }
        if (droppedSeq >= 0 && !stream.subscribers.isEmpty()) {
            log.info("Backlog задачи {} переполнен (>{}) — кадры до seq={} выброшены, подписчики уведомлены",
                    event.streamTaskId(), backlogSize, droppedSeq);
            deliver(stream, new TaskEvent.BacklogOverflow(event.streamTaskId(), droppedSeq));
        }
    }

    private void deliver(TaskStream stream, TaskEvent event) {
        for (Consumer<TaskEvent> subscriber : stream.subscribers) {
            try {
                subscriber.accept(event);
            } catch (Exception e) {
                log.warn("Подписчик задачи {} упал на событии {}: {}",
                        event.streamTaskId(), event, e.getMessage());
            }
        }
    }

    /** Состояние потока одной задачи: подписчики, буфер переупорядочивания, backlog. */
    private static final class TaskStream {
        private final List<Consumer<TaskEvent>> subscribers = new CopyOnWriteArrayList<>();
        private final Map<Long, List<TaskEvent>> pending = new TreeMap<>();
        private final ArrayDeque<TaskEvent> backlog;
        private long lastDeliveredSeq;

        private TaskStream(int backlogSize) {
            this.backlog = new ArrayDeque<>(Math.min(backlogSize, 1024));
        }
    }
}
