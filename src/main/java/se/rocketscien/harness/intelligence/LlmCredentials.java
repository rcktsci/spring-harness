package se.rocketscien.harness.intelligence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Учётные данные LLM-провайдера (data-model §2). {@code apiKeyEncrypted} — AES-GCM (IV+tag,
 * base64), {@code keyVersion} указывает, каким ключом из конфига ({@code harness.llm.encryption-keys})
 * зашифровано значение (D-M1-3).
 */
@Entity
@Table(name = "llm_credentials")
public class LlmCredentials {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column(name = "api_key_encrypted", nullable = false)
    private String apiKeyEncrypted;

    @Column(name = "key_version", nullable = false)
    private int keyVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LlmCredentials() {
    }

    public LlmCredentials(UUID id, String name, String baseUrl, String apiKeyEncrypted, int keyVersion,
                          Instant createdAt) {
        this.id = id;
        this.name = name;
        this.baseUrl = baseUrl;
        this.apiKeyEncrypted = apiKeyEncrypted;
        this.keyVersion = keyVersion;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKeyEncrypted() {
        return apiKeyEncrypted;
    }

    public int getKeyVersion() {
        return keyVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LlmCredentials that)) {
            return false;
        }
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
