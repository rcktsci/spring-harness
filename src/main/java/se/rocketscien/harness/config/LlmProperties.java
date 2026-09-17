package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * Явный таймаут LLM-вызовов в каждой сборке options (защита от невидимых дефолтов #6915) и ключи
 * AES-GCM для расшифровки api_key по версии (D-M1-3): {@code key_version} из {@code llm_credentials}
 * → base64-ключ.
 */
@ConfigurationProperties(prefix = "harness.llm")
public record LlmProperties(Duration timeout, Map<Integer, String> encryptionKeys) {

    public LlmProperties {
        encryptionKeys = encryptionKeys == null ? Map.of() : Map.copyOf(encryptionKeys);
    }
}
