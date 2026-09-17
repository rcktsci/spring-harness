package se.rocketscien.harness.execution;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Активные Turn'ы этого процесса: {@code sessionId → область отмены}. {@code requestStop}
 * находит здесь живой Turn и прерывает его немедленно; остановка Turn'а на другом инстансе
 * ограничена проверкой флага между вызовами (БД).
 */
@Component
public class ActiveTurnRegistry {

    private final ConcurrentMap<UUID, TurnCancellation> active = new ConcurrentHashMap<>();

    public TurnCancellation register(UUID sessionId) {
        TurnCancellation cancellation = new TurnCancellation();
        active.put(sessionId, cancellation);
        return cancellation;
    }

    public TurnCancellation get(UUID sessionId) {
        return active.get(sessionId);
    }

    public void unregister(UUID sessionId) {
        active.remove(sessionId);
    }
}
