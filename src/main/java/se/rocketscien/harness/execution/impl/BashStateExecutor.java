package se.rocketscien.harness.execution.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.config.TaskProperties;
import se.rocketscien.harness.execution.ToolResult;
import se.rocketscien.harness.execution.ToolStatus;
import se.rocketscien.harness.execution.WorkspaceContainerManager;
import se.rocketscien.harness.execution.WorkspaceTools;
import se.rocketscien.harness.execution.impl.TaskGraphReader.GraphState;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.TransitionKind;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Исполнитель системного состояния BASH_SCRIPT (workflow-domain §3, спека task-engine):
 * запускает {@code script} через {@link WorkspaceTools#executeBash} в task-контейнере
 * {@code harness-task-<taskId>}; по завершении определяет вид перехода — exit=0 → NEXT,
 * exit≠0 → ERROR, таймаут состояния → TIMEOUT (отказ контейнера/LOST → ERROR — исполнение
 * не удалось по причине инфраструктуры; при kill без exit-кода reason содержит
 * {@code exitCode=null}; CANCELLED → null — терминал '$CANCELLED' уже записан stop'ом).
 * Повторный вход того же taskId (двойной wake/гонка) отсекается in-flight-гвардией —
 * второй вызов вернёт {@code null}.
 *
 * <p>Task-контейнер одноразовый: удаляется на любом исходе (NEXT/ERROR/TIMEOUT/отмена) —
 * долгоживущих контейнеров задач нет; workspace на хосте сохраняется (bind-mount), остатки
 * после рестарта добивает {@code task-timeout-scanner}.</p>
 *
 * <p>Таймаут состояния: явный {@code state.timeout}, иначе kind-дефолт
 * {@code harness.task.transition.kind-timeouts.bash}.</p>
 *
 * <p><b>Скрипт идемпотентен</b> — обязательный инвариант (D-50/R3): повторный прогон после
 * crash помечается новой попыткой ({@code state_attempt}), side-effect может выполниться
 * дважды; клоны/пуллы в скриптах состояний должны это учитывать.</p>
 *
 * <p>stdout/stderr в reason объединены (поле {@code output}) — таков контракт workspace-tools
 * («stdout и stderr объединены»); раздельные поля без хака над инструментом недоступны.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BashStateExecutor {

    private final WorkspaceTools workspaceTools;
    private final WorkspaceContainerManager containers;
    private final TaskGraphReader graphs;
    private final TaskRegistry taskRegistry;
    private final TaskProperties properties;

    private final ConcurrentMap<UUID, Boolean> inFlight = new ConcurrentHashMap<>();

    /**
     * Исполнение скрипта состояния; переход после завершения выполняет вызывающий
     * (диспетчер) по {@link Outcome#kind()}.
     *
     * @param taskId    задача
     * @param stateCode code BASH_SCRIPT-состояния (текущее состояние задачи)
     * @param script    текст скрипта (параметр — явный контракт вызова)
     * @param workspace workspace-узел состояния (nullable — cwd из графа не требуется)
     * @param attempt   номер попытки ({@code task.state_attempt} на момент входа)
     * @return исход исполнения; {@code null} — исполнение уже идёт (in-flight)
     */
    public Outcome execute(UUID taskId, String stateCode, String script,
                           Map<String, Object> workspace, int attempt) {
        if (inFlight.putIfAbsent(taskId, Boolean.TRUE) != null) {
            log.debug("BASH-исполнение задачи {} уже идёт — повторный вызов пропущен", taskId);
            return null;
        }
        try {
            return executeOnce(taskId, stateCode, script, workspace, attempt);
        } finally {
            inFlight.remove(taskId);
            // Одноразовый task-контейнер: снимается на любом исходе, включая исключение
            // (упавший контейнер не должен переживать попытку — новые создаёт ensureContainer).
            containers.removeContainer(WorkspaceContainerManager.TASK_NAMESPACE, taskId);
        }
    }

    /** Скрипт задачи в полёте (таймаут-скан пропускает — executor сам доведёт до перехода). */
    public boolean isInFlight(UUID taskId) {
        return inFlight.containsKey(taskId);
    }

    private Outcome executeOnce(UUID taskId, String stateCode, String script,
                                Map<String, Object> workspace, int attempt) {
        Duration timeout = timeoutOf(taskId, stateCode);

        Instant started = Instant.now();
        ToolResult result = workspaceTools.executeBash(taskId, script, timeout, resolveCwd(workspace));
        long durationMs = Duration.between(started, Instant.now()).toMillis();

        Outcome outcome = classify(result, durationMs, attempt);
        if (outcome == null) {
            log.info("BASH-исполнение задачи {} отменено (stop)", taskId);
        }
        return outcome;
    }

    /** Чистое отображение результата инструмента в исход состояния (unit-тестируемо). */
    public static Outcome classify(ToolResult result, long durationMs, int attempt) {
        if (result.status() == ToolStatus.CANCELLED) {
            // stop отменил задачу во время исполнения: терминал '$CANCELLED' уже записан стопом.
            return null;
        }
        if (result.status() == ToolStatus.LOST || result.status() == ToolStatus.ERROR) {
            return new Outcome(TransitionKind.ERROR, reason(result, null, durationMs, attempt));
        }
        if (Boolean.TRUE.equals(result.timedOut())) {
            return new Outcome(TransitionKind.TIMEOUT, reason(result, result.exitCode(), durationMs, attempt));
        }
        Integer exitCode = result.exitCode();
        TransitionKind kind = exitCode != null && exitCode == 0 ? TransitionKind.NEXT : TransitionKind.ERROR;
        return new Outcome(kind, reason(result, exitCode, durationMs, attempt));
    }

    private static Map<String, Object> reason(ToolResult result, Integer exitCode, long durationMs, int attempt) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("kind", "bash");
        reason.put("exitCode", exitCode);
        reason.put("output", result.output());
        reason.put("durationMs", durationMs);
        reason.put("attempt", attempt);
        return reason;
    }

    /** Таймаут состояния: явный {@code state.timeout} → kind-дефолт из конфига. */
    private Duration timeoutOf(UUID taskId, String stateCode) {
        Optional<Duration> explicit = graphs
                .state(graphs.loadByRevisionId(taskRegistry.get(taskId).workflowRevisionId()), stateCode)
                .timeout();
        return explicit
                .or(() -> {
                    TaskProperties.Transition transition = properties.transition();
                    TaskProperties.KindTimeouts defaults = transition == null
                            ? null
                            : transition.kindTimeouts();
                    return Optional.ofNullable(defaults == null ? null : defaults.bash());
                })
                .orElse(null);
    }

    /**
     * cwd исполнения: workspace состояния SERVER_DIR/mode=PATH — путь от корня workspace задачи
     * (выражение {@code ${task.id}} — сам корень, MVP-профиль выражений); AUTO/пусто — корень
     * монтирования.
     */
    private String resolveCwd(Map<String, Object> workspace) {
        if (workspace == null
                || !"SERVER_DIR".equals(String.valueOf(workspace.get("type")))
                || !"PATH".equals(String.valueOf(workspace.get("mode")))) {
            return null;
        }
        if (workspace.get("path") instanceof String path && !path.isBlank()) {
            String resolved = path.replace("${task.id}", "").trim();
            return resolved.isBlank() ? null : resolved;
        }
        return null;
    }

    /** Исход исполнения скрипта: вид перехода и reason для истории. */
    public record Outcome(TransitionKind kind, Map<String, Object> reason) {
    }
}
