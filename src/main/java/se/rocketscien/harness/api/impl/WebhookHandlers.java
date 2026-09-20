package se.rocketscien.harness.api.impl;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.WebUtils;
import se.rocketscien.harness.api.SignatureInvalidException;
import se.rocketscien.harness.api.gen.model.TriggerWebhookAccepted;
import se.rocketscien.harness.common.security.WebhookSignatureVerifier;
import se.rocketscien.harness.execution.TaskWebhookPort;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskNotWaitingWebhookException;
import se.rocketscien.harness.task.TaskNotFoundException;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.TaskStateKind;
import se.rocketscien.harness.task.Trigger;
import se.rocketscien.harness.task.TriggerRegistry;
import se.rocketscien.harness.task.TriggerRevokedException;
import se.rocketscien.harness.workflow.WorkflowRegistry;
import se.rocketscien.harness.workflow.WorkflowRegistry.WorkflowRevision;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

/**
 * Обработка входящих вебхуков (api-contracts §4.4, пачка L.3; спека inbound-triggers).
 * Живёт в {@code api.impl} — HTTP-логика; доменные шаги делегирует контрактам
 * {@link TriggerRegistry}/{@link TaskRegistry} (task) и {@link WorkflowRegistry} (workflow),
 * переход WAIT_WEBHOOK — порту {@link TaskWebhookPort} (execution.impl наружу не выдаётся,
 * architecture.md §2).
 *
 * <p>HMAC-гейт — {@code WebhookTokenFilter} (ревью L-1): проверка токена ДО диспетчеризации
 * и HttpMessageConverter'ов — кривой токен даёт 401 signature-invalid при любом теле.
 * Контроллерная проверка ниже оставлена как defense-in-depth (прямой вызов/мискофигурация).
 * Тело задачи конвертером читается уже после гейта; {@code payloadByteSize} для
 * {@code payloadSummary} — размер по проводу из кэша фильтра (ревью L-5, D-29), fallback —
 * повторная сериализация (не-HTTP-контекст).</p>
 *
 * <p>Webhook задачи: verify → задача существует и в WAIT_WEBHOOK (иначе 409
 * task-not-waiting-webhook; несуществующая задача — тоже 409, отклонение dev D-пачки №6)
 * → payloadSchema-валидация и CAS-переход на исполнителе состояния (NEXT/ERROR — 202;
 * результат виден в SSE задачи).</p>
 *
 * <p>Webhook триггера: verify → отозван? → 410 trigger-revoked → создание задачи на пиннутой
 * ревизии с params/tags/owner триггера (author NULL — агент не указан; AGENT-старт
 * раскачивается EVENT-wake'ом из {@code TaskRegistry.createTask} — bootstrap STATE-сессии
 * и Turn без отдельного кода здесь) → 202 {@code { taskId }}.</p>
 */
@Component
@RequiredArgsConstructor
public class WebhookHandlers {

    private final WebhookSignatureVerifier signatureVerifier;
    private final TaskRegistry tasks;
    private final TriggerRegistry triggers;
    private final WorkflowRegistry workflows;
    private final TaskWebhookPort webhookExecutor;
    private final ObjectMapper objectMapper;

    /** {@code POST /api/webhooks/tasks/{taskId}/{token}?source=} — переход WAIT_WEBHOOK. */
    public ResponseEntity<Object> handleTaskWebhook(UUID taskId, String token,
                                                    Map<String, Object> body, String source) {
        requireValidToken("task", taskId, token);
        Task task;
        try {
            task = tasks.get(taskId);
        } catch (TaskNotFoundException e) {
            // Отклонение dev D-пачки №6: несуществующая задача → 409 (без отдельного 404)
            throw new TaskNotWaitingWebhookException(
                    "Задача %s не существует или вне WAIT_WEBHOOK".formatted(taskId));
        }
        if (task.currentStateKind() != TaskStateKind.WAIT_WEBHOOK
                || task.statusProjection().isTerminal()) {
            throw new TaskNotWaitingWebhookException(
                    "Задача %s вне WAIT_WEBHOOK (текущее состояние '%s')"
                            .formatted(taskId, task.currentState()));
        }
        TaskWebhookPort.WebhookOutcome outcome = webhookExecutor.onWebhookArrived(
                taskId, body, source, payloadByteSize(body));
        if (outcome == TaskWebhookPort.WebhookOutcome.NOT_WAITING) {
            // CAS-промах (ретрай/гонка/stop) — идемпотентный отказ без 202
            throw new TaskNotWaitingWebhookException(
                    "Задача %s уже вне WAIT_WEBHOOK (переход выполнен ранее)".formatted(taskId));
        }
        // NEXT и ERROR — оба «принято к обработке» (202); исход виден в истории/SSE задачи
        return ResponseEntity.accepted().build();
    }

    /** {@code POST /api/webhooks/triggers/{triggerId}/{token}} — создание задачи. */
    public ResponseEntity<TriggerWebhookAccepted> handleTriggerWebhook(UUID triggerId, String token) {
        requireValidToken("trigger", triggerId, token);
        Trigger trigger = triggers.get(triggerId);
        if (trigger.revoked()) {
            throw new TriggerRevokedException(
                    "Триггер %s отозван — capability-URL мёртв".formatted(triggerId));
        }
        // Пин триггера (workflow_key + rev) → ревизия; ревизии иммутабельны, отсутствие —
        // повреждённое состояние (защитный 404 workflow-not-found)
        WorkflowRevision revision = workflows.getRevision(trigger.workflowKey(), trigger.rev());
        Task task = tasks.createTask(new TaskRegistry.CreateTaskCommand(
                revision.id(),
                trigger.name(),
                "Создано триггером '%s' по вебхуку".formatted(trigger.name()),
                null,
                trigger.ownerUserId(),
                null,
                trigger.params(),
                trigger.tags()
        ));
        return ResponseEntity.accepted().body(new TriggerWebhookAccepted(task.id()));
    }

    private void requireValidToken(String kind, UUID entityId, String token) {
        if (!signatureVerifier.verify(kind, entityId, token)) {
            throw new SignatureInvalidException("Capability-токен не прошёл HMAC-проверку");
        }
    }

    /**
     * Размер тела в байтах как получено по проводу (ревью L-5): {@code WebhookTokenFilter}
     * оборачивает запрос в {@link ContentCachingRequestWrapper} — конвертер уже прочитал
     * тело, кэш полон. Вне HTTP-контекста (защитная ветка) — размер повторной сериализации.
     */
    private int payloadByteSize(Map<String, Object> body) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            ContentCachingRequestWrapper cached =
                    WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);
            if (cached != null) {
                return cached.getContentAsByteArray().length;
            }
        }
        return objectMapper.writeValueAsBytes(body).length;
    }
}
