package se.rocketscien.harness.intelligence;

import se.rocketscien.harness.common.AesGcmEncryption;

import java.util.Base64;
import java.util.Map;

/**
 * AES-GCM-расшифровка api_key ключом из конфига по {@code key_version} (D-M1-3).
 */
public class AesGcmCredentialDecryptor implements CredentialDecryptor {

    private final Map<Integer, String> encryptionKeys;

    public AesGcmCredentialDecryptor(Map<Integer, String> encryptionKeys) {
        this.encryptionKeys = Map.copyOf(encryptionKeys);
    }

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
