package se.rocketscien.harness.tests.execution;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.execution.TurnManager;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.session.TurnOutcome;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * O.1/O.2 (спека subagent-lifecycle): синхронный spawn_subagent — оркестратор (metaTools=true)
 * спавнит субагента, родительский поток ждёт, финальный ASSISTANT субагента → TOOL_RESULT
 * родителю на ходу, где spawn вызван; дочерняя сессия наследует owner и получает
 * depth = parent + 1. Depth-limit: spawn из child при max-depth=1 → forbidden (depth-limit),
 * внук не создаётся. Обычный агент spawn'ить не может — инструмент скрыт из манифеста
 * (гейт no-metaTools проверен явным вызовом: дочерняя сессия воркера не создаёт сессий).
 */
class SubagentSpawnTest extends BaseApplicationTest {

    private static final String PATH = "/v1/chat/completions";

    @Autowired
    private TurnManager turnManager;

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
        llmWireMock.resetAll();
    }

    @Test
    void orchestratorSpawnsSubagentAndGetsFinalAnswerAsToolResult() {
        Session root = ExecutionFixtures.newSessionWithAgent(
                jdbcTemplate, sessionStore, idGenerator, environment, "{\"metaTools\": true}");
        Session worker = ExecutionFixtures.newSession(jdbcTemplate, sessionStore, idGenerator, environment);
        String workerKey = rootAgentKey(worker.id());

        // Последовательность детерминирована блокирующим spawn: P1 (tool_call) →
        // C1 (финал воркера) → P2 (финал оркестратора)
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("spawn-happy")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(spawnChunk("{\"agentKey\":\"" + workerKey + "\","
                        + "\"prompt\":\"сделай анализ\"}")))
                .willSetStateTo("child"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("spawn-happy")
                .whenScenarioStateIs("child")
                .willReturn(sse(TurnEngineWireMockTest.textChunk("анализ готов")))
                .willSetStateTo("root-final"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("spawn-happy")
                .whenScenarioStateIs("root-final")
                .willReturn(sse(TurnEngineWireMockTest.textChunk("итог собран"))));

        sessionStore.appendEvent(root.id(), MessageKind.USER, root.ownerUserId(),
                Map.of("text", "заведи субагента"));
        turnManager.tryStart(root.id());

        awaitOutcome(root.id(), TurnOutcome.COMPLETED);

        assertThat(journalKinds(root.id())).containsExactly(
                "USER", "ASSISTANT", "TOOL_CALL", "TOOL_RESULT", "ASSISTANT");
        assertThat(journalField(root.id(), "TOOL_RESULT", "status")).isEqualTo("OK");
        assertThat(journalField(root.id(), "TOOL_RESULT", "output")).isEqualTo("анализ готов");

        UUID childId = jdbcTemplate.queryForObject(
                "SELECT id FROM session WHERE parent_session_id = ?", UUID.class, root.id());
        Session child = sessionStore.findSession(childId).orElseThrow();
        assertThat(child.parentSessionId()).isEqualTo(root.id());
        assertThat(child.depth()).isEqualTo(1);
        assertThat(child.ownerUserId()).isEqualTo(root.ownerUserId());
        assertThat(child.lastTurnOutcome()).isEqualTo(TurnOutcome.COMPLETED);

        llmWireMock.verify(3, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void spawnBeyondMaxDepthIsForbiddenAndGrandchildIsNotCreated() {
        // max-depth = 1 (тест-профиль): оркестратор спавнит child (depth 1, оркестратор же —
        // metaTools наследуется агентом, не сессией), child пытается спавнить внука → forbidden
        Session root = ExecutionFixtures.newSessionWithAgent(
                jdbcTemplate, sessionStore, idGenerator, environment, "{\"metaTools\": true}");
        String agentKey = rootAgentKey(root.id());

        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("spawn-depth")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(spawnChunk("{\"agentKey\":\"" + agentKey + "\",\"prompt\":\"первый уровень\"}")))
                .willSetStateTo("child-round1"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("spawn-depth")
                .whenScenarioStateIs("child-round1")
                .willReturn(sse(spawnChunk("{\"agentKey\":\"" + agentKey + "\",\"prompt\":\"второй уровень\"}")))
                .willSetStateTo("child-final"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("spawn-depth")
                .whenScenarioStateIs("child-final")
                .willReturn(sse(TurnEngineWireMockTest.textChunk("child done")))
                .willSetStateTo("root-final"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("spawn-depth")
                .whenScenarioStateIs("root-final")
                .willReturn(sse(TurnEngineWireMockTest.textChunk("root done"))));

        sessionStore.appendEvent(root.id(), MessageKind.USER, root.ownerUserId(),
                Map.of("text", "спавн на пределе"));
        turnManager.tryStart(root.id());

        awaitOutcome(root.id(), TurnOutcome.COMPLETED);

        // forbidden (depth-limit) — в журнале child, где spawn был вызван
        UUID childId = jdbcTemplate.queryForObject(
                "SELECT id FROM session WHERE parent_session_id = ?", UUID.class, root.id());
        assertThat(journalKinds(childId)).containsExactly(
                "USER", "ASSISTANT", "TOOL_CALL", "TOOL_RESULT", "ASSISTANT");
        assertThat(journalField(childId, "TOOL_RESULT", "status")).isEqualTo("ERROR");
        assertThat(journalField(childId, "TOOL_RESULT", "output")).contains("depth-limit");

        // внук не создан
        Integer grandchildren = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session WHERE parent_session_id = ?", Integer.class, childId);
        assertThat(grandchildren).isZero();
    }

    @Test
    void explicitSpawnByPlainAgentIsForbidden() {
        // Обычный агент (без metaTools): spawn_subagent скрыт из манифеста; явный вызов —
        // forbidden (no-metaTools)
        Session root = ExecutionFixtures.newSession(jdbcTemplate, sessionStore, idGenerator, environment);
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("spawn-forbidden")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(spawnChunk("{\"agentKey\":\"whatever\",\"prompt\":\"проникнуть\"}")))
                .willSetStateTo("final"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("spawn-forbidden")
                .whenScenarioStateIs("final")
                .willReturn(sse(TurnEngineWireMockTest.textChunk("понял"))));

        sessionStore.appendEvent(root.id(), MessageKind.USER, root.ownerUserId(),
                Map.of("text", "заспавнь себя"));
        turnManager.tryStart(root.id());

        awaitOutcome(root.id(), TurnOutcome.COMPLETED);
        assertThat(journalField(root.id(), "TOOL_RESULT", "status")).isEqualTo("ERROR");
        assertThat(journalField(root.id(), "TOOL_RESULT", "output")).contains("no-metaTools");
        Integer created = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session WHERE parent_session_id = ?", Integer.class, root.id());
        assertThat(created).isZero();
    }

    @Test
    void spawnerWaitsForParkedSubagentToFinish() {
        // O-1 (D-10): воркер ушёл в PARKED_ASYNC (bash превысил окно) — исход COMPLETED при
        // незакрытом вызове; спавнер обязан ждать финального ASSISTANT, а не закрывать spawn
        // частичным результатом
        Session root = ExecutionFixtures.newSessionWithAgent(
                jdbcTemplate, sessionStore, idGenerator, environment, "{\"metaTools\": true}");
        Session worker = ExecutionFixtures.newSession(jdbcTemplate, sessionStore, idGenerator, environment);
        String workerKey = rootAgentKey(worker.id());

        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("spawn-parked")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(spawnChunk("{\"agentKey\":\"" + workerKey + "\","
                        + "\"prompt\":\"долгая команда\"}")))
                .willSetStateTo("child-round1"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("spawn-parked")
                .whenScenarioStateIs("child-round1")
                .willReturn(sse(TurnEngineWireMockTest.toolCallChunk("call-w", "bash",
                        "{\"command\":\"sleep 15; echo slow\"}")))
                .willSetStateTo("child-final"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("spawn-parked")
                .whenScenarioStateIs("child-final")
                .willReturn(sse(TurnEngineWireMockTest.textChunk("дочитал")))
                .willSetStateTo("root-final"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("spawn-parked")
                .whenScenarioStateIs("root-final")
                .willReturn(sse(TurnEngineWireMockTest.textChunk("готово"))));

        sessionStore.appendEvent(root.id(), MessageKind.USER, root.ownerUserId(),
                Map.of("text", "спавни с долгим bash"));
        turnManager.tryStart(root.id());

        // Воркер запарковался: TOOL_CALL + ASYNC_ACCEPTED без результата — родитель ждёт
        UUID childId = awaitChildSession(root.id());
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
                .until(() -> journalKinds(childId).contains("ASYNC_ACCEPTED"));
        assertThat(journalKinds(root.id())).as("парковка воркера не закрывает spawn")
                .doesNotContain("TOOL_RESULT");

        awaitOutcome(root.id(), TurnOutcome.COMPLETED);

        assertThat(journalField(root.id(), "TOOL_RESULT", "status")).isEqualTo("OK");
        assertThat(journalField(root.id(), "TOOL_RESULT", "output")).isEqualTo("дочитал");
        assertThat(sessionStore.findPendingToolCalls(childId)).isEmpty();
        llmWireMock.verify(4, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void stopDuringSpawnCancelsParentPromptly() {
        // R-2: stop поддерева во время блокирующего spawn — parent получает CANCELLED
        // «subtree-cancelled» немедленно (поллинг cancel_requested child'а), а не через
        // harness.spawn.timeout-ms
        Session root = ExecutionFixtures.newSessionWithAgent(
                jdbcTemplate, sessionStore, idGenerator, environment, "{\"metaTools\": true}");
        Session worker = ExecutionFixtures.newSession(jdbcTemplate, sessionStore, idGenerator, environment);
        String workerKey = rootAgentKey(worker.id());

        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("spawn-stop")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(spawnChunk("{\"agentKey\":\"" + workerKey + "\","
                        + "\"prompt\":\"долго работаем\"}")))
                .willSetStateTo("child-round1"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("spawn-stop")
                .whenScenarioStateIs("child-round1")
                .willReturn(sse(TurnEngineWireMockTest.toolCallChunk("call-c", "bash",
                        "{\"command\":\"sleep 600\"}"))));

        sessionStore.appendEvent(root.id(), MessageKind.USER, root.ownerUserId(),
                Map.of("text", "спавни и останови"));
        turnManager.tryStart(root.id());

        // Воркер в полёте (TOOL_CALL в write-ahead) — stop поддерева
        UUID childId = awaitChildSession(root.id());
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(20))
                .until(() -> journalKinds(childId).contains("TOOL_CALL"));
        turnManager.requestStop(root.id());

        // Немедленный возврат: CANCELLED «subtree-cancelled» в журнале родителя за секунды,
        // не через 30-минутный таймаут
        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(25))
                .until(() -> "CANCELLED".equals(journalField(root.id(), "TOOL_RESULT", "status")));
        assertThat(journalField(root.id(), "TOOL_RESULT", "output")).contains("subtree-cancelled");
        awaitOutcome(root.id(), TurnOutcome.CANCELLED);
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50))
                .until(() -> sessionStore.findSession(childId)
                        .map(s -> s.lastTurnOutcome() == TurnOutcome.CANCELLED)
                        .orElse(false));
        llmWireMock.verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    private UUID awaitChildSession(UUID rootId) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
                .until(() -> childOf(rootId) != null);
        return childOf(rootId);
    }

    private UUID childOf(UUID rootId) {
        List<UUID> ids = jdbcTemplate.queryForList(
                "SELECT id FROM session WHERE parent_session_id = ?", UUID.class, rootId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private String rootAgentKey(UUID sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT a.key FROM session s JOIN agent a ON a.id = s.agent_revision_id"
                        + " WHERE s.id = ?",
                String.class, sessionId);
    }

    private String spawnChunk(String argumentsJson) {
        return TurnEngineWireMockTest.toolCallChunk("call-spawn", "spawn_subagent", argumentsJson);
    }

    private void awaitOutcome(UUID sessionId, TurnOutcome outcome) {
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(100))
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

    private static ResponseDefinitionBuilder sse(String... events) {
        StringBuilder body = new StringBuilder();
        for (String event : events) {
            body.append("data: ").append(event).append("\n\n");
        }
        body.append("data: [DONE]\n\n");
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(body.toString());
    }
}
