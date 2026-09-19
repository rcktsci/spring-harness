package se.rocketscien.harness.execution.impl;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.config.TaskProperties;
import se.rocketscien.harness.execution.impl.TaskGraphReader.GraphEdge;
import se.rocketscien.harness.execution.impl.TaskGraphReader.GraphState;
import se.rocketscien.harness.execution.impl.TaskGraphReader.RevisionGraph;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.TaskStateKind;
import se.rocketscien.harness.task.TaskStatus;
import se.rocketscien.harness.task.TransitionKind;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Диспетчер wake-событий движка состояний (пачка I): подписан на {@link InProcessTaskWakeBus}
 * и разносит события по исполнителям. Работа исполняется на виртуальных потоках — публикация
 * (после коммита, часто в HTTP-потоке) не блокируется, а транзакции обработчиков не пересекаются
 * с afterCommit-контекстом публикатора:
 * <ul>
 *   <li>BASH_SCRIPT (RUNNING, не suspended) — {@link BashStateExecutor#execute}, затем
 *       переход по исходу (NEXT/ERROR/TIMEOUT); цепочка состояний продолжается следующим
 *       EVENT-wake от перехода. Гейт — конфиг {@code harness.task.bash-dispatch.enabled}
 *       (тесты зовут executor напрямую).</li>
 *   <li>WAIT_TASKS (и любые события: терминал подзадачи, resume, blocked/tags changed) —
 *       переоценка всех активных WAIT_TASKS-барьеров (выборка по partial-индексу; переоценка
 *       идемпотентна — CAS; MVP-масштаб допускает полный проход вместо адресной сверки scope).</li>
 *   <li>AGENT — bootstrap STATE-сессии появится в пачке J.3 (AgentStateBootstrapper);
 *       здесь событие фиксируется журналом, POLL подстрахует.</li>
 *   <li>WAIT_WEBHOOK — пассивен (только таймаут-скан), событий не требует.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskWakeDispatcher implements TaskWakeHandler {

    private final TaskRegistry taskRegistry;
    private final TaskGraphReader graphs;
    private final TaskEngine taskEngine;
    private final BashStateExecutor bashExecutor;
    private final WaitTasksStateExecutor waitTasksExecutor;
    private final InProcessTaskWakeBus wakeBus;
    private final JdbcTemplate jdbcTemplate;
    private final TaskProperties properties;

    private final ExecutorService dispatcherExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @PostConstruct
    void subscribe() {
        wakeBus.subscribeTaskWake(this);
    }

    @Override
    public void onTaskWake(UUID taskId) {
        dispatcherExecutor.submit(() -> dispatch(taskId));
    }

    @Override
    public void onTaskTerminal(UUID taskId) {
        dispatcherExecutor.submit(this::reevaluateBarriers);
    }

    private void dispatch(UUID taskId) {
        try {
            Task task = taskRegistry.get(taskId);
            if (task.currentStateKind() == TaskStateKind.BASH_SCRIPT
                    && task.statusProjection() == TaskStatus.RUNNING
                    && !task.suspended()
                    && properties.bashDispatch() != null
                    && properties.bashDispatch().enabled()) {
                dispatchBash(task);
            } else if (task.currentStateKind() == TaskStateKind.AGENT) {
                // bootstrap STATE-сессии — пачка J.3 (AgentStateBootstrapper); POLL подстрахует.
                log.debug("Wake AGENT-задачи {}: bootstrap появится в пачке J", taskId);
            }
            // Любой wake может закрывать чужие барьеры: терминал подзадачи, созданной сразу
            // терминальной (старт в TERMINAL), изменение тегов (TAGGED-скоп), stop ('$CANCELLED'
            // — терминал для ALL_TERMINAL). Переоценка идемпотентна, полный проход — см. класс.
            reevaluateBarriers();
        } catch (Exception e) {
            log.warn("Wake {}: задача недоступна ({})", taskId, e.getMessage());
        }
    }

    private void dispatchBash(Task task) {
        GraphState state = graphs.state(graphs.loadForTask(task), task.currentState());
        if (!(state.raw().get("script") instanceof String script) || script.isBlank()) {
            log.error("BASH-состояние '{}' задачи {} без script — пропуск", state.code(), task.id());
            return;
        }
        Object workspace = state.raw().get("workspace");
        @SuppressWarnings("unchecked")
        Map<String, Object> workspaceMap = workspace instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : null;
        BashStateExecutor.Outcome outcome = bashExecutor.execute(task.id(), state.code(), script,
                workspaceMap, task.stateAttempt());
        if (outcome == null) {
            return;
        }
        applyOutcome(task, state.code(), outcome);
    }

    private void applyOutcome(Task task, String fromState, BashStateExecutor.Outcome outcome) {
        RevisionGraph graph = graphs.loadForTask(task);
        Optional<GraphEdge> edge = graphs.edgeByKind(graph, fromState, outcome.kind());
        if (edge.isEmpty()) {
            log.error("BASH-исход {} задачи {}: ребро из '{}' отсутствует — пропуск",
                    outcome.kind(), task.id(), fromState);
            return;
        }
        taskEngine.processTaskTransition(task.id(), fromState, edge.get().to(), outcome.kind(),
                outcome.reason());
    }

    /** Переоценка всех активных WAIT_TASKS-барьеров (идемпотентна, suspended пропускаются). */
    private void reevaluateBarriers() {
        int batchSize = Math.max(1, properties.scheduler() != null
                ? properties.scheduler().batchSize() : 50);
        List<UUID> waiting = jdbcTemplate.queryForList("""
                SELECT id FROM task
                WHERE current_state_kind = 'WAIT_TASKS' AND status_projection = 'WAITING' AND suspended = false
                ORDER BY id
                LIMIT ?
                """, UUID.class, batchSize);
        for (UUID taskId : waiting) {
            try {
                waitTasksExecutor.reevaluate(taskId);
            } catch (Exception e) {
                log.warn("Переоценка WAIT_TASKS {} упала: {}", taskId, e.getMessage());
            }
        }
    }

    @PreDestroy
    void shutdown() {
        dispatcherExecutor.shutdown();
    }
}
