package se.rocketscien.harness.tests.intelligence;

import lombok.SneakyThrows;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import se.rocketscien.harness.common.AesGcmEncryption;

import javax.crypto.AEADBadTagException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AesGcmEncryptionTest {

    private final byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    @SneakyThrows
    void roundtripRestoresPlaintext() {
        String encrypted = AesGcmEncryption.encrypt("sk-secret", key);

        assertThat(encrypted).isNotEqualTo("sk-secret");
        assertThat(AesGcmEncryption.decrypt(encrypted, key)).isEqualTo("sk-secret");
    }

    @Test
    @SneakyThrows
    void decryptWithWrongKeyFails() {
        String encrypted = AesGcmEncryption.encrypt("sk-secret", key);

        Assertions.assertThatThrownBy(() -> AesGcmEncryption.decrypt(
                        encrypted, "ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(AEADBadTagException.class);
    }
}
