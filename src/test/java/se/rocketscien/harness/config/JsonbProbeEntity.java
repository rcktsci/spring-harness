package se.rocketscien.harness.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "jsonb_probe")
public class JsonbProbeEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_jsonb", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payloadJsonb;

    protected JsonbProbeEntity() {
    }

    public JsonbProbeEntity(UUID id, Map<String, Object> payloadJsonb) {
        this.id = id;
        this.payloadJsonb = payloadJsonb;
    }

    public UUID getId() {
        return id;
    }

    public Map<String, Object> getPayloadJsonb() {
        return payloadJsonb;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JsonbProbeEntity that)) {
            return false;
        }
        return Objects.equals(id, that.id) && Objects.equals(payloadJsonb, that.payloadJsonb);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, payloadJsonb);
    }
}
