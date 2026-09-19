package se.rocketscien.harness.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Строка {@code workflow} (data-model §3): шаблон без логики — логика в иммутабельных ревизиях
 * ({@link WorkflowRevisionEntity}).
 */
@Entity
@Table(name = "workflow")
public class WorkflowEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "key", nullable = false)
    private String key;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WorkflowEntity() {
    }

    public WorkflowEntity(UUID id, String key, String name, UUID ownerUserId, Instant createdAt) {
        this.id = id;
        this.key = key;
        this.name = name;
        this.ownerUserId = ownerUserId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WorkflowEntity that)) {
            return false;
        }
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
