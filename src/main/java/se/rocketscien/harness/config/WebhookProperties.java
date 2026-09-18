package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Вебхуки (api-contracts §4.4): секрет HMAC capability-токенов —
 * {@code token = HMAC-SHA256(secret, kind + ':' + entityId)}. Секрет — env в рантайме VM;
 * dev-дефолт в application.yml не для прода.
 */
@ConfigurationProperties(prefix = "harness.webhook")
public record WebhookProperties(String secret) {

    public WebhookProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException(
                    "harness.webhook.secret не задан — capability-URL вебхуков не защищены");
        }
    }
}
