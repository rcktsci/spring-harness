package se.rocketscien.harness.tests.execution;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.common.AesGcmEncryption;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionStore;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Фикстуры интеграционных тестов исполнения: пользователь → credentials (WireMock-LLM) → модель →
 * агент (все нативные инструменты) → сессия. Ключ шифрования совпадает с
 * {@code harness.llm.encryption-keys.1} из application-test.yml.
 */
final class ExecutionFixtures {

    static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private ExecutionFixtures() {
    }

    static Session newSession(JdbcTemplate jdbcTemplate, SessionStore sessionStore, IdGenerator idGenerator,
                              Environment environment) {
        UUID userId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                "INSERT INTO app_user (id, keycloak_subject, username, display_name, created_at) VALUES (?, ?, ?, ?, now())",
                userId, "subject-" + userId, "user-" + userId, "Тестовый пользователь");

        UUID credentialsId = idGenerator.newUuidV7();
        String apiKey;
        try {
            apiKey = AesGcmEncryption.encrypt("sk-test", KEY);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось зашифровать тестовый api_key", e);
        }
        String baseUrl = environment.getRequiredProperty("wiremock.llm.url") + "/v1";
        jdbcTemplate.update(
                """
                INSERT INTO llm_credentials (id, name, base_url, api_key_encrypted, key_version, created_at)
                VALUES (?, ?, ?, ?, 1, now())
                """,
                credentialsId, "creds-" + credentialsId, baseUrl, apiKey);

        UUID modelId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                "INSERT INTO llm_model (id, credentials_id, model_id, created_at) VALUES (?, ?, ?, now())",
                modelId, credentialsId, "gpt-test");

        UUID revisionId = idGenerator.newUuidV7();
        String agentKey = "agent-" + revisionId;
        jdbcTemplate.update(
                """
                INSERT INTO agent (id, key, name, rev, role_prompt, llm_model_id, created_at)
                VALUES (?, ?, ?, 1, ?, ?, now())
                """,
                revisionId, agentKey, "Агент исполнения", "Ты исполнитель.", modelId);

        return sessionStore.createFreeSession(userId, agentKey, null, null);
    }
}
