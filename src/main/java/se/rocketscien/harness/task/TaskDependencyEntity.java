package se.rocketscien.harness.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Ребро blocked_by ({@code task_dependency}, data-model §4): blocker должен прийти в нужный
 * терминал, пока blocked ждёт; PK — пара. Циклы (включая транзитивные) запрещены — DFS-валидация
 * в {@code TaskRegistryImpl}.
 */
@Entity
@IdClass(TaskDependencyEntity.Pk.class)
@Table(name = "task_dependency")
public class TaskDependencyEntity {

    @Id
    @Column(name = "blocker_task_id", nullable = false)
    private UUID blockerTaskId;

    @Id
    @Column(name = "blocked_task_id", nullable = false)
    private UUID blockedTaskId;

    protected TaskDependencyEntity() {
    }

    public TaskDependencyEntity(UUID blockerTaskId, UUID blockedTaskId) {
        this.blockerTaskId = blockerTaskId;
        this.blockedTaskId = blockedTaskId;
    }

    public UUID getBlockerTaskId() {
        return blockerTaskId;
    }

    public UUID getBlockedTaskId() {
        return blockedTaskId;
    }

    /** PK-пара (blocker, blocked). */
    public static class Pk implements Serializable {

        private UUID blockerTaskId;
        private UUID blockedTaskId;

        protected Pk() {
        }

        public Pk(UUID blockerTaskId, UUID blockedTaskId) {
            this.blockerTaskId = blockerTaskId;
            this.blockedTaskId = blockedTaskId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Pk that)) {
                return false;
            }
            return Objects.equals(blockerTaskId, that.blockerTaskId)
                    && Objects.equals(blockedTaskId, that.blockedTaskId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(blockerTaskId, blockedTaskId);
        }
    }
}
