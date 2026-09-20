package se.rocketscien.harness.session.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.session.AgentNotFoundException;
import se.rocketscien.harness.session.AgentRevisionEntity;
import se.rocketscien.harness.session.AgentRevisionRepository;
import se.rocketscien.harness.session.InvalidCursorException;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionEntity;
import se.rocketscien.harness.session.SessionEvent;
import se.rocketscien.harness.session.SessionEventListener;
import se.rocketscien.harness.session.SessionKind;
import se.rocketscien.harness.session.SessionMessageEntity;
import se.rocketscien.harness.session.SessionMessageId;
import se.rocketscien.harness.session.SessionMessageRepository;
import se.rocketscien.harness.session.SessionNotFoundException;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.session.TurnOutcome;
import se.rocketscien.harness.session.VisibilityRenderer;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

@Repository
@Transactional
@RequiredArgsConstructor
@Slf4j
public class SessionStoreImpl implements SessionStore {


    private final AgentRevisionRepository agentRevisionRepository;
    private final SessionMessageRepository sessionMessageRepository;
    private final IdGenerator idGenerator;
    private final JdbcTemplate jdbcTemplate;
    private final List<SessionEventListener> eventListeners;

    @PersistenceContext
    private EntityManager entityManager;

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
    public Session createChildSession(UUID parentSessionId, String agentKey, String title) {
        SessionEntity parent = entityManager.find(SessionEntity.class, parentSessionId);
        if (parent == null) {
            throw new SessionNotFoundException("Сессия %s не найдена".formatted(parentSessionId));
        }
        UUID revisionId = resolveRevision(agentKey, null);

        Instant now = dbNow();
        SessionEntity child = new SessionEntity(
                idGenerator.newUuidV7(),
                title,
                parent.getOwnerUserId(),
                SessionKind.FREE,
                revisionId,
                now,
                now
        );
        child.setParentSessionId(parent.getId());
        child.setDepth(parent.getDepth() + 1);
        entityManager.persist(child);

        return toSession(child);
    }

    @Override
    @Transactional(readOnly = true)
    public String findLastAssistantText(UUID sessionId) {
        List<String> texts = jdbcTemplate.queryForList(
                "SELECT payload_jsonb ->> 'text' AS text FROM session_message"
                        + " WHERE session_id = ? AND kind = 'ASSISTANT' ORDER BY seq DESC LIMIT 1",
                String.class, sessionId);
        return texts.isEmpty() ? null : texts.getFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MessageRef> findMessageRef(String messageId) {
        List<MessageRef> refs = jdbcTemplate.query(
                "SELECT session_id, seq, kind FROM session_message WHERE id = ?",
                (rs, rowNum) -> new MessageRef(
                        rs.getObject("session_id", UUID.class),
                        rs.getLong("seq"),
                        MessageKind.valueOf(rs.getString("kind"))),
                messageId);
        return refs.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionMessageEntity> findCompactedOriginals(UUID sessionId, long seq) {
        List<VisibilityRenderer.IndexedCovers> compacts = compactCovers(sessionId);
        VisibilityRenderer.IndexedCovers target = null;
        for (VisibilityRenderer.IndexedCovers compact : compacts) {
            boolean coversTarget = compact.compactSeq() == seq
                    || compact.covers().stream().anyMatch(cover ->
                            seq >= cover.fromSeq() && seq <= cover.toSeq());
            if (coversTarget) {
                target = compact;
            }
        }
        if (target == null || target.covers().isEmpty()) {
            return List.of();
        }
        return selectByIntervals(sessionId, target.covers());
    }

    @Override
    public AppendedEvent appendEvent(UUID sessionId, MessageKind kind, UUID authorUserId, Map<String, Object> payload) {
        return appendEvent(sessionId, kind, authorUserId, payload, null);
    }

    @Override
    public AppendedEvent appendEvent(UUID sessionId, MessageKind kind, UUID authorUserId, Map<String, Object> payload,
                                     Integer tokens) {
        ReservedSeq reserved = reserveSeqByRowLock(sessionId);

        String ulid = idGenerator.newUlid();
        SessionMessageEntity message = new SessionMessageEntity(
                new SessionMessageId(sessionId, reserved.seq()),
                ulid,
                kind,
                authorUserId,
                payload,
                tokens,
                reserved.now()
        );
        entityManager.persist(message);
        if (kind == MessageKind.USER) {
            // Явный resume (O-2): USER-сообщение снимает персистентный stop
            // (cancel_requested от requestStop держится до явного старта — гейт
            // TurnManager.tryStart), иначе остановленная сессия не ожила бы никогда.
            // Тот же инструмент неявно резюмирует сессию и в M1-семантике (спека agent-turn:
            // «stop по IDLE не гасит новые ходы»).
            jdbcTemplate.update("UPDATE session SET cancel_requested = false WHERE id = ?", sessionId);
        }
        notifyListenersAfterCommit(message);

        return new AppendedEvent(reserved.seq(), ulid);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasToolResultForCall(UUID sessionId, String callId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_message"
                        + " WHERE session_id = ? AND kind = 'TOOL_RESULT' AND payload_jsonb ->> 'callId' = ?",
                Integer.class, sessionId, callId);
        return count != null && count > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PendingAsyncCall> findExpiredAsyncAccepteds(Instant createdBefore) {
        List<Object[]> rows = jdbcTemplate.query(
                """
                SELECT sm.session_id, sm.payload_jsonb ->> 'callId' AS call_id,
                       sm.payload_jsonb ->> 'tool' AS tool, sm.created_at
                FROM session_message AS sm
                WHERE sm.kind = 'ASYNC_ACCEPTED'
                  AND sm.created_at < ?
                  AND sm.payload_jsonb ->> 'callId' IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM session_message AS tr
                      WHERE tr.session_id = sm.session_id
                        AND tr.kind = 'TOOL_RESULT'
                        AND tr.payload_jsonb ->> 'callId' = sm.payload_jsonb ->> 'callId'
                  )
                ORDER BY sm.session_id, sm.id
                """,
                (rs, rowNum) -> new Object[] {
                        rs.getObject("session_id", UUID.class),
                        rs.getString("call_id"),
                        rs.getString("tool"),
                        rs.getTimestamp("created_at").toInstant()
                },
                Timestamp.from(createdBefore));
        return rows.stream()
                .map(row -> new PendingAsyncCall((UUID) row[0], (String) row[1], (String) row[2], (Instant) row[3]))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Session> findSession(UUID sessionId) {
        return Optional.ofNullable(entityManager.find(SessionEntity.class, sessionId)).map(SessionStoreImpl::toSession);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Session> findSubtree(UUID sessionId, Integer depth) {
        List<Session> rows = jdbcTemplate.query("""
                WITH RECURSIVE subtree AS (
                    SELECT id, 0 AS depth FROM session WHERE id = ?
                    UNION ALL
                    SELECT s.id, t.depth + 1 FROM session s JOIN subtree t ON s.parent_session_id = t.id
                )
                SELECT s.id, s.title, s.owner_user_id, s.kind, s.task_id, s.state_code,
                       s.agent_revision_id, s.parent_session_id, s.depth, s.cancel_requested, s.last_seq,
                       s.last_consumed_seq, s.last_turn_outcome, s.last_activity_at, s.created_at
                FROM session s
                JOIN subtree t ON s.id = t.id
                WHERE ?::int IS NULL OR t.depth <= ?::int
                ORDER BY t.depth, s.created_at, s.id
                """,
                (rs, rowNum) -> new Session(
                        rs.getObject("id", UUID.class),
                        SessionKind.valueOf(rs.getString("kind")),
                        rs.getString("title"),
                        rs.getObject("owner_user_id", UUID.class),
                        rs.getObject("task_id", UUID.class),
                        rs.getString("state_code"),
                        rs.getObject("agent_revision_id", UUID.class),
                        rs.getObject("parent_session_id", UUID.class),
                        rs.getInt("depth"),
                        rs.getBoolean("cancel_requested"),
                        rs.getLong("last_seq"),
                        rs.getLong("last_consumed_seq"),
                        rs.getString("last_turn_outcome") == null
                                ? null : TurnOutcome.valueOf(rs.getString("last_turn_outcome")),
                        rs.getTimestamp("last_activity_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()
                ),
                sessionId, depth, depth);
        if (rows.isEmpty()) {
            throw new SessionNotFoundException("Сессия %s не найдена".formatted(sessionId));
        }
        return rows;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findEligibleSessionIds() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM session WHERE last_seq > last_consumed_seq", UUID.class);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findAllSessionIds() {
        return jdbcTemplate.queryForList("SELECT id FROM session", UUID.class);
    }

    @Override
    public void requestCancel(UUID sessionId) {
        jdbcTemplate.update("UPDATE session SET cancel_requested = true WHERE id = ?", sessionId);
    }

    @Override
    public void resetCancelRequested(UUID sessionId) {
        jdbcTemplate.update("UPDATE session SET cancel_requested = false WHERE id = ?", sessionId);
    }

    @Override
    public void finishTurn(UUID sessionId, TurnOutcome outcome, long consumedSeq) {
        jdbcTemplate.update(
                "UPDATE session SET last_consumed_seq = GREATEST(last_consumed_seq, ?),"
                        + " last_turn_outcome = ?, cancel_requested = false WHERE id = ?",
                consumedSeq, outcome.name(), sessionId);
    }

    @Override
    @Transactional(readOnly = true)
    public AgentRuntime agentRuntime(UUID agentRevisionId) {
        AgentRevisionEntity revision = entityManager.find(AgentRevisionEntity.class, agentRevisionId);
        if (revision == null) {
            throw new AgentNotFoundException("Ревизия агента %s не найдена".formatted(agentRevisionId));
        }
        return new AgentRuntime(
                revision.getId(),
                revision.getAgentKey(),
                revision.getRev(),
                revision.getRolePrompt(),
                revision.getToolsJsonb(),
                revision.getPermissionsJsonb(),
                revision.getLlmModelId()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findSessionIdsWithPendingToolCalls() {
        return jdbcTemplate.queryForList(
                """
                SELECT DISTINCT sm.session_id
                FROM session_message AS sm
                WHERE sm.kind = 'TOOL_CALL'
                  AND NOT EXISTS (
                      SELECT 1 FROM session_message AS tr
                      WHERE tr.session_id = sm.session_id
                        AND tr.kind = 'TOOL_RESULT'
                        AND tr.payload_jsonb ->> 'callId' = sm.payload_jsonb ->> 'callId'
                  )
                """,
                UUID.class);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionMessageEntity> findPendingToolCalls(UUID sessionId) {
        List<SessionMessageEntity> toolEvents = entityManager.createQuery(
                        """
                        SELECT sm
                        FROM SessionMessageEntity AS sm
                        WHERE sm.id.sessionId = :sessionId
                          AND sm.kind IN (:toolCall, :toolResult)
                        ORDER BY sm.id.seq ASC
                        """,
                        SessionMessageEntity.class)
                .setParameter("sessionId", sessionId)
                .setParameter("toolCall", MessageKind.TOOL_CALL)
                .setParameter("toolResult", MessageKind.TOOL_RESULT)
                .getResultList();

        Set<String> answered = new HashSet<>();
        for (SessionMessageEntity event : toolEvents) {
            if (event.getKind() == MessageKind.TOOL_RESULT
                    && event.getPayloadJsonb() != null
                    && event.getPayloadJsonb().get("callId") instanceof String callId) {
                answered.add(callId);
            }
        }
        return toolEvents.stream()
                .filter(event -> event.getKind() == MessageKind.TOOL_CALL)
                .filter(event -> !(event.getPayloadJsonb() != null
                        && event.getPayloadJsonb().get("callId") instanceof String callId
                        && answered.contains(callId)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageKind> findPendingKinds(UUID sessionId, long afterSeq) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT kind FROM session_message WHERE session_id = ? AND seq > ?",
                MessageKind.class, sessionId, afterSeq);
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

    @Override
    @Transactional(readOnly = true)
    public SessionSearchResult searchSessions(SessionSearchCriteria criteria) throws InvalidCursorException {
        List<String> conditions = new ArrayList<>();

        if (criteria.ownerUserId() != null) {
            conditions.add("s.ownerUserId = :owner");
        }
        if (criteria.kind() != null) {
            conditions.add("s.kind = :kind");
        }
        if (criteria.titleContains() != null && !criteria.titleContains().isEmpty()) {
            conditions.add("s.title LIKE :titleContains ESCAPE '\\'");
        }
        if (criteria.cursor() != null) {
            // JPQL не поддерживает tuple-сравнение — разворачиваем (a, id) < (a0, id0) явно
            conditions.add("(s.lastActivityAt < :cursorActivity"
                    + " OR (s.lastActivityAt = :cursorActivity AND s.id < :cursorId))");
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        String jpql = "SELECT s FROM SessionEntity s" + where
                + " ORDER BY s.lastActivityAt DESC, s.id DESC";

        var query = entityManager.createQuery(jpql, SessionEntity.class);
        if (criteria.ownerUserId() != null) {
            query.setParameter("owner", criteria.ownerUserId());
        }
        if (criteria.kind() != null) {
            query.setParameter("kind", criteria.kind());
        }
        if (criteria.titleContains() != null && !criteria.titleContains().isEmpty()) {
            query.setParameter("titleContains", "%" + escapeLike(criteria.titleContains()) + "%");
        }
        if (criteria.cursor() != null) {
            CursorPosition cursor = decodeCursor(criteria.cursor());
            query.setParameter("cursorActivity", cursor.lastActivityAt());
            query.setParameter("cursorId", cursor.sessionId());
        }

        // limit + 1: наличие (limit+1)-й строки — признак следующей страницы
        List<SessionEntity> rows = query.setMaxResults(criteria.limit() + 1).getResultList();

        boolean hasMore = rows.size() > criteria.limit();
        List<SessionEntity> page = hasMore ? rows.subList(0, criteria.limit()) : rows;
        List<Session> items = page.stream().map(SessionStoreImpl::toSession).toList();
        String nextCursor = hasMore
                ? encodeCursor(page.getLast().getLastActivityAt(), page.getLast().getId())
                : null;
        return new SessionSearchResult(items, nextCursor);
    }

    @Override
    public void renameSession(UUID sessionId, String newTitle) {
        int updated = jdbcTemplate.update("UPDATE session SET title = ? WHERE id = ?", newTitle, sessionId);
        if (updated == 0) {
            throw new SessionNotFoundException("Сессия %s не найдена".formatted(sessionId));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentRevisionSummary> agentCatalog() {
        return agentRevisionRepository.findLatestRevisions().stream()
                .map(SessionStoreImpl::toSummary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, AgentRevisionSummary> agentSummaries(Collection<UUID> revisionIds) {
        if (revisionIds.isEmpty()) {
            return Map.of();
        }
        List<AgentRevisionEntity> revisions = entityManager.createQuery(
                        "SELECT a FROM AgentRevisionEntity a WHERE a.id IN (:ids)",
                        AgentRevisionEntity.class)
                .setParameter("ids", revisionIds)
                .getResultList();
        Map<UUID, AgentRevisionSummary> result = new LinkedHashMap<>();
        for (AgentRevisionEntity revision : revisions) {
            result.put(revision.getId(), toSummary(revision));
        }
        return result;
    }

    private static AgentRevisionSummary toSummary(AgentRevisionEntity revision) {
        return new AgentRevisionSummary(
                revision.getId(),
                revision.getAgentKey(),
                revision.getRev(),
                revision.getName(),
                revision.getDescription()
        );
    }

    /** LIKE-маскирование %, _ и escape-символа — пользовательская подстрока, а не паттерн. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Непрозрачный курсор страницы: ISO-8601 время последнего элемента (полная точность
     * Instant — микросекунды Postgres не теряются, E-J-3) + id, Base64-URL.
     */
    private static String encodeCursor(Instant lastActivityAt, UUID sessionId) {
        String raw = lastActivityAt + "|" + sessionId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    private static CursorPosition decodeCursor(String cursor) throws InvalidCursorException {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            int separator = raw.indexOf('|');
            return new CursorPosition(
                    Instant.parse(raw.substring(0, separator)),
                    UUID.fromString(raw.substring(separator + 1)));
        } catch (RuntimeException e) {
            throw new InvalidCursorException("Некорректный курсор страницы", e);
        }
    }

    private record CursorPosition(Instant lastActivityAt, UUID sessionId) {
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
                entity.getDepth(),
                entity.isCancelRequested(),
                entity.getLastSeq(),
                entity.getLastConsumedSeq(),
                entity.getLastTurnOutcome(),
                entity.getLastActivityAt(),
                entity.getCreatedAt()
        );
    }

    /**
     * Уведомление слушателей (broadcaster) строго после коммита: незакоммиченное событие
     * доставить нельзя (подписчик может прочитать журнал и не найти его).
     */
    private void notifyListenersAfterCommit(SessionMessageEntity message) {
        SessionEvent.MessageCreated event = new SessionEvent.MessageCreated(
                message.getId().sessionId(),
                message.getId().seq(),
                message.getUlid(),
                message.getKind(),
                message.getAuthorUserId(),
                message.getPayloadJsonb(),
                message.getTokens(),
                message.getCreatedAt()
        );
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(event);
                }
            });
        } else {
            publish(event);
        }
    }

    private void publish(SessionEvent event) {
        for (SessionEventListener listener : eventListeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.warn("Слушатель событий {} упал на {}: {}", listener.getClass().getSimpleName(), event, e.getMessage());
            }
        }
    }

    private record ReservedSeq(long seq, Instant now) {
    }
}
