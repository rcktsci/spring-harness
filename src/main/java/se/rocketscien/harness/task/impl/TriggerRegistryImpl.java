package se.rocketscien.harness.task.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.support.AbstractSqlTypeValue;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.common.jsonschema.JsonSchemaError;
import se.rocketscien.harness.common.jsonschema.LimitedJsonSchemaValidator;
import se.rocketscien.harness.common.security.WebhookSignatureVerifier;
import se.rocketscien.harness.config.WebhookProperties;
import se.rocketscien.harness.task.InvalidCursorException;
import se.rocketscien.harness.task.ParamsSchemaInvalidException;
import se.rocketscien.harness.task.Trigger;
import se.rocketscien.harness.task.TriggerNotFoundException;
import se.rocketscien.harness.task.TriggerRegistry;
import se.rocketscien.harness.task.WorkflowRevisionNotFoundException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Реализация {@link TriggerRegistry} поверх живой схемы (data-model §6): пин ревизии с
 * валидацией params по {@code paramsSchema} стартового состояния (ограниченный профиль
 * D-58 — тот же путь, что и {@code TaskRegistryImpl.createTask}), revoke одним UPDATE,
 * курсорная пагинация {@code (created_at, id)} desc.
 *
 * <p>Резолв {@code (workflow_key, rev)} и чтение {@code graph_jsonb} — собственный SQL:
 * модуль task не зависит от Java-классов workflow (architecture.md §2; прецедент —
 * «данные ревизии, а не её API», пачка H). Capability-URL собирается stateless из
 * {@code harness.webhook.base-url} + HMAC-токена ({@link WebhookSignatureVerifier}).</p>
 */
@Repository
@Transactional
@RequiredArgsConstructor
@Slf4j
public class TriggerRegistryImpl implements TriggerRegistry {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
    };

    private static final String TRIGGER_ROW_SELECT = """
            SELECT t.id, t.name, t.workflow_key, t.rev, t.params_jsonb::text AS params_json,
                   t.tags, t.owner_user_id, t.revoked_at, t.created_at
            FROM trigger t
            """;

    private final IdGenerator idGenerator;
    private final JdbcTemplate jdbcTemplate;
    private final WebhookSignatureVerifier signatureVerifier;
    private final WebhookProperties webhookProperties;

    @Override
    public Trigger create(CreateTriggerCommand command) {
        if (command.name() == null || command.name().isBlank()
                || command.workflowKey() == null || command.workflowKey().isBlank()
                || command.ownerUserId() == null) {
            throw new IllegalArgumentException("name, workflowKey и owner обязательны");
        }
        RevisionData revision = loadRevision(command.workflowKey(), command.rev());

        Map<String, Object> params = command.params() == null ? Map.of() : command.params();
        List<JsonSchemaError> schemaErrors =
                LimitedJsonSchemaValidator.validate(params, revision.paramsSchema(), "/params");
        if (!schemaErrors.isEmpty()) {
            throw new ParamsSchemaInvalidException(schemaErrors);
        }

        UUID id = idGenerator.newUuidV7();
        jdbcTemplate.update("""
                        INSERT INTO trigger (id, name, workflow_key, rev, params_jsonb, tags,
                                             owner_user_id, revoked_at, created_at)
                        VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, NULL, ?)
                        """,
                id,
                command.name(),
                command.workflowKey(),
                revision.rev(),
                JSON.writeValueAsString(params),
                textArrayArg(command.tags() == null ? List.of() : List.copyOf(command.tags())),
                command.ownerUserId(),
                Timestamp.from(dbNow()));
        Trigger trigger = get(id);
        log.info("Создан триггер {} '{}' → workflow '{}' rev={} (capability-URL выдан)",
                id, command.name(), command.workflowKey(), revision.rev());
        return trigger;
    }

    @Override
    @Transactional(readOnly = true)
    public Trigger get(UUID id) {
        List<Trigger> rows = jdbcTemplate.query(
                TRIGGER_ROW_SELECT + " WHERE t.id = ?",
                this::mapTriggerRow, id);
        if (rows.isEmpty()) {
            throw new TriggerNotFoundException("Триггер %s не найден".formatted(id));
        }
        return rows.getFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public TriggerPage list(TriggerSearchCriteria criteria) throws InvalidCursorException {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (criteria.ownerUserId() != null) {
            where.append(" AND t.owner_user_id = ?");
            params.add(criteria.ownerUserId());
        }
        if (criteria.cursor() != null) {
            // tuple-сравнение (created_at, id) < (c0, id0) — стабильная пагинация при равных created_at
            where.append(" AND (t.created_at < ? OR (t.created_at = ? AND t.id < ?))");
            CursorPosition cursor = decodeCursor(criteria.cursor());
            Timestamp moment = Timestamp.from(cursor.moment());
            params.add(moment);
            params.add(moment);
            params.add(cursor.id());
        }
        String sql = TRIGGER_ROW_SELECT + where + " ORDER BY t.created_at DESC, t.id DESC LIMIT ?";
        params.add(criteria.limit() + 1);

        List<Trigger> rows = jdbcTemplate.query(sql, this::mapTriggerRow, params.toArray());
        boolean hasMore = rows.size() > criteria.limit();
        List<Trigger> page = hasMore ? rows.subList(0, criteria.limit()) : rows;
        String nextCursor = hasMore
                ? encodeCursor(page.getLast().createdAt(), page.getLast().id())
                : null;
        return new TriggerPage(page, nextCursor);
    }

    @Override
    public void revoke(UUID id) {
        // Ревью L-3: один атомарный UPDATE с гардом (SELECT+UPDATE неатомарен — гонка
        // с параллельным revoke); нет строки — не найден или уже отозван → 404
        List<UUID> revoked = jdbcTemplate.query(
                "UPDATE trigger SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL RETURNING id",
                (rs, rowNum) -> rs.getObject("id", UUID.class), id);
        if (revoked.isEmpty()) {
            throw new TriggerNotFoundException("Триггер %s не найден или уже отозван".formatted(id));
        }
        log.info("Триггер {} отозван — capability-URL умер (410 trigger-revoked)", id);
    }

    // ---------------------------------------------------------------- пин ревизии

    private record RevisionData(UUID revisionId, int rev, String startState,
                                Map<String, Object> graph) {

        /** {@code paramsSchema} стартового состояния пиннутой ревизии (H-8: явный start_state). */
        Map<String, Object> paramsSchema() {
            if (!(graph.get("states") instanceof List<?> states)) {
                return null;
            }
            return states.stream()
                    .filter(Map.class::isInstance)
                    .map(state -> (Map<?, ?>) state)
                    .filter(state -> startState != null && startState.equals(state.get("code")))
                    .findFirst()
                    .map(state -> state.get("paramsSchema"))
                    .filter(Map.class::isInstance)
                    .map(state -> (Map<String, Object>) state)
                    .orElse(null);
        }
    }

    /**
     * Резолв пина {@code (workflow_key, rev)}: явная ревизия или latestRev; собственный SQL
     * вместо {@code WorkflowRegistry} — task не зависит от workflow Java-классов.
     */
    private RevisionData loadRevision(String workflowKey, Integer rev) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.id, r.rev, r.graph_jsonb::text AS graph_json, r.start_state
                FROM workflow_revision r
                JOIN workflow w ON r.workflow_id = w.id
                WHERE w.key = ?
                """);
        List<Object> params = new ArrayList<>(List.of(workflowKey));
        if (rev != null) {
            sql.append(" AND r.rev = ?");
            params.add(rev);
        } else {
            sql.append(" ORDER BY r.rev DESC LIMIT 1");
        }
        List<RevisionData> rows = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new RevisionData(
                rs.getObject("id", UUID.class),
                rs.getInt("rev"),
                rs.getString("start_state"),
                JSON.readValue(rs.getString("graph_json"), JSON_MAP)
        ), params.toArray());
        if (rows.isEmpty()) {
            throw new WorkflowRevisionNotFoundException(
                    "Workflow '%s' или его ревизия %s не найдены".formatted(workflowKey, rev));
        }
        return rows.getFirst();
    }

    // ---------------------------------------------------------------- курсоры/утилиты

    private Trigger mapTriggerRow(ResultSet rs, int rowNum) throws SQLException {
        Array tags = rs.getArray("tags");
        List<String> tagList = tags == null ? List.of() : List.of((String[]) tags.getArray());
        String paramsJson = rs.getString("params_json");
        Map<String, Object> params = paramsJson == null ? Map.of() : JSON.readValue(paramsJson, JSON_MAP);
        UUID id = rs.getObject("id", UUID.class);
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        return new Trigger(
                id,
                rs.getString("name"),
                rs.getString("workflow_key"),
                rs.getInt("rev"),
                params,
                tagList,
                rs.getObject("owner_user_id", UUID.class),
                revokedAt == null ? null : revokedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                capabilityUrl(id)
        );
    }

    /** Capability-URL триггера: base + /api/webhooks/triggers/{id}/{HMAC} (api-contracts §4.4). */
    private URI capabilityUrl(UUID id) {
        String token = signatureVerifier.expectedToken("trigger", id);
        return URI.create("%s/api/webhooks/triggers/%s/%s"
                .formatted(webhookProperties.baseUrl(), id, token));
    }

    private record CursorPosition(Instant moment, UUID id) {
    }

    /** Непрозрачный курсор: ISO-8601 время + id, Base64-URL (стиль TaskRegistryImpl). */
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

    private Instant dbNow() {
        return jdbcTemplate.queryForObject("SELECT now()", Timestamp.class).toInstant();
    }

    /**
     * {@code TEXT[]} в JdbcTemplate — только через {@code Connection.createArrayOf}
     * (пачка I: setObject-массивы pgjdbc не поддерживает); значение собирается лениво,
     * когда соединение уже доступно.
     */
    private static Object textArrayArg(List<String> values) {
        String[] array = values.toArray(String[]::new);
        return new AbstractSqlTypeValue() {
            @Override
            protected Object createTypeValue(Connection con, int sqlType, String typeName)
                    throws SQLException {
                return con.createArrayOf("text", array);
            }
        };
    }
}
