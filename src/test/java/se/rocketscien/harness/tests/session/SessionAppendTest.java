package se.rocketscien.harness.tests.session;
import java.util.ArrayList;


import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionNotFoundException;
import se.rocketscien.harness.session.SessionStore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionAppendTest extends BaseApplicationTest {

    private static final int THREADS = 2;
    private static final int APPENDS_PER_THREAD = 25;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    @Test
    void concurrentAppendsHaveNoGapsAndNoDuplicates() throws Exception {
        Session session = SessionTestFixtures.createSession(jdbcTemplate, sessionStore, idGenerator);

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<List<Long>>> futures = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                futures.add(executor.submit((Callable<List<Long>>) () -> {
                    startGate.await();
                    List<Long> seqs = new ArrayList<>();
                    for (int i = 0; i < APPENDS_PER_THREAD; i++) {
                        seqs.add(sessionStore.appendEvent(
                                session.id(),
                                MessageKind.USER,
                                session.ownerUserId(),
                                Map.of("text", "сообщение потока")
                        ).seq());
                    }
                    return seqs;
                }));
            }

            startGate.countDown();

            List<Long> allSeqs = new ArrayList<>();
            for (Future<List<Long>> future : futures) {
                allSeqs.addAll(future.get(60, TimeUnit.SECONDS));
            }

            int expectedTotal = THREADS * APPENDS_PER_THREAD;

            assertThat(allSeqs)
                    .hasSize(expectedTotal)
                    .doesNotHaveDuplicates()
                    .containsExactlyInAnyOrderElementsOf(
                            LongStream.rangeClosed(1, expectedTotal).boxed().toList());

            Long lastSeqInDb = jdbcTemplate.queryForObject(
                    "SELECT last_seq FROM session WHERE id = ?",
                    Long.class,
                    session.id()
            );

            assertThat(lastSeqInDb).isEqualTo(expectedTotal);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void appendToUnknownSessionFails() {
        assertThatThrownBy(() -> sessionStore.appendEvent(
                idGenerator.newUuidV7(),
                MessageKind.USER,
                null,
                Map.of("text", "в никуда")
        )).isInstanceOf(SessionNotFoundException.class);
    }

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void failedAppendRollsBackSeqReservation() {
        Session session = SessionTestFixtures.createSession(jdbcTemplate, sessionStore, idGenerator);
        org.springframework.transaction.support.TransactionTemplate transactionTemplate =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            sessionStore.appendEvent(session.id(), MessageKind.USER, null, Map.of("text", "откатится"));
            throw new IllegalStateException("сбой после дописи");
        })).isInstanceOf(IllegalStateException.class);

        Long lastSeq = jdbcTemplate.queryForObject(
                "SELECT last_seq FROM session WHERE id = ?",
                Long.class,
                session.id()
        );
        Integer messages = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_message WHERE session_id = ?",
                Integer.class,
                session.id()
        );

        assertThat(lastSeq).isZero();
        assertThat(messages).isZero();

        SessionStore.AppendedEvent next = sessionStore.appendEvent(
                session.id(),
                MessageKind.USER,
                null,
                Map.of("text", "после отката")
        );

        assertThat(next.seq()).isEqualTo(1);
    }
}
