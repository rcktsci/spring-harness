package se.rocketscien.harness.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.rocketscien.harness.api.gen.WebhooksApi;
import se.rocketscien.harness.api.gen.model.TriggerWebhookAccepted;
import se.rocketscien.harness.common.security.WebhookSignatureVerifier;

import java.util.Map;
import java.util.UUID;

/**
 * Входящие вебхуки (api-contracts §4.4). Обработка переходов/создания задач — пачка L
 * (WebhookHandlers, L.3); verifier вынесен в {@link WebhookSignatureVerifier} (common.security),
 * его расширение тестами — L.2.
 *
 * <p>D.2 несёт только wiring: HMAC-гейт capability-токена (401 signature-invalid без challenge);
 * всё за гейтом — {@code 501 not-implemented} (переходный код, план удаления — apply-notes
 * «Пачка D.2», задача L.5).
 *
 * <p>Class-level {@code @RequestMapping("/api")} — осознанный обход ограничения
 * openapi-generator (path-item `servers` не поддержан): интерфейс мапится на
 * корневой base-path {@code /api/v1}, а вебхуки обязаны жить на {@code /api/webhooks/**}
 * — без {@code /v1} (§0.5) и без JWT (отдельная SecurityFilterChain без Bearer-резолвера).
 */
@RestController
@RequestMapping("/api")
public class WebhooksController implements WebhooksApi {

    private static final String NOT_IMPLEMENTED = "D.2: stub — реализация в пачках H/I/J/K/L";

    private final WebhookSignatureVerifier signatureVerifier;

    public WebhooksController(WebhookSignatureVerifier signatureVerifier) {
        this.signatureVerifier = signatureVerifier;
    }

    @Override
    public ResponseEntity<Object> handleTaskWebhook(UUID taskId, String token,
                                                    Map<String, Object> requestBody, String source) {
        requireValidToken("task", taskId, token);
        throw new ApiNotImplementedException(NOT_IMPLEMENTED);
    }

    @Override
    public ResponseEntity<TriggerWebhookAccepted> handleTriggerWebhook(UUID triggerId, String token) {
        requireValidToken("trigger", triggerId, token);
        throw new ApiNotImplementedException(NOT_IMPLEMENTED);
    }

    private void requireValidToken(String kind, UUID entityId, String token) {
        if (!signatureVerifier.verify(kind, entityId, token)) {
            throw new SignatureInvalidException("Capability-токен не прошёл HMAC-проверку");
        }
    }
}
