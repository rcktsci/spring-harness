package se.rocketscien.harness.execution;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.SessionMessageEntity;
import se.rocketscien.harness.session.SessionStore;

import java.util.HashSet;
import java.util.UUID;

/**
 * Одноразовые сканы при старте процесса (execution-model §1, спека agent-turn):
 * (1) зависшие {@code TOOL_CALL} без финального результата у сессий со свободным
 * {@code sess-{id}}-локом → синтетический {@code TOOL_RESULT LOST} «операция потеряна при
 * перезапуске» + пробуждение (залоченные не трогаем — Turn жив);
 * (2) удаление осиротевших {@code harness-*}-контейнеров (контейнер без живой сессии).
 * Ошибка одного пункта не валит старт процесса.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RestartScanRunner {

    static final String LOST_REASON = "операция потеряна при перезапуске";

    private final SessionStore sessionStore;
    private final SessionLockManager sessionLocks;
    private final TurnManager turnManager;
    private final WorkspaceContainerManager containers;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        restartScan();
    }

    public void restartScan() {
        closePendingToolCalls();
        removeOrphanContainers();
    }

    private void closePendingToolCalls() {
        for (UUID sessionId : sessionStore.findSessionIdsWithPendingToolCalls()) {
            try {
                closeIfLockFree(sessionId);
            } catch (Exception e) {
                log.warn("Рестарт-скан не смог закрыть зависшие TOOL_CALL сессии {}: {}", sessionId, e.getMessage());
            }
        }
    }

    private void closeIfLockFree(UUID sessionId) {
        SessionLockManager.HeldLock lock = sessionLocks.tryAcquire(sessionId).orElse(null);
        if (lock == null) {
            log.info("Рестарт-скан: сессия {} под живым локом — пропущена", sessionId);
            return;
        }
        boolean closedAny;
        try {
            closedAny = appendLostResults(sessionId);
        } finally {
            lock.close();
        }
        if (closedAny) {
            log.info("Рестарт-скан: зависшие TOOL_CALL сессии {} закрыты LOST, пробуждение", sessionId);
            // tryStart строго после release: сам wake берёт тот же sess-лок
            turnManager.tryStart(sessionId);
        }
    }

    private boolean appendLostResults(UUID sessionId) {
        boolean closed = false;
        for (SessionMessageEntity pendingCall : sessionStore.findPendingToolCalls(sessionId)) {
            String callId = TurnPayloads.callId(pendingCall.getPayloadJsonb());
            String tool = TurnPayloads.tool(pendingCall.getPayloadJsonb());
            if (callId == null) {
                log.warn("Рестарт-скан: TOOL_CALL без callId в сессии {} (seq {}) — пропущен",
                        sessionId, pendingCall.getId().seq());
                continue;
            }
            sessionStore.appendEvent(sessionId, MessageKind.TOOL_RESULT, null,
                    TurnPayloads.toolResultSynthetic(callId, tool, ToolStatus.LOST, LOST_REASON), null);
            closed = true;
        }
        return closed;
    }

    private void removeOrphanContainers() {
        try {
            int removed = containers.removeOrphanContainers(new HashSet<>(sessionStore.findAllSessionIds()));
            if (removed > 0) {
                log.info("Рестарт-скан: удалено осиротевших контейнеров: {}", removed);
            }
        } catch (Exception e) {
            log.warn("Рестарт-скан: не удалось вычистить осиротевшие контейнеры: {}", e.getMessage());
        }
    }
}
