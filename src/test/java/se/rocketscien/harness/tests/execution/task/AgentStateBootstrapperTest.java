package se.rocketscien.harness.tests.execution.task;

import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.AesGcmEncryption;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.execution.impl.AgentStateBootstrapper;
import se.rocketscien.harness.session.SessionKind;
import se.rocketscien.harness.session.StateSessionService;
import se.rocketscien.harness.tests.workflow.WorkflowTestFixtures;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Bootstrap AGENT-состояния (пачка J.3; execution-model §7.2, спека task-engine «Задачный
 * POLL-страховка»): AGENT-задача без STATE-сессии → findOrCreate (атомарно с seed) → wake
 * сессии → Turn стартовал. Повторный bootstrap идемпотентен (резюм, без дубля seed'а).
 */
class AgentStateBootstrapperTest extends BaseApplicationTest {

    private static final String PATH = "/v1/chat/completions";

    /** Тот же ключ, что harness.llm.encryption-keys.1 в application-test.yml. */
    private static final byte[] LLM_KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private AgentStateBootstrapper bootstrapper;

    @Autowired
    private StateSessionService stateSessions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    @Autowired
    private Environment environment;

    private String agentKey;
    private UUID agentRevisionId;

    @BeforeEach
    void seedAgent() {
        llmWireMock.resetAll();
        agentRevisionId = idGenerator.newUuidV7();
        agentKey = "orchestrator-" + agentRevisionId;
        UUID credentialsId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                """
                INSERT INTO llm_credentials (id, name, base_url, api_key_encrypted, key_version, created_at)
                VALUES (?, ?, ?, ?, 1, now())
                """,
                credentialsId, "creds-" + credentialsId,
                environment.getRequiredProperty("wiremock.llm.url") + "/v1", encrypt("sk-test"));
        UUID modelId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                "INSERT INTO llm_model (id, credentials_id, model_id, created_at) VALUES (?, ?, ?, now())",
                modelId, credentialsId, "gpt-test");
        jdbcTemplate.update(
                """
                INSERT INTO agent (id, key, name, rev, role_prompt, llm_model_id, created_at)
                VALUES (?, ?, 'Оркестратор', 1, 'Ты оркестратор.', ?, now())
                """,
                agentRevisionId, agentKey, modelId);
    }

    @Test
    void agentStateWithoutSessionBootstrapsSessionAndStartsTurn() {
        UUID taskId = insertAgentTaskRaw();

        llmWireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("data: " + textChunk("работаю") + "\n\n"
                                + "data: " + usageChunk() + "\n\ndata: [DONE]\n\n")));

        bootstrapper.bootstrap(taskId, "plan");

        // сессия создана (kind=STATE, seed), Turn по seed стартовал и доработал
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> "COMPLETED".equals(turnOutcome(taskId)));

        var session = jdbcTemplate.queryForMap(
                "SELECT id, kind, state_code, last_seq FROM session WHERE task_id = ? AND kind = 'STATE'",
                taskId);
        assertThat(session.get("kind")).isEqualTo(SessionKind.STATE.name());
        assertThat(session.get("state_code")).isEqualTo("plan");
        // seed занимает seq=1; доработавший Turn дописал ASSISTANT → last_seq = 2
        assertThat(((Number) session.get("last_seq")).longValue()).isEqualTo(2);
        Integer seeds = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM session_message WHERE session_id = ? AND kind = 'SYSTEM'",
                Integer.class, session.get("id"));
        assertThat(seeds).as("seed-SYSTEM ровно один, атомарно с сессией").isEqualTo(1);
    }

    @Test
    void repeatedBootstrapResumesSameSessionWithoutDuplicateSeed() {
        UUID taskId = insertAgentTaskRaw();
        llmWireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("data: " + textChunk("работаю") + "\n\n"
                                + "data: " + usageChunk() + "\n\ndata: [DONE]\n\n")));

        bootstrapper.bootstrap(taskId, "plan");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> "COMPLETED".equals(turnOutcome(taskId)));
        UUID firstSessionId = stateSessions.findOrCreate(taskId, "plan", agentRevisionId).id();

        bootstrapper.bootstrap(taskId, "plan");
        UUID resumedSessionId = stateSessions.findOrCreate(taskId, "plan", agentRevisionId).id();

        assertThat(resumedSessionId).isEqualTo(firstSessionId);
        Integer seeds = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM session_message WHERE session_id = ? AND kind = 'SYSTEM'",
                Integer.class, firstSessionId);
        assertThat(seeds).as("повторный bootstrap не плодит seed'ов").isEqualTo(1);
    }

    private String turnOutcome(UUID taskId) {
        UUID sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM session WHERE task_id = ? AND kind = 'STATE'", UUID.class, taskId);
        return sessionId == null ? null : jdbcTemplate.queryForObject(
                "SELECT last_turn_outcome FROM session WHERE id = ?", String.class, sessionId);
    }

    /** AGENT-старт мимо реестра: EVENT-wake не поднимается — bootstrap зовёт тест. */    private UUID insertAgentTaskRaw() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("plan", "AGENT", Map.of("agent_key", agentKey)),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("plan", "done", "NEXT"),
                        WorkflowTestFixtures.transition("plan", "failed", "ERROR")
                ));
        return TaskEngineTestFixtures.insertTaskRaw(jdbcTemplate, idGenerator,
                graph, "plan", "plan", "AGENT", "RUNNING", false);
    }

    @SneakyThrows
    private static String encrypt(String value) {
        return AesGcmEncryption.encrypt(value, LLM_KEY);
    }

    private static String textChunk(String content) {
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"},\"finish_reason\":null}]}";
    }

    private static String usageChunk() {
        return "{\"id\":\"2\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    }
}
