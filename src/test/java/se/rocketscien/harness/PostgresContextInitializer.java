package se.rocketscien.harness;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

/**
 * Поднимает Postgres для интеграционных тестов и публикует технические свойства фреймворка
 * (spring.datasource.*) в окружение контекста.
 */
public class PostgresContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("harness")
            .withUsername("harness")
            .withPassword("harness");

    @Override
    public synchronized void initialize(ConfigurableApplicationContext applicationContext) {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        applicationContext.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "postgres-testcontainer",
                Map.of(
                        "spring.datasource.url", POSTGRES.getJdbcUrl(),
                        "spring.datasource.username", POSTGRES.getUsername(),
                        "spring.datasource.password", POSTGRES.getPassword()
                )
        ));
    }
}
