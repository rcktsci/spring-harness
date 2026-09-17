package se.rocketscien.harness.tests.intelligence;

import se.rocketscien.harness.intelligence.ChatModelFactory;
import se.rocketscien.harness.intelligence.impl.AesGcmCredentialDecryptor;
import se.rocketscien.harness.intelligence.impl.LlmGatewayImpl;
import se.rocketscien.harness.intelligence.LlmConfigurationException;
import se.rocketscien.harness.intelligence.LlmCredentials;
import se.rocketscien.harness.intelligence.LlmCredentialsRepository;
import se.rocketscien.harness.intelligence.LlmGateway;
import se.rocketscien.harness.intelligence.LlmModel;
import se.rocketscien.harness.intelligence.LlmModelRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import se.rocketscien.harness.common.AesGcmEncryption;
import se.rocketscien.harness.config.LlmProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 5.1: сборка клиента из {@code llm_model}+{@code llm_credentials}, кэш по id модели,
 * нормальное поведение при отсутствии учётных данных (LlmConfigurationException вместо падения старта).
 */
class LlmGatewayTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private LlmModelRepository modelRepository;
    private LlmCredentialsRepository credentialsRepository;
    private LlmGateway gateway;

    @BeforeEach
    void setUp() {
        modelRepository = mock(LlmModelRepository.class);
        credentialsRepository = mock(LlmCredentialsRepository.class);
        LlmProperties properties = new LlmProperties(Duration.ofSeconds(60),
                Map.of(1, Base64.getEncoder().encodeToString(KEY)));
        ChatModelFactory factory = new ChatModelFactory(properties, new AesGcmCredentialDecryptor(properties.encryptionKeys()));
        gateway = new LlmGatewayImpl(modelRepository, credentialsRepository, factory);
    }

    @Test
    void buildsClientFromModelAndCredentialsAndCachesById() throws Exception {
        UUID credentialsId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        LlmCredentials credentials = credentials(credentialsId, "http://localhost:1/v1", "sk-test", 1);
        LlmModel model = new LlmModel(modelId, credentialsId, "gpt-4", Map.of("temperature", 0.3), Instant.now());
        when(modelRepository.findById(modelId)).thenReturn(Optional.of(model));
        when(credentialsRepository.findById(credentialsId)).thenReturn(Optional.of(credentials));

        ChatModel first = gateway.chatModel(modelId);
        ChatModel second = gateway.chatModel(modelId);

        assertThat(first).isNotNull();
        assertThat(second).isSameAs(first);
        verify(modelRepository, times(1)).findById(modelId);
    }

    @Test
    void buildsDistinctClientsForModelsWithDifferentBaseUrls() {
        UUID firstCredentialsId = UUID.randomUUID();
        UUID secondCredentialsId = UUID.randomUUID();
        UUID firstModelId = UUID.randomUUID();
        UUID secondModelId = UUID.randomUUID();
        when(credentialsRepository.findById(firstCredentialsId)).thenReturn(Optional.of(
                credentials(firstCredentialsId, "http://first.example/v1", "sk-first", 1)));
        when(credentialsRepository.findById(secondCredentialsId)).thenReturn(Optional.of(
                credentials(secondCredentialsId, "http://second.example/v1", "sk-second", 1)));
        when(modelRepository.findById(firstModelId)).thenReturn(Optional.of(
                new LlmModel(firstModelId, firstCredentialsId, "gpt-4", null, Instant.now())));
        when(modelRepository.findById(secondModelId)).thenReturn(Optional.of(
                new LlmModel(secondModelId, secondCredentialsId, "gpt-4o", null, Instant.now())));

        ChatModel first = gateway.chatModel(firstModelId);
        ChatModel second = gateway.chatModel(secondModelId);

        assertThat(first).isNotSameAs(second);
        verify(modelRepository, times(1)).findById(firstModelId);
        verify(modelRepository, times(1)).findById(secondModelId);
    }

    @Test
    void danglingCredentialsDoNotBreakStartupButFailTurn() {
        UUID credentialsId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        LlmModel model = new LlmModel(modelId, credentialsId, "gpt-4", null, Instant.now());
        when(modelRepository.findById(modelId)).thenReturn(Optional.of(model));
        when(credentialsRepository.findById(credentialsId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gateway.chatModel(modelId))
                .isInstanceOf(LlmConfigurationException.class)
                .hasMessageContaining("LLM credentials not found");
    }

    @Test
    void missingModelFailsWithConfigurationException() {
        UUID modelId = UUID.randomUUID();
        when(modelRepository.findById(modelId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gateway.chatModel(modelId))
                .isInstanceOf(LlmConfigurationException.class)
                .hasMessageContaining("LLM model not found");
    }

    @Test
    void missingEncryptionKeyForVersionFails() {
        UUID credentialsId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        LlmCredentials credentials = credentials(credentialsId, "http://localhost:1/v1", "sk-test", 7);
        LlmModel model = new LlmModel(modelId, credentialsId, "gpt-4", null, Instant.now());
        when(modelRepository.findById(modelId)).thenReturn(Optional.of(model));
        when(credentialsRepository.findById(credentialsId)).thenReturn(Optional.of(credentials));

        assertThatThrownBy(() -> gateway.chatModel(modelId))
                .isInstanceOf(LlmConfigurationException.class)
                .hasMessageContaining("key_version=7");
    }

    private LlmCredentials credentials(UUID id, String baseUrl, String apiKey, int keyVersion) {
        try {
            String encrypted = AesGcmEncryption.encrypt(apiKey, KEY);
            return new LlmCredentials(id, "test", baseUrl, encrypted, keyVersion, Instant.now());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
