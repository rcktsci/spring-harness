package se.rocketscien.harness.tests.execution.task;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.AesGcmEncryption;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.session.StateSessionService;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.tests.task.TaskTestFixtures;
import se.rocketscien.harness.tests.workflow.WorkflowTestFixtures;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stop поддерева задач (пачка K.2, execution-model §7.2): {@code TaskWakeDispatcher.handleStop}
 * по wake после коммита отменяет Turn'ы STATE-сессий всех узлов остановленного поддерева
 * ({@code cancel_requested} + прерывание активного Turn'а). STATE-сессии в тесте создаются
 * напрямую ({@code agent_key} графа не резолвится — bootstrap тихо падает, LLM не вызывается).
 */
class TaskWakeDispatcherStopTest extends BaseApplicationTest {

    @Autowired
    private TaskRegistry taskRegistry;

    @Autowired
    private StateSessionService stateSessions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    @Autowired
    private Environment environment;

    private UUID agentRevisionId;

    @BeforeEach
    void seedAgent() {
        // Реальная строка агента (FK session.agent_revision_id); agent_key графов —
        // «ghost-*», чтобы bootstrap диспетчера не поднимал Turn (LLM не вызывается).
        agentRevisionId = idGenerator.newUuidV7();
        UUID credentialsId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                "INSERT INTO llm_credentials (id, name, base_url, api_key_encrypted, key_version, created_at)"
                        + " VALUES (?, ?, ?, ?, 1, now())",
                credentialsId, "creds-" + credentialsId,
                environment.getRequiredProperty("wiremock.llm.url") + "/v1",
                AesGcmEncryption.encrypt(
                        "sk-test", "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        UUID modelId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                "INSERT INTO llm_model (id, credentials_id, model_id, created_at) VALUES (?, ?, ?, now())",
                modelId, credentialsId, "gpt-test");
        jdbcTemplate.update(
                "INSERT INTO agent (id, key, name, description, rev, role_prompt, llm_model_id, created_at)"
                        + " VALUES (?, ?, ?, ?, 1, ?, ?, now())",
                agentRevisionId, "ghost-" + agentRevisionId, "Агент", "d", "Ты исполнитель.", modelId);
    }

    @Test
    void stopCancelsTurnsOfStateSessionsOfWholeSubtree() {
        UUID parentId = insertAgentTask("plan");
        UUID childId = insertAgentTask("review");
        jdbcTemplate.update("UPDATE task SET parent_task_id = ? WHERE id = ?", parentId, childId);

        UUID parentSessionId = stateSessions.findOrCreate(parentId, "plan", agentRevisionId).id();
        UUID childSessionId = stateSessions.findOrCreate(childId, "review", agentRevisionId).id();

        taskRegistry.stop(parentId);

        // Отмена идёт асинхронно (диспетчер на виртуальных потоках) — ждём фиксацию флага
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    assertThat(cancelRequested(parentSessionId)).isTrue();
                    assertThat(cancelRequested(childSessionId)).isTrue();
                });
    }

    @Test
    void wakeOfRunningTaskDoesNotTouchStateSessionCancelFlag() {
        UUID taskId = insertAgentTask("plan");
        UUID sessionId = stateSessions.findOrCreate(taskId, "plan", agentRevisionId).id();

        taskRegistry.patch(taskId, new TaskRegistry.TaskPatch("переименовали", null, null));

        // переоценка/patch-wake не отменяют Turn живой задачи
        Awaitility.await()
                .during(Duration.ofSeconds(1))
                .atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(cancelRequested(sessionId)).isFalse());
    }

    private boolean cancelRequested(UUID sessionId) {
        Boolean requested = jdbcTemplate.queryForObject(
                "SELECT cancel_requested FROM session WHERE id = ?", Boolean.class, sessionId);
        return Boolean.TRUE.equals(requested);
    }

    /** AGENT-старт с несуществующим agent_key: bootstrap диспетчера молча падает, Turn не стартует. */
    private UUID insertAgentTask(String stateCode) {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state(stateCode, "AGENT", Map.of("agent_key", "ghost-agent")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))),
                List.of(
                        WorkflowTestFixtures.transition(stateCode, "done", "NEXT"),
                        WorkflowTestFixtures.transition(stateCode, "failed", "ERROR")));
        UUID revisionId = TaskTestFixtures.insertRevision(
                jdbcTemplate, idGenerator, graph, stateCode);
        UUID owner = WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator);
        return taskRegistry.createTask(new TaskRegistry.CreateTaskCommand(
                revisionId, "Задача " + stateCode, "d", null, owner, null, Map.of(), List.of())).id();
    }
}
