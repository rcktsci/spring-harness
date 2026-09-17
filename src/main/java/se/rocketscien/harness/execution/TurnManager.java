package se.rocketscien.harness.execution;

import java.util.UUID;

/**
 * Контракт пробуждения и отмены (D-M1-1): tryStart — попытка запуска Turn'а (EVENT и POLL
 * контуры), занятый {@code sess-{id}}-лок или отсутствие непотреблённых событий — no-op.
 */
public interface TurnManager {

    /**
     * Немедленная попытка запуска Turn'а на виртуальном потоке (D-M1-5); идемпотентна
     * благодаря лока сессии.
     */
    void tryStart(UUID sessionId);

    /**
     * Команда stop: {@code cancel_requested} в БД + немедленное прерывание активного Turn'а
     * этого процесса (in-flight bash). Идемпотентна; без активного Turn'а — без последующего
     * эффекта (спека agent-turn).
     */
    void requestStop(UUID sessionId);
}
