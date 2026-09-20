package se.rocketscien.harness.api;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.rocketscien.harness.api.gen.WebhooksApi;
import se.rocketscien.harness.api.gen.model.TriggerWebhookAccepted;
import se.rocketscien.harness.api.impl.WebhookHandlers;

import java.util.Map;
import java.util.UUID;

/**
 * Входящие вебхуки (api-contracts §4.4, пачка L.3): контроллер на сгенерированном
 * интерфейсе — тонкая делегация в {@link WebhookHandlers} (api.impl: HMAC-гейт, гейт
 * WAIT_WEBHOOK, создание задачи триггером).
 *
 * <p>Class-level {@code @RequestMapping("/api")} — осознанный обход ограничения
 * openapi-generator (path-item `servers` не поддержан): интерфейс мапится на
 * корневой base-path {@code /api/v1}, а вебхуки обязаны жить на {@code /api/webhooks/**}
 * — без {@code /v1} (§0.5) и без JWT (отдельная SecurityFilterChain без Bearer-резолвера).
 * Тело вебхука задачи обязательно ({@code consumes application/json}) — 415/422 отдаёт
 * стандартный конвейер до входа в обработчик.</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WebhooksController implements WebhooksApi {

    private final WebhookHandlers handlers;

    @Override
    public ResponseEntity<Object> handleTaskWebhook(UUID taskId, String token,
                                                    Map<String, Object> requestBody, String source) {
        return handlers.handleTaskWebhook(taskId, token, requestBody, source);
    }

    @Override
    public ResponseEntity<TriggerWebhookAccepted> handleTriggerWebhook(UUID triggerId, String token) {
        return handlers.handleTriggerWebhook(triggerId, token);
    }
}
