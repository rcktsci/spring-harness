package se.rocketscien.harness.config;

import java.util.Set;

/**
 * WireMock для LLM-бэкенда (OpenAI-совместимый): qualifier {@code llm}, свойства
 * {@code wiremock.llm.*}. Используется интеграционными тестами исполнения (Turn) и
 * intelligence-тестами; базовый URL для {@code llm_credentials} — {@code wiremock.llm.url}.
 */
public class LlmWireMockContextInitializer extends BaseWireMockContextInitializer {

    public static final String QUALIFIER = "llm";

    public LlmWireMockContextInitializer() {
        super(Set.of(QUALIFIER));
    }
}
