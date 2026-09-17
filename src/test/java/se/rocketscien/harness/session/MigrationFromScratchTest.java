package se.rocketscien.harness.session;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationFromScratchTest extends BaseApplicationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void coreTablesExist() {
        List<String> tables = jdbcTemplate.queryForList(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                """,
                String.class);

        assertThat(tables).contains(
                "app_user",
                "llm_credentials",
                "llm_model",
                "agent",
                "session",
                "session_message",
                "shedlock"
        );
    }

    @Test
    void sessionMessagePrimaryKeyIsCompositeSessionIdAndSeq() {
        List<String> keyColumns = jdbcTemplate.queryForList(
                """
                SELECT a.attname
                FROM pg_index i
                JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
                WHERE i.indisprimary
                  AND i.indrelid = 'public.session_message'::regclass
                ORDER BY a.attnum
                """,
                String.class);

        assertThat(keyColumns).containsExactly("session_id", "seq");
    }

    @Test
    void sessionMessageHasUniqueUlidIndex() {
        String indexDef = jdbcTemplate.queryForObject(
                """
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = 'uidx_session_message__id'
                """,
                String.class);

        assertThat(indexDef).isNotNull().contains("UNIQUE");
    }

    @Test
    void sessionHasPartialUniqueIndexForStateSessions() {
        String indexDef = jdbcTemplate.queryForObject(
                """
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = 'uidx_session__task_id_state_code'
                """,
                String.class);

        assertThat(indexDef)
                .isNotNull()
                .contains("UNIQUE")
                .contains("task_id, state_code")
                .contains("(kind)::text = 'STATE'::text");
    }

    @Test
    void sessionHasPartialIndexForEligibleScan() {
        String indexDef = jdbcTemplate.queryForObject(
                """
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = 'idx_session__last_seq_pending'
                """,
                String.class);

        assertThat(indexDef)
                .isNotNull()
                .contains("(last_seq)")
                .contains("last_seq > last_consumed_seq");
    }

    @Test
    void allMigrationsRecorded() {
        Integer executed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM databasechangelog",
                Integer.class);

        assertThat(executed).isPositive();
    }
}
