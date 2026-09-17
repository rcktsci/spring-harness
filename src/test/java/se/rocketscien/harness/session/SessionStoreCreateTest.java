package se.rocketscien.harness.session;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionStoreCreateTest extends BaseApplicationTest {

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    @Test
    void createFreeSessionPinsLatestAgentRevision() {
        String agentKey = "code-agent-" + idGenerator.newUuidV7();
        UUID ownerUserId = SessionTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        UUID modelId = SessionTestFixtures.insertLlmModel(jdbcTemplate, idGenerator);
        UUID rev1 = insertRevision(modelId, agentKey, 1);
        UUID rev2 = insertRevision(modelId, agentKey, 2);

        Session session = sessionStore.createFreeSession(ownerUserId, agentKey, null, "Моя сессия");

        assertThat(session.id()).isNotNull();
        assertThat(session.kind()).isEqualTo(SessionKind.FREE);
        assertThat(session.title()).isEqualTo("Моя сессия");
        assertThat(session.ownerUserId()).isEqualTo(ownerUserId);
        assertThat(session.agentRevisionId()).isEqualTo(rev2);
        assertThat(session.lastSeq()).isZero();
        assertThat(session.lastConsumedSeq()).isZero();
        assertThat(session.lastActivityAt()).isNotNull();
        assertThat(session.createdAt()).isNotNull();

        UUID pinnedInDb = jdbcTemplate.queryForObject(
                "SELECT agent_revision_id FROM session WHERE id = ?",
                UUID.class,
                session.id()
        );

        assertThat(pinnedInDb).isEqualTo(rev2);
        assertThat(pinnedInDb).isNotEqualTo(rev1);
    }

    @Test
    void createFreeSessionPinsExplicitRevision() {
        String agentKey = "code-agent-" + idGenerator.newUuidV7();
        UUID ownerUserId = SessionTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        UUID modelId = SessionTestFixtures.insertLlmModel(jdbcTemplate, idGenerator);
        UUID rev1 = insertRevision(modelId, agentKey, 1);
        insertRevision(modelId, agentKey, 2);

        Session session = sessionStore.createFreeSession(ownerUserId, agentKey, 1, null);

        assertThat(session.agentRevisionId()).isEqualTo(rev1);
    }

    @Test
    void unknownAgentKeyFails() {
        UUID ownerUserId = SessionTestFixtures.insertAppUser(jdbcTemplate, idGenerator);

        assertThatThrownBy(() -> sessionStore.createFreeSession(ownerUserId, "no-such-agent", null, null))
                .isInstanceOf(AgentNotFoundException.class);
    }

    @Test
    void unknownAgentRevisionFails() {
        String agentKey = "code-agent-" + idGenerator.newUuidV7();
        UUID ownerUserId = SessionTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        UUID modelId = SessionTestFixtures.insertLlmModel(jdbcTemplate, idGenerator);
        insertRevision(modelId, agentKey, 1);

        assertThatThrownBy(() -> sessionStore.createFreeSession(ownerUserId, agentKey, 42, null))
                .isInstanceOf(AgentNotFoundException.class);
    }

    private UUID insertRevision(UUID modelId, String agentKey, int rev) {
        UUID revisionId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                """
                INSERT INTO agent (id, key, name, rev, role_prompt, llm_model_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                """,
                revisionId,
                agentKey,
                "Агент " + agentKey,
                rev,
                "Ты — тестовый агент.",
                modelId
        );
        return revisionId;
    }
}
