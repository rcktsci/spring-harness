package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.util.List;

/**
 * Параметры скачивания серверного workspace (M4, api-contracts §8, D-72): лимит размера
 * файла (pre-stat 413 до отдачи) и safe-лист расширений (случайное сравнение —
 * case-insensitive; вне листа → 422 extension-not-allowed).
 */
@ConfigurationProperties(prefix = "harness.workspace.download")
public record WorkspaceDownloadProperties(DataSize maxBytes, List<String> allowExtensions) {
}
