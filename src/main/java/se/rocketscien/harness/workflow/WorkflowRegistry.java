package se.rocketscien.harness.workflow;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Дверь к шаблонам workflow и их иммутабельным ревизиям (контракт, architecture.md §3;
 * data-model §3; спека workflow-engine).
 *
 * <p>Инварианты:</p>
 * <ul>
 *   <li>Граф валидируется перед сохранением ({@link WorkflowGraphSchemaValidator}, правила §2
 *       workflow-domain + ограниченный профиль JSON-Schema D-58); нарушение →
 *       {@link WorkflowGraphInvalidException} с errors[] { pointer, rule, message }.</li>
 *   <li>Ревизии иммутабельны: UPDATE запрещён, правка = новая строка
 *       ({@code UNIQUE (workflow_id, rev)}); задачи пинятся к конкретной ревизии.</li>
 *   <li>{@code key} workflow уникален (kebab-case проверяет API-слой bean-validation);
 *       дубль → {@link WorkflowKeyAlreadyExistsException}.</li>
 *   <li>Неизвестный key/ревизия → {@link WorkflowNotFoundException}.</li>
 *   <li>{@code graph} — распарсенный JSON ({@code graph_jsonb}); домен не зависит от
 *       сгенерированных DTO api-слоя.</li>
 * </ul>
 */
public interface WorkflowRegistry {

    /**
     * Создание workflow с первой ревизией ({@code rev=1}).
     *
     * @param startState стартовое состояние ревизии (H-1: объявляется явно, ∈ codes графа);
     *                   задачи создаются в этом состоянии
     * @throws WorkflowGraphInvalidException   граф невалиден (включая start_state ∉ codes)
     * @throws WorkflowKeyAlreadyExistsException key занят
     */
    WorkflowRevision createWorkflow(UUID ownerUserId, String key, String name,
                                    Map<String, Object> graph, String startState);

    /**
     * Новая ревизия ({@code rev = prev + 1}) того же ключа; идущие задачи остаются на старой.
     *
     * @throws WorkflowNotFoundException workflow не найден
     * @throws WorkflowGraphInvalidException   граф невалиден (включая start_state ∉ codes)
     */
    WorkflowRevision newRevision(String workflowKey, Map<String, Object> graph, String startState);

    /** Метаданные workflow + {@code latestRev}. */
    Workflow get(String workflowKey);

    /** Конкретная ревизия (граф целиком). */
    WorkflowRevision getRevision(String workflowKey, int rev);

    /**
     * Список workflow, сортировка {@code createdAt desc, id desc}; конверт-пагинация
     * непрозрачным курсором (значение {@code nextCursor} предыдущей страницы).
     *
     * @throws InvalidCursorException курсор не декодируется — ошибка клиента (422)
     */
    WorkflowSearchResult list(WorkflowSearchCriteria criteria) throws InvalidCursorException;

    /** Workflow + последняя ревизия (для каталога GET /workflows). */
    record Workflow(UUID id, String key, String name, UUID ownerUserId, Instant createdAt, int latestRev) {
    }

    /** Иммутабельная ревизия с графом ({@code graph_jsonb}) и явным стартовым состоянием (H-1). */
    record WorkflowRevision(UUID id, UUID workflowId, int rev, Map<String, Object> graph,
                            String startState, Instant createdAt) {
    }

    /**
     * Выжимки пиннутых ревизий одним запросом (TaskDto.workflow: key + rev по
     * {@code task.workflow_revision_id}; стиль {@code SessionStore.agentSummaries}).
     * Отсутствующие ревизии в карту не входят.
     */
    Map<UUID, RevisionSummary> revisionSummaries(Collection<UUID> revisionIds);

    /** Выжимка ревизии для публичных DTO (TaskDto.workflow). */
    record RevisionSummary(UUID revisionId, String workflowKey, int rev) {
    }

    /** Критерии списка; {@code cursor} — opaque, {@code limit} >= 1 (верхняя граница — API-слой). */
    record WorkflowSearchCriteria(String cursor, int limit) {
    }

    /** Страница списка: {@code nextCursor} — null, когда страниц больше нет. */
    record WorkflowSearchResult(List<Workflow> items, String nextCursor) {
    }
}
