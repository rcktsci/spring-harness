package se.rocketscien.harness;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * WireMock-стаб OpenAI-совместимого LLM-бэкенда для интеграционных тестов исполнения (7.2–7.6).
 * Публикует техническое свойство {@code wiremock.llm.url}; {@code llm_credentials.base_url}
 * в фикстурах указывает на него.
 */
public class LlmWireMockInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final WireMockServer LLM = new WireMockServer(options().dynamicPort());

    static {
        LLM.start();
    }

    public static WireMockServer llm() {
        return LLM;
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        applicationContext.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "wiremock-llm",
                Map.of("wiremock.llm.url", LLM.baseUrl())
        ));
    }
}
