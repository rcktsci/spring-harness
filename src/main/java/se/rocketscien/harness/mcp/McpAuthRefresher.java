package se.rocketscien.harness.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import se.rocketscien.harness.config.McpProperties;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * Обновление токенов MCP-серверов через прокси (Q.4, D-63): токены хранятся у прокси,
 * не в приложении (env-переменные — только bootstrap при старте). Контракт:
 * {@code POST proxyUrl {"server": name, "secretRef": ref}} → {@code 200 {"token": "…"}}.
 * Вызывается {@code McpClientRegistry} при 401/403 от сервера; повторная неудача —
 * наверх как {@code auth-refresh-failed} (без автоповторов — правило ревью Q.4).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class McpAuthRefresher {

    private final McpProperties properties;
    private final ObjectMapper objectMapper;

    /** Свежий токен от прокси; неудача — {@link McpAuthException}. */
    public String refresh(String serverName, String secretRef) {
        String proxyUrl = properties.auth() == null ? null : properties.auth().proxyUrl();
        if (proxyUrl == null || proxyUrl.isBlank()) {
            throw new McpAuthException("auth-refresh-failed: harness.mcp.auth.proxy-url не настроен");
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "server", serverName,
                    "secretRef", secretRef == null ? "" : secretRef));
            String response = RestClient.builder()
                    .requestFactory(requestFactory())
                    .build()
                    .post()
                    .uri(proxyUrl)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            String token = response == null ? null
                    : objectMapper.readTree(response).path("token").asString(null);
            if (token == null || token.isBlank()) {
                throw new McpAuthException("auth-refresh-failed: прокси вернул ответ без token");
            }
            log.info("MCP-токен сервера {} обновлён через прокси", serverName);
            return token;
        } catch (McpAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new McpAuthException("auth-refresh-failed: " + e.getMessage());
        }
    }

    /**
     * Таймауты прокси-вызова (Q-5): connect/read — конфиг
     * {@code harness.mcp.auth.connect-timeout-ms}/{@code read-timeout-ms}.
     */
    private JdkClientHttpRequestFactory requestFactory() {
        Duration connect = properties.auth() != null && properties.auth().connectTimeoutMs() != null
                ? Duration.ofMillis(properties.auth().connectTimeoutMs()) : Duration.ofSeconds(5);
        Duration read = properties.auth() != null && properties.auth().readTimeoutMs() != null
                ? Duration.ofMillis(properties.auth().readTimeoutMs()) : Duration.ofSeconds(10);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connect)
                .build();
        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(read);
        return factory;
    }

    /** Неудача обновления токена — наверх (TOOL_RESULT auth-refresh-failed, повторов нет). */
    public static final class McpAuthException extends RuntimeException {

        public McpAuthException(String message) {
            super(message);
        }

        public String errorCode() {
            return "auth-refresh-failed";
        }
    }
}
