package se.rocketscien.harness.tests.session;

import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionMessageEntity;
import se.rocketscien.harness.session.SessionStore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRenderVisibilityTest extends BaseApplicationTest {

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    @Test
    void visibleEventsAreJournalMinusCovered() {
        Session session = SessionTestFixtures.createSession(jdbcTemplate, sessionStore, idGenerator);

        sessionStore.appendEvent(session.id(), MessageKind.USER, session.ownerUserId(), Map.of("text", "первое"));
        sessionStore.appendEvent(session.id(), MessageKind.ASSISTANT, null, Map.of("text", "второе"));
        sessionStore.appendEvent(session.id(), MessageKind.USER, session.ownerUserId(), Map.of("text", "третье"));
        sessionStore.appendEvent(session.id(), MessageKind.ASSISTANT, null, Map.of("text", "четвёртое"));
        sessionStore.appendEvent(session.id(), MessageKind.COMPACT, null, Map.of(
                "covers", List.of(Map.of("from", 1, "to", 2)),
                "summary", "первые два свернуты"
        ));

        List<SessionMessageEntity> visible = sessionStore.renderVisible(session.id());

        assertThat(visible)
                .extracting(message -> message.getId().seq())
                .containsExactly(3L, 4L, 5L);

        assertThat(visible.getLast().getKind()).isEqualTo(MessageKind.COMPACT);
        assertThat(visible.getLast().getPayloadJsonb()).containsEntry("summary", "первые два свернуты");
    }

    @Test
    void laterCompactCanHideEarlierCompact() {
        Session session = SessionTestFixtures.createSession(jdbcTemplate, sessionStore, idGenerator);

        sessionStore.appendEvent(session.id(), MessageKind.USER, session.ownerUserId(), Map.of("text", "q"));
        sessionStore.appendEvent(session.id(), MessageKind.COMPACT, null, Map.of(
                "covers", List.of(Map.of("from", 1, "to", 1)),
                "summary", "первая свёртка"
        ));
        sessionStore.appendEvent(session.id(), MessageKind.COMPACT, null, Map.of(
                "covers", List.of(Map.of("from", 1, "to", 2)),
                "summary", "вторая свёртка поверх"
        ));

        List<SessionMessageEntity> visible = sessionStore.renderVisible(session.id());

        assertThat(visible)
                .extracting(message -> message.getId().seq())
                .containsExactly(3L);
        assertThat(visible.getFirst().getPayloadJsonb()).containsEntry("summary", "вторая свёртка поверх");
    }

    @Test
    void emptyJournalRendersEmpty() {
        Session session = SessionTestFixtures.createSession(jdbcTemplate, sessionStore, idGenerator);

        assertThat(sessionStore.renderVisible(session.id())).isEmpty();
    }

    @Test
    void wideCoverRangeRendersWithoutSeqMaterialization() {
        Session session = SessionTestFixtures.createSession(jdbcTemplate, sessionStore, idGenerator);

        sessionStore.appendEvent(session.id(), MessageKind.USER, session.ownerUserId(), Map.of("text", "первое"));
        sessionStore.appendEvent(session.id(), MessageKind.USER, session.ownerUserId(), Map.of("text", "второе"));
        sessionStore.appendEvent(session.id(), MessageKind.COMPACT, null, Map.of(
                "covers", List.of(Map.of("from", 1, "to", 1_000_000)),
                "summary", "широкое покрытие"
        ));

        List<SessionMessageEntity> visible = sessionStore.renderVisible(session.id());

        assertThat(visible)
                .extracting(message -> message.getId().seq())
                .containsExactly(3L);
    }
}
