package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Контейнеры исполнения (D-M1-7): helper-образ, корень bind-mount workspace-каталогов
 * ({@code workspaceRoot/{sessionId}}; должен быть абсолютным — bind-mount резолвится демоном),
 * лимиты ресурсов контейнера и политика pull (локальный образ приоритетен; pull — backoff-обновление;
 * недоступность registry не фейлит вызов). {@code writeChunkBytes} — размер чанка argv-записи файла
 * (C-J-5, ниже Linux {@code MAX_ARG_STRLEN}); {@code containerStopConfirm} — окно подтверждения
 * остановки контейнера при сигнальном exit (C-J-6 #4).
 */
@ConfigurationProperties(prefix = "harness.docker")
public record DockerProperties(
        String helperImage,
        String workspaceRoot,
        long cpuNanos,
        DataSize memory,
        String network,
        Duration pullTimeout,
        Duration startTimeout,
        Duration execTimeout,
        Duration statePollInterval,
        Duration containerStopConfirm,
        int writeChunkBytes,
        int pullRetries,
        Duration pullBackoff
) {
}
