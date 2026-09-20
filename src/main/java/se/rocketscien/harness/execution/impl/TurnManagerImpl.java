package se.rocketscien.harness.execution.impl;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import se.rocketscien.harness.execution.ActiveTurnRegistry;
import se.rocketscien.harness.execution.AgentTurnEngine;
import se.rocketscien.harness.execution.InstructionSource;
import se.rocketscien.harness.execution.SessionLockManager;
import se.rocketscien.harness.execution.TurnCancellation;
import se.rocketscien.harness.execution.TurnManager;
import se.rocketscien.harness.execution.TurnPayloads;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionEventBroadcaster;
import se.rocketscien.harness.session.SessionRuntimeStatus;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.session.TurnOutcome;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wake-точка и жизненный цикл Turn'а (D-M1-4/D-M1-5): tryStart → лок {@code sess-{id}}
 * (занят — no-op) → виртуальный поток → сброс cancel-флага → агентный цикл
 * {@link AgentTurnEngine} → unlock в finally. Статусы — в broadcaster (SSE-подписчики, 8.5).
 *
 * <p>J-1/R-1: EVENT-wake USER-сообщения теряется, если в момент дописи лок занят идущим
 * Turn'ом (tryStart — no-op). Два сценария потери USER-намерения и их закрытие: (1) ход
 * завершился, USER непрочитан (CANCELLED-watermark / micro-окно после финального ASSISTANT) —
 * post-finish проверка журнала; (2) COMPLETED не-USER-ход отрендерил USER доп. раундом,
 * гейт заблокировал {@code transition} — движок замораживает {@code last_consumed_seq} на
 * USER (pending-намерение, {@code AgentTurnEngine}), USER остаётся в батче. В обоих случаях —
 * session-wake: следующий Turn стартует с {@code instructionSource=USER}.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
class TurnManagerImpl implements TurnManager {


    private final SessionStore sessionStore;
    private final SessionLockManager sessionLocks;
    private final AgentTurnEngine engine;
    private final ActiveTurnRegistry activeTurns;
    private final SessionEventBroadcaster broadcaster;
    private final SubtreeCanceller subtreeCanceller;
    private final ExecutorService turnExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public void tryStart(UUID sessionId) {
        Optional<Session> session = sessionStore.findSession(sessionId);
        if (session.isEmpty() || session.get().lastSeq() <= session.get().lastConsumedSeq()) {
            return;
        }
        if (session.get().cancelRequested()) {
            // O-2: stop персистентен — сессия остановленного поддерева (SubtreeCanceller /
            // requestStop) не поднимается POLL'ом, поздним результатом или рестарт-сканом,
            // пока не случится явный resume: сообщение пользователя (SessionMessagesController)
            // или вход/resume задачи (AgentStateBootstrapper) — они сбрасывают флаг.
            log.debug("Сессия {}: под cancel_requested — попытка запуска без эффекта", sessionId);
            return;
        }
        turnExecutor.submit(() -> runTurn(sessionId));
    }

    @Override
    public void requestStop(UUID sessionId) {
        // O.3: stop каскадирует по поддереву (parent_session_id) — включая саму сессию
        subtreeCanceller.cancelSubtree(sessionId);
    }

    private void runTurn(UUID sessionId) {
        Optional<SessionLockManager.HeldLock> lock = sessionLocks.tryAcquire(sessionId);
        if (lock.isEmpty()) {
            log.debug("Сессия {}: лок занят — попытка запуска без эффекта", sessionId);
            return;
        }
        TurnCancellation cancellation = activeTurns.register(sessionId);
        broadcaster.publishStatus(sessionId, SessionRuntimeStatus.TURN_RUNNING,
                sessionStore.findSession(sessionId).map(Session::lastTurnOutcome).orElse(null));
        AgentTurnEngine.TurnResult result = null;
        try {
            // Сброс флага на старте нового Turn'а (спека agent-turn; stop по IDLE не гасит новые ходы)
            sessionStore.resetCancelRequested(sessionId);
            result = engine.run(sessionId, cancellation);
        } catch (Exception e) {
            log.error("Turn сессии {} упал неожиданно", sessionId, e);
            try {
                SessionStore.AppendedEvent systemEvent = sessionStore.appendEvent(
                        sessionId, MessageKind.SYSTEM, null, TurnPayloads.systemFailure(e), null);
                sessionStore.finishTurn(sessionId, TurnOutcome.FAILED, systemEvent.seq());
            } catch (Exception finishFailure) {
                log.error("Не удалось зафиксировать FAILED для сессии {}", sessionId, finishFailure);
            }
        } finally {
            // DS F5: кадр session.status обязан нести lastTurnOutcome (api-contracts §3.1).
            // M3 D-60: Turn вышел с pending async — сессия паркуется в PARKED_ASYNC;
            // wake придёт с поздним TOOL_RESULT (message.created)
            boolean parkedAsync = result != null && result.parkedAsync();
            broadcaster.publishStatus(sessionId,
                    parkedAsync ? SessionRuntimeStatus.PARKED_ASYNC : SessionRuntimeStatus.IDLE,
                    sessionStore.findSession(sessionId).map(Session::lastTurnOutcome).orElse(null));
            activeTurns.unregister(sessionId);
            lock.get().close();
        }
        if (result != null && result.source() != null) {
            rewakeForUserIntent(sessionId, result.source());
        }
    }

    /**
     * J-1/R-1: после не-USER-хода непрочитанный USER — потерянное USER-намерение: его
     * EVENT-wake выпал на занятом локе, либо ход COMPLETED-потребил бы его без cap'а движка
     * (pending-намерение кодируется самим watermark'ом — USER остаётся в батче). Переподнятие —
     * строго после unlock (иначе tryStart — no-op); новый Turn резолвит
     * {@code instructionSource=USER} из свежего батча. Для USER-ходов не применяется: их
     * mid-turn USER попадает в дополнительный раунд с корректным гейтом.
     */
    private void rewakeForUserIntent(UUID sessionId, InstructionSource finishedSource) {
        if (finishedSource == InstructionSource.USER) {
            return;
        }
        try {
            Session session = sessionStore.findSession(sessionId).orElse(null);
            if (session == null || session.lastSeq() <= session.lastConsumedSeq()) {
                return;
            }
            if (sessionStore.findPendingKinds(sessionId, session.lastConsumedSeq())
                    .contains(MessageKind.USER)) {
                log.info("Сессия {}: после {}-хода остался непрочитанный USER — session-wake нового Turn'а",
                        sessionId, finishedSource);
                tryStart(sessionId);
            }
        } catch (Exception e) {
            log.warn("Сессия {}: re-wake по USER не удался (POLL подстрахует): {}", sessionId, e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        // Потоки не ждём (graceful shutdown не проектируем, D-41); локи истекут по TTL
        turnExecutor.shutdown();
    }
}
