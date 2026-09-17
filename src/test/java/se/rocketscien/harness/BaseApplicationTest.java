package se.rocketscien.harness;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.client.RestClient;
import se.rocketscien.harness.config.DatabaseCleaner;
import se.rocketscien.harness.config.FailFastContextInitializer;
import se.rocketscien.harness.config.KeycloakContextInitializer;
import se.rocketscien.harness.config.LlmWireMockContextInitializer;
import se.rocketscien.harness.config.PostgresContextInitializer;
import se.rocketscien.harness.config.PreventTestMethodsInheritance;

/**
 * Единая база e2e-тестов приложения (по корпоративному навыку тестирования): один
 * Spring-контекст на весь прогон — Postgres (Testcontainers) + живой Keycloak (Testcontainers,
 * максимальная верность рантайму) + WireMock (LLM-бэкенд). Инфраструктура из {@code config}:
 * {@link DatabaseCleaner} (чистка после каждого теста), тестовый {@link RestClient},
 * защита от дублирования тест-методов при наследовании, fail-fast на раскол контекста.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = {
        PostgresContextInitializer.class,
        KeycloakContextInitializer.class,
        LlmWireMockContextInitializer.class,
        FailFastContextInitializer.class
})
@ActiveProfiles("test")
@PreventTestMethodsInheritance
public abstract class BaseApplicationTest {

    @Autowired
    protected DatabaseCleaner databaseCleaner;

    @Autowired
    @Qualifier(LlmWireMockContextInitializer.QUALIFIER)
    protected WireMockServer llmWireMock;

    @Autowired
    protected RestClient testRestClient;

    @LocalServerPort
    protected int localServerPort;

    protected String localServerUrl() {
        return "http://localhost:" + localServerPort;
    }

    @AfterEach
    final void tearDownBaseApplicationTest() {
        databaseCleaner.cleanup();
    }
}
