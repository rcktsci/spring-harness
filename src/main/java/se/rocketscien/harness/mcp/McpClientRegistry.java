package se.rocketscien.harness.mcp;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import se.rocketscien.harness.config.McpProperties;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.concurrent.ConcurrentMap;

/**
 * Реестр MCP-клиентов (Q.1, D-63): клиенты инициализируются ЛЕНИВО — холодный старт не
 * открывает соединений (пустой {@code harness.mcp.servers: []} по умолчанию). Дедупликация
 * имён серверов — fail-fast на загрузке контекста ({@code MCP-name-collision}).
 *
 * <p>Транспорт — streamable HTTP (M3; stdio/sse — точки эволюции). Токен: bootstrap из env
 * {@code secretRef}, при 401/403 — обновление через {@link McpAuthRefresher} (прокси) и
 * ровно одна пересборка клиента с новым токеном; повторная авторазница — наверх как
 * {@code auth-refresh-failed} (Q.4).</p>
 *
 * <p>Манифест инструментов кэшируется в холдере после первого {@code listTools} (M3 —
 * без инструментов-list-changed подписок).</p>
 */
@Component
@Slf4j
public class McpClientRegistry {

    /** Ошибочный исход вызова/инициализации после исчерпания auth-retry. */
    public static final String AUTH_REFRESH_FAILED = "auth-refresh-failed";

    private final McpProperties properties;
    private final McpAuthRefresher authRefresher;
    private final ConcurrentMap<String, Holder> clients = new ConcurrentHashMap<>();
    private final McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());

    public McpClientRegistry(McpProperties properties, McpAuthRefresher authRefresher) {
        this.properties = properties;
        this.authRefresher = authRefresher;
        // Q.1: дедупликация имён — fail-fast при загрузке (контекст не поднимется с дублями)
        long distinct = configuredServers().stream().map(McpProperties.Server::name).distinct().count();
        if (distinct != configuredServers().size()) {
            throw new IllegalStateException("MCP-name-collision: дубликаты имён в harness.mcp.servers");
        }
    }

    /** Имена настроенных серверов (конфиг); соединений при этом не открывается. */
    public List<String> serverNames() {
        return configuredServers().stream().map(McpProperties.Server::name).toList();
    }

    public boolean isKnownServer(String name) {
        return configuredServers().stream().anyMatch(s -> s.name().equals(name));
    }

    /** Принадлежит ли имя инструмента настроенному MCP-серверу ({@code server.tool}). */
    public boolean isManagedTool(String namespacedTool) {
        if (namespacedTool == null || !namespacedTool.contains(".")) {
            return false;
        }
        return isKnownServer(namespacedTool.substring(0, namespacedTool.indexOf('.')));
    }

    /**
     * Манифест инструментов сервера (лениво: первый вызов открывает соединение и
     * инициализирует клиента; далее — кэш холдера).
     */
    public List<McpToolDescriptor> listTools(String serverName) {
        Holder holder = ensureClient(serverName);
        if (holder.tools != null) {
            return holder.tools;
        }
        synchronized (holder) {
            if (holder.tools != null) {
                return holder.tools;
            }
            List<McpSchema.Tool> tools = withAuthRetry(serverName, holder,
                    client -> client.listTools().tools());
            List<McpToolDescriptor> descriptors = tools.stream()
                    .map(tool -> new McpToolDescriptor(
                            serverName,
                            tool.name(),
                            tool.description(),
                            tool.inputSchema(),
                            tool.meta() != null && Boolean.TRUE.equals(tool.meta().get("async-capable"))))
                    .toList();
            log.info("MCP-сервер {}: манифест из {} инструментов", serverName, descriptors.size());
            holder.tools = descriptors;
            return descriptors;
        }
    }

    /** Вызов инструмента (namespace {@code server.tool}); auth-retry — ровно один (Q.4). */
    public McpSchema.CallToolResult callTool(String serverName, String toolName,
                                             Map<String, Object> arguments) {
        Holder holder = ensureClient(serverName);
        return withAuthRetry(serverName, holder,
                client -> client.callTool(new McpSchema.CallToolRequest(toolName, arguments)));
    }

    /** Холодный старт: до первого обращения клиентов нет. */
    public int initializedClients() {
        return (int) clients.values().stream().filter(h -> h.client != null).count();
    }

    @PreDestroy
    void closeAll() {
        clients.values().forEach(h -> {
            McpSyncClient client = h.client;
            if (client != null) {
                try {
                    client.closeGracefully();
                } catch (Exception e) {
                    log.debug("MCP-клиент не закрылся чисто: {}", e.getMessage());
                }
            }
        });
    }

    // ---------------------------------------------------------------- внутреннее

    private List<McpProperties.Server> configuredServers() {
        return properties.servers() == null ? List.of() : properties.servers();
    }

    private McpProperties.Server server(String name) {
        return configuredServers().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "неизвестный MCP-сервер '" + name + "' (harness.mcp.servers)"));
    }

    private Holder ensureClient(String serverName) {
        return clients.computeIfAbsent(serverName, n -> new Holder(buildClient(serverName, bootstrapToken(n))));
    }

    private String bootstrapToken(String serverName) {
        McpProperties.Server server = server(serverName);
        return server.secretRef() == null ? null : System.getenv(server.secretRef());
    }

    private McpSyncClient buildClient(String serverName, String token) {
        McpProperties.Server server = server(serverName);
        if (!"http".equals(server.transport())) {
            throw new IllegalStateException("MCP-транспорт '" + server.transport()
                    + "' сервера " + serverName + " не поддерживается в M3 (http = streamable HTTP)");
        }
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
        applyAuthHeader(requestBuilder, server, token);
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(server.url())
                .endpoint(server.endpoint() == null || server.endpoint().isBlank()
                        ? "/mcp" : server.endpoint())
                .jsonMapper(jsonMapper)
                .requestBuilder(requestBuilder)
                .connectTimeout(initTimeout())
                // Простые request/response-серверы: без фоновых SSE-потоков
                .resumableStreams(false)
                .openConnectionOnStartup(false)
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("harness-mcp", "1.0"))
                .requestTimeout(callTimeout())
                .initializationTimeout(initTimeout())
                .build();
        client.initialize();
        log.info("MCP-клиент сервера {} инициализирован", serverName);
        return client;
    }

    private void applyAuthHeader(HttpRequest.Builder builder, McpProperties.Server server, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String type = server.auth() == null ? "oauth-bearer" : server.auth().type();
        if ("api-key".equals(type)) {
            builder.header("X-Api-Key", token);
        } else {
            builder.header("Authorization", "Bearer " + token);
        }
    }

    private Duration callTimeout() {
        return properties.callTimeout() == null ? Duration.ofSeconds(60) : properties.callTimeout();
    }

    private Duration initTimeout() {
        return properties.initTimeout() == null ? Duration.ofSeconds(30) : properties.initTimeout();
    }

    /**
     * Единая точка auth-retry (Q.4): 401/403 → обновление токена через прокси → пересборка
     * клиента → один повтор; повторная авторазница → {@link McpAuthRefresher.McpAuthException}
     * ({@code auth-refresh-failed}).
     */
    private <T> T withAuthRetry(String serverName, Holder holder,
                                 Function<McpSyncClient, T> action) {
        try {
            return action.apply(holder.client);
        } catch (Exception first) {
            if (!isAuthorizationFailure(first)) {
                throw first;
            }
            log.info("MCP-сервер {}: 401/403 — обновление токена через прокси (1 retry)", serverName);
            McpProperties.Server server = server(serverName);
            String fresh = authRefresher.refresh(serverName, server.secretRef());
            McpSyncClient rebuilt = buildClient(serverName, fresh);
            holder.client = rebuilt;
            holder.tools = null;
            try {
                return action.apply(rebuilt);
            } catch (Exception second) {
                if (isAuthorizationFailure(second)) {
                    throw new McpAuthRefresher.McpAuthException(
                            "auth-refresh-failed: повторный 401/403 от сервера " + serverName);
                }
                throw second;
            }
        }
    }

    private static boolean isAuthorizationFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof McpHttpClientTransportAuthorizationException
                    || String.valueOf(current.getMessage()).contains("401")
                    || String.valueOf(current.getMessage()).contains("403")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** Холдер клиента сервера: клиент + кэш манифеста (сбрасывается при пересборке). */
    private static final class Holder {
        private volatile McpSyncClient client;
        private volatile List<McpToolDescriptor> tools;

        private Holder(McpSyncClient client) {
            this.client = client;
        }
    }
}
