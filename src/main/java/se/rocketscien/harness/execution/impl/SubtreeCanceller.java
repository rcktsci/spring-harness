package se.rocketscien.harness.execution.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.execution.ActiveTurnRegistry;
import se.rocketscien.harness.execution.SessionLockManager;
import se.rocketscien.harness.execution.ToolStatus;
import se.rocketscien.harness.execution.TurnCancellation;
import se.rocketscien.harness.execution.TurnPayloads;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionMessageEntity;
import se.rocketscien.harness.session.SessionStore;

import java.util.List;
import java.util.UUID;

/**
 * Каскадная отмена поддерева сессий (M3 O.3, спека subagent-lifecycle): stop корня
 * (или середины дерева) рекурсивно отменяет все сессии по {@code parent_session_id}
 * (BFS — {@link SessionStore#findSubtree}). Каждой — {@code cancel_requested = true};
 * активные Turn'ы прерываются немедленно (их CANCELLED-результаты пишет движок);
 * незакрытые async {@code TOOL_CALL} сессий без живого Turn'а закрываются синтетическим
 * {@code TOOL_RESULT CANCELLED «subtree-cancelled»} под sess-локом с правилом «первый
 * финальный выигрывает» (D-64). Wake после каскада НЕ выполняется (M1-семантика: stop
 * по IDLE не гасит будущие ходы; флаг сбрасывает старт нового Turn'а) — запаркованных
 * поднимут POLL или поздний результат, и модель увидит CANCELLED-результаты. Идемпотентен:
 * повторный stop — no-op (незакрытых нет, активных нет).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubtreeCanceller {

    private static final String CANCELLED_REASON = "отменено пользователем (subtree-cancelled)";

    private final SessionStore sessionStore;
    private final ActiveTurnRegistry activeTurns;
    private final SessionLockManager sessionLocks;

    public void cancelSubtree(UUID sessionId) {
        List<Session> subtree = sessionStore.findSubtree(sessionId, null);
        for (Session session : subtree) {
            sessionStore.requestCancel(session.id());
        }
        for (Session session : subtree) {
            TurnCancellation cancellation = activeTurns.get(session.id());
            if (cancellation != null) {
                cancellation.cancel();
            }
        }
        for (Session session : subtree) {
            closePendingToolCalls(session.id());
        }
        log.info("Stop поддерева {}: отменено сессий {}", sessionId, subtree.size());
    }

    /** Незакрытые TOOL_CALL сессии без живого Turn'а → синтетический CANCELLED (D-64). */
    private void closePendingToolCalls(UUID sessionId) {
        List<SessionMessageEntity> pending = sessionStore.findPendingToolCalls(sessionId);
        for (SessionMessageEntity event : pending) {
            String callId = TurnPayloads.callId(event.getPayloadJsonb());
            String tool = TurnPayloads.tool(event.getPayloadJsonb());
            if (callId == null) {
                continue;
            }
            SessionLockManager.HeldLock lock = sessionLocks.tryAcquire(sessionId).orElse(null);
            if (lock == null) {
                // Сессия под живым Turn'ом — его CANCELLED-результаты напишет сам движок
                return;
            }
            try {
                if (sessionStore.hasToolResultForCall(sessionId, callId)) {
                    continue;
                }
                sessionStore.appendEvent(sessionId, MessageKind.TOOL_RESULT, null,
                        TurnPayloads.toolResultSynthetic(callId, tool,
                                ToolStatus.CANCELLED, CANCELLED_REASON),
                        null);
                log.info("Stop поддерева {}: TOOL_CALL {} ({}) закрыт CANCELLED", sessionId, callId, tool);
            } finally {
                lock.close();
            }
        }
    }
}
