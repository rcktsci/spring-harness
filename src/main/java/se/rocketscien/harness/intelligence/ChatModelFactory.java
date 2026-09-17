package se.rocketscien.harness.intelligence;

import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.stereotype.Service;
import se.rocketscien.harness.config.LlmProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Сборка OpenAI-совместимого клиента из записей БД (D-M1-3). В каждой сборке options — явные
 * {@code .timeout()} (конфиг) и {@code .maxRetries(0)}: встроенные ретраи Spring AI отключены
 * (регрессия #6915 + управляемая политика наших ретраев 429/5xx, см. {@link LlmInvoker}).
 * {@code params_jsonb} модели пробрасывается в options, включая {@code reasoning_effort};
 * неизвестные ключи не теряются молча — WARN со списком (C-J-4).
 */
@Service
@RequiredArgsConstructor
public class ChatModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ChatModelFactory.class);
    private static final int NO_RETRIES = 0;

    private static final Set<String> KNOWN_PARAMS = Set.of(
            "temperature",
            "maxTokens", "max_tokens",
            "topP", "top_p",
            "reasoning_effort", "reasoningEffort",
            "verbosity",
            "seed",
            "stop",
            "frequencyPenalty", "frequency_penalty",
            "presencePenalty", "presence_penalty");

    private final LlmProperties llmProperties;
    private final CredentialDecryptor credentialDecryptor;

    public OpenAiChatModel create(LlmModel model, LlmCredentials credentials) {
        String apiKey = credentialDecryptor.decrypt(credentials.getApiKeyEncrypted(), credentials.getKeyVersion());

        var syncClient = OpenAiSetup.setupSyncClient(
                credentials.getBaseUrl(), apiKey, null, null, null, null,
                false, false, model.getModelId(), llmProperties.timeout(), NO_RETRIES,
                null, Map.of(), ObservationRegistry.NOOP, null, List.of());
        var asyncClient = OpenAiSetup.setupAsyncClient(
                credentials.getBaseUrl(), apiKey, null, null, null, null,
                false, false, model.getModelId(), llmProperties.timeout(), NO_RETRIES,
                null, Map.of(), ObservationRegistry.NOOP, null, List.of());

        return OpenAiChatModel.builder()
                .openAiClient(syncClient)
                .openAiClientAsync(asyncClient)
                .options(options(model))
                .toolCallingManager(ToolCallingManager.builder().build())
                .build();
    }

    /** Public для unit-проверки проброса {@code params_jsonb} (C-J-4; тесты живут в tests.intelligence). */
    public OpenAiChatOptions options(LlmModel model) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(model.getModelId())
                .timeout(llmProperties.timeout())
                .maxRetries(NO_RETRIES)
                .streamUsage(true);

        Map<String, Object> params = model.getParamsJsonb() == null ? Map.of() : model.getParamsJsonb();

        Double temperature = number(params, "temperature");
        if (temperature != null) {
            builder.temperature(temperature);
        }
        Integer maxTokens = integer(params, "maxTokens", "max_tokens");
        if (maxTokens != null) {
            builder.maxTokens(maxTokens);
        }
        Double topP = number(params, "topP", "top_p");
        if (topP != null) {
            builder.topP(topP);
        }
        Double frequencyPenalty = number(params, "frequencyPenalty", "frequency_penalty");
        if (frequencyPenalty != null) {
            builder.frequencyPenalty(frequencyPenalty);
        }
        Double presencePenalty = number(params, "presencePenalty", "presence_penalty");
        if (presencePenalty != null) {
            builder.presencePenalty(presencePenalty);
        }
        Integer seed = integer(params, "seed");
        if (seed != null) {
            builder.seed(seed);
        }
        String reasoningEffort = string(params, "reasoning_effort", "reasoningEffort");
        if (reasoningEffort != null) {
            builder.reasoningEffort(reasoningEffort);
        }
        String verbosity = string(params, "verbosity");
        if (verbosity != null) {
            builder.verbosity(verbosity);
        }
        if (params.get("stop") instanceof List<?> stop) {
            builder.stop(stop.stream().map(String::valueOf).toList());
        }

        List<String> unknown = new ArrayList<>();
        for (String key : params.keySet()) {
            if (!KNOWN_PARAMS.contains(key)) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            log.warn("Ignoring unknown llm_model.params_jsonb keys for model {} ({}): {}",
                    model.getId(), model.getModelId(), unknown);
        }

        return builder.build();
    }

    private static Double number(Map<String, Object> params, String... keys) {
        Object value = first(params, keys);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Integer integer(Map<String, Object> params, String... keys) {
        Object value = first(params, keys);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static String string(Map<String, Object> params, String... keys) {
        Object value = first(params, keys);
        return value instanceof String text ? text : null;
    }

    private static Object first(Map<String, Object> params, String... keys) {
        for (String key : keys) {
            if (params.containsKey(key)) {
                return params.get(key);
            }
        }
        return null;
    }
}
