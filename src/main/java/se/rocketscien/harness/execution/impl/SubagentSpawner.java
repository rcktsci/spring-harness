package se.rocketscien.harness.execution.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.config.SpawnProperties;
import se.rocketscien.harness.execution.ToolResult;
import se.rocketscien.harness.execution.TurnManager;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionEventBroadcaster;
import se.rocketscien.harness.session.SessionRuntimeStatus;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.session.TurnOutcome;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Мета-инструмент {@code spawn_subagent(agentKey, prompt, params?)} (M3 O.1/O.2, спека
 * subagent-lifecycle): строго синхронный — родительский виртуальный поток ждёт завершения
 * субагента. Создаёт дочернюю сессию ({@code parent_session_id}, {@code depth = parent.depth + 1},
 * owner наследуется, ревизия агента — latest), сеет USER-промпт и запускает Turn
 * ({@code TurnManager.tryStart}). Завершение — см. «Завершение субагента» (D-10):
 * исход Turn'а зафиксирован и батч потреблён; финальный ASSISTANT → {@code TOOL_RESULT}
 * родителю на ходу, где spawn вызван. Гейт metaTools — на стороне {@code AgentTurnEngine}.
 * Если родительский Turn завершился до возврата (отмена/рестарт), результат попадает
 * в журнал обычным порядком движка, а parked/cancelled сессию поднимет wake/POLL.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubagentSpawner {

    /** Имя инструмента в декларациях модели и журнале TOOL_CALL/TOOL_RESULT. */
    public static final String NAME = "spawn_subagent";

    private final SessionStore sessionStore;
    /** Ленивый wake: прямой TurnManager дал бы цикл turnManager → engine → spawner → turnManager. */
    private final ObjectProvider<TurnManager> turnManager;
    private final SessionEventBroadcaster broadcaster;
    private final SpawnProperties properties;
    private final ObjectMapper objectMapper;

    /** Декларация для модели (добавляется в manifest только оркестраторам — metaTools=true). */
    public ToolCallback declaration() {
        return FunctionToolCallback.builder(NAME, (SpawnSubagentArgs unused) ->
                        "spawn_subagent is executed by the turn engine (write-ahead journaling)")
                .description("Spawn a subagent session for a scoped task and wait for its final answer. "
                        + "agentKey is the key of a registered agent; prompt is the task text; "
                        + "params is an optional JSON object with task parameters.")
                .inputType(SpawnSubagentArgs.class)
                .build();
    }

    /**
     * Исполнение tool-call: depth-гейт → создание дочерней сессии → seed → ожидание →
     * маппинг исхода субагента в TOOL_RESULT родителя.
     */
    public ToolResult execute(UUID parentSessionId, String callId, Map<String, Object> arguments) {
        Session parent = sessionStore.findSession(parentSessionId).orElse(null);
        if (parent == null) {
            return ToolResult.error(callId, NAME, "родительская сессия не найдена");
        }
        int maxDepth = properties.maxDepth() == null ? 2 : Math.max(0, properties.maxDepth());
        if (parent.depth() + 1 > maxDepth) {
            log.info("Спавн отклонён: depth {} + 1 > max-depth {} (сессия {})",
                    parent.depth(), maxDepth, parentSessionId);
            return ToolResult.error(callId, NAME,
                    "forbidden (depth-limit): depth %d достигнут лимит %d".formatted(parent.depth(), maxDepth));
        }
        String strategy = properties.workspaceStrategy() == null ? "inherit" : properties.workspaceStrategy();
        if (!"inherit".equals(strategy)) {
            return ToolResult.error(callId, NAME,
                    "forbidden (workspace-strategy): '%s' не поддерживается в M3".formatted(strategy));
        }

        String agentKey = text(arguments, "agentKey");
        if (agentKey == null || agentKey.isBlank()) {
            return ToolResult.error(callId, NAME, "agentKey обязателен");
        }
        String prompt = text(arguments, "prompt");
        if (prompt == null || prompt.isBlank()) {
            return ToolResult.error(callId, NAME, "prompt обязателен");
        }

        Session child;
        try {
            child = sessionStore.createChildSession(parentSessionId, agentKey.strip(),
                    "Субагент: " + agentKey.strip());
        } catch (Exception e) {
            return ToolResult.error(callId, NAME, "spawn-failed: " + e.getMessage());
        }
        sessionStore.appendEvent(child.id(), MessageKind.USER,
                parent.ownerUserId(), Map.of("text", promptWithParams(prompt, arguments)));
        turnManager.getObject().tryStart(child.id());
        log.info("Сессия {}: спавн субагента {} → сессия {} (depth {})",
                parentSessionId, agentKey, child.id(), child.depth());

        SpawnWait wait = awaitCompletion(child.id());
        if (wait == SpawnWait.TIMEOUT) {
            return ToolResult.error(callId, NAME,
                    "spawn-timeout: субагент не завершился за " + properties.timeoutMs());
        }
        if (wait == SpawnWait.CANCELLED) {
            // R-2: stop поддерева flagged child — ожидание прекращается немедленно
            // (без выжидания harness.spawn.timeout-ms), родитель получает CANCELLED
            return ToolResult.cancelled(callId, NAME, "субагент отменён (subtree-cancelled)");
        }

        Session finished = sessionStore.findSession(child.id()).orElse(null);
        TurnOutcome outcome = finished == null ? null : finished.lastTurnOutcome();
        String output = sessionStore.findLastAssistantText(child.id());
        if (outcome == TurnOutcome.COMPLETED && output != null && !output.isBlank()) {
            return ToolResult.ok(callId, NAME, output);
        }
        if (outcome == TurnOutcome.CANCELLED) {
            return ToolResult.cancelled(callId, NAME, "субагент отменён (subtree-cancelled)");
        }
        if (outcome == TurnOutcome.FAILED) {
            return ToolResult.error(callId, NAME, "субагент упал: " + output);
        }
        return ToolResult.error(callId, NAME, "spawn-failed: субагент завершился без ответа");
    }

    /** Исход блокирующего ожидания субагента. */
    private enum SpawnWait {
        /** Финал по D-10: исход зафиксирован, батч потреблён, pending == 0, сессия IDLE. */
        COMPLETED,
        /** Субагент под cancel_requested (stop поддерева) — ожидание прекращено. */
        CANCELLED,
        /** Таймаут {@code harness.spawn.timeout-ms}. */
        TIMEOUT
    }

    /**
     * Блокирующее ожидание завершения субагента (D-10, «Завершение субагента»): исход
     * последнего Turn'а зафиксирован, батч потреблён, {@code pending_tool_calls == 0} и
     * рантайм-статус сессии — IDLE. Последние два условия критичны: Turn с превысившим окно
     * async-инструментом завершается COMPLETED при незакрытом вызове (PARKED_ASYNC) — без
     * них родитель получил бы частичный результат, а финальный ASSISTANT субагента потерялся.
     * Отдельный выход (b) — {@code cancel_requested} у child: stop поддерева пишет
     * CANCELLED-результаты (после них {@code lastSeq > lastConsumed} держится вечно), без
     * этого выхода родитель висел бы до {@code harness.spawn.timeout-ms}.
     */
    private SpawnWait awaitCompletion(UUID childSessionId) {
        Duration timeout = properties.timeoutMs() == null || properties.timeoutMs().isNegative()
                || properties.timeoutMs().isZero() ? Duration.ofMinutes(30) : properties.timeoutMs();
        Duration poll = properties.pollInterval() == null || properties.pollInterval().isNegative()
                || properties.pollInterval().isZero() ? Duration.ofMillis(200) : properties.pollInterval();
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Session child = sessionStore.findSession(childSessionId).orElse(null);
            if (child != null) {
                if (child.cancelRequested()) {
                    log.info("Субагент {} под cancel_requested — spawn прекращён (subtree-cancelled)",
                            childSessionId);
                    return SpawnWait.CANCELLED;
                }
                if (child.lastTurnOutcome() != null
                        && child.lastSeq() <= child.lastConsumedSeq()
                        && sessionStore.findPendingToolCalls(childSessionId).isEmpty()
                        && broadcaster.statusSnapshot(childSessionId).runtimeStatus()
                                == SessionRuntimeStatus.IDLE) {
                    return SpawnWait.COMPLETED;
                }
            }
            try {
                Thread.sleep(poll.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return SpawnWait.CANCELLED;
            }
        }
        log.warn("Субагент {} не завершился за {}", childSessionId, timeout);
        return SpawnWait.TIMEOUT;
    }

    private String promptWithParams(String prompt, Map<String, Object> arguments) {
        Object params = arguments == null ? null : arguments.get("params");
        if (params == null) {
            return prompt;
        }
        try {
            return prompt + "\n\nparams: " + objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            return prompt + "\n\nparams: " + params;
        }
    }

    private static String text(Map<String, Object> arguments, String key) {
        return arguments != null && arguments.get(key) instanceof String value ? value : null;
    }

    public record SpawnSubagentArgs(String agentKey, String prompt, Map<String, Object> params) {
    }
}
