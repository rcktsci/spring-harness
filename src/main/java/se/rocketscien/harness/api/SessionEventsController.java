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
import se.rocketscien.harness.api.gen.model.SessionStatusEvent;
import se.rocketscien.harness.config.SseProperties;
import se.rocketscien.harness.identity.AppUserDirectory;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionEvent;
import se.rocketscien.harness.session.SessionEventBroadcaster;
import se.rocketscien.harness.session.SessionMessageEntity;
import se.rocketscien.harness.session.SessionNotFoundException;
import se.rocketscien.harness.session.SessionStore;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * SSE-поток событий сессии (api-contracts §3.1, D-M1-8): Spring MVC {@link SseEmitter} вручную —
 * генерация интерфейсов эндпоинт не покрывает. Первым кадром при коннекте/реконнекте —
 * {@code retry: 5000} и снапшот {@code session.status}; далее бэкфилл видимых событий журнала
 * за {@code (cursor, …]} (источник — SessionStore, D-J-5: broadcaster живёт в границах процесса)
 * и живая доставка подписки. {@code Last-Event-ID} приоритетен над {@code ?since=}.
 * {@code id:} кадров message.created = seq; ping-комментарий по таймеру конфига.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class SessionEventsController {

    /** Контрактная константа reconnect-интервала (api-contracts §3.1: «retry: 5000»). */
    private static final long RETRY_MILLIS = 5000L;

    private final SessionStore sessionStore;
    private final SessionEventBroadcaster broadcaster;
    private final AppUserDirectory users;
    private final SseProperties sseProperties;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService pingExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "sse-ping");
        thread.setDaemon(true);
        return thread;
    });

    @GetMapping(path = "/api/v1/sessions/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(
            @PathVariable("id") UUID id,
            @RequestParam(name = "since", required = false, defaultValue = "0") Long since,
            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {
        if (since == null || since < 0 || (lastEventId != null && lastEventId < 0)) {
            throw new ApiValidationException(List.of(new ApiValidationException.ValidationError(
                    "/since", "minimum", "Курсор не может быть отрицательным")));
        }
        Session session = sessionStore.findSession(id)
                .orElseThrow(() -> new SessionNotFoundException("Сессия %s не найдена".formatted(id)));

        // Last-Event-ID приоритетен над ?since= (api-contracts §3.1)
        long cursor = lastEventId != null ? lastEventId : since;
        SseEmitter emitter = new SseEmitter(sseProperties.timeout().toMillis());
        new ClientStream(emitter, id, cursor, session).start();
        return emitter;
    }

    /**
     * Живой поток одного клиента: подписка до бэкфилла (ни пропусков, ни дублей — lastSentSeq
     * и буфер переупорядочивания), все отправки под общим локом эмиттера.
     */
    private final class ClientStream {

        private final SseEmitter emitter;
        private final UUID sessionId;
        private final AtomicLong lastSentSeq;
        private final Session session;
        private final ConcurrentLinkedQueue<SessionEvent> pending = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean backfilled = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Object sendLock = new Object();
        private volatile SessionEventBroadcaster.Subscription subscription;
        private volatile ScheduledFuture<?> pingTask;

        private ClientStream(SseEmitter emitter, UUID sessionId, long cursor, Session session) {
            this.emitter = emitter;
            this.sessionId = sessionId;
            this.lastSentSeq = new AtomicLong(cursor);
            this.session = session;
        }

        @SneakyThrows
        private void start() {
            emitter.onTimeout(emitter::complete);
            emitter.onError(error -> close());
            emitter.onCompletion(this::close);

            send(SseEmitter.event().reconnectTime(RETRY_MILLIS));
            SessionEventBroadcaster.StatusSnapshot snapshot = broadcaster.statusSnapshot(sessionId);
            send(nameFrame("session.status", ApiMappers.toStatusEvent(
                    snapshot.runtimeStatus(), session.lastTurnOutcome())));

            subscription = broadcaster.subscribe(sessionId, this::onEvent);

            List<SessionMessageEntity> visible = sessionStore.renderVisible(sessionId);
            Set<UUID> authorIds = visible.stream()
                    .map(SessionMessageEntity::getAuthorUserId)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());
            Map<UUID, String> usernames = users.usernames(authorIds);
            synchronized (sendLock) {
                for (SessionMessageEntity message : visible) {
                    long seq = message.getId().seq();
                    if (seq > lastSentSeq.get()) {
                        send(nameFrame("message.created", ApiMappers.toDto(message, usernames))
                                .id(Long.toString(seq)));
                        lastSentSeq.set(seq);
                    }
                }
                backfilled.set(true);
                drain();
            }
            long pingIntervalMillis = sseProperties.pingInterval().toMillis();
            pingTask = pingExecutor.scheduleAtFixedRate(
                    this::ping, pingIntervalMillis, pingIntervalMillis, TimeUnit.MILLISECONDS);
        }

        /** Все события до флага бэкфилла — в буфер; после — немедленная доставка. */
        private void onEvent(SessionEvent event) {
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
                SessionEvent event;
                while ((event = pending.poll()) != null) {
                    if (closed.get()) {
                        return;
                    }
                    if (event instanceof SessionEvent.MessageCreated message) {
                        long seq = message.seq();
                        if (seq <= lastSentSeq.get()) {
                            continue;
                        }
                        try {
                            send(nameFrame("message.created", ApiMappers.toDto(message, usernameOf(message)))
                                    .id(Long.toString(seq)));
                        } catch (IOException e) {
                            close();
                            return;
                        }
                        lastSentSeq.set(seq);
                    } else if (event instanceof SessionEvent.StatusChanged status) {
                        try {
                            send(nameFrame("session.status", ApiMappers.toStatusEvent(
                                    status.runtimeStatus(), status.lastTurnOutcome())));
                        } catch (IOException e) {
                            close();
                            return;
                        }
                    }
                }
            }
        }

        private String usernameOf(SessionEvent.MessageCreated message) {
            return message.authorUserId() == null ? null
                    : users.usernames(List.of(message.authorUserId())).get(message.authorUserId());
        }

        private void ping() {
            try {
                synchronized (sendLock) {
                    emitter.send(SseEmitter.event().comment("ping"));
                }
            } catch (Exception e) {
                log.debug("Ping SSE сессии {} не отправлен — соединение закрыто", sessionId);
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
            SessionEventBroadcaster.Subscription active = subscription;
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
