package se.rocketscien.harness.session;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Доступ к append-only журналу {@code session_message}.
 *
 * <p>Контракт (data-model §7.1): события только дописываются — интерфейс не содержит
 * методов изменения/удаления ({@code save}/{@code update}/{@code delete} отсутствуют),
 * сущность {@link SessionMessageEntity} иммутабельна. Монотонность {@code seq}
 * обеспечивает дописывающая сторона (транзакционный row-lock строки сессии, D-M1-4),
 * а не этот репозиторий.</p>
 */
public interface SessionMessageRepository {

    SessionMessageEntity append(SessionMessageEntity message);

    Optional<SessionMessageEntity> find(UUID sessionId, long seq);

    List<SessionMessageEntity> findAllBySessionId(UUID sessionId);
}
