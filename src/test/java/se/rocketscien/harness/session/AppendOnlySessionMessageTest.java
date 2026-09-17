package se.rocketscien.harness.session;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class AppendOnlySessionMessageTest extends BaseApplicationTest {

    private static final Pattern ALLOWED_METHOD_NAMES = Pattern.compile("^(append|find|exists|count)\\w*$");

    private static final Set<String> OBJECT_METHODS = Set.of("equals", "hashCode", "toString", "wait", "notify",
            "notifyAll", "getClass", "clone", "finalize");

    @Autowired
    private SessionMessageRepository sessionMessageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private IdGenerator idGenerator;

    @Test
    void appendedMessageIsReadableWithSameContent() {
        UUID userId = insertAppUser();
        UUID sessionId = insertFreeSession(userId);
        String ulid = idGenerator.newUlid();

        SessionMessageEntity message = new SessionMessageEntity(
                new SessionMessageId(sessionId, 1L),
                ulid,
                MessageKind.USER,
                userId,
                Map.of("text", "первое сообщение"),
                null,
                Instant.now()
        );

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                sessionMessageRepository.append(message));

        SessionMessageEntity read = new TransactionTemplate(transactionManager).execute(status ->
                sessionMessageRepository.find(sessionId, 1L).orElseThrow());

        assertThat(read.getId().sessionId()).isEqualTo(sessionId);
        assertThat(read.getId().seq()).isEqualTo(1L);
        assertThat(read.getUlid()).isEqualTo(ulid);
        assertThat(read.getKind()).isEqualTo(MessageKind.USER);
        assertThat(read.getAuthorUserId()).isEqualTo(userId);
        assertThat(read.getPayloadJsonb()).containsEntry("text", "первое сообщение");
        assertThat(read.getTokens()).isNull();

        List<SessionMessageEntity> all = sessionMessageRepository.findAllBySessionId(sessionId);

        assertThat(all).hasSize(1);
    }

    @Test
    void repositoryApiIsAppendOnly() {
        for (Method method : SessionMessageRepository.class.getDeclaredMethods()) {
            if (OBJECT_METHODS.contains(method.getName())) {
                continue;
            }
            assertThat(method.getName())
                    .as("метод %s вне append-only контракта", method.getName())
                    .matches(ALLOWED_METHOD_NAMES);
        }
    }

    @Test
    void entityHasNoSetterMethods() {
        for (Method method : SessionMessageEntity.class.getDeclaredMethods()) {
            assertThat(method.getName())
                    .as("сеттер %s запрещён: сущность append-only", method.getName())
                    .doesNotStartWith("set");
        }
    }

    private UUID insertAppUser() {
        UUID userId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                "INSERT INTO app_user (id, keycloak_subject, username, display_name, created_at) VALUES (?, ?, ?, ?, now())",
                userId,
                "subject-" + userId,
                "tester",
                "Тестер"
        );
        return userId;
    }

    private UUID insertFreeSession(UUID userId) {
        UUID sessionId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                """
                INSERT INTO session (id, owner_user_id, kind, last_seq, last_consumed_seq, last_activity_at, created_at)
                VALUES (?, ?, 'FREE', 0, 0, now(), now())
                """,
                sessionId,
                userId
        );
        return sessionId;
    }
}
