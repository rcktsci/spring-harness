package se.rocketscien.harness.relay;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory реестр активных WS-соединений релея, ключ — {@code sessionId} (M4, D-78):
 * одна сессия — одно активное соединение. Реестр не персистится (D-80): рестарт процесса
 * обнуляет его, а осиротевшие вызовы закрывает рестарт-скан (пачка W).
 *
 * <p>Политика регистрации ({@link #register}): свободная сессия — добавить; живое соединение
 * того же principal — takeover (старое закрывается 4409 {@code superseded}, новое становится
 * активным); иной principal — {@link RegisterOutcome#OCCUPIED} (старое не трогается). Повторная
 * регистрация того же соединения — идемпотентный успех. {@link #unregister} — CAS по
 * identity соединения: протухший сокет не удаляет сменившее его новое (D-78).</p>
 */
@Component
@Slf4j
public class RelayConnectionRegistry {

    private final Map<UUID, RelayConnection> connections = new ConcurrentHashMap<>();

    /** Итог регистрации: {@code REGISTERED} (в т.ч. takeover) либо {@code OCCUPIED} (иной principal). */
    public enum RegisterOutcome {
        REGISTERED,
        OCCUPIED
    }

    /**
     * Регистрирует соединение на сессии по политике takeover/занятости (D-78). Атомарность —
     * через {@code compute} по ключу: решение о вытеснении принимается под бин-локом, закрытие
     * вытесненного соединения выполняется уже вне {@code compute}.
     */
    public RegisterOutcome register(UUID sessionId, RelayConnection connection) {
        Decision decision = new Decision();
        connections.compute(sessionId, (key, existing) -> {
            if (existing == null || existing == connection) {
                decision.registered = true;
                return connection;
            }
            if (existing.principal().equals(connection.principal())) {
                decision.registered = true;
                decision.superseded = existing;
                return connection;
            }
            log.warn("Реле: сессия {} занята соединением другого principal", sessionId);
            return existing;
        });
        RelayConnection superseded = decision.superseded;
        if (superseded != null) {
            log.info("Реле: takeover сессии {} — старое соединение вытеснено (4409 superseded)", sessionId);
            superseded.close(RelayCloseCodes.CONFLICT, "superseded");
        }
        return decision.registered ? RegisterOutcome.REGISTERED : RegisterOutcome.OCCUPIED;
    }

    /**
     * CAS-удаление по identity соединения (D-78): удаляет запись, только если она всё ещё
     * указывает на это соединение; протухший heartbeat старого сокета не вытесняет новое.
     */
    public boolean unregister(UUID sessionId, RelayConnection connection) {
        return connections.remove(sessionId, connection);
    }

    /**
     * Активное соединение сессии. Резолв по parent-цепочке (для sub-сессий, D-84) — пачка V:
     * здесь только прямое соответствие {@code sessionId}.
     */
    public Optional<RelayConnection> findForSession(UUID sessionId) {
        return Optional.ofNullable(connections.get(sessionId));
    }

    private static final class Decision {
        private boolean registered;
        private RelayConnection superseded;
    }
}
