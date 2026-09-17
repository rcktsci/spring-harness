package se.rocketscien.harness.tests.execution;

import se.rocketscien.harness.execution.PollWakeJob;
import se.rocketscien.harness.execution.TurnManager;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.session.TurnOutcome;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Task 7.4: POLL-джоба как страховка — подбирает сессии, пропущенные контуром EVENT
 * (имитация «упал процесс между дописью и wake»); consumed-сессии не поднимаются.
 * Чистка ShedLock-строк отменена (D-46) — её тесты удалены вместе с джобовой чисткой.
 */
class PollWakeJobTest extends BaseApplicationTest {

    private static final String PATH = "/v1/chat/completions";

    @Autowired
    private PollWakeJob pollWakeJob;

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
    void pollPicksUpSessionMissedByEventWake() {
        Session session = newSession();
        llmWireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aSseResponse(TurnEngineWireMockTest.textChunk("подобрано POLL-ом"))));

        // Краш между коммитом USER-сообщения и EVENT-wake: строка в журнале есть, tryStart не случился.
        insertUserMessageBypassingWake(session, "пропущенное сообщение");

        pollWakeJob.poll();

        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> sessionStore.findSession(session.id())
                        .map(s -> s.lastTurnOutcome() == TurnOutcome.COMPLETED)
                        .orElse(false));
        llmWireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void pollDoesNothingForConsumedSessions() {
        Session session = newSession();

        pollWakeJob.poll();

        llmWireMock.verify(0, postRequestedFor(urlEqualTo(PATH)));
        assertThat(sessionStore.findSession(session.id()).orElseThrow().lastTurnOutcome()).isNull();
    }

    private Session newSession() {
        return ExecutionFixtures.newSession(jdbcTemplate, sessionStore, idGenerator, environment);
    }

    private void insertUserMessageBypassingWake(Session session, String text) {
        jdbcTemplate.update(
                """
                INSERT INTO session_message (session_id, seq, id, kind, author_user_id, payload_jsonb, created_at)
                VALUES (?, 1, ?, 'USER', ?, to_jsonb(?::jsonb), now())
                """,
                session.id(), idGenerator.newUlid(), session.ownerUserId(), "{\"text\": \"" + text + "\"}");
        jdbcTemplate.update("UPDATE session SET last_seq = 1, last_activity_at = now() WHERE id = ?", session.id());
    }

    private static ResponseDefinitionBuilder aSseResponse(String... events) {
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
