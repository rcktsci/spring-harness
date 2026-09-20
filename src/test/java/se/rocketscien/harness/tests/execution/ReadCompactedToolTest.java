package se.rocketscien.harness.tests.execution;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.execution.ToolResult;
import se.rocketscien.harness.execution.ToolStatus;
import se.rocketscien.harness.execution.impl.ReadCompactedTool;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O.4 (спека subagent-lifecycle, D-44/D-67): read_compacted возвращает оригиналы, скрытые
 * COMPACT-покрытием (id компакта или любого покрытого сообщения); лимит
 * {@code harness.compact.read-max-bytes} — усечение с маркером {@code truncated};
 * неизвестный/чужой id → not-found (no-such-message), cross-session-чтения нет (R8).
 */
class ReadCompactedToolTest extends BaseApplicationTest {

    @Autowired
    private ReadCompactedTool tool;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    @Autowired
    private Environment environment;

    @Test
    void returnsOriginalsHiddenByCompactCoverage() {
        Session session = newSession();

        SessionStore.AppendedEvent hidden = sessionStore.appendEvent(
                session.id(), MessageKind.USER, null, Map.of("text", "скрытое сообщение"));
        sessionStore.appendEvent(session.id(), MessageKind.USER, null, Map.of("text", "видимое"));
        SessionStore.AppendedEvent compact = sessionStore.appendEvent(
                session.id(), MessageKind.COMPACT, null,
                Map.of("covers", List.of(Map.of("from", hidden.seq(), "to", hidden.seq())),
                        "summary", "сводка первого"));

        ToolResult byCompact = tool.execute(session.id(), "call-1",
                Map.of("compactMessageId", compact.ulid()));
        assertThat(byCompact.status()).isEqualTo(ToolStatus.OK);
        assertThat(byCompact.output())
                .contains("скрытое сообщение")
                .contains("\"kind\":\"USER\"")
                .doesNotContain("видимое");

        // id указывает на само покрытое сообщение — тот же результат
        ToolResult byHidden = tool.execute(session.id(), "call-2",
                Map.of("compactMessageId", hidden.ulid()));
        assertThat(byHidden.status()).isEqualTo(ToolStatus.OK);
        assertThat(byHidden.output()).contains("скрытое сообщение");

        // ничем не покрытое сообщение — not-found
        SessionStore.AppendedEvent uncovered = sessionStore.appendEvent(
                session.id(), MessageKind.USER, null, Map.of("text", "после компакции"));
        ToolResult notCovered = tool.execute(session.id(), "call-3",
                Map.of("compactMessageId", uncovered.ulid()));
        assertThat(notCovered.status()).isEqualTo(ToolStatus.ERROR);
        assertThat(notCovered.output()).contains("not-found");
    }

    @Test
    void oversizedRecordIsTruncatedWithMarker() {
        Session session = newSession();
        String big = "х".repeat(500);
        SessionStore.AppendedEvent bigEvent = sessionStore.appendEvent(
                session.id(), MessageKind.USER, null, Map.of("text", big));
        SessionStore.AppendedEvent compact = sessionStore.appendEvent(
                session.id(), MessageKind.COMPACT, null,
                Map.of("covers", List.of(Map.of("from", bigEvent.seq(), "to", bigEvent.seq())),
                        "summary", "сводка большого"));

        // read-max-bytes = 64B (тест-профиль): 500 символов кириллицы (~1КБ UTF-8) усекаются
        ToolResult result = tool.execute(session.id(), "call-1",
                Map.of("compactMessageId", compact.ulid()));
        assertThat(result.status()).isEqualTo(ToolStatus.OK);
        assertThat(result.output()).contains("truncated");
        assertThat(result.output().getBytes().length).isLessThan(2000);
    }

    @Test
    void unknownOrForeignIdIsNotFound() {
        Session session = newSession();
        Session other = newSession();
        SessionStore.AppendedEvent foreign = sessionStore.appendEvent(
                other.id(), MessageKind.USER, null, Map.of("text", "чужое"));

        ToolResult unknown = tool.execute(session.id(), "call-1",
                Map.of("compactMessageId", "01ARZ3NDEKTSV4RRFFQ69G5FAV"));
        assertThat(unknown.status()).isEqualTo(ToolStatus.ERROR);
        assertThat(unknown.output()).contains("not-found (no-such-message)");

        ToolResult foreignResult = tool.execute(session.id(), "call-2",
                Map.of("compactMessageId", foreign.ulid()));
        assertThat(foreignResult.status()).isEqualTo(ToolStatus.ERROR);
        assertThat(foreignResult.output()).contains("not-found (no-such-message)");
        assertThat(foreignResult.output()).doesNotContain("чужое");
    }

    private Session newSession() {
        return ExecutionFixtures.newSession(jdbcTemplate, sessionStore, idGenerator, environment);
    }
}
