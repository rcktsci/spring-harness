package se.rocketscien.harness.task.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.common.jsonschema.JsonSchemaError;
import se.rocketscien.harness.common.jsonschema.LimitedJsonSchemaValidator;
import se.rocketscien.harness.task.Comment;
import se.rocketscien.harness.task.DependencyInvalidException;
import se.rocketscien.harness.task.InvalidCursorException;
import se.rocketscien.harness.task.ParamsSchemaInvalidException;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskAlreadyTerminalException;
import se.rocketscien.harness.task.TaskCommentEntity;
import se.rocketscien.harness.task.TaskDependencyEntity;
import se.rocketscien.harness.task.TaskEntity;
import se.rocketscien.harness.task.TaskEvent;
import se.rocketscien.harness.task.TaskEventListener;
import se.rocketscien.harness.task.TaskNotFoundException;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.TaskStateKind;
import se.rocketscien.harness.task.TaskStatus;
import se.rocketscien.harness.task.TaskTransitionHistoryEntity;
import se.rocketscien.harness.task.TaskTreeNode;
import se.rocketscien.harness.task.TaskWakeListener;
import se.rocketscien.harness.task.Transition;
import se.rocketscien.harness.task.TransitionKind;
import se.rocketscien.harness.task.WorkflowRevisionNotFoundException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Реализация {@link TaskRegistry} поверх живой схемы (data-model §4): append-only история и
 * комментарии, CAS-отмена stop, DFS-валидация циклов зависимостей, курсорная пагинация
 * {@code (updated_at, id)} / {@code (created_at, id)} — стиль SessionStoreImpl.
 *
 * <p>Чтение {@code graph_jsonb} пиннутой ревизии — собственным SQL-запросом по id: модуль task
 * не зависит от workflow Java-классов (architecture.md §2); граф — данные ревизии, а не её API.</p>
 */
@Repository
@Transactional
@RequiredArgsConstructor
@Slf4j
public class TaskRegistryImpl implements TaskRegistry {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
    };

    private static final String TASK_ROW_SELECT = """
            SELECT t.id, t.title, t.description, t.author_user_id, t.owner_user_id,
                   t.workflow_revision_id, t.current_state, t.current_state_kind, t.state_attempt,
                   t.task_event_seq, t.deadline_at, t.status_projection,
                   t.params_jsonb::text AS params_json, t.parent_task_id, t.tags, t.suspended,
                   t.created_at, t.updated_at
            FROM task t
            """;

    private final IdGenerator idGenerator;
    private final JdbcTemplate jdbcTemplate;
    private final List<TaskWakeListener> wakeListeners;
    private final List<TaskEventListener> eventListeners;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Task createTask(CreateTaskCommand command) {
        if (command.title() == null || command.title().isBlank()
                || command.description() == null
                || command.ownerUserId() == null
                || command.workflowRevisionId() == null) {
            throw new IllegalArgumentException("title, description, owner и workflowRevisionId обязательны");
        }
        RevisionData revision = loadRevision(command.workflowRevisionId());
        InitialState initial = resolveStartState(revision.graph(), revision.startState());

        Map<String, Object> params = command.params() == null ? Map.of() : command.params();
        List<JsonSchemaError> schemaErrors =
                LimitedJsonSchemaValidator.validate(params, initial.paramsSchema(), "/params");
        if (!schemaErrors.isEmpty()) {
            throw new ParamsSchemaInvalidException(schemaErrors);
        }

        if (command.parentTaskId() != null && entityManager.find(TaskEntity.class, command.parentTaskId()) == null) {
            throw new TaskNotFoundException(
                    "Родительская задача %s не найдена".formatted(command.parentTaskId()));
        }

        Instant now = dbNow();
        TaskEntity entity = new TaskEntity(
                idGenerator.newUuidV7(),
                command.title(),
                command.description(),
                command.authorUserId(),
                command.ownerUserId(),
                command.workflowRevisionId(),
                initial.code(),
                initial.kind(),
                initial.kind() == TaskStateKind.BASH_SCRIPT ? 1 : 0,
                0,
                initial.deadline(now),
                initial.statusProjection(),
                params,
                command.parentTaskId(),
                command.tags() == null ? List.of() : List.copyOf(command.tags()),
                false,
                now,
                now
        );
        entityManager.persist(entity);
        // D-49: EVENT-wake из insert — стартовое состояние (BASH/WAIT_TASKS/AGENT) раскачивается
        // шиной движка сразу, без ожидания POLL.
        publishWakeAfterCommit(entity.getId());
        log.info("Создана задача {} '{}' в состоянии '{}' ({})", entity.getId(), entity.getTitle(),
                initial.code(), initial.kind());
        return toTask(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Task get(UUID id) {
        return toTask(loadTask(id));
    }

    @Override
    public Task patch(UUID id, TaskPatch patch) {
        if (patch == null) {
            throw new IllegalArgumentException("patch обязателен");
        }
        TaskEntity entity = loadTask(id);
        if (patch.title() != null) {
            if (patch.title().isBlank()) {
                throw new IllegalArgumentException("title не может быть пустым");
            }
            entity.setTitle(patch.title());
        }
        if (patch.description() != null) {
            entity.setDescription(patch.description());
        }
        if (patch.tags() != null) {
            entity.setTags(List.copyOf(patch.tags()));
            // Триггер переоценки WAIT_TASKS/TAGGED(x) (спека task-engine): изменение тегов
            // задачи — EVENT-wake после коммита; диспетчер движка переоценивает барьеры.
            publishWakeAfterCommit(id);
        }
        entity.setUpdatedAt(dbNow());
        return toTask(entity);
    }

    @Override
    public void addDependency(UUID blockerTaskId, UUID blockedTaskId) {
        if (blockerTaskId == null || blockedTaskId == null) {
            throw new IllegalArgumentException("blockerTaskId и blockedTaskId обязательны");
        }
        List<JsonSchemaError> errors = new ArrayList<>();
        if (blockerTaskId.equals(blockedTaskId)) {
            errors.add(new JsonSchemaError("/blockedBy", "self-loop",
                    "Задача не может блокировать сама себя: %s".formatted(blockedTaskId)));
            throw new DependencyInvalidException(errors);
        }
        // H-6: лок обеих задач (детерминированный порядок по id — без deadlock) сериализует
        // конкурентные addDependency по тем же задачам; проверка цикла и вставка — под локом
        jdbcTemplate.queryForList(
                "SELECT id FROM task WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                UUID.class, blockerTaskId, blockedTaskId);
        requireTask(blockerTaskId);
        requireTask(blockedTaskId);

        if (dependencyExists(blockerTaskId, blockedTaskId)) {
            return;
        }

        if (reaches(blockedTaskId, blockerTaskId, loadGatesGraph(), new HashSet<>())) {
            errors.add(new JsonSchemaError("/blockedBy", "cycle",
                    "Ребро %s → %s замыкает цикл зависимостей".formatted(blockerTaskId, blockedTaskId)));
            throw new DependencyInvalidException(errors);
        }

        entityManager.persist(new TaskDependencyEntity(blockerTaskId, blockedTaskId));
        // Триггер переоценки WAIT_TASKS (спека task-engine): изменение blocked_by блокируемой
        // задачи — EVENT-wake после коммита; диспетчер движка переоценивает её барьер.
        publishWakeAfterCommit(blockedTaskId);
        log.info("Добавлена зависимость: {} блокирует {}", blockerTaskId, blockedTaskId);
    }

    @Override
    public void removeDependency(UUID blockerTaskId, UUID blockedTaskId) {
        int removed = jdbcTemplate.update(
                "DELETE FROM task_dependency WHERE blocker_task_id = ? AND blocked_task_id = ?",
                blockerTaskId, blockedTaskId);
        if (removed > 0) {
            publishWakeAfterCommit(blockedTaskId);
        }
    }

    @Override
    public void suspend(UUID taskId, boolean cascade) {
        requireTask(taskId);
        if (!cascade) {
            jdbcTemplate.update("UPDATE task SET suspended = true, updated_at = now() WHERE id = ?", taskId);
            return;
        }
        jdbcTemplate.update("""
                WITH RECURSIVE subtree AS (
                    SELECT id FROM task WHERE id = ?
                    UNION ALL
                    SELECT t.id FROM task t JOIN subtree s ON t.parent_task_id = s.id
                )
                UPDATE task SET suspended = true, updated_at = now()
                WHERE id IN (SELECT id FROM subtree)
                """, taskId);
        log.info("Задача {} приостановлена (cascade={})", taskId, cascade);
    }

    @Override
    public void resume(UUID taskId) {
        // H-4: блокируем строку до проверки — гонка «проверили не-терминал → stop отменил →
        // сняли suspended у отменённой» невозможна: проверка и снятие атомарны под локом
        List<LockedStatus> locked = jdbcTemplate.query(
                "SELECT status_projection, suspended FROM task WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> new LockedStatus(
                        TaskStatus.valueOf(rs.getString("status_projection")),
                        rs.getBoolean("suspended")),
                taskId
        );
        if (locked.isEmpty()) {
            throw new TaskNotFoundException("Задача %s не найдена".formatted(taskId));
        }
        if (locked.getFirst().statusProjection().isTerminal()) {
            throw new TaskAlreadyTerminalException(
                    "Задача %s уже терминальна (%s), resume недопустим"
                            .formatted(taskId, locked.getFirst().statusProjection()));
        }
        if (locked.getFirst().suspended()) {
            jdbcTemplate.update(
                    "UPDATE task SET suspended = false, updated_at = now() WHERE id = ?", taskId);
        }
        // не была suspended — флаг не трогаем; wake публикуется всегда
        // (переоценка/bootstrap идемпотентны)
        publishWakeAfterCommit(taskId);
    }

    @Override
    public void stop(UUID taskId) {
        requireTask(taskId);
        List<UUID> subtree = subtreeIds(taskId, null);
        UUID root = subtree.getFirst();
        List<TaskEvent> events = new ArrayList<>();
        if (!cancelByStop(root, events)) {
            throw new TaskAlreadyTerminalException(
                    "Задача %s уже терминальна, stop недопустим".formatted(taskId));
        }
        for (UUID id : subtree.subList(1, subtree.size())) {
            cancelByStop(id, events);
        }
        // D-49: EVENT-wake из update — '$CANCELLED' терминален для WAIT_TASKS-наблюдателей
        // (ALL_TERMINAL), их переоценка срабатывает сразу.
        publishWakeAfterCommit(taskId);
        publishEventsAfterCommit(events);
        log.info("Задача {} остановлена ('$CANCELLED'), поддерево: {} узлов", taskId, subtree.size());
    }

    /**
     * CAS в {@code '$CANCELLED'} одного узла: SELECT ... FOR UPDATE фиксирует old-состояние без
     * гонки, UPDATE с гвардом «не терминальная» (без гварда suspended — data-model §7.2)
     * выигрывает у переходов; запись истории и инкремент {@code task_event_seq} — в той же
     * транзакции. SSE-события (переход CANCEL + статус, терминал родителю) накапливаются
     * в {@code events} — публикация после коммита. false — узел уже терминален.
     */
    private boolean cancelByStop(UUID id, List<TaskEvent> events) {
        PreviousState previous = jdbcTemplate.queryForObject(
                "SELECT current_state, status_projection, parent_task_id FROM task WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> new PreviousState(
                        rs.getString("current_state"),
                        TaskStatus.valueOf(rs.getString("status_projection")),
                        rs.getObject("parent_task_id", UUID.class)),
                id
        );
        if (previous.statusProjection().isTerminal()) {
            return false;
        }
        UUID transitionId = idGenerator.newUuidV7();
        List<Long> reserved = jdbcTemplate.query("""
                UPDATE task
                SET suspended = true,
                    current_state = ?,
                    current_state_kind = 'TERMINAL',
                    status_projection = 'CANCELLED',
                    deadline_at = NULL,
                    task_event_seq = task_event_seq + 1,
                    updated_at = now()
                WHERE id = ? AND status_projection IN ('RUNNING', 'WAITING')
                RETURNING task_event_seq
                """, (rs, rowNum) -> rs.getLong("task_event_seq"), CANCELLED_STATE, id);
        if (reserved.isEmpty()) {
            return false;
        }
        long eventSeq = reserved.getFirst();
        Instant now = dbNow();
        Map<String, Object> reason = Map.of("kind", "stop", "actor", "user");
        entityManager.persist(new TaskTransitionHistoryEntity(
                transitionId, id, previous.currentState(), CANCELLED_STATE,
                TransitionKind.CANCEL, reason, now));
        events.add(new TaskEvent.Transition(eventSeq, id, transitionId, previous.currentState(),
                CANCELLED_STATE, TransitionKind.CANCEL, reason, now));
        events.add(new TaskEvent.Status(eventSeq, id, CANCELLED_STATE, TaskStatus.CANCELLED, true));
        if (previous.parentTaskId() != null) {
            events.add(new TaskEvent.SubtaskTerminal(incrementEventSeq(previous.parentTaskId()),
                    previous.parentTaskId(), id, TaskStatus.CANCELLED));
        }
        return true;
    }

    @Override
    public Comment addComment(UUID taskId, UUID authorUserId, String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Текст комментария обязателен");
        }
        requireTask(taskId);
        // seq комментария резервируется транзакционно с дописью (спека session-api: курсор
        // task_event_seq нумерует все события задачи, включая не-transition)
        long eventSeq = incrementEventSeq(taskId);
        TaskCommentEntity entity = new TaskCommentEntity(
                idGenerator.newUuidV7(), taskId, authorUserId, body, dbNow());
        entityManager.persist(entity);
        Comment comment = new Comment(entity.getId(), entity.getTaskId(), entity.getAuthorUserId(),
                entity.getBody(), entity.getCreatedAt());
        publishEventsAfterCommit(List.of(new TaskEvent.Comment(eventSeq, comment.taskId(),
                comment.id(), comment.authorUserId(), comment.body(), comment.createdAt())));
        return comment;
    }

    @Override
    @Transactional(readOnly = true)
    public TaskSearchResult list(TaskSearchCriteria criteria) throws InvalidCursorException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (criteria.parentTaskId() != null) {
            where.append(" AND t.parent_task_id = ?");
            params.add(criteria.parentTaskId());
        }
        if (criteria.status() != null) {
            where.append(" AND t.status_projection = ?");
            params.add(criteria.status().name());
        }
        if (criteria.ownerUserId() != null) {
            where.append(" AND t.owner_user_id = ?");
            params.add(criteria.ownerUserId());
        }
        if (criteria.tags() != null) {
            for (String tag : criteria.tags()) {
                where.append(" AND EXISTS (SELECT 1 FROM unnest(t.tags) tg WHERE tg = ?)");
                params.add(tag);
            }
        }
        if (criteria.titleOrDescriptionContains() != null && !criteria.titleOrDescriptionContains().isEmpty()) {
            where.append(" AND (t.title ILIKE ? ESCAPE '\\' OR t.description ILIKE ? ESCAPE '\\')");
            String pattern = "%" + escapeLike(criteria.titleOrDescriptionContains()) + "%";
            params.add(pattern);
            params.add(pattern);
        }
        if (criteria.cursor() != null) {
            // tuple-сравнение (updated_at, id) < (u0, id0) — стабильная пагинация при равных updated_at
            where.append(" AND (t.updated_at < ? OR (t.updated_at = ? AND t.id < ?))");
            CursorPosition cursor = decodeCursor(criteria.cursor());
            Timestamp moment = Timestamp.from(cursor.moment());
            params.add(moment);
            params.add(moment);
            params.add(cursor.id());
        }

        String sql = TASK_ROW_SELECT + where
                + " ORDER BY t.updated_at DESC, t.id DESC LIMIT ?";
        params.add(criteria.limit() + 1);

        List<Task> rows = jdbcTemplate.query(sql, this::mapTaskRow, params.toArray());
        boolean hasMore = rows.size() > criteria.limit();
        List<Task> page = hasMore ? rows.subList(0, criteria.limit()) : rows;
        String nextCursor = hasMore
                ? encodeCursor(page.getLast().updatedAt(), page.getLast().id())
                : null;
        return new TaskSearchResult(page, nextCursor);
    }

    @Override
    @Transactional(readOnly = true)
    public TaskTreeNode getTree(UUID taskId, Integer depth) {
        String cte = """
                JOIN (
                    WITH RECURSIVE subtree AS (
                        SELECT id, 0 AS depth FROM task WHERE id = ?
                        UNION ALL
                        SELECT t.id, s.depth + 1 FROM task t JOIN subtree s ON t.parent_task_id = s.id
                    )
                    SELECT id, depth FROM subtree
                    WHERE ?::int IS NULL OR depth <= ?::int
                ) sub ON sub.id = t.id
                """;
        List<Task> nodes = jdbcTemplate.query(
                TASK_ROW_SELECT + cte + " ORDER BY sub.depth, t.created_at, t.id",
                this::mapTaskRow, taskId, depth, depth);
        if (nodes.isEmpty()) {
            throw new TaskNotFoundException("Задача %s не найдена".formatted(taskId));
        }

        Map<UUID, TaskTreeNode> built = new LinkedHashMap<>();
        List<Map.Entry<UUID, UUID>> childLinks = new ArrayList<>();
        Task root = nodes.getFirst();
        for (Task nodeTask : nodes) {
            built.put(nodeTask.id(), new TaskTreeNode(nodeTask, new ArrayList<>()));
            if (nodeTask.parentTaskId() != null) {
                childLinks.add(Map.entry(nodeTask.parentTaskId(), nodeTask.id()));
            }
        }
        for (Map.Entry<UUID, UUID> link : childLinks) {
            TaskTreeNode parent = built.get(link.getKey());
            if (parent != null) {
                parent.children().add(built.get(link.getValue()));
            }
        }
        return built.get(root.id());
    }

    @Override
    @Transactional(readOnly = true)
    public HistoryPage getHistory(UUID taskId, String since, Integer limit) throws InvalidCursorException {
        requireTask(taskId);
        StringBuilder where = new StringBuilder(" WHERE h.task_id = ?");
        List<Object> params = new ArrayList<>(List.of(taskId));
        if (since != null) {
            where.append(" AND (h.created_at > ? OR (h.created_at = ? AND h.id > ?))");
            CursorPosition cursor = decodeCursor(since);
            Timestamp moment = Timestamp.from(cursor.moment());
            params.add(moment);
            params.add(moment);
            params.add(cursor.id());
        }
        String sql = """
                SELECT h.id, h.task_id, h.from_state, h.to_state, h.kind,
                       h.reason_jsonb::text AS reason_json, h.created_at
                FROM task_transition_history h%s
                ORDER BY h.created_at ASC, h.id ASC
                """.formatted(where);
        if (limit != null) {
            sql += " LIMIT ?";
            params.add(limit + 1);
        }

        List<Transition> rows = jdbcTemplate.query(sql, this::mapTransitionRow, params.toArray());
        if (limit == null) {
            return new HistoryPage(rows, null);
        }
        boolean hasMore = rows.size() > limit;
        List<Transition> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore
                ? encodeCursor(page.getLast().createdAt(), page.getLast().id())
                : null;
        return new HistoryPage(page, nextCursor);
    }

    // ---------------------------------------------------------------- граф пиннутой ревизии

    private record InitialState(String code, TaskStateKind kind, Map<String, Object> state) {

        TaskStatus statusProjection() {
            return switch (kind) {
                case WAIT_WEBHOOK, WAIT_TASKS -> TaskStatus.WAITING;
                case TERMINAL -> outcomeStatus(state.get("outcome"));
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

        Instant deadline(Instant now) {
            if (kind == TaskStateKind.TERMINAL || !(state.get("timeout") instanceof String timeout)) {
                return null;
            }
            return now.plus(Duration.parse(timeout));
        }

        Map<String, Object> paramsSchema() {
            return state.get("paramsSchema") instanceof Map<?, ?> schema
                    ? (Map<String, Object>) schema
                    : null;
        }
    }

    private record RevisionData(Map<String, Object> graph, String startState) {
    }

    /** Граф и явный стартовый state пиннутой ревизии (H-1: current_state := start_state). */
    private RevisionData loadRevision(UUID revisionId) {
        List<RevisionData> revisions = jdbcTemplate.query(
                "SELECT graph_jsonb::text AS graph_json, start_state FROM workflow_revision WHERE id = ?",
                (rs, rowNum) -> new RevisionData(
                        JSON.readValue(rs.getString("graph_json"), JSON_MAP),
                        rs.getString("start_state")),
                revisionId
        );
        if (revisions.isEmpty()) {
            throw new WorkflowRevisionNotFoundException(
                    "Ревизия workflow %s не найдена".formatted(revisionId));
        }
        return revisions.getFirst();
    }

    /**
     * Стартовое состояние — по явному {@code start_state} ревизии (H-1/H-8); эвристика
     * «единственный source» удалена (ломалась на циклических графах). Отсутствие state в графе —
     * повреждённая ревизия: валидатор не даёт её сохранить, здесь — защитный отказ.
     */
    private InitialState resolveStartState(Map<String, Object> graph, String startState) {
        return states(graph).stream()
                .filter(state -> startState != null && startState.equals(state.get("code")))
                .findFirst()
                .map(state -> new InitialState(
                        startState,
                        TaskStateKind.valueOf(String.valueOf(state.get("type"))),
                        state
                ))
                .orElseThrow(() -> new IllegalStateException(
                        "Стартовое состояние '%s' не найдено в графе ревизии".formatted(startState)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> states(Map<String, Object> graph) {
        if (!(graph.get("states") instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream()
                .filter(Map.class::isInstance)
                .map(state -> (Map<String, Object>) state)
                .toList();
    }

    // ---------------------------------------------------------------- зависимости

    private boolean dependencyExists(UUID blockerTaskId, UUID blockedTaskId) {
        return !jdbcTemplate.queryForList(
                "SELECT blocker_task_id FROM task_dependency WHERE blocker_task_id = ? AND blocked_task_id = ?",
                UUID.class, blockerTaskId, blockedTaskId).isEmpty();
    }

    /** Все рёбра «кто кого блокирует»: ключ — блокирующая, значения — кого блокирует. */
    private Map<UUID, List<UUID>> loadGatesGraph() {
        List<Object[]> rows = jdbcTemplate.query(
                "SELECT blocker_task_id, blocked_task_id FROM task_dependency",
                (rs, rowNum) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)});
        Map<UUID, List<UUID>> gates = new HashMap<>();
        for (Object[] row : rows) {
            gates.computeIfAbsent((UUID) row[0], key -> new ArrayList<>()).add((UUID) row[1]);
        }
        return gates;
    }

    /** DFS по рёбрам «блокирует»: путь from ⇒* to означает, что новое ребро to → from замкнёт цикл. */
    private boolean reaches(UUID from, UUID to, Map<UUID, List<UUID>> gates, Set<UUID> visiting) {
        if (from.equals(to)) {
            return true;
        }
        if (!visiting.add(from)) {
            return false;
        }
        for (UUID next : gates.getOrDefault(from, List.of())) {
            if (reaches(next, to, gates, visiting)) {
                visiting.remove(from);
                return true;
            }
        }
        visiting.remove(from);
        return false;
    }

    // ---------------------------------------------------------------- сканы/курсоры/утилиты

    private List<UUID> subtreeIds(UUID taskId, Integer depth) {
        return jdbcTemplate.queryForList("""
                WITH RECURSIVE subtree AS (
                    SELECT id, 0 AS depth FROM task WHERE id = ?
                    UNION ALL
                    SELECT t.id, s.depth + 1 FROM task t JOIN subtree s ON t.parent_task_id = s.id
                )
                SELECT id FROM subtree WHERE ?::int IS NULL OR depth <= ?::int ORDER BY depth, id
                """, UUID.class, taskId, depth, depth);
    }

    private record PreviousState(String currentState, TaskStatus statusProjection, UUID parentTaskId) {
    }

    private record LockedStatus(TaskStatus statusProjection, boolean suspended) {
    }

    private record CursorPosition(Instant moment, UUID id) {
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Непрозрачный курсор: ISO-8601 время + id, Base64-URL (стиль SessionStoreImpl). */
    private static String encodeCursor(Instant moment, UUID id) {
        String raw = moment + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    private static CursorPosition decodeCursor(String cursor) throws InvalidCursorException {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            int separator = raw.indexOf('|');
            return new CursorPosition(
                    Instant.parse(raw.substring(0, separator)),
                    UUID.fromString(raw.substring(separator + 1)));
        } catch (RuntimeException e) {
            throw new InvalidCursorException("Некорректный курсор страницы", e);
        }
    }

    private TaskEntity loadTask(UUID id) {
        TaskEntity entity = entityManager.find(TaskEntity.class, id);
        if (entity == null) {
            throw new TaskNotFoundException("Задача %s не найдена".formatted(id));
        }
        return entity;
    }

    private void requireTask(UUID id) {
        loadTask(id);
    }

    private Task mapTaskRow(ResultSet rs, int rowNum) throws SQLException {
        Array tags = rs.getArray("tags");
        List<String> tagList = tags == null ? List.of() : List.of((String[]) tags.getArray());
        String paramsJson = rs.getString("params_json");
        Map<String, Object> params = paramsJson == null ? Map.of() : JSON.readValue(paramsJson, JSON_MAP);
        Timestamp deadline = rs.getTimestamp("deadline_at");
        return new Task(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("description"),
                rs.getObject("author_user_id", UUID.class),
                rs.getObject("owner_user_id", UUID.class),
                rs.getObject("workflow_revision_id", UUID.class),
                rs.getString("current_state"),
                TaskStateKind.valueOf(rs.getString("current_state_kind")),
                rs.getInt("state_attempt"),
                rs.getLong("task_event_seq"),
                deadline == null ? null : deadline.toInstant(),
                TaskStatus.valueOf(rs.getString("status_projection")),
                params,
                rs.getObject("parent_task_id", UUID.class),
                tagList,
                rs.getBoolean("suspended"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private Transition mapTransitionRow(ResultSet rs, int rowNum) throws SQLException {
        String reasonJson = rs.getString("reason_json");
        Map<String, Object> reason = reasonJson == null ? Map.of() : JSON.readValue(reasonJson, JSON_MAP);
        return new Transition(
                rs.getObject("id", UUID.class),
                rs.getObject("task_id", UUID.class),
                rs.getString("from_state"),
                rs.getString("to_state"),
                TransitionKind.valueOf(rs.getString("kind")),
                reason,
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private static Task toTask(TaskEntity entity) {
        return new Task(
                entity.getId(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getAuthorUserId(),
                entity.getOwnerUserId(),
                entity.getWorkflowRevisionId(),
                entity.getCurrentState(),
                entity.getCurrentStateKind(),
                entity.getStateAttempt(),
                entity.getTaskEventSeq(),
                entity.getDeadlineAt(),
                entity.getStatusProjection(),
                entity.getParams(),
                entity.getParentTaskId(),
                entity.getTags(),
                entity.isSuspended(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private void publishWakeAfterCommit(UUID taskId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishWake(taskId);
                }
            });
        } else {
            publishWake(taskId);
        }
    }

    private void publishWake(UUID taskId) {
        for (TaskWakeListener listener : wakeListeners) {
            try {
                listener.onTaskWake(taskId);
            } catch (Exception e) {
                log.warn("TaskWake-слушатель {} упал на {}: {}",
                        listener.getClass().getSimpleName(), taskId, e.getMessage());
            }
        }
    }

    /** SSE-события — строго после коммита транзакции, зарезервировавшей seq (пачка J.4). */
    private void publishEventsAfterCommit(List<TaskEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishEvents(events);
                }
            });
        } else {
            publishEvents(events);
        }
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

    /** Резерв монотонного {@code task_event_seq} (курсор SSE) транзакционно с эмиссией. */
    private long incrementEventSeq(UUID taskId) {
        Long reserved = jdbcTemplate.queryForObject(
                "UPDATE task SET task_event_seq = task_event_seq + 1 WHERE id = ? RETURNING task_event_seq",
                Long.class, taskId);
        if (reserved == null) {
            throw new TaskNotFoundException("Задача %s не найдена".formatted(taskId));
        }
        return reserved;
    }

    private Instant dbNow() {
        return jdbcTemplate.queryForObject("SELECT now()", Timestamp.class).toInstant();
    }
}
