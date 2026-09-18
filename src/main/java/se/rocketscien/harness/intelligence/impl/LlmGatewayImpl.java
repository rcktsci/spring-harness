package se.rocketscien.harness.intelligence.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;
import se.rocketscien.harness.intelligence.ChatModelFactory;
import se.rocketscien.harness.intelligence.LlmConfigurationException;
import se.rocketscien.harness.intelligence.LlmCredentials;
import se.rocketscien.harness.intelligence.LlmCredentialsRepository;
import se.rocketscien.harness.intelligence.LlmGateway;
import se.rocketscien.harness.intelligence.LlmModel;
import se.rocketscien.harness.intelligence.LlmModelRepository;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Кэш клиентов по {@code llm_model.id} (D-M1-3): актуализация — рестартом процесса (MVP).
 * Отсутствие модели/учётных данных не кэшируется — следующий Turn повторит попытку.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LlmGatewayImpl implements LlmGateway {


    private final LlmModelRepository modelRepository;
    private final LlmCredentialsRepository credentialsRepository;
    private final ChatModelFactory chatModelFactory;
    private final ConcurrentMap<UUID, ChatModel> cache = new ConcurrentHashMap<>();

    @Override
    public ChatModel chatModel(UUID llmModelId) {
        return cache.computeIfAbsent(llmModelId, id -> {
            LlmModel model = modelRepository.findById(id)
                    .orElseThrow(() -> new LlmConfigurationException("LLM model not found: " + id));
            LlmCredentials credentials = credentialsRepository.findById(model.getCredentialsId())
                    .orElseThrow(() -> new LlmConfigurationException(
                            "LLM credentials not found: " + model.getCredentialsId()));
            log.info("Building LLM client for model {} ({})", model.getId(), model.getModelId());
            return chatModelFactory.create(model, credentials);
        });
    }
}
