package se.rocketscien.harness.execution.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.common.jsonschema.JsonSchemaError;
import se.rocketscien.harness.common.jsonschema.LimitedJsonSchemaValidator;
import se.rocketscien.harness.config.WebhookProperties;
import se.rocketscien.harness.execution.TaskWebhookPort;
import se.rocketscien.harness.execution.impl.TaskGraphReader.GraphState;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.TaskStateKind;
import se.rocketscien.harness.task.Transition;
import se.rocketscien.harness.task.TransitionKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Исполнитель системного состояния WAIT_WEBHOOK — пассивный (workflow-domain §3): метод
 * вызывается webhook-handler'ом (пачка L.3, HTTP-слой) при валидном
 * {@code POST /api/webhooks/tasks/{taskId}/{token}}. Валидация payload по ограниченному
 * профилю {@code payloadSchema} (D-58): прошла → переход NEXT, нет → переход ERROR (reason:
 * validationErrors). Повторная доставка/ретрай — no-op по построению: CAS {@code current_state},
 * второй вызов промахивается; вне WAIT_WEBHOOK — {@code NOT_WAITING} (HTTP-слой ответит 409).
 *
 * <p>В reason хранится только {@code payloadSummary} ({topKeys, byteSize} либо
 * {@code {byteSize, truncated}} сверх лимита {@code harness.webhook.payload-summary.byte-size-limit})
 * — полное тело вебхука в истории не живёт (D-29, inbound-triggers «Threat-model»). Оба поля
 * reason ({@code source}, {@code payload}) опциональны — null-значения в reason не попадают.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WaitWebhookStateExecutor implements TaskWebhookPort {

    private final TaskRegistry taskRegistry;
    private final TaskGraphReader graphs;
    private final TaskEngine taskEngine;
    private final WebhookProperties webhookProperties;

    /**
     * Приём payload'а вебхука задачей.
     *
     * @param taskId          задача
     * @param payload         распарсенный JSON-тело вебхука
     * @param source          метка источника (query {@code source=…}); null — без метки
     * @param payloadByteSize размер тела в байтах как получено по проводу (считает HTTP-слой —
     *                        ревью L-5: без повторной сериализации; D-29)
     * @return исход обработки (для маппинга HTTP-ответов webhook-handler'ом)
     */
    @Override
    public WebhookOutcome onWebhookArrived(UUID taskId, Map<String, Object> payload, String source,
                                           int payloadByteSize) {
        Task task = taskRegistry.get(taskId);
        if (task.currentStateKind() != TaskStateKind.WAIT_WEBHOOK
                || task.statusProjection().isTerminal()) {
            return WebhookOutcome.NOT_WAITING;
        }
        GraphState state = graphs.state(graphs.loadForTask(task), task.currentState());
        Map<String, Object> schema = state.raw().get("payloadSchema") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw
                : null;
        List<JsonSchemaError> errors = LimitedJsonSchemaValidator.validate(payload, schema, "/payload");
        if (!errors.isEmpty()) {
            Map<String, Object> reason = webhookReason(source);
            reason.put("validationErrors", errors.stream()
                    .map(error -> Map.of("pointer", error.pointer(), "rule", error.rule(),
                            "message", error.message()))
                    .toList());
            return apply(task, TransitionKind.ERROR, reason) ? WebhookOutcome.ACCEPTED_ERROR
                    : WebhookOutcome.NOT_WAITING;
        }

        Map<String, Object> reason = webhookReason(source);
        reason.put("payloadSummary", summary(payload, payloadByteSize));
        return apply(task, TransitionKind.NEXT, reason) ? WebhookOutcome.ACCEPTED_NEXT
                : WebhookOutcome.NOT_WAITING;
    }

    /** База reason вебхука; nullable {@code source} в reason не попадает (Map.copyOf-безопасность). */
    private static Map<String, Object> webhookReason(String source) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("kind", "webhook");
        if (source != null) {
            reason.put("source", source);
        }
        return reason;
    }

    /** CAS-переход по ребру kind из текущего состояния; CAS-промах (ретрай/гонка) → false. */
    private boolean apply(Task task, TransitionKind kind, Map<String, Object> reason) {
        var edge = graphs.edgeByKind(graphs.loadForTask(task), task.currentState(), kind);
        if (edge.isEmpty()) {
            log.error("WAIT_WEBHOOK {}: исход {}, но ребро {} из '{}' отсутствует — пропуск",
                    task.id(), kind, kind, task.currentState());
            return false;
        }
        Transition applied = taskEngine.processTaskTransition(
                task.id(), task.currentState(), edge.get().to(), kind, reason);
        if (applied != null) {
            log.info("WAIT_WEBHOOK {}: payload принят ({}) — переход {}", task.id(), kind, edge.get().to());
        }
        return applied != null;
    }

    /**
     * Сводка payload (design.md пачки L: дефолт {topKeys, byteSize}); сверх лимита
     * {@code byte-size-limit} — только {byteSize, truncated} (полное тело не хранится, D-29).
     * {@code byteSize} — размер тела по проводу от HTTP-слоя (ревью L-5), без повторной
     * сериализации распарсенной мапы.
     */
    private Map<String, Object> summary(Map<String, Object> payload, int payloadByteSize) {
        int limit = webhookProperties.payloadSummary() == null
                ? Integer.MAX_VALUE
                : webhookProperties.payloadSummary().limitOrMax();
        Map<String, Object> summary = new LinkedHashMap<>();
        if (payload != null && payloadByteSize <= limit) {
            summary.put("topKeys", payload.keySet().stream().toList());
        }
        summary.put("byteSize", payloadByteSize);
        if (payloadByteSize > limit) {
            summary.put("truncated", true);
        }
        return summary;
    }
}
