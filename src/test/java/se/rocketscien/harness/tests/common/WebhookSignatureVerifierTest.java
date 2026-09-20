package se.rocketscien.harness.tests.common;

import org.junit.jupiter.api.Test;
import se.rocketscien.harness.common.security.WebhookSignatureVerifier;
import se.rocketscien.harness.config.WebhookProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Верификация HMAC capability-токенов (пачка L.2, api-contracts §4.4, спека
 * inbound-triggers «Capability-URL токен»): чистая функция
 * {@code HMAC-SHA256(secret, kind + ':' + entityId)}. Позитив: совпадение с эталонным
 * HMAC; негатив: битый токен, токен чужого kind, токен чужого id, null — все false
 * без исключений; сравнение в постоянном времени — поведение неотличимо от обычного.
 */
class WebhookSignatureVerifierTest {

    private static final String SECRET = "unit-test-secret";

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(
            new WebhookProperties(SECRET, "http://webhook-test", null));

    private final UUID entityId = UUID.randomUUID();

    @Test
    void validTokenVerifies() {
        assertThat(verifier.verify("task", entityId, hmac("task", entityId))).isTrue();
        assertThat(verifier.verify("trigger", entityId, hmac("trigger", entityId))).isTrue();
    }

    @Test
    void expectedTokenMatchesManualHmac() {
        // эталон — независимое вычисление HMAC-SHA256 без участия verifier
        assertThat(verifier.expectedToken("task", entityId)).isEqualTo(hmac("task", entityId));
        assertThat(verifier.expectedToken("trigger", entityId)).isEqualTo(hmac("trigger", entityId));
    }

    @Test
    void garbageTokenFails() {
        assertThat(verifier.verify("task", entityId, "deadbeef-not-an-hmac")).isFalse();
        assertThat(verifier.verify("task", entityId, "")).isFalse();
        assertThat(verifier.verify("task", entityId, hmac("task", entityId).toUpperCase())).isFalse();
    }

    @Test
    void truncatedAndExtendedTokensFail() {
        String valid = hmac("task", entityId);
        assertThat(verifier.verify("task", entityId, valid.substring(0, valid.length() - 2))).isFalse();
        assertThat(verifier.verify("task", entityId, valid + "00")).isFalse();
    }

    @Test
    void tokenOfOtherKindOrEntityFails() {
        // токен задачи не открывает вебхук триггера того же id (и наоборот)
        assertThat(verifier.verify("trigger", entityId, hmac("task", entityId))).isFalse();
        assertThat(verifier.verify("task", entityId, hmac("task", UUID.randomUUID()))).isFalse();
    }

    @Test
    void nullTokenFailsWithoutException() {
        assertThat(verifier.verify("task", entityId, null)).isFalse();
    }

    @Test
    void secretChangeInvalidatesTokens() {
        WebhookSignatureVerifier otherSecret = new WebhookSignatureVerifier(
                new WebhookProperties("another-secret", "http://webhook-test", null));
        String token = verifier.expectedToken("task", entityId);
        assertThat(otherSecret.verify("task", entityId, token)).isFalse();
    }

    /** Независимое эталонное вычисление {@code HMAC-SHA256(secret, kind:id)}. */
    private String hmac(String kind, UUID id) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal((kind + ":" + id).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 недоступен", e);
        }
    }
}
