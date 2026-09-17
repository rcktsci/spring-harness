package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Контейнеры исполнения (D-M1-7): helper-образ, корень bind-mount workspace-каталогов
 * ({@code workspaceRoot/{sessionId}}) и лимиты ресурсов контейнера.
 */
@ConfigurationProperties(prefix = "harness.docker")
public record DockerProperties(String helperImage, String workspaceRoot, long cpuNanos, DataSize memory) {
}
