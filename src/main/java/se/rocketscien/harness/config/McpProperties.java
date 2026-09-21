package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Параметры MCP-клиента (M3 пачка Q, D-63): каталог серверов {@code harness.mcp.servers}
 * (пустой по умолчанию — холодный старт, соединения ленивые), прокси обновления токенов
 * {@code harness.mcp.auth.proxy-url} (токены живут у прокси, в приложении только bootstrap-
 * secret-ref на env) и таймауты клиента.
 */
@ConfigurationProperties(prefix = "harness.mcp")
public record McpProperties(List<Server> servers, Auth auth, Duration callTimeout, Duration initTimeout) {

    /** Тип авторизации сервера: oauth-bearer (Authorization: Bearer) | api-key (X-Api-Key). */
    public record ServerAuth(String type) {
    }

    /**
     * Сервер MCP: {@code name} — пространство имён инструментов ({@code name.tool});
     * {@code transport} — stdio|http|sse (M3 реализует http = streamable HTTP);
     * {@code auth.type} — oauth-bearer | api-key; {@code secretRef} — имя env-переменной
     * с bootstrap-токеном; {@code endpoint} — путь JSON-RPC (дефолт /mcp).
     */
    public record Server(String name, String url, String transport, ServerAuth auth, String secretRef,
                         String endpoint) {
    }

    /** Токены у прокси; {@code proxyUrl} — точка обновления (POST → {"token": …}); таймауты — конфиг (Q-5). */
    public record Auth(String proxyUrl, Integer connectTimeoutMs, Integer readTimeoutMs) {
    }
}
