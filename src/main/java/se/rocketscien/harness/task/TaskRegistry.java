package se.rocketscien.harness.task;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Дверь к задачам (контракт, architecture.md §3; data-model §4; спека task-engine): создание
 * с пином ревизии, merge-patch, зависимости с DFS-валидацией циклов, suspend/resume/stop,
 * комментарии, список, дерево, история переходов. Переходы (CAS + запись истории) — движок
 * ({@code TaskEngine}, execution, пачка I), не этот контракт.
 *
 * <p>Инварианты:</p>
 * <ul>
 *   <li>{@code params} иммутабельны после создания (api §4.1) — контракт не даёт способа их
 *       менять; попытка PATCH с {@code params} отклоняется API-слоем (422 rule=immutable).</li>
 *   <li>Создание валидирует {@code params} против {@code paramsSchema} начального состояния
 *       ревизии (ограниченный профиль D-58) → {@link ParamsSchemaInvalidException}.</li>
 *   <li>Зависимости: self-loop и циклы (транзитивные) запрещены — DFS по рёбрам при установке
 *       → {@link DependencyInvalidException}; {@code removeDependency} идемпотентен.</li>
 *   <li>Stop — атомарный CAS в {@code '$CANCELLED'} (гвард только «не терминальная», без
 *       проверки suspended — data-model §7.2): выигрывает у переходов; терминальная →
 *       {@link TaskAlreadyTerminalException}; всегда каскадный (подзадачи тоже).</li>
 *   <li>Resume публикует task-wake после коммита ({@link TaskWakeListener}); для терминальной —
 *       409. Suspend идемпотентен.</li>
 *   <li>История — append-only, порядок {@code created_at asc, id asc}, курсор — пара
 *       {@code (created_at, id)} (стабильная пагинация при равных {@code created_at}).</li>
 *   <li>{@code current_state} ∈ codes ревизии или {@link #CANCELLED_STATE}.</li>
 * </ul>
 */
public interface TaskRegistry {

    /** Зарезервированный псевдо-код принудительной отмены — вне codes ревизии (data-model §7.2). */
    String CANCELLED_STATE = "$CANCELLED";

    /**
     * Создание задачи с пином к ревизии workflow. Начальное состояние — единственный source
     * (state без входящих рёбер) ревизии; {@code current_state_kind}/{@code status_projection}
     * — его проекция; {@code deadline_at} — из {@code state.timeout} (если задан и состояние не
     * TERMINAL); params валидируются {@code paramsSchema} начального состояния (если объявлена).
     *
     * @throws WorkflowRevisionNotFoundException ревизия не найдена
     * @throws ParamsSchemaInvalidException      params не прошли paramsSchema
     * @throws TaskNotFoundException             parent-задача не найдена
     */
    Task createTask(CreateTaskCommand command);

    /** Задача по id.
     * @throws TaskNotFoundException не найдена */
    Task get(UUID id);

    /**
     * Merge-patch полей {@code title}/{@code description}/{@code tags}; null = поле отсутствует
     * в патче (не менять). Наличие/отсутствие и запрет {@code params} (422 rule=immutable) —
     * забота API-слоя (merge-patch-машина).
     *
     * @throws TaskNotFoundException не найдена
     */
    Task patch(UUID id, TaskPatch patch);

    /**
     * Ребро «blocker должен прийти в терминал, пока blocked ждёт».
     *
     * @throws DependencyInvalidException self-loop / цикл (транзитивный) / неизвестная задача
     * @throws TaskNotFoundException      задача не найдена
     */
    void addDependency(UUID blockerTaskId, UUID blockedTaskId);

    /**
     * Атомарная пачка рёбер «каждый blocker → blocked» (REST `POST /tasks/{id}/dependencies`,
     * K-1): лок всех затронутых задач в детерминированном порядке по id, проверки и вставки —
     * в одной транзакции; частичный коммит невозможен. Дубликаты рёбер — no-op; после вставки
     * хотя бы одного нового ребра — wake «blocked-changed» блокируемой задачи после коммита.
     *
     * @throws DependencyInvalidException self-loop / цикл (с учётом рёбер пачки) / неизвестный blocker
     * @throws TaskNotFoundException      блокируемая задача не найдена
     */
    void addDependencies(UUID blockedTaskId, Collection<UUID> blockerTaskIds);

    /** Снятие ребра; идемпотентно (отсутствующее ребро — no-op, отклонение dev D-пачки №4). */
    void removeDependency(UUID blockerTaskId, UUID blockedTaskId);

    /**
     * Аварийный флаг; планировщик пропускает. Идемпотентен. {@code cascade} — распространить
     * на всё поддерево подзадач.
     *
     * @throws TaskNotFoundException не найдена
     */
    void suspend(UUID taskId, boolean cascade);

    /**
     * Снять флаг нетерминальной задачи и опубликовать task-wake после коммита (переоценка
     * WAIT_TASKS / bootstrap AGENT-state подхватят без ожидания POLL).
     *
     * @throws TaskAlreadyTerminalException терминальная (включая '$CANCELLED') → 409
     * @throws TaskNotFoundException        не найдена
     */
    void resume(UUID taskId);

    /**
     * Принудительная отмена — всегда каскадная (подзадачи тоже; D-54): suspend + атомарный CAS
     * каждого нетерминального узла поддерева в {@link #CANCELLED_STATE} + запись в историю
     * {@code kind=CANCEL, reason={kind:'stop', actor:'user'}}. Отмена Turn'ов STATE-сессий —
     * выше ({@code StopTaskFacade}, execution, пачка K.2).
     *
     * @throws TaskAlreadyTerminalException уже терминальна (и у родителя, и у потомков —
     *                                      терминальные потомки пропускаются) → 409
     * @throws TaskNotFoundException        не найдена
     */
    void stop(UUID taskId);

    /**
     * Допись комментария (append-only); {@code authorUserId == null} — агентский
     * (NULL + агент-пометка, data-model §4).
     *
     * @throws TaskNotFoundException не найдена
     */
    Comment addComment(UUID taskId, UUID authorUserId, String body);

    /**
     * Комментарии задачи в порядке {@code created_at asc, id asc} (api-contracts §4.1);
     * {@code cursor} — непрозрачная пара (created_at, id), {@code limit == null} — все записи.
     *
     * @throws TaskNotFoundException     не найдена
     * @throws InvalidCursorException    курсор не декодируется
     */
    CommentPage listComments(UUID taskId, String cursor, Integer limit)
            throws InvalidCursorException;

    /**
     * Список задач: фильтры parent/status/mine(владелец)/tags(содержит все)/q(подстрока
     * title|description); сортировка {@code updatedAt desc, id desc}; конверт-пагинация
     * непрозрачным курсором. limit >= 1 (верхняя граница — API-слой, {@code limits.page}).
     *
     * @throws InvalidCursorException курсор не декодируется
     */
    TaskSearchResult list(TaskSearchCriteria criteria) throws InvalidCursorException;

    /**
     * Поддерево подзадач BFS от корня; {@code depth} — max расстояние от корня
     * ({@code null} — всё поддерево).
     *
     * @throws TaskNotFoundException не найдена
     */
    TaskTreeNode getTree(UUID taskId, Integer depth);

    /**
     * История переходов за интервал {@code (since, …]} в порядке {@code created_at asc, id asc};
     * {@code since} — непрозрачный курсор-пара (created_at, id); {@code limit == null} — все записи.
     */
    HistoryPage getHistory(UUID taskId, String since, Integer limit) throws InvalidCursorException;

    /** Команда создания; {@code authorUserId == null} — агент-автор; {@code parentTaskId} — подзадача. */
    record CreateTaskCommand(UUID workflowRevisionId, String title, String description,
                             UUID authorUserId, UUID ownerUserId, UUID parentTaskId,
                             Map<String, Object> params, Collection<String> tags) {
    }

    /** Merge-patch: null = не менять (RFC 7396-машина — в API-слое). */
    record TaskPatch(String title, String description, Collection<String> tags) {
    }

    /** Критерии списка; {@code cursor} — opaque; {@code mine} выражен готовым {@code ownerUserId}. */
    record TaskSearchCriteria(UUID parentTaskId, TaskStatus status, UUID ownerUserId,
                              Collection<String> tags, String titleOrDescriptionContains,
                              String cursor, int limit) {
    }

    /** Страница списка: {@code nextCursor} — null, когда страниц больше нет. */
    record TaskSearchResult(List<Task> items, String nextCursor) {
    }

    /** Страница истории: {@code nextCursor} — null, когда записей больше нет. */
    record HistoryPage(List<Transition> items, String nextCursor) {
    }

    /** Страница комментариев: {@code nextCursor} — null, когда страниц больше нет. */
    record CommentPage(List<Comment> items, String nextCursor) {
    }
}
