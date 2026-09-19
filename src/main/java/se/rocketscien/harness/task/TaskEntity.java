package se.rocketscien.harness.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Строка {@code task} (data-model §4). Денормализации ({@code current_state_kind},
 * {@code status_projection}, {@code deadline_at}) обновляются только транзакционно со сменой
 * {@code current_state} — атомарный CAS движка переходов (data-model §7.2).
 * {@code params_jsonb} иммутабельны после создания (api §4.1) — сеттера нет.
 */
@Entity
@Table(name = "task")
public class TaskEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "author_user_id")
    private UUID authorUserId;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "workflow_revision_id", nullable = false)
    private UUID workflowRevisionId;

    @Column(name = "current_state", nullable = false)
    private String currentState;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_state_kind", nullable = false, length = 16)
    private TaskStateKind currentStateKind;

    @Column(name = "state_attempt", nullable = false)
    private int stateAttempt;

    @Column(name = "task_event_seq", nullable = false)
    private long taskEventSeq;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_projection", nullable = false, length = 16)
    private TaskStatus statusProjection;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params_jsonb", columnDefinition = "jsonb")
    private Map<String, Object> params;

    @Column(name = "parent_task_id")
    private UUID parentTaskId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tags", nullable = false, columnDefinition = "text[]")
    private List<String> tags = new ArrayList<>();

    @Column(name = "suspended", nullable = false)
    private boolean suspended;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TaskEntity() {
    }

    public TaskEntity(UUID id, String title, String description, UUID authorUserId, UUID ownerUserId,
                      UUID workflowRevisionId, String currentState, TaskStateKind currentStateKind,
                      int stateAttempt, long taskEventSeq, Instant deadlineAt, TaskStatus statusProjection,
                      Map<String, Object> params, UUID parentTaskId, List<String> tags, boolean suspended,
                      Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.authorUserId = authorUserId;
        this.ownerUserId = ownerUserId;
        this.workflowRevisionId = workflowRevisionId;
        this.currentState = currentState;
        this.currentStateKind = currentStateKind;
        this.stateAttempt = stateAttempt;
        this.taskEventSeq = taskEventSeq;
        this.deadlineAt = deadlineAt;
        this.statusProjection = statusProjection;
        this.params = params;
        this.parentTaskId = parentTaskId;
        this.tags = tags;
        this.suspended = suspended;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getAuthorUserId() {
        return authorUserId;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public UUID getWorkflowRevisionId() {
        return workflowRevisionId;
    }

    public String getCurrentState() {
        return currentState;
    }

    public TaskStateKind getCurrentStateKind() {
        return currentStateKind;
    }

    public int getStateAttempt() {
        return stateAttempt;
    }

    public long getTaskEventSeq() {
        return taskEventSeq;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public TaskStatus getStatusProjection() {
        return statusProjection;
    }

    /** Иммутабельны после создания (api §4.1): доступ только для чтения. */
    public Map<String, Object> getParams() {
        return params;
    }

    public UUID getParentTaskId() {
        return parentTaskId;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public boolean isSuspended() {
        return suspended;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Денормализация двигается только транзакционно с изменением строки (patch и движок). */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TaskEntity that)) {
            return false;
        }
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
