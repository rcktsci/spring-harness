package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Параметры спавна субагентов (M3 O.1/O.2, спека subagent-lifecycle): лимит глубины дерева
 * (проверяется ДО создания дочерней сессии, D-61), workspace-стратегия (M3 — только
 * {@code inherit}; {@code new} — точка эволюции) и таймаут блокирующего ожидания субагента.
 */
@ConfigurationProperties(prefix = "harness.spawn")
public record SpawnProperties(Integer maxDepth, String workspaceStrategy, Duration timeoutMs,
                              Duration pollInterval) {
}
