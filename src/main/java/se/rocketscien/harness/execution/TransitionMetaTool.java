package se.rocketscien.harness.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.execution.impl.TaskEngine;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionKind;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.Transition;
import se.rocketscien.harness.task.TransitionKind;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Мета-инструмент агента {@code transition(taskId, toState, kind, reason)} (спека agent-turn,
 * task-engine «Мета-инструмент transition»; D-52/D-59): перевод задачи по исходящему ребру
 * текущего состояния, применение — транзакционно в момент исполнения tool-call
 * ({@link TaskEngine#processTaskTransition}: CAS + история + {@code task_event_seq} атомарны),
 * НЕ отложенно до turn-finish. Гейт instructionSource и лимит на Turn — на стороне
 * {@link AgentTurnEngine} (turn-контекст); здесь — доменная валидация.
 *
 * <p>{@code taskId} резолвится адаптером из STATE-сессии, в которой исполняется Turn
 * (в контракте реестра обязателен); явно переданный чужой taskId — ошибка инструмента.
 * {@code reason} обязателен непустой (rule=reason-required); {@code kind} — NEXT (по умолчанию)
 * или ERROR, определяется ребром графа.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TransitionMetaTool {

    /** Имя инструмента в декларациях модели и журнале TOOL_CALL/TOOL_RESULT. */
    public static final String NAME = "transition";

    private static final Set<String> KINDS = Set.of(TransitionKind.NEXT.name(), TransitionKind.ERROR.name());

    private final SessionStore sessionStore;
    private final TaskRegistry taskRegistry;
    private final TaskEngine taskEngine;

    /**
     * Декларация для модели: доступна только в STATE-сессиях (добавляет AgentTurnEngine).
     * Стиль M1 ({@code NativeAgentTools}): callback — только схема для провайдера, внутреннее
     * исполнение Spring AI отключено — write-ahead и исполнение на Turn'е ({@link #execute}).
     * Опциональность полей помечена {@code @ToolParam(required=false)} — попадает в JSON-Schema.
     */
    public ToolCallback declaration() {
        return FunctionToolCallback.builder(NAME, (TransitionArgs unused) ->
                        "transition is applied by the turn engine (write-ahead journaling)")
                .description("Transition the task to the next workflow state along an outgoing edge of the "
                        + "current state. reason is mandatory: a short non-empty justification recorded in "
                        + "the task history. kind is NEXT (default) or ERROR as defined by the edge. "
                        + "taskId is optional and resolved from the current state session.")
                .inputType(TransitionArgs.class)
                .build();
    }

    /**
     * Исполнение tool-call: валидация → транзакционный CAS-переход. Ошибки гейта/валидации —
     * результат инструмента (переход не происходит); расовое промахивание CAS — тоже
     * ошибка инструмента (победитель уже сменил состояние).
     */
    public ToolResult execute(UUID sessionId, String callId, Map<String, Object> arguments) {
        Session session = sessionStore.findSession(sessionId).orElse(null);
        if (session == null || session.kind() != SessionKind.STATE || session.taskId() == null) {
            return ToolResult.error(callId, NAME, "transition доступен только в STATE-сессии задачи");
        }

        String toState = text(arguments, "toState");
        if (toState == null || toState.isBlank()) {
            return ToolResult.error(callId, NAME, "wrong-transition: toState обязателен (rule=to-state-required)");
        }
        String reason = text(arguments, "reason");
        if (reason == null || reason.isBlank()) {
            return ToolResult.error(callId, NAME, "wrong-transition: reason обязателен и непуст (rule=reason-required)");
        }
        TransitionKind kind = resolveKind(callId, text(arguments, "kind"));
        if (kind == null) {
            return ToolResult.error(callId, NAME,
                    "wrong-transition: kind должен быть NEXT или ERROR (rule=not-an-outgoing-edge)");
        }

        UUID taskId = session.taskId();
        UUID explicit = arguments != null && arguments.get("taskId") instanceof UUID id ? id : null;
        if (explicit != null && !explicit.equals(taskId)) {
            return ToolResult.error(callId, NAME,
                    "wrong-transition: taskId %s не совпадает с задачей STATE-сессии (%s)"
                            .formatted(explicit, taskId));
        }

        Task task = taskRegistry.get(taskId);
        Transition applied;
        try {
            applied = taskEngine.processTaskTransition(taskId, task.currentState(), toState, kind,
                    Map.of(TurnPayloads.TEXT, reason));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(callId, NAME, "wrong-transition: " + e.getMessage());
        }
        if (applied == null) {
            return ToolResult.error(callId, NAME,
                    "переход не применён: состояние задачи изменилось или задача приостановлена (CAS)");
        }
        log.info("Мета-инструмент transition: задача {} {} → {} ({})", taskId,
                applied.fromState(), applied.toState(), applied.kind());
        return ToolResult.ok(callId, NAME, ("переход применён: %s → %s (%s), задача %s"
                .formatted(applied.fromState(), applied.toState(), applied.kind(), taskId)));
    }

    private TransitionKind resolveKind(String callId, String raw) {
        if (raw == null || raw.isBlank()) {
            return TransitionKind.NEXT;
        }
        return KINDS.contains(raw) ? TransitionKind.valueOf(raw) : null;
    }

    private static String text(Map<String, Object> args, String key) {
        return args != null && args.get(key) instanceof String value ? value : null;
    }

    /** Аргументы декларации для провайдера (исполнение — по Map из журнала, см. execute). */
    public record TransitionArgs(
            @ToolParam(required = false, description = "Optional; resolved from the current state session")
            UUID taskId,
            @ToolParam(description = "Code of the target state (outgoing edge of the current state)")
            String toState,
            @ToolParam(required = false, description = "NEXT (default) or ERROR, as defined by the edge")
            String kind,
            @ToolParam(description = "Mandatory non-empty justification recorded in the task history")
            String reason) {
    }
}
