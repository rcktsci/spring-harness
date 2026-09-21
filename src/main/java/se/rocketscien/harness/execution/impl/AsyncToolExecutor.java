package se.rocketscien.harness.execution.impl;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.config.AsyncProperties;
import se.rocketscien.harness.execution.NativeAgentTools;
import se.rocketscien.harness.execution.SessionLockManager;
import se.rocketscien.harness.execution.ToolResult;
import se.rocketscien.harness.execution.TurnCancellation;
import se.rocketscien.harness.execution.TurnManager;
import se.rocketscien.harness.execution.TurnPayloads;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.SessionStore;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Обёртка над нативными инструментами с окном синхронного ожидания (M3 N.1, D-60):
 * async-capable инструмент исполняется на виртуальном потоке; уложился в
 * {@code harness.async.window.default-ms} — результат возвращается ходу как sync;
 * превысил — ход получает {@code Parked} (журнальная пара {@code TOOL_CALL + ASYNC_ACCEPTED}
 * пишет движок), а фоновое продолжение по завершении публикует поздний
 * {@code TOOL_RESULT(late=true)}: под программным локом сессии {@code sess-{id}} (D-64),
 * с проверкой «первый финальный выигрывает» (существующий TOOL_RESULT с тем же callId —
 * no-op), с wake существующим {@code TurnManager.tryStart}-путём (message.created).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AsyncToolExecutor {

    private final NativeAgentTools nativeTools;
    private final SessionStore sessionStore;
    private final SessionLockManager sessionLocks;
    /**
     * Ленивый wake: прямой конструкторный TurnManager дал бы цикл
     * turnManager → engine → executor → turnManager; резолвится в момент публикации.
     */
    private final ObjectProvider<TurnManager> turnManager;
    private final AsyncProperties properties;
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Graceful drain (N-5): новые async-задачи не принимаются; текущие дорабатывают — их
     * поздние результаты публикуются в обычном порядке (под sess-локом, с wake). Потоки
     * не ждём — graceful shutdown не проектируем (D-41); недописанные результаты закроют
     * рестарт-скан/async-timeout-watcher после старта нового процесса.
     */
    @PreDestroy
    void shutdown() {
        background.shutdown();
    }

    /** Итог исполнения в окне: sync-результат либо парковка (фоновое продолжение публикует). */
    public sealed interface Outcome {

        /** Инструмент уложился в окно — результат журналируется движком как sync. */
        record Resolved(ToolResult result) implements Outcome {
        }

        /** Окно превышено — движок фиксирует {@code TOOL_CALL + ASYNC_ACCEPTED}; фон дорабатывает. */
        record Parked(String callId) implements Outcome {
        }
    }

    public boolean isAsyncCapable(String tool) {
        return tool != null && nativeTools.asyncCapabilities().contains(tool);
    }

    public Outcome execute(UUID sessionId, String callId, String tool, Map<String, Object> arguments,
                           TurnCancellation cancellation) {
        return executeSupply(sessionId, callId, tool,
                () -> nativeTools.execute(sessionId, tool, arguments, cancellation), cancellation);
    }

    /**
     * Обобщённое окно (Q.2: «окна те же» для MCP): работа передаётся supplier'ом — нативные
     * инструменты идут через {@link #execute}, MCP-инструменты — через {@code McpToolAdapter}.
     */
    public Outcome executeSupply(UUID sessionId, String callId, String tool,
                                 Supplier<ToolResult> work,
                                 TurnCancellation cancellation) {
        CompletableFuture<ToolResult> future = CompletableFuture.supplyAsync(work, background);
        try {
            ToolResult result = future.get(properties.window().defaultMs().toMillis(), TimeUnit.MILLISECONDS);
            return new Outcome.Resolved(result);
        } catch (TimeoutException e) {
            // Порядок журнала гарантирует sess-лок: живой Turn удерживает его, пока не
            // допишет ASYNC_ACCEPTED; поздний результат уходит строго после release
            future.whenComplete((result, error) -> publishLate(
                    sessionId, callId, tool, error != null ? lostFrom(callId, tool, error) : result));
            return new Outcome.Parked(callId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.whenComplete((result, error) -> publishLate(
                    sessionId, callId, tool, error != null ? lostFrom(callId, tool, error) : result));
            return new Outcome.Resolved(ToolResult.cancelled(callId, tool, "turn interrupted"));
        } catch (ExecutionException e) {
            return new Outcome.Resolved(ToolResult.error(callId, tool, rootMessage(e.getCause())));
        }
    }

    /**
     * Поздний TOOL_RESULT (D-64): ожидание свободного sess-лока (Turn мог ещё жить),
     * под локом — проверка «уже есть финальный TOOL_RESULT с этим callId» (LOST от
     * рестарт-скана/watcher пришёл раньше — no-op), допись, wake строго после release.
     */
    private void publishLate(UUID sessionId, String callId, String tool, ToolResult result) {
        try {
            ToolResult late = result == null
                    ? ToolResult.lost(callId, tool, "async-инструмент не вернул результат")
                    : result.asLate();
            while (true) {
                if (sessionStore.findSession(sessionId).isEmpty()) {
                    log.warn("Поздний результат {} сессии {} не опубликован — сессия исчезла", callId, sessionId);
                    return;
                }
                Optional<SessionLockManager.HeldLock> acquired = sessionLocks.tryAcquire(sessionId);
                if (acquired.isEmpty()) {
                    Thread.sleep(properties.latePublishRetry().toMillis());
                    continue;
                }
                SessionLockManager.HeldLock lock = acquired.get();
                boolean appended = false;
                try {
                    if (sessionStore.hasToolResultForCall(sessionId, callId)) {
                        log.info("Сессия {}: для callId {} уже есть финальный TOOL_RESULT —"
                                        + " поздний результат пропущен (первый выигрывает, D-64)",
                                sessionId, callId);
                        return;
                    }
                    sessionStore.appendEvent(sessionId, MessageKind.TOOL_RESULT, null,
                            TurnPayloads.toolResult(callId, tool, late), null);
                    appended = true;
                    log.info("Сессия {}: поздний TOOL_RESULT {} ({}) опубликован, wake", sessionId, callId, tool);
                } finally {
                    lock.close();
                }
                if (appended) {
                    turnManager.getObject().tryStart(sessionId);
                }
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Публикация позднего результата {} сессии {} прервана —"
                    + " вызов закроют рестарт-скан/async-timeout-watcher", callId, sessionId);
        } catch (Exception e) {
            log.warn("Публикация позднего результата {} сессии {} не удалась: {}",
                    callId, sessionId, e.getMessage());
        }
    }

    private static ToolResult lostFrom(String callId, String tool, Throwable error) {
        return ToolResult.lost(callId, tool, rootMessage(error));
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root != null && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == null || root.getMessage() == null
                ? "async-инструмент упал"
                : root.getMessage();
    }
}
