package se.rocketscien.harness.session.impl;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import se.rocketscien.harness.session.SessionEvent;
import se.rocketscien.harness.session.SessionEventBroadcaster;
import se.rocketscien.harness.session.SessionEventListener;
import se.rocketscien.harness.session.SessionRuntimeStatus;
import se.rocketscien.harness.session.SessionStore;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-memory доставка событий сессии (D-M1-5): подписчики — список на сессию, сообщения
 * доставляются строго по возрастанию seq (буфер переупорядочения: seq резервируется row-lock'ом
 * раньше публикации, поэтому соседние дописи могут прийти в обратном порядке — дренаж по
 * смежности закрывает дыру; вечных дыр не бывает — резерв seq сериализован блокировкой строки).
 *
 * <p>D-J-5: broadcaster — in-memory и живёт в границах процесса. {@code lastDeliveredSeq} —
 * деталь буфера переупорядочения, НЕ источник истины: после рестарта процесса он начинается
 * с нуля и не восстанавливается, события, опубликованные до старта текущего процесса,
 * подписчикам не доставляются. Восстановление потока при коннекте/реконнекте SSE — бэкфилл
 * из {@link SessionStore#renderVisible}/{@code ?since=} (эндпоинт 8.5), broadcaster — только
 * живая доставка поверх этого базлайна.</p>
 */
@Component
@Slf4j
public class InMemorySessionEventBroadcaster implements SessionEventBroadcaster, SessionEventListener {


    private final ConcurrentMap<UUID, SessionState> sessions = new ConcurrentHashMap<>();

    @Override
    public SessionRuntimeStatus runtimeStatus(UUID sessionId) {
        SessionState state = sessions.get(sessionId);
        return state == null ? SessionRuntimeStatus.IDLE : state.runtimeStatus;
    }

    @Override
    public void publishStatus(UUID sessionId, SessionRuntimeStatus status) {
        onEvent(new SessionEvent.StatusChanged(sessionId, status));
    }

    @Override
    public Subscription subscribe(UUID sessionId, Consumer<SessionEvent> consumer) {
        SessionState state = state(sessionId);
        state.subscribers.add(consumer);
        return () -> state.subscribers.remove(consumer);
    }

    @Override
    public void onEvent(SessionEvent event) {
        if (event instanceof SessionEvent.MessageCreated message) {
            synchronized (state(message.sessionId())) {
                bufferAndDrain(message);
            }
        } else if (event instanceof SessionEvent.StatusChanged status) {
            SessionState state = state(status.sessionId());
            state.runtimeStatus = status.runtimeStatus();
            deliver(state, status);
        }
    }

    private void bufferAndDrain(SessionEvent.MessageCreated message) {
        SessionState state = state(message.sessionId());
        if (message.seq() <= state.lastDeliveredSeq) {
            log.warn("Дубликат/опоздавшее событие seq={} сессии {} — пропущено (доставлено до seq={})",
                    message.seq(), message.sessionId(), state.lastDeliveredSeq);
            return;
        }
        state.pending.put(message.seq(), message);
        while (true) {
            SessionEvent.MessageCreated next = state.pending.remove(state.lastDeliveredSeq + 1);
            if (next == null) {
                return;
            }
            state.lastDeliveredSeq = next.seq();
            deliver(state, next);
        }
    }

    private void deliver(SessionState state, SessionEvent event) {
        for (Consumer<SessionEvent> subscriber : state.subscribers) {
            try {
                subscriber.accept(event);
            } catch (Exception e) {
                log.warn("Подписчик сессии {} упал на событии {}: {}", event instanceof SessionEvent.MessageCreated m
                        ? m.sessionId() : ((SessionEvent.StatusChanged) event).sessionId(),
                        event, e.getMessage());
            }
        }
    }

    private SessionState state(UUID sessionId) {
        return sessions.computeIfAbsent(sessionId, id -> new SessionState());
    }

    private static final class SessionState {
        private final List<Consumer<SessionEvent>> subscribers = new CopyOnWriteArrayList<>();
        private final Map<Long, SessionEvent.MessageCreated> pending = new TreeMap<>();
        private volatile SessionRuntimeStatus runtimeStatus = SessionRuntimeStatus.IDLE;
        private long lastDeliveredSeq;
    }
}
