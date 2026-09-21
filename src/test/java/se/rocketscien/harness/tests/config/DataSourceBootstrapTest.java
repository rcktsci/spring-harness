package se.rocketscien.harness.tests.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import net.lbruun.springboot.preliquibase.PreLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

class DataSourceBootstrapTest extends BaseApplicationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextStartsWithManualPoolPreliquibaseLiquibaseAndJpa() {
        assertThat(applicationContext.getBean(HikariDataSource.class)).isNotNull();
        assertThat(applicationContext.getBean(PreLiquibase.class)).isNotNull();
        assertThat(applicationContext.getBean(EntityManagerFactory.class)).isNotNull();
    }

    @Test
    void liquibaseHasRunFromScratch() {
        Integer tables = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_name = 'databasechangelog'
                """,
                Integer.class);

        assertThat(tables).isEqualTo(1);
    }
}
