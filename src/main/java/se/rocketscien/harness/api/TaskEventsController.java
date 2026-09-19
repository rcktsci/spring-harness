package se.rocketscien.harness.api;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import se.rocketscien.harness.api.gen.model.SubtaskTerminalEvent;
import se.rocketscien.harness.api.gen.model.TaskCommentEvent;
import se.rocketscien.harness.api.gen.model.TaskStatusEvent;
import se.rocketscien.harness.api.gen.model.TaskTransitionEvent;
import se.rocketscien.harness.config.SseProperties;
import se.rocketscien.harness.execution.TaskEventBroadcaster;
import se.rocketscien.harness.identity.AppUserDirectory;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskEvent;
import se.rocketscien.harness.task.TaskRegistry;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE-поток событий задачи (api-contracts §3.2, пачка J.4): ручной {@link SseEmitter} —
 * SSE-теги исключены из генерации (D-M1-8), путь/параметры — по замороженной спеке.
 * Первым кадром при коннекте/реконнекте — {@code retry: 5000} и снапшот {@code task.status}
 * ({@code taskEventSeq} — точка входа live-доставки); далее бэкфилл backlog'а broadcaster'а
 * за {@code (cursor, …]} и живая доставка подписки. {@code Last-Event-ID} приоритетен над
 * {@code ?since=} (api-contracts §3.2). {@code id:} кадров = task_event_seq; ping —
 * комментарий по таймеру конфига. Неизвестная задача — 404 {@code task-not-found}.
 *
 * <p>J-6: парный кадр {@code task.status} перехода делит {@code id:} своего
 * {@code task.transition} — счётчик нумерует события, а не кадры; сам снапшот — без
 * {@code id:} (точка ресинхронизации — {@code taskEventSeq} в payload). Служебный кадр
 * {@code notify-dropped-events} (без {@code id:}) сигнализирует о выброшенных из backlog
 * кадрах — клиенту полная ресинхронизация по снапшоту. Числовые параметры — только конфиг
 * ({@code harness.sse.ping-interval}/{@code timeout}); дефолтов в коде нет.</p>
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class TaskEventsController {

    /** Контрактная константа reconnect-интервала (api-contracts §3.2: «retry: 5000»). */
    private static final long RETRY_MILLIS = 5000L;

    private final TaskRegistry taskRegistry;
    private final TaskEventBroadcaster broadcaster;
    private final AppUserDirectory users;
    private final SseProperties sseProperties;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService pingExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "task-sse-ping");
        thread.setDaemon(true);
        return thread;
    });

    @GetMapping(path = "/api/v1/tasks/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTaskEvents(
            @PathVariable("id") UUID id,
            @RequestParam(name = "since", required = false, defaultValue = "0") Long since,
            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {
        if (since == null || since < 0 || (lastEventId != null && lastEventId < 0)) {
            throw new ApiValidationException(List.of(new ApiValidationException.ValidationError(
                    "/since", "minimum", "Курсор не может быть отрицательным")));
        }
        Task task = taskRegistry.get(id);

        // Last-Event-ID приоритетен над ?since= (api-contracts §3.2)
        long cursor = lastEventId != null ? lastEventId : since;
        SseEmitter emitter = new SseEmitter(sseProperties.timeout().toMillis());
        new ClientStream(emitter, task, cursor).start();
        return emitter;
    }

    /**
     * Живой поток одного клиента: подписка до бэкфилла (ни пропусков, ни дублей — lastSentSeq
     * и буфер переупорядочивания), все отправки под общим локом эмиттера.
     */
    private final class ClientStream {

        private final SseEmitter emitter;
        private final UUID taskId;
        private final AtomicLong lastSentSeq;
        private final ConcurrentLinkedQueue<TaskEvent> pending = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean backfilled = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Object sendLock = new Object();
        private volatile TaskEventBroadcaster.Subscription subscription;
        private volatile ScheduledFuture<?> pingTask;

        private ClientStream(SseEmitter emitter, Task task, long cursor) {
            this.emitter = emitter;
            this.taskId = task.id();
            this.lastSentSeq = new AtomicLong(cursor);
        }

        @SneakyThrows
        private void start() {
            emitter.onTimeout(emitter::complete);
            emitter.onError(error -> close());
            emitter.onCompletion(this::close);

            send(SseEmitter.event().reconnectTime(RETRY_MILLIS));
            sendSnapshot();

            subscription = broadcaster.subscribe(taskId, this::onEvent);

            synchronized (sendLock) {
                for (TaskEvent event : broadcaster.backlog(taskId, lastSentSeq.get())) {
                    sendNumbered(event);
                    if (event.seq() > lastSentSeq.get()) {
                        lastSentSeq.set(event.seq());
                    }
                }
                backfilled.set(true);
                drain();
            }
            long pingIntervalMillis = sseProperties.pingInterval().toMillis();
            pingTask = pingExecutor.scheduleAtFixedRate(
                    this::ping, pingIntervalMillis, pingIntervalMillis, TimeUnit.MILLISECONDS);
        }

        /** Снапшот (последний task.status из строки task) — без id: точка входа — taskEventSeq в payload. */
        private void sendSnapshot() throws IOException {
            Task task = taskRegistry.get(taskId);
            TaskStatusEvent snapshot = ApiMappers.toStatusEvent(task.id(), task.currentState(),
                    task.statusProjection(), task.suspended(), task.taskEventSeq());
            synchronized (sendLock) {
                send(nameFrame("task.status", snapshot));
            }
        }

        /** Все события до флага бэкфилла — в буфер; после — немедленная доставка. */
        private void onEvent(TaskEvent event) {
            if (closed.get()) {
                return;
            }
            pending.add(event);
            if (backfilled.get()) {
                drain();
            }
        }

        private void drain() {
            synchronized (sendLock) {
                TaskEvent event;
                while ((event = pending.poll()) != null) {
                    if (closed.get()) {
                        return;
                    }
                    // кадры одной пары делят seq (transition + status) — фильтр строго «меньше»
                    if (event.seq() >= 0 && event.seq() < lastSentSeq.get()) {
                        continue;
                    }
                    try {
                        sendNumbered(event);
                    } catch (IOException e) {
                        close();
                        return;
                    }
                    if (event.seq() > lastSentSeq.get()) {
                        lastSentSeq.set(event.seq());
                    }
                }
            }
        }

        private void sendNumbered(TaskEvent event) throws IOException {
            SseEmitter.SseEventBuilder frame = switch (event) {
                case TaskEvent.Transition transition -> nameFrame(
                        "task.transition", ApiMappers.toEvent(transition));
                case TaskEvent.Status status -> nameFrame("task.status", ApiMappers.toEvent(status));
                case TaskEvent.SubtaskTerminal terminal -> {
                    SubtaskTerminalEvent payload = ApiMappers.toEvent(terminal);
                    yield nameFrame("subtask.terminal", payload);
                }
                case TaskEvent.Comment comment -> {
                    TaskCommentEvent payload = ApiMappers.toEvent(comment, usernameOf(comment));
                    yield nameFrame("task.comment", payload);
                }
                case TaskEvent.BacklogOverflow overflow -> nameFrame("notify-dropped-events",
                        Map.of("taskId", overflow.taskId().toString(),
                                "lastDroppedSeq", overflow.lastDroppedSeq()));
            };
            if (event.seq() >= 0) {
                frame = frame.id(Long.toString(event.seq()));
            }
            send(frame);
        }

        private String usernameOf(TaskEvent.Comment comment) {
            return comment.authorUserId() == null ? null
                    : users.usernames(List.of(comment.authorUserId())).get(comment.authorUserId());
        }

        private void ping() {
            try {
                synchronized (sendLock) {
                    emitter.send(SseEmitter.event().comment("ping"));
                }
            } catch (Exception e) {
                log.debug("Ping SSE задачи {} не отправлен — соединение закрыто", taskId);
                close();
            }
        }

        private SseEmitter.SseEventBuilder nameFrame(String name, Object payload) {
            return SseEmitter.event().name(name).data(toJson(payload), MediaType.APPLICATION_JSON);
        }

        private String toJson(Object payload) {
            return objectMapper.writeValueAsString(payload);
        }

        private void send(SseEmitter.SseEventBuilder frame) throws IOException {
            try {
                emitter.send(frame);
            } catch (IOException e) {
                close();
                throw e;
            }
        }

        /** Отписка + остановка ping + завершение эмиттера; идемпотентно. */
        private void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            TaskEventBroadcaster.Subscription active = subscription;
            if (active != null) {
                active.close();
            }
            ScheduledFuture<?> activePing = pingTask;
            if (activePing != null) {
                activePing.cancel(false);
            }
            emitter.complete();
        }
    }
}
