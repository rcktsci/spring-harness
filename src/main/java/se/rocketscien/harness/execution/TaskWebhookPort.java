package se.rocketscien.harness.execution;

import java.util.Map;
import java.util.UUID;

/**
 * Порт приёма payload'а вебхука задачей (api-contracts §4.4; пачка L.3): валидация
 * {@code payloadSchema} состояния (ограниченный профиль D-58) и CAS-переход NEXT/ERROR —
 * работа исполнителя WAIT_WEBHOOK (execution.impl, {@code WaitWebhookStateExecutor});
 * HTTP-слой ({@code api}) зовёт только этот контракт — чужой {@code .impl} не импортируется
 * (architecture.md §2).
 *
 * <p>Идемпотентность по построению: повторная доставка после перехода и CAS-гонки
 * возвращают {@code NOT_WAITING} (HTTP-слой отвечает 409 task-not-waiting-webhook).</p>
 */
public interface TaskWebhookPort {

    /**
     * Приём payload'а задачей в WAIT_WEBHOOK.
     *
     * @param taskId          задача
     * @param payload         распарсенный JSON-тело вебхука
     * @param source          метка источника (query {@code source=…}); null — без метки
     * @param payloadByteSize размер тела в байтах <b>как получено по проводу</b> — считает
     *                        HTTP-слой (ревью L-5: без повторной сериализации в executor'е;
     *                        D-29 — сводка по фактическому телу)
     * @return исход обработки (HTTP-маппинг: NEXT/ERROR → 202, NOT_WAITING → 409)
     */
    WebhookOutcome onWebhookArrived(UUID taskId, Map<String, Object> payload, String source,
                                    int payloadByteSize);

    /** Исход приёма вебхука (см. спеку inbound-triggers «Webhook задачи»). */
    enum WebhookOutcome {
        /** Payload прошёл payloadSchema — CAS-переход NEXT выполнен. */
        ACCEPTED_NEXT,
        /** Payload вне payloadSchema — CAS-переход ERROR с reason {validationErrors}. */
        ACCEPTED_ERROR,
        /** Задача вне WAIT_WEBHOOK (или CAS-промах) — HTTP-слой ответит 409. */
        NOT_WAITING
    }
}
