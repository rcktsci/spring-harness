package se.rocketscien.harness.session;

import java.util.List;
import java.util.Map;
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
 * </ul>
 */
public interface SessionStore {

    Session createFreeSession(UUID ownerUserId, String agentKey, Integer agentRevision, String title);

    AppendedEvent appendEvent(UUID sessionId, MessageKind kind, UUID authorUserId, Map<String, Object> payload);

    /**
     * Видимые события = журнал минус {@code COMPACT.covers} позднейших COMPACT-событий:
     * COMPACT сам виден, если не покрыт более поздним COMPACT. Payload COMPACT —
     * {@code {"covers": [{"from": <seq>, "to": <seq>}, ...], "summary": "..."}} —
     * covers в seq-диапазонах в пределах сессии (D-44; оригиналы по seq, не по ULID).
     * Порядок — по возрастанию seq.
     */
    List<SessionMessageEntity> renderVisible(UUID sessionId);

    /**
     * Результат дописи: присвоенный {@code seq} и ULID события.
     */
    record AppendedEvent(long seq, String ulid) {
    }
}
