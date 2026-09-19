package se.rocketscien.harness.workflow;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

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

    /** latestRev для страницы workflow одним запросом (без N+1). */
    @Query("""
            SELECT r.workflowId, MAX(r.rev)
            FROM WorkflowRevisionEntity r
            WHERE r.workflowId IN :workflowIds
            GROUP BY r.workflowId
            """)
    List<Object[]> findLatestRevs(List<UUID> workflowIds);
}
