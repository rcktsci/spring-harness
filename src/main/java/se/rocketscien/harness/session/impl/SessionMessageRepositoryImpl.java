package se.rocketscien.harness.session.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import se.rocketscien.harness.session.SessionMessageEntity;
import se.rocketscien.harness.session.SessionMessageId;
import se.rocketscien.harness.session.SessionMessageRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SessionMessageRepositoryImpl implements SessionMessageRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public SessionMessageEntity append(SessionMessageEntity message) {
        entityManager.persist(message);
        return message;
    }

    @Override
    public Optional<SessionMessageEntity> find(UUID sessionId, long seq) {
        return Optional.ofNullable(entityManager.find(SessionMessageEntity.class, new SessionMessageId(sessionId, seq)));
    }

    @Override
    public List<SessionMessageEntity> findAllBySessionId(UUID sessionId) {
        return entityManager.createQuery("""
                        SELECT sm
                        FROM SessionMessageEntity AS sm
                        WHERE sm.id.sessionId = :sessionId
                        ORDER BY sm.id.seq ASC
                        """,
                        SessionMessageEntity.class)
                .setParameter("sessionId", sessionId)
                .getResultList();
    }
}
