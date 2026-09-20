package se.rocketscien.harness.task;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Проекция строки {@code trigger} (data-model §6) для домена и API-слоя: {@code rev} — пин
 * ревизии workflow на момент создания, {@code url} — capability-URL вебхука
 * ({@code base + /api/webhooks/triggers/{id}/{HMAC}}, токен вычисляется stateless).
 */
public record Trigger(UUID id, String name, String workflowKey, int rev, Map<String, Object> params,
                      List<String> tags, UUID ownerUserId, Instant revokedAt, Instant createdAt,
                      URI url) {

    /** {@code true} — триггер отозван, capability-URL мёртв (вебхук → 410 trigger-revoked). */
    public boolean revoked() {
        return revokedAt != null;
    }
}
