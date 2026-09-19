package se.rocketscien.harness.execution.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.config.TaskProperties;
import se.rocketscien.harness.execution.impl.TaskGraphReader.GraphEdge;
import se.rocketscien.harness.execution.impl.TaskGraphReader.GraphState;
import se.rocketscien.harness.execution.impl.TaskGraphReader.RevisionGraph;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskEvent;
import se.rocketscien.harness.task.TaskEventListener;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.TaskStateKind;
import se.rocketscien.harness.task.TaskStatus;
import se.rocketscien.harness.task.Transition;
import se.rocketscien.harness.task.TransitionKind;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Реализация {@link TaskEngine}: один CAS-UPDATE (гвард {@code current_state = expected AND
 * suspended = false}; stop его гварды не имеет и выигрывает — data-model §7.2) + INSERT истории
 * в одной транзакции; денормализации ({@code status_projection}, {@code current_state_kind},
 * {@code deadline_at}, {@code state_attempt} для входа в BASH_SCRIPT) и {@code task_event_seq}
 * пересчитываются в том же UPDATE. Дедлайн целевого состояния: явный {@code state.timeout},
 * иначе kind-дефолт из {@code harness.task.transition.kind-timeouts.*}, иначе null (TERMINAL —
 * всегда null). После коммита — EVENT-wake (и task-terminal для терминальных целей) в шину и
 * SSE-события ({@code task.transition} + парный {@code task.status} того же seq;
 * {@code subtask.terminal} на потоке родителя — seq родителя, инкремент в той же транзакции).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskEngineImpl implements TaskEngine {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String CAS_UPDATE = """
            UPDATE task
            SET current_state = ?,
                current_state_kind = ?,
                status_projection = ?,
                deadline_at = ?,
                state_attempt = CASE WHEN ? THEN state_attempt + 1 ELSE state_attempt END,
                task_event_seq = task_event_seq + 1,
                updated_at = now()
            WHERE id = ? AND current_state = ? AND suspended = false
            RETURNING task_event_seq
            """;

    private final TaskRegistry taskRegistry;
    private final TaskGraphReader graphs;
    private final InProcessTaskWakeBus wakeBus;
    private final List<TaskEventListener> eventListeners;
    private final IdGenerator idGenerator;
    private final JdbcTemplate jdbcTemplate;
    private final TaskProperties properties;

    @Override
    @Transactional
    public Transition processTaskTransition(UUID taskId, String fromState, String toState,
                                            TransitionKind kind, Map<String, Object> reason) {
        if (kind == TransitionKind.CANCEL) {
            throw new IllegalArgumentException(
                    "CANCEL-переходы исполняет TaskRegistry.stop ('$CANCELLED'), не движок");
        }
        Task task = taskRegistry.get(taskId);
        RevisionGraph graph = graphs.loadForTask(task);
        GraphState target = graphs.state(graph, toState);
        if (graphs.edge(graph, fromState, toState, kind).isEmpty()) {
            throw new IllegalArgumentException(
                    "Недопустимый переход: ребро %s → %s (%s) отсутствует в графе"
                            .formatted(fromState, toState, kind));
        }

        Instant now = dbNow();
        Instant deadline = deadlineOf(target, now);
        boolean incrementAttempt = target.kind() == TaskStateKind.BASH_SCRIPT;
        List<Long> reserved = jdbcTemplate.query(CAS_UPDATE,
                (rs, rowNum) -> rs.getLong("task_event_seq"),
                toState,
                target.kind().name(),
                projectionOf(target).name(),
                deadline == null ? null : Timestamp.from(deadline),
                incrementAttempt,
                taskId,
                fromState);
        if (reserved.isEmpty()) {
            log.debug("CAS-переход {} → {} ({}) задачи {} промахнулся — no-op",
                    fromState, toState, kind, taskId);
            return null;
        }
        long eventSeq = reserved.getFirst();

        Transition transition = new Transition(idGenerator.newUuidV7(), taskId, fromState, toState,
                kind, immutableReason(reason), now);
        jdbcTemplate.update("""
                        INSERT INTO task_transition_history (id, task_id, from_state, to_state, kind, reason_jsonb, created_at)
                        VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::jsonb, ?)
                        """,
                transition.id().toString(),
                transition.taskId().toString(),
                transition.fromState(),
                transition.toState(),
                transition.kind().name(),
                JSON.writeValueAsString(transition.reason()),
                Timestamp.from(transition.createdAt()));

        List<TaskEvent> events = new ArrayList<>();
        events.add(new TaskEvent.Transition(eventSeq, taskId, transition.id(), fromState, toState,
                kind, transition.reason(), now));
        events.add(new TaskEvent.Status(eventSeq, taskId, toState, projectionOf(target), false));
        boolean terminal = target.kind() == TaskStateKind.TERMINAL;
        if (terminal && task.parentTaskId() != null) {
            events.add(new TaskEvent.SubtaskTerminal(incrementEventSeq(task.parentTaskId()),
                    task.parentTaskId(), taskId, projectionOf(target)));
        }
        publishEventsAfterCommit(events);
        publishAfterCommit(task, target);
        log.info("Переход задачи {}: {} → {} ({})", taskId, fromState, toState, kind);
        return transition;
    }

    /** Проекция статуса целевого состояния: TERMINAL — по outcome, остальное — по kind. */
    static TaskStatus projectionOf(GraphState target) {
        return switch (target.kind()) {
            case WAIT_WEBHOOK, WAIT_TASKS -> TaskStatus.WAITING;
            case TERMINAL -> outcomeStatus(target.raw().get("outcome"));
            default -> TaskStatus.RUNNING;
        };
    }

    private static TaskStatus outcomeStatus(Object outcome) {
        if (outcome instanceof String value) {
            return switch (value) {
                case "FAILED" -> TaskStatus.FAILED;
                case "CANCELLED" -> TaskStatus.CANCELLED;
                default -> TaskStatus.SUCCEEDED;
            };
        }
        return TaskStatus.SUCCEEDED;
    }

    /**
     * Неизменяемая копия reason с сохранением null-значений ({@code exitCode=null} у bash-TIMEOUT
     * — валидное содержимое истории): {@link Map#copyOf} на null бросает NPE — не годится.
     */
    private static Map<String, Object> immutableReason(Map<String, Object> reason) {
        return reason == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(reason));
    }

    /** Дедлайн цели: явный timeout состояния → kind-дефолт из конфига → null (TERMINAL — null). */
    private Instant deadlineOf(GraphState target, Instant now) {
        if (target.kind() == TaskStateKind.TERMINAL) {
            return null;
        }
        Duration timeout = target.timeout()
                .or(() -> kindDefault(target.kind()))
                .orElse(null);
        return timeout == null ? null : now.plus(timeout);
    }

    private java.util.Optional<Duration> kindDefault(TaskStateKind kind) {
        TaskProperties.Transition transition = properties.transition();
        TaskProperties.KindTimeouts defaults = transition == null ? null : transition.kindTimeouts();
        if (defaults == null) {
            return java.util.Optional.empty();
        }
        return switch (kind) {
            case BASH_SCRIPT -> java.util.Optional.ofNullable(defaults.bash());
            case WAIT_WEBHOOK -> java.util.Optional.ofNullable(defaults.waitWebhook());
            case WAIT_TASKS -> java.util.Optional.ofNullable(defaults.waitTasks());
            case AGENT -> java.util.Optional.ofNullable(defaults.agent());
            case TERMINAL -> java.util.Optional.empty();
        };
    }

    /**
     * EVENT-wake строго после коммита (в транзакции подписчиков ещё нет состояния): раскачка
     * следующего состояния (BASH), переоценка WAIT_TASKS-наблюдателей, bootstrap AGENT.
     * Терминальная цель — дополнительный быстрый канал task-terminal для наблюдателей.
     */
    private void publishAfterCommit(Task task, GraphState target) {
        boolean terminal = target.kind() == TaskStateKind.TERMINAL;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish(task.id(), terminal);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish(task.id(), terminal);
            }
        });
    }

    /** SSE-события — строго после коммита транзакции, зарезервировавшей seq (пачка J.4). */
    private void publishEventsAfterCommit(List<TaskEvent> events) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishEvents(events);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishEvents(events);
            }
        });
    }

    private void publishEvents(List<TaskEvent> events) {
        for (TaskEventListener listener : eventListeners) {
            try {
                for (TaskEvent event : events) {
                    listener.onTaskEvent(event);
                }
            } catch (Exception e) {
                log.warn("TaskEvent-слушатель {} упал: {}", listener.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /** Резерв seq под SSE-событие другой задачи (subtask.terminal на потоке родителя). */
    private long incrementEventSeq(UUID taskId) {
        Long reserved = jdbcTemplate.queryForObject(
                "UPDATE task SET task_event_seq = task_event_seq + 1 WHERE id = ? RETURNING task_event_seq",
                Long.class, taskId);
        if (reserved == null) {
            throw new IllegalStateException("Резерв task_event_seq задачи %s не удался".formatted(taskId));
        }
        return reserved;
    }

    private void publish(UUID taskId, boolean terminal) {
        wakeBus.publishTaskWake(taskId);
        if (terminal) {
            wakeBus.publishTaskTerminal(taskId);
        }
    }

    private Instant dbNow() {
        return jdbcTemplate.queryForObject("SELECT now()", Timestamp.class).toInstant();
    }
}
