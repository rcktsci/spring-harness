package se.rocketscien.harness.execution;

import tools.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.UUID;

/**
 * SPI клиентского релея (M4, D-85): доменный слой {@code execution} обращается к
 * клиентским инструментам только через этот контракт, объявленный в собственном пакете —
 * зависимость {@code execution → relay} запрещена (ArchUnit), реализация живёт в
 * {@code relay} и связывается Spring-ом.
 *
 * <ul>
 *   <li>{@link #isClientSession} — гейт нативных файловых инструментов (D-84: toolset
 *       сессии = наличие соединения в parent-цепочке);</li>
 *   <li>{@link #resolveTool} — резолв клиентского инструмента из live-оверлея (манифест
 *       собирается на Turn, резолв — на момент вызова);</li>
 *   <li>{@link #invoke} — маршрутизация вызова в WS-релей; возвращает финальный
 *       {@link ToolResult} (или синтетический ERROR/LOST/CANCELLED).</li>
 * </ul>
 */
public interface ClientToolBridge {

    boolean isClientSession(UUID sessionId);

    Optional<ToolDescriptor> resolveTool(UUID sessionId, String toolName);

    ToolResult invoke(UUID sessionId, String callId, String toolName, JsonNode args);
}
