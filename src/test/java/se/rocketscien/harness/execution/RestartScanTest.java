package se.rocketscien.harness.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.LlmWireMockInitializer;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.session.TurnOutcome;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Task 7.6: рестарт-скан — зависший TOOL_CALL без результата закрывается синтетическим LOST
 * (только у сессий со свободным {@code sess-{id}}-локом), сессия пробуждается; осиротевшие
 * {@code harness-*}-контейнеры удаляются, контейнеры живых сессий — нет. Состояние «после
 * kill -9» моделируется прямо в БД/Docker (журнал + контейнеры; in-memory-состояния у мёртвого
 * процесса не было — скан идемпотентен к состоянию процесса).
 */
class RestartScanTest extends BaseExecutionTest {

    private static final String PATH = "/v1/chat/completions";

    @Autowired
    private RestartScanRunner restartScanRunner;

    @Autowired
    private SessionLockManager sessionLocks;

    @Autowired
    private WorkspaceContainerManager containers;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    @Autowired
    private Environment environment;

    @BeforeEach
    void resetStubs() {
        LlmWireMockInitializer.llm().resetAll();
    }

    @Test
    void pendingToolCallOnFreeLockClosedWithLostAndSessionWakes() {
        Session session = newSession();
        LlmWireMockInitializer.llm().stubFor(post(urlEqualTo(PATH))
                .willReturn(sse(TurnEngineWireMockTest.textChunk("восстановился"))));
        sessionStore.appendEvent(session.id(), MessageKind.TOOL_CALL, null, Map.of(
                "callId", "call-lost-1", "toolCallId", "call-llm-1", "tool", "bash",
                "arguments", Map.of("command", "echo work")));

        restartScanRunner.restartScan();

        assertThat(journalField(session.id(), "TOOL_RESULT", "status")).isEqualTo("LOST");
        assertThat(journalField(session.id(), "TOOL_RESULT", "output")).contains("перезапуск");

        awaitOutcome(session.id(), TurnOutcome.COMPLETED);
        assertThat(journalKinds(session.id())).containsExactly("TOOL_CALL", "TOOL_RESULT", "ASSISTANT");
        LlmWireMockInitializer.llm().verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void pendingToolCallUnderLiveLockIsNotTouched() {
        Session session = newSession();
        sessionStore.appendEvent(session.id(), MessageKind.TOOL_CALL, null, Map.of(
                "callId", "call-live-1", "toolCallId", "call-llm-2", "tool", "bash",
                "arguments", Map.of("command", "echo work")));

        Optional<SessionLockManager.HeldLock> held = sessionLocks.tryAcquire(session.id());
        assertThat(held).as("лок имитирует живой Turn другого владельца").isPresent();
        try {
            restartScanRunner.restartScan();

            Integer toolResults = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM session_message WHERE session_id = ? AND kind = 'TOOL_RESULT'",
                    Integer.class, session.id());
            assertThat(toolResults).as("залоченная сессия не тронута").isZero();
        } finally {
            held.get().close();
        }
        // Сессия остаётся eligible (зависший TOOL_CALL) — убираем, чтобы её не подбирали
        // poll()/сканы других тестов
        jdbcTemplate.update("DELETE FROM session WHERE id = ?", session.id());
    }

    @Test
    void orphanContainersRemovedLiveOnesKept() {
        Session liveSession = newSession();
        containers.ensureContainer(liveSession.id());
        UUID orphanSessionId = idGenerator.newUuidV7();
        containers.ensureContainer(orphanSessionId);
        jdbcTemplate.update("DELETE FROM session WHERE id = ?", orphanSessionId);

        restartScanRunner.restartScan();

        assertThat(containers.isRunning(liveSession.id())).as("контейнер живой сессии остаётся").isTrue();
        assertThat(containers.isRunning(orphanSessionId)).as("осиротевший контейнер удалён").isFalse();
        containers.removeContainer(liveSession.id());
    }

    private Session newSession() {
        return ExecutionFixtures.newSession(jdbcTemplate, sessionStore, idGenerator, environment);
    }

    private void awaitOutcome(java.util.UUID sessionId, TurnOutcome outcome) {
        await().atMost(java.time.Duration.ofSeconds(60))
                .pollInterval(java.time.Duration.ofMillis(100))
                .until(() -> sessionStore.findSession(sessionId)
                        .map(s -> s.lastTurnOutcome() == outcome)
                        .orElse(false));
    }

    private List<String> journalKinds(UUID sessionId) {
        return jdbcTemplate.queryForList(
                "SELECT kind FROM session_message WHERE session_id = ? ORDER BY seq", String.class, sessionId);
    }

    private String journalField(UUID sessionId, String kind, String field) {
        return jdbcTemplate.queryForObject(
                "SELECT payload_jsonb ->> ? FROM session_message WHERE session_id = ? AND kind = ?"
                        + " ORDER BY seq DESC LIMIT 1",
                String.class, field, sessionId, kind);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder sse(String... events) {
        StringBuilder body = new StringBuilder();
        for (String event : events) {
            body.append("data: ").append(event).append("\n\n");
        }
        body.append("data: [DONE]\n\n");
        return com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(body.toString());
    }
}
