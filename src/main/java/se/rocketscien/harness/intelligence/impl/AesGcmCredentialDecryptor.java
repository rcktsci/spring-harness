package se.rocketscien.harness.intelligence.impl;

import lombok.RequiredArgsConstructor;
import se.rocketscien.harness.common.AesGcmEncryption;
import se.rocketscien.harness.intelligence.CredentialDecryptor;
import se.rocketscien.harness.intelligence.LlmConfigurationException;

import java.util.Base64;
import java.util.Map;

/**
 * AES-GCM-расшифровка api_key ключом из конфига по {@code key_version} (D-M1-3).
 * Копия мапы ключей делается в конструкторе (иммутабельный снимок конфига).
 */
@RequiredArgsConstructor
public class AesGcmCredentialDecryptor implements CredentialDecryptor {

    private final Map<Integer, String> encryptionKeys;

    @Override
    public String decrypt(String encryptedApiKey, int keyVersion) {
        String keyBase64 = encryptionKeys.get(keyVersion);
        if (keyBase64 == null || keyBase64.isBlank()) {
            throw new LlmConfigurationException(
                    "No LLM encryption key configured for key_version=" + keyVersion);
        }
        try {
            byte[] key = Base64.getDecoder().decode(keyBase64);
            return AesGcmEncryption.decrypt(encryptedApiKey, key);
        } catch (Exception e) {
            throw new LlmConfigurationException(
                    "Failed to decrypt LLM api_key with key_version=" + keyVersion, e);
        }
    }
}
