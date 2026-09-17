package se.rocketscien.harness.intelligence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Конфигурация модели LLM (data-model §2): OpenAI-совместимый {@code base_url} и расшифрованный
 * api_key берутся из связанной записи {@link LlmCredentials}; {@code params_jsonb} — параметры
 * запроса (temperature и т.п.). Строка правится вручную в БД (D-M1-3), кэш актуализируется рестартом.
 */
@Entity
@Table(name = "llm_model")
public class LlmModel {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "credentials_id", nullable = false)
    private UUID credentialsId;

    @Column(name = "model_id", nullable = false)
    private String modelId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params_jsonb", columnDefinition = "jsonb")
    private Map<String, Object> paramsJsonb;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LlmModel() {
    }

    public LlmModel(UUID id, UUID credentialsId, String modelId, Map<String, Object> paramsJsonb, Instant createdAt) {
        this.id = id;
        this.credentialsId = credentialsId;
        this.modelId = modelId;
        this.paramsJsonb = paramsJsonb;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCredentialsId() {
        return credentialsId;
    }

    public String getModelId() {
        return modelId;
    }

    public Map<String, Object> getParamsJsonb() {
        return paramsJsonb;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LlmModel that)) {
            return false;
        }
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
