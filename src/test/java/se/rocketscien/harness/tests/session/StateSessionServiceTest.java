package se.rocketscien.harness.tests.session;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionKind;
import se.rocketscien.harness.session.StateSessionService;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.tests.task.TaskTestFixtures;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STATE-сессии (спека session-store «Создание STATE-сессии», пачка J.1): атомарное создание
 * (сессия + seed-SYSTEM + last_seq — execution-model §7.2), UPSERT-резюм по PARTIAL UNIQUE
 * {@code (task_id, state_code) WHERE kind='STATE'} без гонок и дублей seed'а.
 */
class StateSessionServiceTest extends BaseApplicationTest {

    @Autowired
    private StateSessionService stateSessions;

    @Autowired
    private TaskRegistry taskRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    @Test
    void firstEntryCreatesSessionWithSeedAtomically() {
        Task task = newWaitTask();

        Session session = stateSessions.findOrCreate(task.id(), "plan", pinRevision());

        assertThat(session.kind()).isEqualTo(SessionKind.STATE);
        assertThat(session.taskId()).isEqualTo(task.id());
        assertThat(session.stateCode()).isEqualTo("plan");
        assertThat(session.lastSeq()).as("seed занимает seq=1").isEqualTo(1);
        assertThat(session.lastConsumedSeq()).isZero();
        assertThat(session.lastActivityAt()).isNotNull();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT owner_user_id, agent_revision_id, title FROM session WHERE id = ?", session.id());
        assertThat(UUID.fromString(row.get("owner_user_id").toString()))
                .as("владелец STATE-сессии — владелец задачи").isEqualTo(task.ownerUserId());
        assertThat(UUID.fromString(row.get("agent_revision_id").toString()))
                .isEqualTo(session.agentRevisionId());

        Map<String, Object> seed = jdbcTemplate.queryForMap(
                "SELECT seq, kind, payload_jsonb FROM session_message WHERE session_id = ?", session.id());
        assertThat(((Number) seed.get("seq")).longValue()).isEqualTo(1);
        assertThat(seed.get("kind")).isEqualTo(MessageKind.SYSTEM.name());
        assertThat(seed.get("payload_jsonb").toString()).contains("plan");
    }

    @Test
    void secondEntryResumesExistingSessionWithoutDuplicateSeed() {
        Task task = newWaitTask();
        UUID pinned = pinRevision();
        Session first = stateSessions.findOrCreate(task.id(), "plan", pinned);

        // повторный вход: другая (более новая) ревизия в аргументе пин НЕ меняет
        Session resumed = stateSessions.findOrCreate(task.id(), "plan", pinRevision());

        assertThat(resumed.id()).isEqualTo(first.id());
        assertThat(resumed.agentRevisionId()).isEqualTo(pinned);
        Integer seeds = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM session_message WHERE session_id = ?", Integer.class, first.id());
        assertThat(seeds).as("seed не дублируется при резюме").isEqualTo(1);
    }

    @Test
    void differentStateCodesGetDifferentSessions() {
        Task task = newWaitTask();

        Session plan = stateSessions.findOrCreate(task.id(), "plan", pinRevision());
        Session review = stateSessions.findOrCreate(task.id(), "review", pinRevision());

        assertThat(plan.id()).isNotEqualTo(review.id());
        Integer sessions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM session WHERE task_id = ? AND kind = 'STATE'",
                Integer.class, task.id());
        assertThat(sessions).isEqualTo(2);
    }

    @Test
    void concurrentFindOrCreateHasSingleWinnerAndSingleSeed() throws Exception {
        Task task = newWaitTask();
        int competitors = 8;
        CountDownLatch ready = new CountDownLatch(competitors);
        ExecutorService pool = Executors.newFixedThreadPool(competitors);
        try {
            Callable<Session> attempt = () -> {
                ready.countDown();
                ready.await(5, TimeUnit.SECONDS);
                return stateSessions.findOrCreate(task.id(), "plan", pinRevision());
            };
            List<Future<Session>> results = new java.util.ArrayList<>();
            for (int i = 0; i < competitors; i++) {
                results.add(pool.submit(attempt));
            }
            List<Session> sessions = new java.util.ArrayList<>();
            for (Future<Session> result : results) {
                sessions.add(result.get(15, TimeUnit.SECONDS));
            }
            assertThat(sessions.stream().map(Session::id).distinct())
                    .as("PARTIAL UNIQUE закрывает гонку — все видят одну сессию")
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        Integer seeds = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM session_message WHERE session_id = ?",
                Integer.class, jdbcTemplate.queryForObject(
                        "SELECT id FROM session WHERE task_id = ? AND kind='STATE'", UUID.class, task.id()));
        assertThat(seeds).as("seed ровно один — создание атомарно").isEqualTo(1);
    }

    /** Задача в WAIT_TASKS-старте (без AGENT — иначе EVENT-wake поднимет bootstrap). */
    private Task newWaitTask() {
        return TaskTestFixtures.createTask(taskRegistry, jdbcTemplate, idGenerator,
                TaskTestFixtures.waitTasksGraph(), "gather");
    }

    private UUID pinRevision() {
        UUID revisionId = idGenerator.newUuidV7();
        UUID modelId = SessionTestFixtures.insertLlmModel(jdbcTemplate, idGenerator);
        jdbcTemplate.update(
                """
                INSERT INTO agent (id, key, name, rev, role_prompt, llm_model_id, created_at)
                VALUES (?, ?, 'Агент состояния', 1, 'Промпт', ?, now())
                """,
                revisionId, "state-agent-" + revisionId, modelId);
        return revisionId;
    }
}
