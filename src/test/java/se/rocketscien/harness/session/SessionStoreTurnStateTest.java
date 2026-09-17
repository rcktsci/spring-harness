package se.rocketscien.harness.session;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 7.x: контрактные методы TurnManager'а поверх SessionStore — потребление батча, флаг отмены,
 * eligible-скан, поиск зависших TOOL_CALL.
 */
class SessionStoreTurnStateTest extends BaseApplicationTest {

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    @org.junit.jupiter.api.AfterEach
    void cleanupEligibleLeftovers() {
        // Тесты класса специально оставляют eligible-сессии (watermark/eligible-скан) —
        // убираем их, чтобы poll()/сканы других тест-классов не подбирали их
        jdbcTemplate.update("DELETE FROM session");
    }

    @Test
    void finishTurnConsumesUpToWatermarkSetsOutcomeAndResetsCancelFlag() {
        Session session = SessionTestFixtures.createSession(jdbcTemplate, sessionStore, idGenerator);
        sessionStore.appendEvent(session.id(), MessageKind.USER, null, Map.of("text", "вопрос"));
        sessionStore.requestCancel(session.id());

        // D-45: watermark виденного — отмена без рендера не потребляет журнал
        sessionStore.finishTurn(session.id(), TurnOutcome.CANCELLED, 0);

        Session after = sessionStore.findSession(session.id()).orElseThrow();
        assertThat(after.lastConsumedSeq()).isZero();
        assertThat(after.lastTurnOutcome()).isEqualTo(TurnOutcome.CANCELLED);
        assertThat(after.cancelRequested()).as("флаг cancel_requested сбрасывается при завершении Turn'а").isFalse();
    }

    @Test
    void eventsAfterWatermarkStayUnconsumed() {
        Session session = SessionTestFixtures.createSession(jdbcTemplate, sessionStore, idGenerator);
        sessionStore.appendEvent(session.id(), MessageKind.USER, null, Map.of("text", "вопрос"));
        sessionStore.appendEvent(session.id(), MessageKind.ASSISTANT, null, Map.of("text", "ответ"), 10);

        sessionStore.finishTurn(session.id(), TurnOutcome.COMPLETED, 2);
        assertThat(sessionStore.findSession(session.id()).orElseThrow().lastConsumedSeq()).isEqualTo(2);

        // USER дописан после последнего рендера Turn'а — остаётся непотреблённым (D-45)
        sessionStore.appendEvent(session.id(), MessageKind.USER, null, Map.of("text", "позже"));
        sessionStore.finishTurn(session.id(), TurnOutcome.COMPLETED, 2);
        assertThat(sessionStore.findSession(session.id()).orElseThrow().lastConsumedSeq()).isEqualTo(2);
    }

    @Test
    void requestCancelIsIdempotentAndInvisibleForUnknownSession() {
        Session session = SessionTestFixtures.createSession(jdbcTemplate, sessionStore, idGenerator);

        sessionStore.requestCancel(session.id());
        sessionStore.requestCancel(session.id());

        assertThat(sessionStore.findSession(session.id()).orElseThrow().cancelRequested()).isTrue();

        sessionStore.requestCancel(idGenerator.newUuidV7());
    }

    @Test
    void resetCancelRequestedClearsFlag() {
        Session session = SessionTestFixtures.createSession(jdbcTemplate, sessionStore, idGenerator);
        sessionStore.requestCancel(session.id());

        sessionStore.resetCancelRequested(session.id());

        assertThat(sessionStore.findSession(session.id()).orElseThrow().cancelRequested()).isFalse();
    }

    @Test
    void eligibleScanReturnsOnlySessionsWithUnconsumedEvents() {
        Session eligible = SessionTestFixtures.createSession(jdbcTemplate, sessionStore, idGenerator);
        Session consumed = SessionTestFixtures.createSession(jdbcTemplate, sessionStore, idGenerator);
        Session idle = SessionTestFixtures.createSession(jdbcTemplate, sessionStore, idGenerator);

        sessionStore.appendEvent(eligible.id(), MessageKind.USER, null, Map.of("text", "есть работа"));
        sessionStore.appendEvent(consumed.id(), MessageKind.USER, null, Map.of("text", "сделано"));
        sessionStore.finishTurn(consumed.id(), TurnOutcome.COMPLETED, consumed.lastSeq() + 1);

        List<UUID> eligibleIds = sessionStore.findEligibleSessionIds();

        assertThat(eligibleIds).contains(eligible.id());
        assertThat(eligibleIds).doesNotContain(consumed.id(), idle.id());
    }

    @Test
    void pendingToolCallsAreFoundAndClosedByResult() {
        Session session = SessionTestFixtures.createSession(jdbcTemplate, sessionStore, idGenerator);
        Session other = SessionTestFixtures.createSession(jdbcTemplate, sessionStore, idGenerator);

        sessionStore.appendEvent(session.id(), MessageKind.TOOL_CALL, null,
                Map.of("callId", "call-open", "tool", "bash", "arguments", Map.of()));
        sessionStore.appendEvent(session.id(), MessageKind.TOOL_RESULT, null,
                Map.of("callId", "call-closed", "tool", "bash", "status", "OK"));
        sessionStore.appendEvent(session.id(), MessageKind.TOOL_CALL, null,
                Map.of("callId", "call-closed", "tool", "glob", "arguments", Map.of()));
        sessionStore.appendEvent(other.id(), MessageKind.TOOL_CALL, null,
                Map.of("callId", "call-other", "tool", "grep", "arguments", Map.of()));

        List<UUID> withPending = sessionStore.findSessionIdsWithPendingToolCalls();
        assertThat(withPending).containsExactlyInAnyOrder(session.id(), other.id());

        List<SessionMessageEntity> pending = sessionStore.findPendingToolCalls(session.id());
        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().getId().seq()).isEqualTo(1);

        sessionStore.appendEvent(session.id(), MessageKind.TOOL_RESULT, null,
                Map.of("callId", "call-open", "tool", "bash", "status", "LOST"));
        assertThat(sessionStore.findSessionIdsWithPendingToolCalls()).containsExactly(other.id());
    }

    @Test
    void agentRuntimeResolvesRevisionToModel() {
        Session session = SessionTestFixtures.createSession(jdbcTemplate, sessionStore, idGenerator);

        SessionStore.AgentRuntime runtime = sessionStore.agentRuntime(session.agentRevisionId());

        assertThat(runtime.llmModelId()).isNotNull();
        assertThat(runtime.rolePrompt()).isEqualTo("Промпт");
    }

    @Test
    void appendEventWithTokensFixesTokenCount() {
        Session session = SessionTestFixtures.createSession(jdbcTemplate, sessionStore, idGenerator);

        SessionStore.AppendedEvent appended = sessionStore.appendEvent(
                session.id(), MessageKind.ASSISTANT, null, Map.of("text", "ответ"), 42);

        Integer tokens = jdbcTemplate.queryForObject(
                "SELECT tokens FROM session_message WHERE session_id = ? AND seq = ?",
                Integer.class,
                session.id(), appended.seq()
        );
        assertThat(tokens).isEqualTo(42);
    }
}
