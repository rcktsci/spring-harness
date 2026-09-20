package se.rocketscien.harness.task;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Дверь к входящим триггерам (контракт, architecture.md §3; data-model §6; спека
 * inbound-triggers «CRUD триггеров»): создание с пином ревизии workflow, список
 * (createdAt desc), revoke (capability-URL умирает мгновенно). Создание задач по
 * вебхуку триггера — забота API-слоя ({@code WebhookHandlers}, пачка L.3): он
 * комбинирует {@link #get}, {@code WorkflowRegistry.getRevision} и
 * {@link TaskRegistry#createTask}.
 *
 * <p>Инварианты:</p>
 * <ul>
 *   <li>{@code rev} пинится при создании — из команды или latestRev workflow; неизвестный
 *       ключ/ревизия → {@link WorkflowRevisionNotFoundException} (404 workflow-not-found).</li>
 *   <li>{@code params} валидируются {@code paramsSchema} стартового состояния пиннутой
 *       ревизии (ограниченный профиль D-58) → {@link ParamsSchemaInvalidException}.</li>
 *   <li>{@code url} — capability-URL с HMAC-токеном (stateless, без БД); revoke не меняет
 *       URL — умирает он семантикой ({@code revoked_at} → 410), проверка токена остаётся
 *       честной (HMAC валиден, но триггер отозван).</li>
 *   <li>Revoke необратим и атомарен (ревью L-3): {@code UPDATE SET revoked_at = now()
 *       WHERE id = ? AND revoked_at IS NULL} — один атомарный UPDATE с гардом, SELECT+UPDATE
 *       (гонка с параллельным revoke) исключён. Отсутствующий или уже отозванный триггер →
 *       {@link TriggerNotFoundException} (404: ревоукить нечего, capability уже мёртв).</li>
 *   <li>Список — сортировка {@code createdAt desc, id desc}, конверт-пагинация непрозрачным
 *       курсором-парой {@code (created_at, id)} (стиль {@code TaskRegistry.list}).</li>
 * </ul>
 */
public interface TriggerRegistry {

    /**
     * Создание триггера с пином ревизии.
     *
     * @throws WorkflowRevisionNotFoundException ключ или ревизия workflow не найдены
     * @throws ParamsSchemaInvalidException      params не прошли paramsSchema пиннутой ревизии
     */
    Trigger create(CreateTriggerCommand command);

    /** Триггер по id.
     * @throws TriggerNotFoundException не найден */
    Trigger get(UUID id);

    /**
     * Список триггеров: {@code ownerUserId} — фильтр «mine» (готовый id из JWT, null — все);
     * {@code cursor} — opaque-пара (created_at, id); {@code limit} >= 1 (верхняя граница —
     * API-слой, {@code limits.page}).
     *
     * @throws InvalidCursorException курсор не декодируется
     */
    TriggerPage list(TriggerSearchCriteria criteria) throws InvalidCursorException;

    /**
     * Revoke: атомарный {@code UPDATE SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL}
     * (ревью L-3 — SELECT+UPDATE неатомарен); URL умирает мгновенно.
     *
     * @throws TriggerNotFoundException триггер не найден либо уже отозван (404 — ревоукить
     *                                  нечего; повторный revoke — тоже 404)
     */
    void revoke(UUID id);

    /** Команда создания; {@code rev == null} — пин latestRev workflow. */
    record CreateTriggerCommand(UUID ownerUserId, String name, String workflowKey, Integer rev,
                                Map<String, Object> params, Collection<String> tags) {
    }

    /** Критерии списка; {@code cursor} — opaque; {@code mine} выражен готовым {@code ownerUserId}. */
    record TriggerSearchCriteria(UUID ownerUserId, String cursor, int limit) {
    }

    /** Страница списка: {@code nextCursor} — null, когда страниц больше нет. */
    record TriggerPage(List<Trigger> items, String nextCursor) {
    }
}
