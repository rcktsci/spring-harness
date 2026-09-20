package se.rocketscien.harness.session;

/**
 * Рантайм-статус сессии в памяти (api-contracts §2/§3: снапшот {@code session.status} при коннекте).
 * {@code PARKED_ASYNC} — с M3 (ожидание поздних результатов async-инструментов, D-60);
 * {@code PARKED_CLIENT} — с клиентским релеем (M4).
 */
public enum SessionRuntimeStatus {
    IDLE,
    TURN_RUNNING,
    PARKED_ASYNC
}
