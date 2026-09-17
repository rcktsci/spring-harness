package se.rocketscien.harness.execution;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.LlmWireMockInitializer;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.session.TurnOutcome;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Task 7.2: агентный цикл на WireMock-LLM с tool-calling — полный раунд, событие во время хода,
 * FAILED при исчерпании ретраев; task 7.1: конкурентный tryStart — один победитель;
 * task 7.3: EVENT-старт быстрее интервала опроса.
 */
class TurnEngineWireMockTest extends BaseExecutionTest {

    private static final String PATH = "/v1/chat/completions";
    private static final int START_COMPETITORS = 5;

    @Autowired
    private TurnManager turnManager;

    @Autowired
    private PollWakeJob pollWakeJob;

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
        llm().resetAll();
    }

    @Test
    void fullCycleWithToolThenFinalAnswer() {
        Session session = newSession();
        stubToolCallThenFinal();

        sessionStore.appendEvent(session.id(), se.rocketscien.harness.session.MessageKind.USER,
                session.ownerUserId(), Map.of("text", "создай файл"));
        turnManager.tryStart(session.id());

        awaitOutcome(session.id(), TurnOutcome.COMPLETED);

        assertThat(journalKinds(session.id())).containsExactly(
                "USER", "ASSISTANT", "TOOL_CALL", "TOOL_RESULT", "ASSISTANT");

        assertThat(journalField(session.id(), "TOOL_RESULT", "status")).isEqualTo("OK");
        assertThat(journalField(session.id(), "TOOL_RESULT", "output")).contains("hello-from-tool");
        Session after = sessionStore.findSession(session.id()).orElseThrow();
        assertThat(after.lastConsumedSeq()).isEqualTo(after.lastSeq());

        llm().verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void messageDuringTurnIsSeenByExtraRound() {
        Session session = newSession();
        llm().stubFor(post(urlEqualTo(PATH)).inScenario("extra-round")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(sse(toolCallChunk("call-1", "bash",
                        "{\"command\":\"sleep 2; echo slow-done\"}"), usageChunk(1, 1)))
                .willSetStateTo("second"));
        llm().stubFor(post(urlEqualTo(PATH)).inScenario("extra-round")
                .whenScenarioStateIs("second")
                .willReturn(sse(textChunk("готово"), usageChunk(1, 1))));

        sessionStore.appendEvent(session.id(), se.rocketscien.harness.session.MessageKind.USER,
                session.ownerUserId(), Map.of("text", "первый вопрос"));
        turnManager.tryStart(session.id());

        long userSeqDuringTurn = awaitToolCallAndAppendUser(session);

        awaitOutcome(session.id(), TurnOutcome.COMPLETED);

        // USER дописан во время sleep-раунда — до TOOL_RESULT (детерминированно: сон длиннее поллинга)
        assertThat(journalKinds(session.id())).containsExactly(
                "USER", "ASSISTANT", "TOOL_CALL", "USER", "TOOL_RESULT", "ASSISTANT");
        assertThat(journalSeq(session.id(), "ASSISTANT", 0))
                .as("финальный ASSISTANT обязан идти после дописанного USER")
                .isGreaterThan(userSeqDuringTurn);
        llm().verify(2, postRequestedFor(urlEqualTo(PATH)));
        llm().verify(2, postRequestedFor(urlEqualTo(PATH)).withRequestBody(containing("первый вопрос")));
        llm().verify(1, postRequestedFor(urlEqualTo(PATH)).withRequestBody(containing("второй вопрос")));
    }

    @Test
    void llmRetriesExhaustedFailsTurnWithSystemEventAndConsumesBatch() {
        Session session = newSession();
        llm().stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(503).withBody("{\"error\":\"unavailable\"}")));

        sessionStore.appendEvent(session.id(), se.rocketscien.harness.session.MessageKind.USER,
                session.ownerUserId(), Map.of("text", "сломайся"));
        turnManager.tryStart(session.id());

        awaitOutcome(session.id(), TurnOutcome.FAILED);

        List<String> kinds = journalKinds(session.id());
        assertThat(kinds).containsExactly("USER", "SYSTEM");
        assertThat(journalField(session.id(), "SYSTEM", "text")).contains("503");

        Session after = sessionStore.findSession(session.id()).orElseThrow();
        assertThat(after.lastConsumedSeq()).isEqualTo(after.lastSeq());
        assertThat(after.cancelRequested()).isFalse();
        llm().verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void failedTurnIsNotRewokenByPoll() {
        Session session = newSession();
        llm().stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(503).withBody("{\"error\":\"unavailable\"}")));

        sessionStore.appendEvent(session.id(), se.rocketscien.harness.session.MessageKind.USER,
                session.ownerUserId(), Map.of("text", "сломайся"));
        turnManager.tryStart(session.id());

        awaitOutcome(session.id(), TurnOutcome.FAILED);
        llm().verify(2, postRequestedFor(urlEqualTo(PATH)));

        // D-45/D-J-2: FAILED потребляет батч (SYSTEM-причина — последнее событие Turn'а) —
        // POLL не перезапускает сломанный Turn (нет retry-шторма)
        pollWakeJob.poll();
        llm().verify(2, postRequestedFor(urlEqualTo(PATH)));
        assertThat(sessionStore.findSession(session.id()).orElseThrow().lastTurnOutcome())
                .isEqualTo(TurnOutcome.FAILED);
    }

    @Test
    void concurrentTryStartRunsExactlyOneTurn() throws Exception {
        Session session = newSession();
        stubToolCallThenFinal();

        sessionStore.appendEvent(session.id(), se.rocketscien.harness.session.MessageKind.USER,
                session.ownerUserId(), Map.of("text", "конкурентный запуск"));
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(START_COMPETITORS);
        try {
            List<Future<?>> attempts = new java.util.ArrayList<>();
            for (int i = 0; i < START_COMPETITORS; i++) {
                attempts.add(pool.submit(() -> {
                    startGate.await();
                    turnManager.tryStart(session.id());
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> attempt : attempts) {
                attempt.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        awaitOutcome(session.id(), TurnOutcome.COMPLETED);

        llm().verify(2, postRequestedFor(urlEqualTo(PATH)));
        assertThat(journalKinds(session.id()))
                .containsExactly("USER", "ASSISTANT", "TOOL_CALL", "TOOL_RESULT", "ASSISTANT");
    }

    @Test
    void wakeEventStartsTurnWellBeforePollInterval() {
        Session session = newSession();
        stubToolCallThenFinal();
        long pollIntervalMillis = environment.getRequiredProperty(
                "harness.turn.poll-interval", java.time.Duration.class).toMillis();
        AtomicLong startedAt = new AtomicLong(System.nanoTime());

        sessionStore.appendEvent(session.id(), se.rocketscien.harness.session.MessageKind.USER,
                session.ownerUserId(), Map.of("text", "быстрый старт"));
        turnManager.tryStart(session.id());

        awaitOutcome(session.id(), TurnOutcome.COMPLETED);
        long elapsedMillis = (System.nanoTime() - startedAt.get()) / 1_000_000;

        assertThat(elapsedMillis).as("EVENT-старт обязан обгонять интервал опроса").isLessThan(pollIntervalMillis);
        llm().verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    private Session newSession() {
        return ExecutionFixtures.newSession(jdbcTemplate, sessionStore, idGenerator, environment);
    }

    private void stubToolCallThenFinal() {
        llm().stubFor(post(urlEqualTo(PATH)).inScenario("tool-then-final")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(sse(toolCallChunk("call-1", "bash",
                        "{\"command\":\"echo hello-from-tool\"}"), usageChunk(1, 1)))
                .willSetStateTo("final"));
        llm().stubFor(post(urlEqualTo(PATH)).inScenario("tool-then-final")
                .whenScenarioStateIs("final")
                .willReturn(sse(textChunk("готово"), usageChunk(1, 1))));
    }

    private long awaitToolCallAndAppendUser(Session session) {
        // Ждём журналирования TOOL_CALL (write-ahead) и дописываем USER, пока bash ещё спит:
        // раунд 2 гарантированно рендерится уже с новым сообщением.
        await().atMost(java.time.Duration.ofSeconds(30))
                .pollInterval(java.time.Duration.ofMillis(50))
                .until(() -> journalKinds(session.id()).contains("TOOL_CALL"));
        return sessionStore.appendEvent(session.id(), se.rocketscien.harness.session.MessageKind.USER,
                session.ownerUserId(), Map.of("text", "второй вопрос")).seq();
    }

    private void awaitOutcome(UUID sessionId, TurnOutcome outcome) {
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

    private long journalSeq(UUID sessionId, String kind, int fromEnd) {
        List<Long> seqs = jdbcTemplate.queryForList(
                "SELECT seq FROM session_message WHERE session_id = ? AND kind = ? ORDER BY seq",
                Long.class, sessionId, kind);
        return seqs.get(seqs.size() - 1 + fromEnd);
    }

    private static WireMockServer llm() {
        return LlmWireMockInitializer.llm();
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

    static String textChunk(String content) {
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"},\"finish_reason\":null}]}";
    }

    static String toolCallChunk(String id, String tool, String argumentsJson) {
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"" + id
                + "\",\"type\":\"function\",\"function\":{\"name\":\"" + tool + "\",\"arguments\":\""
                + argumentsJson.replace("\"", "\\\"") + "\"}}]},\"finish_reason\":\"tool_calls\"}]}";
    }

    private static String usageChunk(int promptTokens, int completionTokens) {
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":" + promptTokens + ",\"completion_tokens\":" + completionTokens
                + ",\"total_tokens\":" + (promptTokens + completionTokens) + "}}";
    }
}
