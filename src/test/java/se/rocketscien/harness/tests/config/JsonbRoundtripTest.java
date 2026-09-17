package se.rocketscien.harness.tests.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.type.format.FormatMapper;
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JsonbRoundtripTest extends BaseApplicationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private IdGenerator idGenerator;

    @Test
    void effectiveJsonFormatMapperIsJackson3() {
        SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);

        FormatMapper jsonFormatMapper = sessionFactory.getSessionFactoryOptions().getJsonFormatMapper();

        assertThat(jsonFormatMapper).isInstanceOf(Jackson3JsonFormatMapper.class);
    }

    @Test
    void jsonbPayloadRoundtrip() {
        UUID id = idGenerator.newUuidV7();
        Map<String, Object> payload = Map.of(
                "text", "привет, «jsonb»",
                "flag", true,
                "nested", Map.of("count", 7, "items", List.of("a", "b"))
        );

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> entityManager.persist(new JsonbProbeEntity(id, payload)));

        Map<String, Object> read = transactionTemplate.execute(status ->
                entityManager.find(JsonbProbeEntity.class, id).getPayloadJsonb());

        assertThat(read).isEqualTo(payload);

        String columnType = jdbcTemplate.queryForObject(
                """
                SELECT data_type
                FROM information_schema.columns
                WHERE table_name = 'jsonb_probe' AND column_name = 'payload_jsonb'
                """,
                String.class);

        assertThat(columnType).isEqualTo("jsonb");
    }
}
