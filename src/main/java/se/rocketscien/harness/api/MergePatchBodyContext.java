package se.rocketscien.harness.api;

import tools.jackson.databind.JsonNode;

/**
 * Сырое JSON-дерево тела merge-patch текущего запроса (RFC 7396): связка «converter ↔ контроллер».
 * Типизированный DTO теряет различие «поле отсутствует»/«поле = null» (обе — null в String), а
 * семантика патча на нём построена; converter для {@code application/merge-patch+json} захватывает
 * дерево до связывания, контроллер читает. Очистка — {@link MergePatchBodyFilter} (finally).
 */
public final class MergePatchBodyContext {

    private static final ThreadLocal<JsonNode> BODY = new ThreadLocal<>();

    private MergePatchBodyContext() {
    }

    public static void capture(JsonNode body) {
        BODY.set(body);
    }

    /** Дерево текущего merge-patch-тела; null — конвертер не вызывался в этом запросе. */
    public static JsonNode current() {
        return BODY.get();
    }

    public static void clear() {
        BODY.remove();
    }
}
