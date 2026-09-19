package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Вебхуки (api-contracts §4.4): секрет HMAC capability-токенов —
 * {@code token = HMAC-SHA256(secret, kind + ':' + entityId)}. Секрет — env в рантайме VM;
 * dev-дефолт в application.yml не для прода. Плюс параметры сводки payload в
 * {@code reason_jsonb} (inbound-triggers «Threat-model и логирование», D-29: полное тело
 * вебхука в истории не хранится).
 */
@ConfigurationProperties(prefix = "harness.webhook")
public record WebhookProperties(String secret, String baseUrl, PayloadSummary payloadSummary) {

    public WebhookProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException(
                    "harness.webhook.secret обязателен — capability-URL вебхуков без него небезопасны");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "harness.webhook.base-url обязателен — база capability-URL вебхуков (api-contracts §4.4)");
        }
    }

    /**
     * Параметры {@code payloadSummary}: при превышении {@code byte-size-limit} сводка
     * ужимается до {@code {byteSize, truncated}} — без перечня топ-ключей.
     */
    public record PayloadSummary(Integer byteSizeLimit) {

        /** Лимит байт; null-лимит в конфиге = усечения нет (всякое тело компактно). */
        public int limitOrMax() {
            return byteSizeLimit == null ? Integer.MAX_VALUE : byteSizeLimit;
        }
    }
}
