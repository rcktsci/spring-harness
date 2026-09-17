package se.rocketscien.harness.intelligence;

import org.junit.jupiter.api.Test;
import se.rocketscien.harness.common.AesGcmEncryption;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AesGcmEncryptionTest {

    private final byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void roundtripRestoresPlaintext() throws Exception {
        String encrypted = AesGcmEncryption.encrypt("sk-secret", key);

        assertThat(encrypted).isNotEqualTo("sk-secret");
        assertThat(AesGcmEncryption.decrypt(encrypted, key)).isEqualTo("sk-secret");
    }

    @Test
    void decryptWithWrongKeyFails() throws Exception {
        String encrypted = AesGcmEncryption.encrypt("sk-secret", key);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AesGcmEncryption.decrypt(
                        encrypted, "ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(javax.crypto.AEADBadTagException.class);
    }
}
