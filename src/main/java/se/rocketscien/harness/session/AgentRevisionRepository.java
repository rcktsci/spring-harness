package se.rocketscien.harness.session;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Чтение ревизий агентов (иммутабельные строки, изменение — вручную в БД, D-39).
 * Намеренно узкий read-only-контракт: полный JpaRepository экспортировал бы save/delete
 * для иммутабельных ревизий.
 */
public interface AgentRevisionRepository extends Repository<AgentRevisionEntity, UUID> {

    Optional<AgentRevisionEntity> findFirstByAgentKeyOrderByRevDesc(String agentKey);

    Optional<AgentRevisionEntity> findByAgentKeyAndRev(String agentKey, int rev);

    /** Последняя ревизия каждого ключа, порядок по ключу (каталог GET /agents). */
    @Query("""
            SELECT a FROM AgentRevisionEntity a
            WHERE a.rev = (SELECT MAX(b.rev) FROM AgentRevisionEntity b WHERE b.agentKey = a.agentKey)
            ORDER BY a.agentKey
            """)
    List<AgentRevisionEntity> findLatestRevisions();
}
