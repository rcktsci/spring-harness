package se.rocketscien.harness.execution;

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

import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Task 7.4: POLL-джоба как страховка — подбирает сессии, пропущенные контуром EVENT
 * (имитация «упал процесс между дописью и wake»), и чистит просроченные {@code sess-*}-строки
 * ShedLock по критерию {@code lock_until < now()}.
 */
class PollWakeJobTest extends BaseExecutionTest {

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
        LlmWireMockInitializer.llm().resetAll();
    }

    @Test
    void pollPicksUpSessionMissedByEventWake() {
        Session session = newSession();
        LlmWireMockInitializer.llm().stubFor(post(urlEqualTo(PATH))
                .willReturn(aSseResponse(TurnEngineWireMockTest.textChunk("подобрано POLL-ом"))));

        // Краш между коммитом USER-сообщения и EVENT-wake: строка в журнале есть, tryStart не случился.
        insertUserMessageBypassingWake(session, "пропущенное сообщение");

        pollWakeJob.poll();

        await().atMost(java.time.Duration.ofSeconds(60))
                .pollInterval(java.time.Duration.ofMillis(100))
                .until(() -> sessionStore.findSession(session.id())
                        .map(s -> s.lastTurnOutcome() == TurnOutcome.COMPLETED)
                        .orElse(false));
        LlmWireMockInitializer.llm().verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void pollCleansExpiredSessionLockRowsOnly() {
        String expiredSessionLock = "sess-" + idGenerator.newUuidV7();
        String liveSessionLock = "sess-" + idGenerator.newUuidV7();
        String jobLockRow = "another-job";

        // Данные пишутся так же, как их пишет ShedLock — в UTC (сравнение D-J-6 именно с UTC)
        jdbcTemplate.update(
                "INSERT INTO shedlock (name, lock_until, locked_at, locked_by)"
                        + " VALUES (?, timezone('utc', now()) - interval '1 hour', timezone('utc', now()) - interval '2 hours', 'dead-instance')",
                expiredSessionLock);
        jdbcTemplate.update(
                "INSERT INTO shedlock (name, lock_until, locked_at, locked_by)"
                        + " VALUES (?, timezone('utc', now()) + interval '5 minutes', timezone('utc', now()), 'live-instance')",
                liveSessionLock);
        jdbcTemplate.update(
                "INSERT INTO shedlock (name, lock_until, locked_at, locked_by)"
                        + " VALUES (?, timezone('utc', now()) - interval '1 hour', timezone('utc', now()) - interval '2 hours', 'job-row')",
                jobLockRow);

        pollWakeJob.poll();

        List<String> remaining = jdbcTemplate.queryForList("SELECT name FROM shedlock ORDER BY name", String.class);
        assertThat(remaining).doesNotContain(expiredSessionLock);
        assertThat(remaining).as("живой продлённый лок и чужие строки не трогаем").contains(liveSessionLock);
    }

    @Test
    void cleanupComparesLockUntilInDbUtc() {
        // D-J-6: сравнение строго в БД (UTC, как пишет ShedLock), без Java-параметра времени
        assertThat(PollWakeJob.CLEANUP_SQL).contains("timezone('utc', now())");
        assertThat(PollWakeJob.CLEANUP_SQL).contains("sess-%");
        assertThat(PollWakeJob.CLEANUP_SQL).doesNotContain("?");
    }

    @Test
    void pollDoesNothingForConsumedSessions() {
        Session session = newSession();

        pollWakeJob.poll();

        LlmWireMockInitializer.llm().verify(0, postRequestedFor(urlEqualTo(PATH)));
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

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder aSseResponse(String... events) {
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
