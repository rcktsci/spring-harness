package se.rocketscien.harness.execution.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.support.AbstractSqlTypeValue;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.execution.impl.TaskGraphReader.GraphState;
import se.rocketscien.harness.execution.impl.WaitTasksScope.Condition;
import se.rocketscien.harness.execution.impl.WaitTasksScope.Evaluation;
import se.rocketscien.harness.execution.impl.WaitTasksScope.Scope;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.TaskStateKind;
import se.rocketscien.harness.task.TaskStatus;
import se.rocketscien.harness.task.Transition;
import se.rocketscien.harness.task.TransitionKind;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Исполнитель системного состояния WAIT_TASKS (workflow-domain §3): переоценка барьера —
 * выборка scope-задач по индексам ({@code (parent_task_id, status_projection)},
 * {@code (blocker_task_id)}, GIN {@code (tags)}, params), вычисление условия, при закрытии —
 * переход NEXT ({@code reason.closedBy}) или ERROR при ALL_SUCCESS с FAILED/CANCELLED-участником
 * ({@code reason.failed}). Переоценка идемпотентна: CAS по {@code current_state}, двойной вызов —
 * no-op; suspended отсекается гвардией CAS.
 *
 * <p>Триггеры переоценки: терминал подзадачи/блокера (InProcessTaskWakeBus → диспетчер),
 * изменение тегов/blocked_by (wake), POLL-страховка ({@code task-scheduler}).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WaitTasksStateExecutor {

    private final TaskRegistry taskRegistry;
    private final TaskGraphReader graphs;
    private final TaskEngine taskEngine;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Переоценка барьера задачи; при закрытии условия — переход.
     *
     * @return true — условие закрылось и переход применён; false — ждём дальше / состояние
     *         не WAIT_TASKS / CAS промахнулся
     */
    public boolean reevaluate(UUID taskId) {
        Task task;
        try {
            task = taskRegistry.get(taskId);
        } catch (Exception e) {
            log.debug("Переоценка {}: задача недоступна ({})", taskId, e.getMessage());
            return false;
        }
        if (task.currentStateKind() != TaskStateKind.WAIT_TASKS
                || task.statusProjection().isTerminal()) {
            return false;
        }
        GraphState state = graphs.state(graphs.loadForTask(task), task.currentState());
        Scope scope = WaitTasksScope.parse(string(state.raw().get("scope")));
        Condition condition = WaitTasksScope.parseCondition(string(state.raw().get("condition")));

        List<UUID> scopeIds = resolveIds(task, scope);
        if (scopeIds.isEmpty()) {
            log.debug("WAIT_TASKS {}: scope {} пуст — барьер ждёт", taskId, scope.kind());
            return false;
        }
        List<TaskStatus> statuses = statusesBy(scopeIds);

        Evaluation evaluation = WaitTasksScope.evaluate(condition, statuses);
        if (evaluation == Evaluation.PENDING) {
            return false;
        }
        return close(task, state, evaluation, scopeIds);
    }

    private boolean close(Task task, GraphState state, Evaluation evaluation, List<UUID> scopeIds) {
        TransitionKind kind = evaluation == Evaluation.NEXT ? TransitionKind.NEXT : TransitionKind.ERROR;
        var edge = graphs.edgeByKind(graphs.loadForTask(task), task.currentState(), kind);
        if (edge.isEmpty()) {
            log.error("WAIT_TASKS {}: условие закрыто по {}, но ребро {} из '{}' отсутствует — пропуск",
                    task.id(), evaluation, kind, task.currentState());
            return false;
        }
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("kind", "wait-tasks");
        if (evaluation == Evaluation.NEXT) {
            reason.put("closedBy", scopeIds.stream().map(UUID::toString).toList());
        } else {
            reason.put("failed", failedIds(scopeIds));
        }
        Transition applied = taskEngine.processTaskTransition(
                task.id(), task.currentState(), edge.get().to(), kind, reason);
        if (applied != null) {
            log.info("WAIT_TASKS {}: барьер закрыт ({}) — переход {}", task.id(), evaluation, edge.get().to());
        }
        return applied != null;
    }

    private List<UUID> failedIds(List<UUID> scopeIds) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM task WHERE id = ANY(?) AND status_projection IN ('FAILED','CANCELLED')",
                UUID.class, uuidArrayArg(scopeIds));
    }

    /**
     * Выборка scope-задач по kind (SQL по индексам H.1/§3). Сама задача из scope исключается
     * (BLOCKED_BY/TAGGED/EXPLICIT): она в WAITING и никогда не терминальна — попав в собственный
     * барьер, закрыла бы его навсегда. ALL_CHILDREN сам себя содержать не может.
     */
    private List<UUID> resolveIds(Task task, Scope scope) {
        return switch (scope.kind()) {
            case ALL_CHILDREN -> jdbcTemplate.queryForList(
                    "SELECT id FROM task WHERE parent_task_id = ?", UUID.class, task.id());
            case BLOCKED_BY -> jdbcTemplate.queryForList(
                    "SELECT blocker_task_id FROM task_dependency WHERE blocked_task_id = ? "
                            + "AND blocker_task_id <> ?",
                    UUID.class, task.id(), task.id());
            case TAGGED -> jdbcTemplate.queryForList(
                    "SELECT id FROM task WHERE tags @> ARRAY[?]::text[] AND id <> ?",
                    UUID.class, scope.tag(), task.id());
            case EXPLICIT -> explicitIds(task, scope.paramsKey());
        };
    }

    @SuppressWarnings("unchecked")
    private List<UUID> explicitIds(Task task, String paramsKey) {
        if (!(task.params().get(paramsKey) instanceof List<?> raw)) {
            return List.of();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (Object item : raw) {
            try {
                UUID id = UUID.fromString(String.valueOf(item));
                if (!id.equals(task.id())) {
                    ids.add(id);
                }
            } catch (IllegalArgumentException e) {
                log.warn("WAIT_TASKS {}: EXPLICIT '{}' содержит не-UUID {}: пропуск",
                        task.id(), paramsKey, item);
            }
        }
        return new ArrayList<>(ids);
    }

    private List<TaskStatus> statusesBy(List<UUID> scopeIds) {
        return jdbcTemplate.query(
                "SELECT status_projection FROM task WHERE id = ANY(?)",
                (rs, rowNum) -> TaskStatus.valueOf(rs.getString(1)),
                uuidArrayArg(scopeIds));
    }

    /**
     * UUID-список как один параметр {@code uuid[]}: pgjdbc биндит массив только через
     * {@code createArrayOf} (setObject(UUID[]) не поддержан), поэтому значение собирается
     * лениво, когда соединение уже доступно.
     */
    private static Object uuidArrayArg(List<UUID> ids) {
        UUID[] array = ids.toArray(UUID[]::new);
        return new AbstractSqlTypeValue() {
            @Override
            protected Object createTypeValue(Connection con, int sqlType, String typeName)
                    throws SQLException {
                return con.createArrayOf("uuid", array);
            }
        };
    }

    private static String string(Object raw) {
        return raw instanceof String value ? value : null;
    }
}
