package se.rocketscien.harness.workflow;

import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only доступ к строкам {@code workflow}; записи — только через
 * {@code WorkflowRegistryImpl} (ревизии иммутабельны, D-39-стиль).
 */
public interface WorkflowRepository extends Repository<WorkflowEntity, UUID> {

    Optional<WorkflowEntity> findByKey(String key);
}
