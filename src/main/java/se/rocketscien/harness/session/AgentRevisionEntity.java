package se.rocketscien.harness.session;

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
 * Ревизия агента (data-model §2): иммутабельна — правка = новая строка ревизии.
 */
@Entity
@Immutable
@Table(name = "agent")
public class AgentRevisionEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "key", nullable = false)
    private String agentKey;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "rev", nullable = false)
    private int rev;

    @Column(name = "role_prompt", nullable = false)
    private String rolePrompt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tools_jsonb", columnDefinition = "jsonb")
    private Map<String, Object> toolsJsonb;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "permissions_jsonb", columnDefinition = "jsonb")
    private Map<String, Object> permissionsJsonb;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skills_jsonb", columnDefinition = "jsonb")
    private Map<String, Object> skillsJsonb;

    @Column(name = "llm_model_id", nullable = false)
    private UUID llmModelId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AgentRevisionEntity() {
    }

    public UUID getId() {
        return id;
    }

    public String getAgentKey() {
        return agentKey;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public int getRev() {
        return rev;
    }

    public String getRolePrompt() {
        return rolePrompt;
    }

    public Map<String, Object> getToolsJsonb() {
        return toolsJsonb;
    }

    public Map<String, Object> getPermissionsJsonb() {
        return permissionsJsonb;
    }

    public Map<String, Object> getSkillsJsonb() {
        return skillsJsonb;
    }

    public UUID getLlmModelId() {
        return llmModelId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AgentRevisionEntity that)) {
            return false;
        }
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
