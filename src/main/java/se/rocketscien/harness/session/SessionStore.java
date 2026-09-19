package se.rocketscien.harness.session;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Единственная дверь к сессиям и их append-only журналу (контракт, D-M1-1).
 *
 * <p>Инварианты (data-model §5, §7; спека session-store):</p>
 * <ul>
 *   <li>Ревизия агента пинится в момент создания ({@code agentRev} явно или последняя по
 *       {@code agentKey}) и не меняется после; неизвестный {@code agentKey} или несуществующая
 *       ревизия → {@link AgentNotFoundException}.</li>
 *   <li>Созданная сессия начинается с {@code lastSeq = 0}, {@code lastConsumedSeq = 0}.</li>
 *   <li>Допись: {@code seq} монотонный бездырочный в пределах сессии — транзакционный row-lock
 *       строки сессии ({@code UPDATE session SET last_seq = last_seq + 1 ... RETURNING}),
 *       лок {@code sess-{id}} НЕ используется — USER-допись работает и при активном Turn'е
 *       (D-M1-4). Идентификатор события — ULID. {@code lastSeq} обновляется в той же транзакции,
 *       {@code lastConsumedSeq} допись не меняет (её двигает только завершение Turn'а).
 *       Допись в несуществующую сессию → {@link SessionNotFoundException}.</li>
 *   <li>События только дописываются: UPDATE/DELETE {@code session_message} невозможны через этот
 *       контракт (см. {@link SessionMessageRepository}).</li>
 *   <li>Допись уведомляет {@link SessionEventListener}'ов (broadcaster) после коммита.</li>
 * </ul>
 */
public interface SessionStore {

    Session createFreeSession(UUID ownerUserId, String agentKey, Integer agentRevision, String title);

    AppendedEvent appendEvent(UUID sessionId, MessageKind kind, UUID authorUserId, Map<String, Object> payload);

    /**
     * Допись с фиксацией {@code tokens} (usage LLM-вызова; data-model §5 — учёт стоимости).
     */
    AppendedEvent appendEvent(UUID sessionId, MessageKind kind, UUID authorUserId, Map<String, Object> payload,
                              Integer tokens);

    Optional<Session> findSession(UUID sessionId);

    /**
     * Eligible-скан POLL (execution-model §1): {@code last_seq > last_consumed_seq}, index-only
     * по partial index; занятость лока проверяется самим tryStart, не этим методом.
     */
    List<UUID> findEligibleSessionIds();

    List<UUID> findAllSessionIds();

    /** {@code cancel_requested = true}; идемпотентно, неизвестная сессия — no-op (спека agent-turn). */
    void requestCancel(UUID sessionId);

    /** Сброс {@code cancel_requested} на старте нового Turn'а (спека agent-turn). */
    void resetCancelRequested(UUID sessionId);

    /**
     * Атомарное завершение Turn'а (любой исход) — потребление по D-45: {@code consumedSeq} —
     * watermark виденного (макс. seq, вошедший в отрендеренный контекст/порождённый Turn'ом),
     * {@code last_consumed_seq := GREATEST(last_consumed_seq, consumedSeq)}; фиксация исхода;
     * сброс {@code cancel_requested}. События после watermark остаются непотреблёнными — их
     * поднимет EVENT/POLL новым Turn'ом.
     * <ul>
     *   <li>COMPLETED: watermark = финальный ASSISTANT (сообщение Turn'а);
     *       USER, дописанный в микро-окне, остаётся непотреблённым;</li>
     *   <li>FAILED: watermark = SYSTEM-событие причины — батч потреблён, retry-шторма POLL нет;</li>
     *   <li>CANCELLED: watermark = последний рендер — собственные недописанные результаты и
     *       свежий USER остаются непотреблёнными и поднимают новый Turn.</li>
     * </ul>
     */
    void finishTurn(UUID sessionId, TurnOutcome outcome, long consumedSeq);

    /** Ревизия агента → данные для исполнения Turn'а (промпт, инструменты, модель). */
    AgentRuntime agentRuntime(UUID agentRevisionId);

    /**
     * Сессии с зависшими {@code TOOL_CALL} (без парного финального {@code TOOL_RESULT} по
     * {@code payload.callId}) — вход рестарт-скана (execution-model §1).
     */
    List<UUID> findSessionIdsWithPendingToolCalls();

    /** Зависшие {@code TOOL_CALL}-события конкретной сессии (без финального результата). */
    List<SessionMessageEntity> findPendingToolCalls(UUID sessionId);

    /**
     * Виды событий незапрошенного батча {@code (afterSeq, …]} — источник инструкции текущего
     * Turn'а (D-59): содержит USER → Turn поднят пользовательским сообщением; только
     * TOOL_RESULT → продолжение после инструмента; иначе — системное событие (seed и пр.).
     */
    List<MessageKind> findPendingKinds(UUID sessionId, long afterSeq);

    /**
     * Видимые события = журнал минус {@code COMPACT.covers} позднейших COMPACT-событий:
     * COMPACT сам виден, если не покрыт более поздним COMPACT. Payload COMPACT —
     * {@code {"covers": [{"from": <seq>, "to": <seq>}, ...], "summary": "..."}} —
     * covers в seq-диапазонах в пределах сессии (D-44; оригиналы по seq, не по ULID).
     * Порядок — по возрастанию seq.
     */
    List<SessionMessageEntity> renderVisible(UUID sessionId);

    /**
     * Поиск сессий для списка (api-contracts §2): сортировка {@code lastActivityAt desc,
     * id desc}; конверт-пагинация непрозрачным курсором (значение {@code nextCursor}
     * предыдущей страницы), фильтры по владельцу/роду/подстроке title. Все фильтры,
     * кроме {@code limit}, опциональны. Ограничение страницы применяется как есть
     * (верхняя граница — забота вызывающего, конфиг API-слоя).
     *
     * @throws InvalidCursorException курсор не декодируется — ошибка клиента (422)
     */
    SessionSearchResult searchSessions(SessionSearchCriteria criteria) throws InvalidCursorException;

    /** Переименование (merge-patch title); {@code null} — очистить. Неизвестная сессия → {@link SessionNotFoundException}. */
    void renameSession(UUID sessionId, String newTitle);

    /** Каталог агентов: последняя ревизия каждого ключа, порядок по ключу (api-contracts §1). */
    List<AgentRevisionSummary> agentCatalog();

    /** Ключ/ревизия агента для пиннутых ревизий (заполнение SessionDto.agent); отсутствующие не входят. */
    Map<UUID, AgentRevisionSummary> agentSummaries(Collection<UUID> revisionIds);

    /**
     * Результат дописи: присвоенный {@code seq} и ULID события.
     */
    record AppendedEvent(long seq, String ulid) {
    }

    /**
     * Критерии поиска сессий; {@code cursor} — непрозрачный курсор (opaque), интерпретируется
     * только реализацией. {@code limit} >= 1.
     */
    record SessionSearchCriteria(
            UUID ownerUserId,
            SessionKind kind,
            String titleContains,
            String cursor,
            int limit
    ) {
    }

    /** Страница поиска: {@code nextCursor} — null, когда страниц больше нет. */
    record SessionSearchResult(List<Session> items, String nextCursor) {
    }

    /** Выжимка ревизии агента для публичных DTO (SessionDto.agent, GET /agents). */
    record AgentRevisionSummary(
            UUID revisionId,
            String agentKey,
            int rev,
            String name,
            String description
    ) {
    }

    /**
     * Данные ревизии агента, необходимые исполняющему контуру: системный промпт, декларации
     * инструментов/разрешений (jsonb как есть), модель LLM.
     */
    record AgentRuntime(
            UUID revisionId,
            String agentKey,
            int rev,
            String rolePrompt,
            Map<String, Object> tools,
            Map<String, Object> permissions,
            UUID llmModelId
    ) {
    }
}
