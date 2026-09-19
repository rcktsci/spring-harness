package se.rocketscien.harness.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Комментарий задачи — append-only, иммутабелен (data-model §4, §7.1): только INSERT,
 * UPDATE/DELETE невозможны через контракт ({@code TaskRegistry}).
 * {@code authorUserId == null} — агентский комментарий (авторство агента — не user).
 */
@Entity
@Immutable
@Table(name = "task_comment")
public class TaskCommentEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "author_user_id")
    private UUID authorUserId;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TaskCommentEntity() {
    }

    public TaskCommentEntity(UUID id, UUID taskId, UUID authorUserId, String body, Instant createdAt) {
        this.id = id;
        this.taskId = taskId;
        this.authorUserId = authorUserId;
        this.body = body;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public UUID getAuthorUserId() {
        return authorUserId;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TaskCommentEntity that)) {
            return false;
        }
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
