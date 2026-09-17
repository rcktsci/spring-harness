package se.rocketscien.harness.session;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import se.rocketscien.harness.common.IdGenerator;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

@Repository
@Transactional
public class SessionStoreImpl implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStoreImpl.class);

    private final AgentRevisionRepository agentRevisionRepository;
    private final SessionMessageRepository sessionMessageRepository;
    private final IdGenerator idGenerator;
    private final JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    public SessionStoreImpl(AgentRevisionRepository agentRevisionRepository,
                            SessionMessageRepository sessionMessageRepository,
                            IdGenerator idGenerator,
                            JdbcTemplate jdbcTemplate) {
        this.agentRevisionRepository = agentRevisionRepository;
        this.sessionMessageRepository = sessionMessageRepository;
        this.idGenerator = idGenerator;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Session createFreeSession(UUID ownerUserId, String agentKey, Integer agentRevision, String title) {
        UUID revisionId = resolveRevision(agentKey, agentRevision);

        Instant now = dbNow();
        SessionEntity entity = new SessionEntity(
                idGenerator.newUuidV7(),
                title,
                ownerUserId,
                SessionKind.FREE,
                revisionId,
                now,
                now
        );
        entityManager.persist(entity);

        return toSession(entity);
    }

    @Override
    public AppendedEvent appendEvent(UUID sessionId, MessageKind kind, UUID authorUserId, Map<String, Object> payload) {
        ReservedSeq reserved = reserveSeqByRowLock(sessionId);

        String ulid = idGenerator.newUlid();
        SessionMessageEntity message = new SessionMessageEntity(
                new SessionMessageId(sessionId, reserved.seq()),
                ulid,
                kind,
                authorUserId,
                payload,
                null,
                reserved.now()
        );
        entityManager.persist(message);

        return new AppendedEvent(reserved.seq(), ulid);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionMessageEntity> renderVisible(UUID sessionId) {
        SessionEntity session = entityManager.find(SessionEntity.class, sessionId);
        if (session == null) {
            throw new SessionNotFoundException("Сессия %s не найдена".formatted(sessionId));
        }

        List<VisibilityRenderer.IndexedCovers> compacts = compactCovers(sessionId);
        List<VisibilityRenderer.Cover> visibleIntervals =
                VisibilityRenderer.visibleIntervals(compacts, session.getLastSeq());

        if (visibleIntervals.size() == 1 && 
            visibleIntervals.getFirst().fromSeq() == 1 && 
            visibleIntervals.getFirst().toSeq() == session.getLastSeq()) {
            return sessionMessageRepository.findAllBySessionId(sessionId);
        }
        if (visibleIntervals.isEmpty()) {
            return List.of();
        }
        return selectByIntervals(sessionId, visibleIntervals);
    }

    /**
     * Монотонный seq через транзакционный row-lock строки сессии (D-M1-4): вторая конкурентная
     * допись ждёт блокировку до коммита первой и получает {@code last_seq + 1} без дыр и дублей.
     * Откат транзакции откатывает и резерв seq, и INSERT события — атомарно. Источник времени —
     * БД (единый с {@code last_activity_at}).
     */
    private ReservedSeq reserveSeqByRowLock(UUID sessionId) {
        List<ReservedSeq> reserved = jdbcTemplate.query(
                """
                UPDATE session
                    SET last_seq = last_seq + 1, last_activity_at = now()
                    WHERE id = ?
                    RETURNING last_seq, last_activity_at
                """,
                (rs, rowNum) -> new ReservedSeq(rs.getLong("last_seq"), rs.getTimestamp("last_activity_at").toInstant()),
                sessionId
        );
        if (reserved.isEmpty()) {
            throw new SessionNotFoundException("Сессия %s не найдена".formatted(sessionId));
        }
        return reserved.get(0);
    }

    /**
     * Лёгкая проекция: только seq и payload COMPACT-событий (без payload остальных).
     */
    private List<VisibilityRenderer.IndexedCovers> compactCovers(UUID sessionId) {
        List<Object[]> compactRows = entityManager.createQuery(
                        """
                        SELECT sm.id.seq, sm.payloadJsonb
                        FROM SessionMessageEntity AS sm
                        WHERE sm.id.sessionId = :sessionId
                          AND sm.kind = :kind
                        ORDER BY sm.id.seq ASC
                        """,
                        Object[].class)
                .setParameter("sessionId", sessionId)
                .setParameter("kind", MessageKind.COMPACT)
                .getResultList();

        return compactRows.stream()
                .map(row -> new VisibilityRenderer.IndexedCovers(
                        (Long) row[0],
                        parseCovers(sessionId, (Long) row[0], (Map<String, Object>) row[1])
                ))
                .toList();
    }

    /**
     * Payload видимых — условием по видимым интервалам seq (не списком seq): параметров
     * 2 на интервал, интервалов — единицы даже при покрытии в миллион событий.
     */
    private List<SessionMessageEntity> selectByIntervals(UUID sessionId, List<VisibilityRenderer.Cover> intervals) {
        StringJoiner conditions = new StringJoiner(" OR ", "(", ")");
        for (int i = 0; i < intervals.size(); i++) {
            conditions.add("sm.id.seq BETWEEN :from" + i + " AND :to" + i);
        }
        var query = entityManager.createQuery(
                """
                SELECT sm
                FROM SessionMessageEntity AS sm
                WHERE sm.id.sessionId = :sessionId
                  AND %s
                ORDER BY sm.id.seq ASC
                """.formatted(conditions),
                SessionMessageEntity.class);
        query.setParameter("sessionId", sessionId);
        for (int i = 0; i < intervals.size(); i++) {
            query.setParameter("from" + i, intervals.get(i).fromSeq());
            query.setParameter("to" + i, intervals.get(i).toSeq());
        }
        return query.getResultList();
    }

    /**
     * Lenient-парсинг covers: некорректные элементы отбрасываются с WARN (события не теряют
     * видимость молча).
     */
    private static List<VisibilityRenderer.Cover> parseCovers(UUID sessionId, long compactSeq, Map<String, Object> payload) {
        Object covers = payload == null ? null : payload.get("covers");
        if (!(covers instanceof List<?> list)) {
            if (payload != null && !payload.containsKey("covers")) {
                log.warn("COMPACT seq={} сессии {} без ключа covers", compactSeq, sessionId);
            }
            return List.of();
        }
        List<VisibilityRenderer.Cover> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map
                    && map.get("from") instanceof Number from
                    && map.get("to") instanceof Number to
                    && from.longValue() <= to.longValue()) {
                result.add(new VisibilityRenderer.Cover(from.longValue(), to.longValue()));
            } else {
                log.warn("COMPACT seq={} сессии {} содержит некорректный элемент covers: {}", compactSeq, sessionId, item);
            }
        }
        return result;
    }

    private Instant dbNow() {
        return jdbcTemplate.queryForObject("SELECT now()", Timestamp.class).toInstant();
    }

    private UUID resolveRevision(String agentKey, Integer agentRevision) {
        var revision = agentRevision != null
                ? agentRevisionRepository.findByAgentKeyAndRev(agentKey, agentRevision)
                : agentRevisionRepository.findFirstByAgentKeyOrderByRevDesc(agentKey);

        return revision
                .map(AgentRevisionEntity::getId)
                .orElseThrow(() -> new AgentNotFoundException(
                        "Агент '%s' (ревизия %s) не найден".formatted(agentKey, agentRevision != null ? agentRevision : "latest")));
    }

    private static Session toSession(SessionEntity entity) {
        return new Session(
                entity.getId(),
                entity.getKind(),
                entity.getTitle(),
                entity.getOwnerUserId(),
                entity.getTaskId(),
                entity.getStateCode(),
                entity.getAgentRevisionId(),
                entity.getParentSessionId(),
                entity.isCancelRequested(),
                entity.getLastSeq(),
                entity.getLastConsumedSeq(),
                entity.getLastTurnOutcome(),
                entity.getLastActivityAt(),
                entity.getCreatedAt()
        );
    }

    private record ReservedSeq(long seq, Instant now) {
    }
}
