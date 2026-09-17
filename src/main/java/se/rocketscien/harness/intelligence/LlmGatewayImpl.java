package se.rocketscien.harness.intelligence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Кэш клиентов по {@code llm_model.id} (D-M1-3): актуализация — рестартом процесса (MVP).
 * Отсутствие модели/учётных данных не кэшируется — следующий Turn повторит попытку.
 */
@Service
public class LlmGatewayImpl implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayImpl.class);

    private final LlmModelRepository modelRepository;
    private final LlmCredentialsRepository credentialsRepository;
    private final ChatModelFactory chatModelFactory;
    private final ConcurrentMap<UUID, ChatModel> cache = new ConcurrentHashMap<>();

    public LlmGatewayImpl(LlmModelRepository modelRepository,
                          LlmCredentialsRepository credentialsRepository,
                          ChatModelFactory chatModelFactory) {
        this.modelRepository = modelRepository;
        this.credentialsRepository = credentialsRepository;
        this.chatModelFactory = chatModelFactory;
    }

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
