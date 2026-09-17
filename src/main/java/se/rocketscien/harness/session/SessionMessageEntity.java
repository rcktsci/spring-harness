package se.rocketscien.harness.session;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Событие append-only журнала сессии (data-model §5, §7): только INSERT, UPDATE/DELETE запрещены —
 * сущность {@link Immutable}, сеттеры отсутствуют, изменение через API невозможно.
 */
@Entity
@Immutable
@Table(name = "session_message")
public class SessionMessageEntity {

    @EmbeddedId
    private SessionMessageId id;

    @Column(name = "id", nullable = false, unique = true)
    private String ulid;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private MessageKind kind;

    @Column(name = "author_user_id")
    private UUID authorUserId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_jsonb", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payloadJsonb;

    @Column(name = "tokens")
    private Integer tokens;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SessionMessageEntity() {
    }

    public SessionMessageEntity(
            SessionMessageId id,
            String ulid,
            MessageKind kind,
            UUID authorUserId,
            Map<String, Object> payloadJsonb,
            Integer tokens,
            Instant createdAt
    ) {
        this.id = id;
        this.ulid = ulid;
        this.kind = kind;
        this.authorUserId = authorUserId;
        this.payloadJsonb = payloadJsonb;
        this.tokens = tokens;
        this.createdAt = createdAt;
    }

    public SessionMessageId getId() {
        return id;
    }

    public String getUlid() {
        return ulid;
    }

    public MessageKind getKind() {
        return kind;
    }

    public UUID getAuthorUserId() {
        return authorUserId;
    }

    public Map<String, Object> getPayloadJsonb() {
        return payloadJsonb;
    }

    public Integer getTokens() {
        return tokens;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SessionMessageEntity that)) {
            return false;
        }
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
