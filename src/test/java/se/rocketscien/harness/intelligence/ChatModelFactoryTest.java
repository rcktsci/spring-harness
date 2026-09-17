package se.rocketscien.harness.intelligence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiChatOptions;
import se.rocketscien.harness.common.AesGcmEncryption;
import se.rocketscien.harness.config.LlmProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-J-4: {@code params_jsonb} пробрасывается в options (включая {@code reasoning_effort}),
 * неизвестные ключи не теряются молча — WARN.
 */
class ChatModelFactoryTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private final ChatModelFactory factory = new ChatModelFactory(
            new LlmProperties(Duration.ofSeconds(60), Map.of(1, Base64.getEncoder().encodeToString(KEY))),
            new AesGcmCredentialDecryptor(Map.of(1, Base64.getEncoder().encodeToString(KEY))));

    @Test
    void mapsKnownParamsIncludingReasoningEffort() {
        LlmModel model = model(Map.of(
                "temperature", 0.5,
                "max_tokens", 100,
                "top_p", 0.9,
                "reasoning_effort", "high"));

        OpenAiChatOptions options = factory.options(model);

        assertThat(options.getTemperature()).isEqualTo(0.5);
        assertThat(options.getMaxTokens()).isEqualTo(100);
        assertThat(options.getTopP()).isEqualTo(0.9);
        assertThat(options.getReasoningEffort()).isEqualTo("high");
    }

    @Test
    void acceptsCamelCaseAliases() {
        LlmModel model = model(Map.of("maxTokens", 55, "reasoningEffort", "low"));

        OpenAiChatOptions options = factory.options(model);

        assertThat(options.getMaxTokens()).isEqualTo(55);
        assertThat(options.getReasoningEffort()).isEqualTo("low");
    }

    @Test
    void warnsOnUnknownParamsWithoutFailing() {
        Logger logger = (Logger) LoggerFactory.getLogger(ChatModelFactory.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            OpenAiChatOptions options = factory.options(model(Map.of("mystery_flag", true)));

            assertThat(options.getModel()).isEqualTo("gpt-4");
            assertThat(appender.list)
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage()).contains("mystery_flag");
                    });
        } finally {
            logger.detachAppender(appender);
        }
    }

    private LlmModel model(Map<String, Object> params) {
        return new LlmModel(UUID.randomUUID(), UUID.randomUUID(), "gpt-4", params, Instant.now());
    }
}
