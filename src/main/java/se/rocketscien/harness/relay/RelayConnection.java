package se.rocketscien.harness.relay;

/**
 * Активное WS-соединение релея (M4, D-78): реестр хранит его по {@code sessionId} и через
 * этот контракт закрывает соединение и шлёт кадры; транспортная конкретика — в
 * {@link WebSocketRelayConnection}. Интерфейс позволяет реестру и хендлеру не зависеть
 * от Spring WebSocket напрямую и тестироваться на подставных соединениях.
 */
public interface RelayConnection {

    /** Имя аутентифицированного principal — опора takeover-политики реестра (D-78). */
    String principal();

    /**
     * Отправка текстового JSON-кадра (исходящие сериализуются декоратором, D-83).
     *
     * @return {@code false}, если кадр не доставлен (соединение закрыто/переполнено) — вызывающий
     *         может завершить ожидание немедленно (V-7), а не ждать полного timeout'а
     */
    boolean sendText(String frame);

    /** Закрытие соединения WS-close-кодом (4401/4403/4409, heartbeat-разрыв). */
    void close(int statusCode, String reason);
}
