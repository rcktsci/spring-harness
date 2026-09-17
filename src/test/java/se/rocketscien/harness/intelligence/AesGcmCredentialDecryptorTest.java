package se.rocketscien.harness.intelligence;

import org.junit.jupiter.api.Test;
import se.rocketscien.harness.common.AesGcmEncryption;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmCredentialDecryptorTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void decryptsWithKeyOfMatchingVersion() throws Exception {
        CredentialDecryptor decryptor = new AesGcmCredentialDecryptor(
                Map.of(1, Base64.getEncoder().encodeToString(KEY)));
        String encrypted = AesGcmEncryption.encrypt("sk-1", KEY);

        assertThat(decryptor.decrypt(encrypted, 1)).isEqualTo("sk-1");
    }

    @Test
    void missingKeyVersionFails() throws Exception {
        CredentialDecryptor decryptor = new AesGcmCredentialDecryptor(
                Map.of(2, Base64.getEncoder().encodeToString(KEY)));
        String encrypted = AesGcmEncryption.encrypt("sk-1", KEY);

        assertThatThrownBy(() -> decryptor.decrypt(encrypted, 1))
                .isInstanceOf(LlmConfigurationException.class)
                .hasMessageContaining("key_version=1");
    }
}
