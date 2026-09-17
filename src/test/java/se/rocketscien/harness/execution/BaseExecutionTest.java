package se.rocketscien.harness.execution;

import org.springframework.test.context.ContextConfiguration;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.LlmWireMockInitializer;

/**
 * База интеграционных тестов исполняющего контура: Postgres (родительский инициализатор) +
 * WireMock-LLM. Helper-образ собирается к первому docker-тесту JVM (общий для классов).
 */
@ContextConfiguration(initializers = LlmWireMockInitializer.class)
public abstract class BaseExecutionTest extends BaseApplicationTest {

    static {
        DockerTestSupport.helperImage();
    }
}
