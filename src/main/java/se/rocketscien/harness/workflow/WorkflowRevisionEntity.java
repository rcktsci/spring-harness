package se.rocketscien.harness.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Иммутабельная ревизия workflow (data-model §3, §7.5): UPDATE запрещён — правка = новая строка.
 * Задачи пинятся к конкретной ревизии ({@code task.workflow_revision_id}).
 */
@Entity
@Immutable
@Table(name = "workflow_revision")
public class WorkflowRevisionEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "rev", nullable = false)
    private int rev;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "graph_jsonb", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> graphJsonb;

    /** Стартовое состояние ревизии (H-1): ∈ codes графа (валидатор), задачи стартуют в нём. */
    @Column(name = "start_state", nullable = false)
    private String startState;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WorkflowRevisionEntity() {
    }

    public WorkflowRevisionEntity(UUID id, UUID workflowId, int rev, Map<String, Object> graphJsonb,
                                  String startState, Instant createdAt) {
        this.id = id;
        this.workflowId = workflowId;
        this.rev = rev;
        this.graphJsonb = graphJsonb;
        this.startState = startState;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public int getRev() {
        return rev;
    }

    public Map<String, Object> getGraphJsonb() {
        return graphJsonb;
    }

    public String getStartState() {
        return startState;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WorkflowRevisionEntity that)) {
            return false;
        }
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
