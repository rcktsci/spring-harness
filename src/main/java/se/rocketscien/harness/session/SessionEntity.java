package se.rocketscien.harness.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Строка {@code session} (data-model §5). Денормализации {@code lastSeq/lastConsumedSeq}
 * обновляются только транзакционно с дописью сообщений (см. {@code session.impl.SessionStoreImpl}).
 */
@Entity
@Table(name = "session")
public class SessionEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "title")
    private String title;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private SessionKind kind;

    @Column(name = "task_id")
    private UUID taskId;

    @Column(name = "state_code")
    private String stateCode;

    @Column(name = "agent_revision_id")
    private UUID agentRevisionId;

    @Column(name = "parent_session_id")
    private UUID parentSessionId;

    /** Глубина в дереве сессий: root = 0, субагентская = parent.depth + 1 (D-61, миграция 017). */
    @Column(name = "depth", nullable = false)
    private int depth;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    @Column(name = "last_consumed_seq", nullable = false)
    private long lastConsumedSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_turn_outcome", length = 16)
    private TurnOutcome lastTurnOutcome;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SessionEntity() {
    }

    public SessionEntity(UUID id, String title, UUID ownerUserId, SessionKind kind, UUID agentRevisionId,
                         Instant lastActivityAt, Instant createdAt) {
        this.id = id;
        this.title = title;
        this.ownerUserId = ownerUserId;
        this.kind = kind;
        this.agentRevisionId = agentRevisionId;
        this.lastActivityAt = lastActivityAt;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public SessionKind getKind() {
        return kind;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public String getStateCode() {
        return stateCode;
    }

    public UUID getAgentRevisionId() {
        return agentRevisionId;
    }

    public UUID getParentSessionId() {
        return parentSessionId;
    }

    public void setParentSessionId(UUID parentSessionId) {
        this.parentSessionId = parentSessionId;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public long getLastConsumedSeq() {
        return lastConsumedSeq;
    }

    public TurnOutcome getLastTurnOutcome() {
        return lastTurnOutcome;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SessionEntity that)) {
            return false;
        }
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
