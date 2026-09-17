package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import se.rocketscien.harness.intelligence.AesGcmCredentialDecryptor;
import se.rocketscien.harness.intelligence.CredentialDecryptor;

/**
 * Расшифровка api_key конфигурируемым ключом (D-M1-3). Изолирована в intelligence, наружу —
 * интерфейс {@link CredentialDecryptor}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    @Bean
    public CredentialDecryptor credentialDecryptor(LlmProperties properties) {
        return new AesGcmCredentialDecryptor(properties.encryptionKeys());
    }
}
