package se.rocketscien.harness.session;

import java.util.UUID;

/**
 * STATE-сессии пар «задача × состояние» (спека session-store, task-engine «STATE-сессии»):
 * UPSERT по PARTIAL UNIQUE {@code (task_id, state_code) WHERE kind='STATE'} — существует →
 * резюм (подхватить), иначе → создать атомарно.
 *
 * <p>Атомарность создания (execution-model §7.2): в одной транзакции insert сессии +
 * seed-{@code SYSTEM}-сообщение + {@code last_seq} — состояния «сессия есть, seed не записан»
 * не существует. Ревизия агента пинится на момент первого входа; повторный вход возвращает
 * существующую сессию с изначально пиннутой ревизией (переданный {@code agentRevisionId}
 * используется только при создании).</p>
 */
public interface StateSessionService {

    /**
     * Найти или атомарно создать STATE-сессию пары {@code (taskId, stateCode)}.
     *
     * @param taskId          задача-владелец (неизвестная — {@link IllegalStateException}: движок
     *                        зовёт по живой задаче)
     * @param stateCode       code AGENT-состояния из графа пиннутой ревизии
     * @param agentRevisionId ревизия агента для пина при создании (на резюме не влияет)
     * @return созданная или резюмированная сессия ({@code kind=STATE})
     */
    Session findOrCreate(UUID taskId, String stateCode, UUID agentRevisionId);
}
