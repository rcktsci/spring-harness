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
import se.rocketscien.harness.execution.RestartScanRunner;
import se.rocketscien.harness.execution.TurnManager;
import se.rocketscien.harness.execution.WorkspaceContainerManager;
import se.rocketscien.harness.agent.AsyncTimeoutWatcher;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionEventBroadcaster;
import se.rocketscien.harness.session.SessionRuntimeStatus;
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
 * Пачка N (M3, D-60/D-64/D-65): bash превысил окно → журнал TOOL_CALL + ASYNC_ACCEPTED,
 * сессия паркуется в PARKED_ASYNC (исход COMPLETED); поздний TOOL_RESULT(late=true) → wake →
 * новый Turn; stop во время окна → CANCELLED; рестарт-скан закрывает зависший async одним LOST
 * и не дублирует при повторном проходе (N.3/N.5); async-timeout-watcher закрывает протухший
 * плейсхолдер LOST (N.6).
 */
class AsyncToolTurnTest extends BaseApplicationTest {

    static {
        DockerTestSupport.helperImage();
    }

    private static final String PATH = "/v1/chat/completions";

    @Autowired
    private TurnManager turnManager;

    @Autowired
    private RestartScanRunner restartScanRunner;

    @Autowired
    private AsyncTimeoutWatcher timeoutWatcher;

    @Autowired
    private WorkspaceContainerManager containers;

    @Autowired
    private SessionEventBroadcaster broadcaster;

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
    void bashBeyondWindowParksThenLateResultWakesNewTurn() {
        Session session = newSession();
        containers.ensureContainer(session.id());
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("park")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(TurnEngineWireMockTest.toolCallChunk("call-1", "bash",
                        "{\"command\":\"sleep 15; echo slow-done\"}")))
                .willSetStateTo("final"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("park")
                .whenScenarioStateIs("final")
                .willReturn(sse(TurnEngineWireMockTest.textChunk("готово"))));

        sessionStore.appendEvent(session.id(), MessageKind.USER,
                session.ownerUserId(), Map.of("text", "запусти долгую команду"));
        turnManager.tryStart(session.id());

        // Парк: пара TOOL_CALL + ASYNC_ACCEPTED, исход COMPLETED, статус PARKED_ASYNC
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
                .until(() -> journalKinds(session.id())
                        .equals(List.of("USER", "ASSISTANT", "TOOL_CALL", "ASYNC_ACCEPTED")));
        awaitStatus(session.id(), SessionRuntimeStatus.PARKED_ASYNC);
        assertThat(sessionStore.findSession(session.id()).orElseThrow().lastTurnOutcome())
                .isEqualTo(TurnOutcome.COMPLETED);

        // Поздний результат (sleep 15) — late=true, wake, новый Turn, финальный ASSISTANT
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(50))
                .until(() -> journalKinds(session.id()).contains("TOOL_RESULT"));
        assertThat(journalField(session.id(), "TOOL_RESULT", "late")).isEqualTo("true");
        assertThat(journalField(session.id(), "TOOL_RESULT", "status")).isEqualTo("OK");
        assertThat(journalField(session.id(), "TOOL_RESULT", "output")).contains("slow-done");

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .until(() -> journalKinds(session.id()).equals(List.of(
                        "USER", "ASSISTANT", "TOOL_CALL", "ASYNC_ACCEPTED", "TOOL_RESULT", "ASSISTANT")));
        awaitOutcome(session.id(), TurnOutcome.COMPLETED);
        awaitStatus(session.id(), SessionRuntimeStatus.IDLE);
        llmWireMock.verify(2, postRequestedFor(urlEqualTo(PATH)));

        containers.removeContainer(session.id());
    }

    @Test
    void stopDuringWindowYieldsCancelledResult() {
        Session session = newSession();
        containers.ensureContainer(session.id());
        llmWireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(sse(TurnEngineWireMockTest.toolCallChunk("call-2", "bash",
                        "{\"command\":\"sleep 15; echo never\"}"))));

        sessionStore.appendEvent(session.id(), MessageKind.USER,
                session.ownerUserId(), Map.of("text", "долгая и отмена"));
        turnManager.tryStart(session.id());

        // Write-ahead: TOOL_CALL — маркер «исполнение началось»; stop в пределах окна
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(20))
                .until(() -> journalKinds(session.id()).contains("TOOL_CALL"));
        turnManager.requestStop(session.id());

        awaitOutcome(session.id(), TurnOutcome.CANCELLED);
        assertThat(journalKinds(session.id())).containsExactly(
                "USER", "ASSISTANT", "TOOL_CALL", "TOOL_RESULT");
        assertThat(journalField(session.id(), "TOOL_RESULT", "status")).isEqualTo("CANCELLED");
        assertThat(journalField(session.id(), "TOOL_RESULT", "output")).contains("отменено");
        assertThat(journalKinds(session.id())).doesNotContain("ASYNC_ACCEPTED");

        containers.removeContainer(session.id());
    }

    @Test
    void restartScanClosesParkedAsyncWithSingleLostAndDoesNotDuplicate() {
        Session session = newSession();
        llmWireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(sse(TurnEngineWireMockTest.textChunk("восстановился"))));

        // Журнал после kill: пара TOOL_CALL + ASYNC_ACCEPTED без финального результата (N.3)
        sessionStore.appendEvent(session.id(), MessageKind.TOOL_CALL, null, Map.of(
                "callId", "call-restart-1", "toolCallId", "call-llm-9", "tool", "bash",
                "arguments", Map.of("command", "sleep 60")));
        sessionStore.appendEvent(session.id(), MessageKind.ASYNC_ACCEPTED, null, Map.of(
                "callId", "call-restart-1", "tool", "bash"));

        restartScanRunner.restartScan();

        awaitOutcome(session.id(), TurnOutcome.COMPLETED);
        assertThat(journalKinds(session.id())).containsExactly(
                "TOOL_CALL", "ASYNC_ACCEPTED", "TOOL_RESULT", "ASSISTANT");
        assertThat(journalField(session.id(), "TOOL_RESULT", "status")).isEqualTo("LOST");
        assertThat(journalField(session.id(), "TOOL_RESULT", "output")).contains("перезапуск");
        llmWireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));

        // Повторный скан: у TOOL_CALL уже есть финальный результат — дублирования нет (N.5)
        restartScanRunner.restartScan();
        assertThat(journalKinds(session.id())).containsExactly(
                "TOOL_CALL", "ASYNC_ACCEPTED", "TOOL_RESULT", "ASSISTANT");
        assertThat(countToolResults(session.id(), "call-restart-1")).isEqualTo(1);
        llmWireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void timeoutWatcherClosesExpiredAsyncAndLeavesFreshOnes() {
        Session session = newSession();
        llmWireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(sse(TurnEngineWireMockTest.textChunk("дождались"))));

        sessionStore.appendEvent(session.id(), MessageKind.TOOL_CALL, null, Map.of(
                "callId", "call-expired", "toolCallId", "call-llm-7", "tool", "bash",
                "arguments", Map.of("command", "sleep 60")));
        sessionStore.appendEvent(session.id(), MessageKind.ASYNC_ACCEPTED, null, Map.of(
                "callId", "call-expired", "tool", "bash"));
        jdbcTemplate.update(
                "UPDATE session_message SET created_at = now() - interval '1 hour'"
                        + " WHERE session_id = ? AND kind = 'ASYNC_ACCEPTED'",
                session.id());

        timeoutWatcher.scan();

        awaitOutcome(session.id(), TurnOutcome.COMPLETED);
        assertThat(journalKinds(session.id())).containsExactly(
                "TOOL_CALL", "ASYNC_ACCEPTED", "TOOL_RESULT", "ASSISTANT");
        assertThat(journalField(session.id(), "TOOL_RESULT", "status")).isEqualTo("LOST");
        assertThat(journalField(session.id(), "TOOL_RESULT", "output")).contains("лимит");

        // Свежий плейсхолдер (created_at сброшен) — не трогается
        Session fresh = newSession();
        sessionStore.appendEvent(fresh.id(), MessageKind.TOOL_CALL, null, Map.of(
                "callId", "call-fresh", "toolCallId", "call-llm-8", "tool", "bash",
                "arguments", Map.of("command", "sleep 60")));
        sessionStore.appendEvent(fresh.id(), MessageKind.ASYNC_ACCEPTED, null, Map.of(
                "callId", "call-fresh", "tool", "bash"));
        jdbcTemplate.update(
                "UPDATE session_message SET created_at = now()"
                        + " WHERE session_id = ? AND kind IN ('TOOL_CALL', 'ASYNC_ACCEPTED')",
                fresh.id());

        timeoutWatcher.scan();

        assertThat(sessionStore.findSession(fresh.id()).orElseThrow().lastTurnOutcome()).isNull();
        assertThat(countToolResults(fresh.id(), "call-fresh")).isZero();
    }

    @Test
    void twoAsyncCallsWithinWindowResolveInSameRound() {
        Session session = newSession();
        containers.ensureContainer(session.id());
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("two-fast")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(twoToolCallsChunk("call-a", "call-b", "bash",
                        "{\"command\":\"echo one\"}", "{\"command\":\"echo two\"}")))
                .willSetStateTo("final"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("two-fast")
                .whenScenarioStateIs("final")
                .willReturn(sse(TurnEngineWireMockTest.textChunk("готово"))));

        sessionStore.appendEvent(session.id(), MessageKind.USER,
                session.ownerUserId(), Map.of("text", "два быстрых вызова"));
        turnManager.tryStart(session.id());

        // Оба async-capable уложились в окно — оба результата в том же раунде, без парковки
        awaitOutcome(session.id(), TurnOutcome.COMPLETED);
        assertThat(journalKinds(session.id())).containsExactly(
                "USER", "ASSISTANT", "TOOL_CALL", "TOOL_CALL", "TOOL_RESULT", "TOOL_RESULT", "ASSISTANT");
        assertThat(journalKinds(session.id())).doesNotContain("ASYNC_ACCEPTED");
        assertThat(toolResultOutputs(session.id())).containsExactlyInAnyOrder("one", "two");
        awaitStatus(session.id(), SessionRuntimeStatus.IDLE);
        llmWireMock.verify(2, postRequestedFor(urlEqualTo(PATH)));

        containers.removeContainer(session.id());
    }

    private Session newSession() {
        return ExecutionFixtures.newSession(jdbcTemplate, sessionStore, idGenerator, environment);
    }

    private void awaitStatus(UUID sessionId, SessionRuntimeStatus status) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
                .until(() -> broadcaster.statusSnapshot(sessionId).runtimeStatus() == status);
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

    private int countToolResults(UUID sessionId, String callId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_message WHERE session_id = ? AND kind = 'TOOL_RESULT'"
                        + " AND payload_jsonb ->> 'callId' = ?",
                Integer.class, sessionId, callId);
        return count == null ? 0 : count;
    }

    private List<String> toolResultOutputs(UUID sessionId) {
        return jdbcTemplate.queryForList(
                        "SELECT payload_jsonb ->> 'output' FROM session_message"
                                + " WHERE session_id = ? AND kind = 'TOOL_RESULT' ORDER BY seq",
                        String.class, sessionId)
                .stream().map(String::trim).toList();
    }

    private static String twoToolCallsChunk(String id1, String id2, String tool, String args1, String args2) {
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":["
                + "{\"index\":0,\"id\":\"" + id1 + "\",\"type\":\"function\",\"function\":{\"name\":\"" + tool
                + "\",\"arguments\":\"" + args1.replace("\"", "\\\"") + "\"}},"
                + "{\"index\":1,\"id\":\"" + id2 + "\",\"type\":\"function\",\"function\":{\"name\":\"" + tool
                + "\",\"arguments\":\"" + args2.replace("\"", "\\\"") + "\"}}"
                + "]},\"finish_reason\":\"tool_calls\"}]}";
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
