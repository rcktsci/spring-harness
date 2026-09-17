package se.rocketscien.harness.tests.session;

import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionStore;

import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.common.IdGenerator;

import java.util.UUID;

/**
 * Общая фикстура session-тестов: уникальные пользователь/credentials/model/agent/сессия.
 */
final class SessionTestFixtures {

    private SessionTestFixtures() {
    }

    static Session createSession(JdbcTemplate jdbcTemplate, SessionStore sessionStore, IdGenerator idGenerator) {
        UUID ownerUserId = insertAppUser(jdbcTemplate, idGenerator);
        UUID modelId = insertLlmModel(jdbcTemplate, idGenerator);
        String agentKey = insertAgentRevision(jdbcTemplate, idGenerator, modelId);
        return sessionStore.createFreeSession(ownerUserId, agentKey, null, null);
    }

    static UUID insertAppUser(JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
        UUID userId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                "INSERT INTO app_user (id, keycloak_subject, username, display_name, created_at) VALUES (?, ?, ?, ?, now())",
                userId,
                "subject-" + userId,
                "user-" + userId,
                "Пользователь"
        );
        return userId;
    }

    static UUID insertLlmModel(JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
        UUID credentialsId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                """
                INSERT INTO llm_credentials (id, name, base_url, api_key_encrypted, key_version, created_at)
                VALUES (?, ?, ?, ?, 1, now())
                """,
                credentialsId,
                "creds-" + credentialsId,
                "http://localhost/v1",
                "encrypted"
        );
        UUID modelId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                """
                INSERT INTO llm_model (id, credentials_id, model_id, created_at)
                VALUES (?, ?, ?, now())
                """,
                modelId,
                credentialsId,
                "gpt-test"
        );
        return modelId;
    }

    static String insertAgentRevision(JdbcTemplate jdbcTemplate, IdGenerator idGenerator, UUID modelId) {
        UUID revisionId = idGenerator.newUuidV7();
        String agentKey = "code-agent-" + revisionId;
        jdbcTemplate.update(
                """
                INSERT INTO agent (id, key, name, rev, role_prompt, llm_model_id, created_at)
                VALUES (?, ?, ?, 1, ?, ?, now())
                """,
                revisionId,
                agentKey,
                "Агент",
                "Промпт",
                modelId
        );
        return agentKey;
    }
}
