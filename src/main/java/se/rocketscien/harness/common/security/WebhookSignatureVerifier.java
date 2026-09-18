package se.rocketscien.harness.common.security;

import org.springframework.stereotype.Component;
import se.rocketscien.harness.config.WebhookProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Верификация HMAC capability-токенов вебхуков (api-contracts §4.4):
 * {@code token = HMAC-SHA256(secret, kind + ':' + entityId)} — чистая функция
 * над конфиг-секретом; для задач — проверка без БД (stateless), для триггеров
 * вызовущий дополнительно смотрит {@code revoked_at} (L.3).
 *
 * <p>Сравнение — в постоянном времени ({@link MessageDigest#isEqual}): без утечки
 * длины/префикса совпадения. Кинутые {@code kind} — "task" | "trigger" (контракт §4.4).
 */
@Component
public class WebhookSignatureVerifier {

    private final WebhookProperties webhookProperties;

    public WebhookSignatureVerifier(WebhookProperties webhookProperties) {
        this.webhookProperties = webhookProperties;
    }

    /**
     * {@code true} — токен совпал с ожидаемым HMAC; {@code false} — нет/null токен.
     * Расширение тестами на позитивные/негативные кейсы — пачка L (задача L.2).
     */
    public boolean verify(String kind, UUID entityId, String token) {
        if (token == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedToken(kind, entityId).getBytes(StandardCharsets.US_ASCII),
                token.getBytes(StandardCharsets.US_ASCII));
    }

    /** Ожидаемый hex-токен для {@code kind:entityId} — переиспользуется при выдаче URL (L.1). */
    public String expectedToken(String kind, UUID entityId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    webhookProperties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal((kind + ":" + entityId).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 недоступен", e);
        }
    }
}
