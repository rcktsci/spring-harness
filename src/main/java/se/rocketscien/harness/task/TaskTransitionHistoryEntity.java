package se.rocketscien.harness.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Запись истории переходов — append-only (data-model §4, §7.1): только INSERT; дописывается
 * транзакционно с CAS-сменой {@code task.current_state} (data-model §7.2). Курсор чтения —
 * пара {@code (created_at, id)} (индекс {@code (task_id, created_at, id)}).
 */
@Entity
@Immutable
@Table(name = "task_transition_history")
public class TaskTransitionHistoryEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "from_state", nullable = false)
    private String fromState;

    @Column(name = "to_state", nullable = false)
    private String toState;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private TransitionKind kind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reason_jsonb", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TaskTransitionHistoryEntity() {
    }

    public TaskTransitionHistoryEntity(UUID id, UUID taskId, String fromState, String toState,
                                       TransitionKind kind, Map<String, Object> reason, Instant createdAt) {
        this.id = id;
        this.taskId = taskId;
        this.fromState = fromState;
        this.toState = toState;
        this.kind = kind;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public String getFromState() {
        return fromState;
    }

    public String getToState() {
        return toState;
    }

    public TransitionKind getKind() {
        return kind;
    }

    public Map<String, Object> getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TaskTransitionHistoryEntity that)) {
            return false;
        }
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
