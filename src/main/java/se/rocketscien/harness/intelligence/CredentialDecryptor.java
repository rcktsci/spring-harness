package se.rocketscien.harness.intelligence;

/**
 * Расшифровка {@code llm_credentials.api_key_encrypted} ключом, выбранным по {@code key_version}
 * (D-M1-3). Ключи — в конфиге окружения ({@code harness.llm.encryption-keys}).
 */
public interface CredentialDecryptor {

    String decrypt(String encryptedApiKey, int keyVersion);
}
