package se.rocketscien.harness.workflow;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only доступ к строкам {@code workflow_revision}; запись — только через
 * {@code WorkflowRegistryImpl} (ревизии иммутабельны, data-model §7.5).
 */
public interface WorkflowRevisionRepository extends Repository<WorkflowRevisionEntity, UUID> {

    Optional<WorkflowRevisionEntity> findByWorkflowIdAndRev(UUID workflowId, int rev);

    Optional<WorkflowRevisionEntity> findFirstByWorkflowIdOrderByRevDesc(UUID workflowId);

    /** Список ревизий workflow (GET /workflows/{key} — пачка L.4); сортировка rev asc. */
    List<WorkflowRevisionEntity> findByWorkflowIdOrderByRevAsc(UUID workflowId);

    /** latestRev для страницы workflow одним запросом (без N+1). */
    @Query("""
            SELECT r.workflowId, MAX(r.rev)
            FROM WorkflowRevisionEntity r
            WHERE r.workflowId IN :workflowIds
            GROUP BY r.workflowId
            """)
    List<Object[]> findLatestRevs(List<UUID> workflowIds);

    /** Выжимки пиннутых ревизий (key + rev) для TaskDto.workflow одним запросом (без N+1). */
    @Query(value = """
            SELECT r.id, w.key, r.rev
            FROM workflow_revision r
            JOIN workflow w ON w.id = r.workflow_id
            WHERE r.id IN (?1)
            """, nativeQuery = true)
    List<Object[]> findRevisionSummaries(Collection<UUID> revisionIds);
}
