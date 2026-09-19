package se.rocketscien.harness.workflow.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.common.jsonschema.JsonSchemaError;
import se.rocketscien.harness.config.WorkflowProperties;
import se.rocketscien.harness.workflow.InvalidCursorException;
import se.rocketscien.harness.workflow.WorkflowEntity;
import se.rocketscien.harness.workflow.WorkflowGraphInvalidException;
import se.rocketscien.harness.workflow.WorkflowGraphSchemaValidator;
import se.rocketscien.harness.workflow.WorkflowKeyAlreadyExistsException;
import se.rocketscien.harness.workflow.WorkflowNotFoundException;
import se.rocketscien.harness.workflow.WorkflowRegistry;
import se.rocketscien.harness.workflow.WorkflowRepository;
import se.rocketscien.harness.workflow.WorkflowRevisionEntity;
import se.rocketscien.harness.workflow.WorkflowRevisionRepository;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Реализация {@link WorkflowRegistry}: иммутабельные ревизии (data-model §3, §7.5), валидация
 * графа перед каждым сохранением, cursor-пагинация списка по {@code createdAt desc, id desc}
 * (стиль SessionStoreImpl.searchSessions).
 */
@Repository
@Transactional
@RequiredArgsConstructor
@Slf4j
public class WorkflowRegistryImpl implements WorkflowRegistry {

    private static final int FIRST_REV = 1;

    private final WorkflowGraphSchemaValidator graphValidator;
    private final WorkflowRepository workflowRepository;
    private final WorkflowRevisionRepository revisionRepository;
    private final IdGenerator idGenerator;
    private final JdbcTemplate jdbcTemplate;
    private final WorkflowProperties workflowProperties;
    private final PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public WorkflowRevision createWorkflow(UUID ownerUserId, String key, String name,
                                           Map<String, Object> graph, String startState) {
        if (key == null || key.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("key и name workflow обязательны");
        }
        if (workflowRepository.findByKey(key).isPresent()) {
            throw new WorkflowKeyAlreadyExistsException(key);
        }
        List<JsonSchemaError> errors = graphValidator.validate(graph, startState);
        if (!errors.isEmpty()) {
            throw new WorkflowGraphInvalidException(errors);
        }

        Instant now = dbNow();
        UUID workflowId = idGenerator.newUuidV7();
        entityManager.persist(new WorkflowEntity(workflowId, key, name, ownerUserId, now));

        WorkflowRevisionEntity revision = new WorkflowRevisionEntity(
                idGenerator.newUuidV7(), workflowId, FIRST_REV, graph, startState, now);
        entityManager.persist(revision);
        log.info("Создан workflow '{}' rev={}, start_state='{}'", key, FIRST_REV, startState);
        return toRevision(revision);
    }

    @Override
    public WorkflowRevision newRevision(String workflowKey, Map<String, Object> graph, String startState) {
        List<JsonSchemaError> errors = graphValidator.validate(graph, startState);
        if (!errors.isEmpty()) {
            throw new WorkflowGraphInvalidException(errors);
        }
        UUID workflowId = workflowRepository.findByKey(workflowKey)
                .map(WorkflowEntity::getId)
                .orElseThrow(() -> new WorkflowNotFoundException(
                        "Workflow '%s' не найден".formatted(workflowKey)));

        // R-2: попытки в независимых транзакциях (REQUIRES_NEW) — после UNIQUE-violation
        // текущая транзакция Postgres прервана, retry внутри неё невозможен
        TransactionTemplate attempt = new TransactionTemplate(transactionManager);
        attempt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        AtomicReference<DataIntegrityViolationException> lastConflict = new AtomicReference<>();
        for (int tryNo = 1; tryNo <= workflowProperties.revisionInsertRetries(); tryNo++) {
            try {
                return attempt.execute(status -> insertRevision(workflowId, graph, startState));
            } catch (DataIntegrityViolationException e) {
                lastConflict.set(e);
                log.warn("Конфликт вставки ревизии '{}' (попытка {}/{}): {}", workflowKey, tryNo,
                        workflowProperties.revisionInsertRetries(), e.getMessage());
            }
        }
        throw lastConflict.get();
    }

    /**
     * Вставка ревизии в собственной транзакции. Основной барьер (R-2) — лок строки-родителя
     * {@code workflow}: конкурентные newRevision одного workflow сериализуются, фантом
     * READ COMMITTED («max(rev) не видит незакоммиченную ревизию конкурента») невозможен.
     * Дополнительно блокируется строка максимальной ревизии; UNIQUE (workflow_id, rev) —
     * последний рубеж, его ловит retry.
     */
    private WorkflowRevision insertRevision(UUID workflowId, Map<String, Object> graph, String startState) {
        jdbcTemplate.queryForList(
                "SELECT id FROM workflow WHERE id = ? FOR UPDATE", UUID.class, workflowId);
        Integer maxRev = jdbcTemplate.query(
                        "SELECT rev FROM workflow_revision WHERE workflow_id = ? ORDER BY rev DESC LIMIT 1 FOR UPDATE",
                        (rs, rowNum) -> rs.getInt("rev"),
                        workflowId
                ).stream().findFirst().orElse(0);
        WorkflowRevisionEntity revision = new WorkflowRevisionEntity(
                idGenerator.newUuidV7(), workflowId, maxRev + 1, graph, startState, dbNow());
        entityManager.persist(revision);
        // flush: UNIQUE-violation должен случиться внутри попытки, а не на внешнем коммите
        entityManager.flush();
        log.info("Создана ревизия rev={} workflow {}, start_state='{}'", maxRev + 1, workflowId, startState);
        return toRevision(revision);
    }

    @Override
    @Transactional(readOnly = true)
    public Workflow get(String workflowKey) {
        WorkflowEntity workflow = workflowRepository.findByKey(workflowKey)
                .orElseThrow(() -> new WorkflowNotFoundException(
                        "Workflow '%s' не найден".formatted(workflowKey)));
        return new Workflow(
                workflow.getId(),
                workflow.getKey(),
                workflow.getName(),
                workflow.getOwnerUserId(),
                workflow.getCreatedAt(),
                latestRev(workflow.getId())
        );
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowRevision getRevision(String workflowKey, int rev) {
        WorkflowEntity workflow = workflowRepository.findByKey(workflowKey)
                .orElseThrow(() -> new WorkflowNotFoundException(
                        "Workflow '%s' не найден".formatted(workflowKey)));
        WorkflowRevisionEntity revision = revisionRepository.findByWorkflowIdAndRev(workflow.getId(), rev)
                .orElseThrow(() -> new WorkflowNotFoundException(
                        "Ревизия %d workflow '%s' не найдена".formatted(rev, workflowKey)));
        return toRevision(revision);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, RevisionSummary> revisionSummaries(Collection<UUID> revisionIds) {
        if (revisionIds == null || revisionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, RevisionSummary> result = new HashMap<>();
        for (Object[] row : revisionRepository.findRevisionSummaries(revisionIds)) {
            RevisionSummary summary = new RevisionSummary(
                    (UUID) row[0], (String) row[1], ((Number) row[2]).intValue());
            result.put(summary.revisionId(), summary);
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowSearchResult list(WorkflowSearchCriteria criteria) throws InvalidCursorException {
        List<String> conditions = new ArrayList<>();
        if (criteria.cursor() != null) {
            // JPQL не поддерживает tuple-сравнение — разворачиваем (createdAt, id) < (c0, id0) явно
            conditions.add("(w.createdAt < :cursorCreatedAt OR (w.createdAt = :cursorCreatedAt AND w.id < :cursorId))");
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        var query = entityManager.createQuery(
                "SELECT w FROM WorkflowEntity w" + where + " ORDER BY w.createdAt DESC, w.id DESC",
                WorkflowEntity.class);
        if (criteria.cursor() != null) {
            CursorPosition cursor = decodeCursor(criteria.cursor());
            query.setParameter("cursorCreatedAt", cursor.createdAt());
            query.setParameter("cursorId", cursor.id());
        }

        // limit + 1: наличие (limit+1)-й строки — признак следующей страницы
        List<WorkflowEntity> rows = query.setMaxResults(criteria.limit() + 1).getResultList();
        boolean hasMore = rows.size() > criteria.limit();
        List<WorkflowEntity> page = hasMore ? rows.subList(0, criteria.limit()) : rows;

        Map<UUID, Integer> latestRevs = latestRevs(page.stream().map(WorkflowEntity::getId).toList());
        List<Workflow> items = page.stream()
                .map(workflow -> new Workflow(
                        workflow.getId(),
                        workflow.getKey(),
                        workflow.getName(),
                        workflow.getOwnerUserId(),
                        workflow.getCreatedAt(),
                        latestRevs.getOrDefault(workflow.getId(), 0))
                )
                .toList();
        String nextCursor = hasMore
                ? encodeCursor(page.getLast().getCreatedAt(), page.getLast().getId())
                : null;
        return new WorkflowSearchResult(items, nextCursor);
    }

    private int latestRev(UUID workflowId) {
        return revisionRepository.findFirstByWorkflowIdOrderByRevDesc(workflowId)
                .map(WorkflowRevisionEntity::getRev)
                .orElse(0);
    }

    private Map<UUID, Integer> latestRevs(List<UUID> workflowIds) {
        Map<UUID, Integer> result = new HashMap<>();
        if (workflowIds.isEmpty()) {
            return result;
        }
        for (Object[] row : revisionRepository.findLatestRevs(workflowIds)) {
            result.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return result;
    }

    private static WorkflowRevision toRevision(WorkflowRevisionEntity entity) {
        return new WorkflowRevision(
                entity.getId(),
                entity.getWorkflowId(),
                entity.getRev(),
                entity.getGraphJsonb(),
                entity.getStartState(),
                entity.getCreatedAt()
        );
    }

    /** Непрозрачный курсор: ISO-8601 время + id, Base64-URL (стиль SessionStoreImpl). */
    private static String encodeCursor(Instant createdAt, UUID workflowId) {
        String raw = createdAt + "|" + workflowId;
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

    private record CursorPosition(Instant createdAt, UUID id) {
    }

    private Instant dbNow() {
        return jdbcTemplate.queryForObject("SELECT now()", Timestamp.class).toInstant();
    }
}
