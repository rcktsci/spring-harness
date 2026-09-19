package se.rocketscien.harness.session.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionKind;
import se.rocketscien.harness.session.StateSessionService;
import se.rocketscien.harness.session.TurnOutcome;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Реализация {@link StateSessionService} поверх PARTIAL UNIQUE
 * {@code uidx_session__task_id_state_code (task_id, state_code) WHERE kind='STATE'} (миграция 005):
 * CREATE-путь — {@code INSERT ... ON CONFLICT (task_id, state_code) WHERE kind='STATE' DO NOTHING}
 * (гонка двух бутстрапов закрывается индексом — победитель создаёт, проигравший резюмирует);
 * создание атомарно — в одной транзакции insert сессии ({@code last_seq = 1}) и seed-SYSTEM-сообщение
 * с тем же {@code now()} (execution-model §7.2).
 *
 * <p>{@code owner_user_id} — из строки {@code task}: сессия состояния принадлежит владельцу задачи.
 * Чтение — собственным SQL по id (прецедент {@code TaskRegistryImpl} → {@code workflow_revision}:
 * строка задачи — данные, а не API task-модуля; session-модуль task-классы не импортирует).</p>
 */
@Repository
@Transactional
@RequiredArgsConstructor
@Slf4j
public class StateSessionServiceImpl implements StateSessionService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String CREATE_STATE_SESSION = """
            INSERT INTO session (id, title, owner_user_id, kind, task_id, state_code, agent_revision_id,
                                 cancel_requested, last_seq, last_consumed_seq, last_activity_at, created_at)
            VALUES (?, ?, ?, 'STATE', ?, ?, ?, false, 1, 0, ?, ?)
            ON CONFLICT (task_id, state_code) WHERE kind = 'STATE' DO NOTHING
            RETURNING id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final IdGenerator idGenerator;

    @Override
    public Session findOrCreate(UUID taskId, String stateCode, UUID agentRevisionId) {
        UUID ownerUserId = jdbcTemplate.query(
                "SELECT owner_user_id FROM task WHERE id = ?",
                (rs, rowNum) -> rs.getObject("owner_user_id", UUID.class),
                taskId).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Задача %s не найдена — STATE-сессию создаёт движок по живой задаче".formatted(taskId)));

        Timestamp now = Timestamp.from(dbNow());
        List<UUID> created = jdbcTemplate.query(
                CREATE_STATE_SESSION,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                idGenerator.newUuidV7(), stateCode, ownerUserId, taskId, stateCode, agentRevisionId, now, now);
        if (created.isEmpty()) {
            Session existing = findStateSession(taskId, stateCode);
            log.debug("STATE-сессия ({}, {}) уже существует — резюм", taskId, stateCode);
            return existing;
        }

        UUID sessionId = created.getFirst();
        insertSeed(sessionId, taskId, stateCode, now);
        log.info("Создана STATE-сессия {} ({}, {}) задачи {}", sessionId, taskId, stateCode, taskId);
        return findStateSession(taskId, stateCode);
    }

    /**
     * Seed-SYSTEM-сообщение (seq=1, тот же {@code now()}, что у сессии): контекст входа —
     * пара (task, state) и предыдущий переход задачи, если он был (execution-model §7.2).
     */
    private void insertSeed(UUID sessionId, UUID taskId, String stateCode, Timestamp now) {
        jdbcTemplate.update("""
                INSERT INTO session_message (session_id, seq, id, kind, author_user_id, payload_jsonb, tokens, created_at)
                VALUES (?, 1, ?, 'SYSTEM', NULL, ?::jsonb, NULL, ?)
                """,
                sessionId,
                idGenerator.newUlid(),
                JSON.writeValueAsString(seedPayload(taskId, stateCode)),
                now);
    }

    private Map<String, Object> seedPayload(UUID taskId, String stateCode) {
        String seed = "STATE-сессия состояния '%s' задачи %s.".formatted(stateCode, taskId)
                + previousTransitionLine(taskId)
                + " Смену состояния оформляй инструментом transition с обязательным непустым reason.";
        return Map.of("text", seed);
    }

    /** Предыдущий переход задачи (для резюме после возврата в состояние) — null, если его нет. */
    private String previousTransitionLine(UUID taskId) {
        return jdbcTemplate.query("""
                        SELECT from_state, to_state, kind, reason_jsonb::text AS reason
                        FROM task_transition_history
                        WHERE task_id = ?
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        """,
                        (rs, rowNum) -> {
                            String reason = rs.getString("reason");
                            String detail = reason == null || reason.isBlank() || "null".equals(reason)
                                    ? "" : "; reason: " + reason;
                            return " Предыдущий переход: %s → %s (%s)%s."
                                    .formatted(rs.getString("from_state"), rs.getString("to_state"),
                                            rs.getString("kind"), detail);
                        },
                        taskId).stream()
                .findFirst()
                .orElse("");
    }

    private Session findStateSession(UUID taskId, String stateCode) {
        return jdbcTemplate.query("""
                        SELECT id, title, owner_user_id, kind, task_id, state_code, agent_revision_id,
                               parent_session_id, cancel_requested, last_seq, last_consumed_seq,
                               last_turn_outcome, last_activity_at, created_at
                        FROM session
                        WHERE task_id = ? AND state_code = ? AND kind = 'STATE'
                        """,
                (rs, rowNum) -> new Session(
                        rs.getObject("id", UUID.class),
                        SessionKind.STATE,
                        rs.getString("title"),
                        rs.getObject("owner_user_id", UUID.class),
                        rs.getObject("task_id", UUID.class),
                        rs.getString("state_code"),
                        rs.getObject("agent_revision_id", UUID.class),
                        rs.getObject("parent_session_id", UUID.class),
                        rs.getBoolean("cancel_requested"),
                        rs.getLong("last_seq"),
                        rs.getLong("last_consumed_seq"),
                        rs.getString("last_turn_outcome") == null
                                ? null : TurnOutcome.valueOf(rs.getString("last_turn_outcome")),
                        rs.getTimestamp("last_activity_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()
                ),
                taskId, stateCode).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "STATE-сессия (%s, %s) исчезла между INSERT и SELECT".formatted(taskId, stateCode)));
    }

    private Instant dbNow() {
        return jdbcTemplate.queryForObject("SELECT now()", Timestamp.class).toInstant();
    }
}
