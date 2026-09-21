package se.rocketscien.harness.tests.execution;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.config.AsyncProperties;
import se.rocketscien.harness.config.LockProperties;
import se.rocketscien.harness.execution.NativeAgentTools;
import se.rocketscien.harness.execution.SessionLockManager;
import se.rocketscien.harness.execution.ToolResult;
import se.rocketscien.harness.execution.TurnCancellation;
import se.rocketscien.harness.execution.TurnManager;
import se.rocketscien.harness.execution.WorkspaceTools;
import se.rocketscien.harness.execution.impl.AsyncToolExecutor;
import se.rocketscien.harness.session.InvalidCursorException;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionKind;
import se.rocketscien.harness.session.SessionMessageEntity;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.session.TurnOutcome;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит N.1: окно {@code AsyncToolExecutor} — уложившийся инструмент возвращает sync-результат;
 * превысивший окно — {@code Parked} с поздней публикацией {@code TOOL_RESULT(late=true)} под
 * sess-локом и wake; правило «первый финальный выигрывает» (D-64) — существующий TOOL_RESULT
 * с тем же callId делает публикацию no-op; занятый лок — повторные попытки с интервалом конфига.
 */
class AsyncToolExecutorTest {

    private final IdGenerator idGenerator = new IdGenerator();

    @Test
    void fastToolResolvesWithinWindow() {
        StubWorkspaceTools workspace = new StubWorkspaceTools(latchNever(), Duration.ZERO);
        AsyncFixture fixture = newFixture(workspace, new AtomicInteger(0), false);

        AsyncToolExecutor.Outcome outcome = fixture.executor().execute(
                fixture.sessionId(), "call-fast", "bash", Map.of("command", "echo hi"),
                new TurnCancellation());

        assertThat(outcome).isInstanceOf(AsyncToolExecutor.Outcome.Resolved.class);
        assertThat(((AsyncToolExecutor.Outcome.Resolved) outcome).result().output()).isEqualTo("done");
        assertThat(fixture.store().appended()).as("sync-результат журналирует движок, не исполнитель").isEmpty();
        assertThat(fixture.turnManager().started()).isEmpty();
    }

    @Test
    void slowToolParksAndPublishesLateResult() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        StubWorkspaceTools workspace = new StubWorkspaceTools(running, finish);
        AsyncFixture fixture = newFixture(workspace, new AtomicInteger(0), false);

        AsyncToolExecutor.Outcome outcome = fixture.executor().execute(
                fixture.sessionId(), "call-slow", "bash", Map.of("command", "sleep 5"),
                new TurnCancellation());

        assertThat(outcome).isInstanceOf(AsyncToolExecutor.Outcome.Parked.class);
        finish.countDown();

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> !fixture.store().appended().isEmpty());
        assertThat(running.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(fixture.store().appended()).hasSize(1);
        Appended appended = fixture.store().appended().getFirst();
        assertThat(appended.kind()).isEqualTo(MessageKind.TOOL_RESULT);
        assertThat(appended.payload().get("callId")).isEqualTo("call-slow");
        assertThat(appended.payload().get("status")).isEqualTo("OK");
        assertThat(appended.payload().get("late")).isEqualTo(true);
        assertThat(fixture.turnManager().started()).containsExactly(fixture.sessionId());
    }

    @Test
    void existingFinalResultMakesLatePublicationNoOp() throws Exception {
        // bash завершится сам через 300ms (окно 150ms) — публикация уйдёт в фон и упрётся в LOST
        StubWorkspaceTools workspace = new StubWorkspaceTools(latchNever(), Duration.ofMillis(300));
        AsyncFixture fixture = newFixture(workspace, new AtomicInteger(0), true);

        AsyncToolExecutor.Outcome outcome = fixture.executor().execute(
                fixture.sessionId(), "call-lost", "bash", Map.of("command", "sleep 5"),
                new TurnCancellation());
        assertThat(outcome).isInstanceOf(AsyncToolExecutor.Outcome.Parked.class);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> workspace.completedCalls() > 0);
        Thread.sleep(300);

        assertThat(fixture.store().appended()).as("LOST пришёл раньше — поздний результат no-op (D-64)").isEmpty();
        assertThat(fixture.turnManager().started()).isEmpty();
    }

    @Test
    void busyLockIsRetriedUntilPublishSucceeds() throws Exception {
        StubWorkspaceTools workspace = new StubWorkspaceTools(latchNever(), Duration.ofMillis(200));
        AsyncFixture fixture = newFixture(workspace, new AtomicInteger(2), false);

        AsyncToolExecutor.Outcome outcome = fixture.executor().execute(
                fixture.sessionId(), "call-contended", "bash", Map.of("command", "sleep 5"),
                new TurnCancellation());
        assertThat(outcome).isInstanceOf(AsyncToolExecutor.Outcome.Parked.class);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> !fixture.store().appended().isEmpty());
        assertThat(fixture.turnManager().started()).containsExactly(fixture.sessionId());
    }

    // ---------------------------------------------------------------- фикстуры

    private record AsyncFixture(UUID sessionId, AsyncToolExecutor executor,
                                StubSessionStore store, StubTurnManager turnManager) {
    }

    private AsyncFixture newFixture(StubWorkspaceTools workspace, AtomicInteger lockDenials,
                                    boolean toolResultAlreadyPresent) {
        StubSessionStore store = new StubSessionStore(toolResultAlreadyPresent);
        StubTurnManager turnManager = new StubTurnManager();
        SessionLockManager locks = new SessionLockManager(
                new CountingLockProvider(lockDenials),
                new LockProperties(Duration.ofSeconds(10), Duration.ofHours(1), Duration.ofSeconds(10)));
        AsyncProperties properties = new AsyncProperties(
                new AsyncProperties.Window(Duration.ofMillis(150)), Duration.ofMillis(20));
        AsyncToolExecutor executor = new AsyncToolExecutor(
                new NativeAgentTools(workspace), store, locks, new ObjectProvider<>() {
                @Override
                public TurnManager getObject() {
                    return turnManager;
                }
            }, properties);
        return new AsyncFixture(store.sessionId(), executor, store, turnManager);
    }

    private static CountDownLatch latchNever() {
        return new CountDownLatch(1);
    }

    /** bash-заглушка: сигнал старта, удержание длительности, фиксированный результат. */
    private static final class StubWorkspaceTools implements WorkspaceTools {

        private final CountDownLatch running;
        private final CountDownLatch hold;
        private final Duration sleep;
        private final AtomicInteger completed = new AtomicInteger();

        StubWorkspaceTools(CountDownLatch hold) {
            this.running = null;
            this.hold = hold;
            this.sleep = Duration.ZERO;
        }

        StubWorkspaceTools(CountDownLatch running, CountDownLatch hold) {
            this.running = running;
            this.hold = hold;
            this.sleep = Duration.ZERO;
        }

        StubWorkspaceTools(CountDownLatch running, Duration sleep) {
            this.running = running;
            this.hold = null;
            this.sleep = sleep;
        }

        int completedCalls() {
            return completed.get();
        }

        @Override
        public Set<String> asyncCapabilities() {
            return Set.of("bash");
        }

        @Override
        public ToolResult readFile(UUID sessionId, String path, Integer offset, Integer limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ToolResult writeFile(UUID sessionId, String path, String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ToolResult editFile(UUID sessionId, String path, String oldString, String newString,
                                   boolean replaceAll) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ToolResult glob(UUID sessionId, String pattern) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ToolResult grep(UUID sessionId, String pattern, String include) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ToolResult bash(UUID sessionId, String command, Duration timeout, String cwd) {
            return bash(sessionId, command, timeout, cwd, null);
        }

        @Override
        public ToolResult bash(UUID sessionId, String command, Duration timeout, String cwd,
                               TurnCancellation cancellation) {
            try {
                sleepQuietly(sleep);
                if (running != null && running.getCount() > 0) {
                    running.countDown();
                }
                if (hold != null) {
                    hold.await(10, TimeUnit.SECONDS);
                }
                return ToolResult.ok("internal", "bash", "done");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.cancelled("internal", "bash", "interrupted");
            } finally {
                completed.incrementAndGet();
            }
        }

        @Override
        public ToolResult executeBash(UUID taskId, String script, Duration timeout, String cwd) {
            throw new UnsupportedOperationException();
        }

        private static void sleepQuietly(Duration duration) {
            if (duration == null || duration.isZero() || duration.isNegative()) {
                return;
            }
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    record Appended(MessageKind kind, Map<String, Object> payload) {
    }

    /** Минимальный SessionStore: append/чек результата/поиск сессии; остальное — не для юнита. */
    private static final class StubSessionStore implements SessionStore {

        private final UUID sessionId = UUID.randomUUID();
        private final boolean toolResultPresent;
        /** Публикация позднего результата — фон: список читается из другого потока, copy-on-write. */
        private final List<Appended> appended = new CopyOnWriteArrayList<>();

        StubSessionStore(boolean toolResultPresent) {
            this.toolResultPresent = toolResultPresent;
        }

        UUID sessionId() {
            return sessionId;
        }

        List<Appended> appended() {
            return appended;
        }

        @Override
        public Session createFreeSession(UUID ownerUserId, String agentKey, Integer agentRevision, String title) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Session createChildSession(UUID parentSessionId, String agentKey, String title) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String findLastAssistantText(UUID id) {
            return null;
        }

        @Override
        public Optional<MessageRef> findMessageRef(String messageId) {
            return Optional.empty();
        }

        @Override
        public List<SessionMessageEntity> findCompactedOriginals(UUID id, long seq) {
            return List.of();
        }

        @Override
        public AppendedEvent appendEvent(UUID id, MessageKind kind, UUID authorUserId, Map<String, Object> payload) {
            appended.add(new Appended(kind, payload));
            return new AppendedEvent(appended.size(), "ulid-" + appended.size());
        }

        @Override
        public AppendedEvent appendEvent(UUID id, MessageKind kind, UUID authorUserId, Map<String, Object> payload,
                                         Integer tokens) {
            return appendEvent(id, kind, authorUserId, payload);
        }

        @Override
        public boolean hasToolResultForCall(UUID id, String callId) {
            return toolResultPresent;
        }

        @Override
        public List<PendingAsyncCall> findExpiredAsyncAccepteds(Instant createdBefore) {
            return List.of();
        }

        @Override
        public Optional<Session> findSession(UUID id) {
            return Optional.of(new Session(sessionId, SessionKind.FREE, null, UUID.randomUUID(), null, null,
                    UUID.randomUUID(), null, 0, false, 0, 0, TurnOutcome.COMPLETED,
                    Instant.now(), Instant.now()));
        }

        @Override
        public List<Session> findSubtree(UUID id, Integer depth) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UUID> findEligibleSessionIds() {
            return List.of();
        }

        @Override
        public List<UUID> findAllSessionIds() {
            return List.of();
        }

        @Override
        public void requestCancel(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void resetCancelRequested(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void finishTurn(UUID id, TurnOutcome outcome, long consumedSeq) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentRuntime agentRuntime(UUID agentRevisionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UUID> findSessionIdsWithPendingToolCalls() {
            return List.of();
        }

        @Override
        public List<SessionMessageEntity> findPendingToolCalls(UUID id) {
            return List.of();
        }

        @Override
        public List<MessageKind> findPendingKinds(UUID id, long afterSeq) {
            return List.of();
        }

        @Override
        public List<SessionMessageEntity> renderVisible(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SessionSearchResult searchSessions(SessionSearchCriteria criteria) throws InvalidCursorException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void renameSession(UUID id, String newTitle) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AgentRevisionSummary> agentCatalog() {
            return List.of();
        }

        @Override
        public Map<UUID, AgentRevisionSummary> agentSummaries(Collection<UUID> revisionIds) {
            return Map.of();
        }
    }

    private static final class StubTurnManager implements TurnManager {

        /** Wake зовётся из фонового потока публикации — copy-on-write (гонка флаки). */
        private final List<UUID> started = new CopyOnWriteArrayList<>();

        List<UUID> started() {
            return started;
        }

        @Override
        public void tryStart(UUID sessionId) {
            started.add(sessionId);
        }

        @Override
        public void requestStop(UUID sessionId) {
            throw new UnsupportedOperationException();
        }
    }

    /** LockProvider-заглушка: первые {@code denials} попыток — «лок занят». */
    private static final class CountingLockProvider implements LockProvider {

        private final AtomicInteger denials;

        CountingLockProvider(AtomicInteger denials) {
            this.denials = denials;
        }

        @Override
        public Optional<SimpleLock> lock(LockConfiguration lockConfiguration) {
            if (denials.getAndUpdate(before -> Math.max(0, before - 1)) > 0) {
                return Optional.empty();
            }
            return Optional.of(new SimpleLock() {
                @Override
                public Optional<SimpleLock> extend(Duration lockAtMostFor, Duration lockAtLeastFor) {
                    return Optional.of(this);
                }

                @Override
                public void unlock() {
                }
            });
        }
    }
}
