package se.rocketscien.harness.execution;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SPI клиентского релея (M4, D-85): доменный слой {@code execution} обращается к
 * клиентским инструментам только через этот контракт, объявленный в собственном пакете —
 * зависимость {@code execution → relay} запрещена (ArchUnit), реализация живёт в
 * {@code relay} и связывается Spring-ом.
 *
 * <ul>
 *   <li>{@link #isClientSession} — toolset сессии (D-84: наличие соединения в parent-цепочке);
 *       в CLIENT нативные файловые инструменты исключаются;</li>
 *   <li>{@link #manifest} — декларации клиентского оверлея для манифеста модели (namespace —
 *       parent-цепочка до root-сессии с соединением);</li>
 *   <li>{@link #resolve} — резолв клиентского инструмента из live-оверлея (манифест
 *       собирается на Turn, резолв — на момент вызова);</li>
 *   <li>{@link #invoke} — маршрутизация вызова в WS-релей; возвращает финальный
 *       {@link ToolResult} (или синтетический ERROR/LOST/CANCELLED).</li>
 * </ul>
 */
public interface ClientToolBridge {

    boolean isClientSession(UUID sessionId);

    List<ToolCallback> manifest(UUID sessionId);

    Optional<ToolDescriptor> resolve(UUID sessionId, String toolName);

    ToolResult invoke(UUID sessionId, String callId, String toolName, Map<String, Object> args);

    /**
     * Отмена in-flight клиентского вызова (stop / каскад поддерева, D-59): клиенту уходит
     * {@code tool.cancel { callId }}, ожидающий Turn-поток получает синтетический CANCELLED.
     */
    void cancel(UUID sessionId, String callId);
}
