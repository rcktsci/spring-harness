package se.rocketscien.harness.tests.execution;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Duration;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;


import se.rocketscien.harness.execution.TurnManager;
import se.rocketscien.harness.execution.WorkspaceContainerManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.session.TurnOutcome;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Task 7.5: отмена — stop во время bash (убийство процесса в контейнере → TOOL_RESULT CANCELLED,
 * Turn CANCELLED), идемпотентность повторного stop, безвредность stop на IDLE-сессии и штатная
 * обработка сообщения после отмены.
 */
class TurnCancellationTest extends BaseApplicationTest {

    static {
        DockerTestSupport.helperImage();
    }

    private static final String PATH = "/v1/chat/completions";

    @Autowired
    private TurnManager turnManager;

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
        llmWireMock.resetAll();
    }

    @Test
    void stopDuringBashKillsProcessAndCancelsTurn() {
        Session session = newSession();
        stubToolCallBash("sleep 600; echo never");
        long startedAt = System.nanoTime();

        appendUser(session, "запусти долгий bash");
        turnManager.tryStart(session.id());
        awaitJournalContains(session.id(), "TOOL_CALL");

        turnManager.requestStop(session.id());

        awaitOutcome(session.id(), TurnOutcome.CANCELLED);

        long elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000;
        assertThat(elapsedSeconds).as("отмена не ждёт таймаута bash").isLessThan(60);

        assertThat(journalField(session.id(), "TOOL_RESULT", "status")).isEqualTo("CANCELLED");

        Session after = sessionStore.findSession(session.id()).orElseThrow();
        // D-45: потреблено только отрендеренное (первый USER); собственные результаты
        // (ASSISTANT/TOOL_CALL/TOOL_RESULT CANCELLED) остаются непотреблёнными
        assertThat(after.lastConsumedSeq()).isEqualTo(1);
        assertThat(after.lastSeq()).isGreaterThan(after.lastConsumedSeq());
        assertThat(after.cancelRequested()).as("флаг сбрасывается при завершении Turn'а").isFalse();

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> sleepProcessDead(session.id()));
        assertThat(sleepProcessDead(session.id())).as("sleep в контейнере убит").isTrue();

        // Повторный stop по завершённой сессии идемпотентен: новых событий и исходов нет
        int journalSize = journalKinds(session.id()).size();
        turnManager.requestStop(session.id());
        turnManager.requestStop(session.id());
        org.assertj.core.api.Assertions.assertThatCode(() ->
                await().atMost(Duration.ofSeconds(2)).until(() -> true)).doesNotThrowAnyException();
        assertThat(journalKinds(session.id())).hasSize(journalSize);
        assertThat(sessionStore.findSession(session.id()).orElseThrow().lastTurnOutcome())
                .isEqualTo(TurnOutcome.CANCELLED);

        containers.removeContainer(session.id());
    }

    @Test
    void stopOnIdleSessionIsHarmlessAndNextMessageStillWorks() {
        Session session = newSession();
        llmWireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(sse(TurnEngineWireMockTest.textChunk("штатный ответ"))));

        turnManager.requestStop(session.id());
        turnManager.requestStop(session.id());
        assertThat(sessionStore.findSession(session.id()).orElseThrow().cancelRequested()).isTrue();

        appendUser(session, "сообщение после отмены");
        turnManager.tryStart(session.id());

        awaitOutcome(session.id(), TurnOutcome.COMPLETED);
        llmWireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));

        Session after = sessionStore.findSession(session.id()).orElseThrow();
        assertThat(after.lastTurnOutcome()).isEqualTo(TurnOutcome.COMPLETED);
        assertThat(after.cancelRequested()).isFalse();
    }

    @Test
    void stopDuringLlmStreamDisposesSubscriptionAndCancelsQuickly() {
        Session session = newSession();
        // Медленный стрим: без диспоза Turn дочитывал бы генерацию до конца задержки
        llmWireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                                + "\"model\":\"gpt-test\",\"choices\":[{\"index\":0,\"delta\":{\"content\":"
                                + "\"медленный ответ\"},\"finish_reason\":null}]}\n\n"
                                + "data: [DONE]\n\n")
                        .withFixedDelay(60_000)));

        long startedAt = System.nanoTime();
        appendUser(session, "долгая генерация");
        turnManager.tryStart(session.id());

        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> !llmWireMock.getAllServeEvents().isEmpty());

        turnManager.requestStop(session.id());
        awaitOutcome(session.id(), TurnOutcome.CANCELLED);

        long elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000;
        assertThat(elapsedSeconds).as("стрим не дочитывается — Turn отменяется быстро").isLessThan(15);

        assertThat(journalKinds(session.id())).as("ответ не журналируется").containsExactly("USER");
        Session after = sessionStore.findSession(session.id()).orElseThrow();
        assertThat(after.lastConsumedSeq()).isEqualTo(after.lastSeq());
    }

    @Test
    void userMessageDuringCancellationWindowSurvivesAndStartsNewTurn() {
        Session session = newSession();
        String argumentsJson = "{\"command\":\"sleep 600; echo never\"}";
        llmWireMock.stubFor(post(urlEqualTo(PATH))
                .inScenario("cancel-window")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(TurnEngineWireMockTest.toolCallChunk("call-cancel-2", "bash", argumentsJson)))
                .willSetStateTo("resume"));
        llmWireMock.stubFor(post(urlEqualTo(PATH))
                .inScenario("cancel-window")
                .whenScenarioStateIs("resume")
                .willReturn(sse(TurnEngineWireMockTest.textChunk("продолжаю после отмены"))));

        appendUser(session, "первый вопрос");
        turnManager.tryStart(session.id());
        awaitJournalContains(session.id(), "TOOL_CALL");

        // USER в окне отмены: после write-ahead TOOL_CALL, до CANCELLED-результата
        long userInWindowSeq = sessionStore.appendEvent(
                session.id(), MessageKind.USER, session.ownerUserId(),
                Map.of("text", "сообщение-в-окне-отмены")).seq();
        turnManager.requestStop(session.id());
        awaitOutcome(session.id(), TurnOutcome.CANCELLED);

        // D-45: потреблено только отрендеренное (первый USER) — сообщение в окне отмены живо
        Session afterCancel = sessionStore.findSession(session.id()).orElseThrow();
        assertThat(afterCancel.lastConsumedSeq()).isLessThan(afterCancel.lastSeq());

        // EVENT/POLL поднимает новый Turn — он видит сообщение из окна отмены
        turnManager.tryStart(session.id());
        awaitOutcome(session.id(), TurnOutcome.COMPLETED);

        llmWireMock.verify(1, postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(containing("сообщение-в-окне-отмены")));
        assertThat(journalSeqOfLast(session.id(), "ASSISTANT")).isGreaterThan(userInWindowSeq);

        Session completed = sessionStore.findSession(session.id()).orElseThrow();
        assertThat(completed.lastConsumedSeq()).isEqualTo(completed.lastSeq());

        containers.removeContainer(session.id());
    }

    private long journalSeqOfLast(UUID sessionId, String kind) {
        return jdbcTemplate.queryForObject(
                "SELECT max(seq) FROM session_message WHERE session_id = ? AND kind = ?",
                Long.class, sessionId, kind);
    }

    private Session newSession() {
        return ExecutionFixtures.newSession(jdbcTemplate, sessionStore, idGenerator, environment);
    }

    private void stubToolCallBash(String command) {
        String argumentsJson = "{\"command\":\"" + command + "\"}";
        llmWireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(sse(TurnEngineWireMockTest.toolCallChunk("call-cancel-1", "bash", argumentsJson))));
    }

    private void appendUser(Session session, String text) {
        sessionStore.appendEvent(session.id(), MessageKind.USER, session.ownerUserId(), Map.of("text", text));
    }

    /**
     * Проверка смерти процесса: паттерн с классом символов, чтобы обёртка {@code sh -c
     * "pgrep -f 'sleep 6[0]0'..."} не матчила сама себя; код выхода = код pgrep (1 — не найдено).
     */
    private boolean sleepProcessDead(UUID sessionId) {
        var result = containers.exec(sessionId,
                List.of("sh", "-c", "pgrep -f 'sleep 6[0]0' >/dev/null 2>&1"),
                null, Duration.ofSeconds(10), null);
        return result.exitCode() == 1;
    }

    private void awaitJournalContains(UUID sessionId, String kind) {
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> journalKinds(sessionId).contains(kind));
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
