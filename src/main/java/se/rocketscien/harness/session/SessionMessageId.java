package se.rocketscien.harness.session;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Составной первичный ключ события: сессия + монотонный {@code seq} (data-model §5).
 */
@Embeddable
public record SessionMessageId(
        @Column(name = "session_id", nullable = false)
        UUID sessionId,

        @Column(name = "seq", nullable = false)
        long seq
) implements Serializable {

    static final long serialVersionUID = 1L;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SessionMessageId that)) {
            return false;
        }
        return seq == that.seq && Objects.equals(sessionId, that.sessionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, seq);
    }
}
