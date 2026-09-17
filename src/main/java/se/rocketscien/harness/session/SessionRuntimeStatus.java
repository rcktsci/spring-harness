package se.rocketscien.harness.session;

/**
 * Рантайм-статус сессии в памяти (api-contracts §2/§3: снапшот {@code session.status} при коннекте).
 * {@code PARKED_ASYNC}/{@code PARKED_CLIENT} появятся с async-инструментами (M3) и клиентским
 * релеем (M4) — значения добавятся без смены контракта.
 */
public enum SessionRuntimeStatus {
    IDLE,
    TURN_RUNNING
}
