package se.rocketscien.harness.tests.relay;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * Программный тестовый WS-клиент релея (M4 batch X): реальный {@link WebSocket} к
 * {@code /api/v1/relay} с Bearer-JWT. Ведёт handshake ({@code hello}→{@code welcome},
 * {@code register}→{@code registered}/{@code error}), принимает {@code tool.call} и отвечает
 * {@code tool.result} (конфигурируемый responder), отвечает на {@code ping}, фиксирует
 * {@code tool.cancel} и close-код (для takeover-сценариев «офисов»). Полноценный клиент
 * Web Desktop — отдельный этап.
 */
public final class TestRelayClient implements AutoCloseable {

    private static final int SEND_WAIT_SECONDS = 10;

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final URI uri;
    private final String token;
    private final String sessionId;
    private final String basePath;
    private final List<Map<String, Object>> tools;
    private final BiFunction<String, Map<String, Object>, ClientToolResult> responder;

    private final List<String> frames = new CopyOnWriteArrayList<>();
    private final CountDownLatch registered = new CountDownLatch(1);
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile WebSocket webSocket;
    private volatile Integer closeCode;
    private volatile String closeReason;
    private volatile String registrationError;

    /** Ответ клиента на tool.call: output + exitCode (информативный, V-2). */
    public record ClientToolResult(String output, Integer exitCode) {
    }

    public TestRelayClient(URI uri, String token, String sessionId, String basePath,
                           List<Map<String, Object>> tools,
                           BiFunction<String, Map<String, Object>, ClientToolResult> responder) {
        this.uri = uri;
        this.token = token;
        this.sessionId = sessionId;
        this.basePath = basePath;
        this.tools = tools;
        this.responder = responder;
    }

    /** Подключение + handshake (hello) + регистрация на сессии; блокируется до {@code registered}/{@code error}. */
    public void connectAndRegister() throws InterruptedException {
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket socket) {
                webSocket = socket;
                socket.request(1);
                send(Map.of("type", "hello", "protocol", 1));
            }

            @Override
            public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                socket.request(1);
                handle(data.toString());
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
                closeCode = statusCode;
                closeReason = reason;
                closed.countDown();
                return null;
            }

            @Override
            public void onError(WebSocket socket, Throwable error) {
                closed.countDown();
            }
        };
        webSocket = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .buildAsync(uri, listener)
                .join();
        if (!registered.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Реле не ответил registered/error (ошибка=" + registrationError + ")");
        }
    }

    public boolean awaitClosed(long timeoutMillis) throws InterruptedException {
        return closed.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public boolean awaitClosed() throws InterruptedException {
        return awaitClosed(30_000);
    }

    public Integer closeCode() {
        return closeCode;
    }

    public String closeReason() {
        return closeReason;
    }

    public List<String> frames() {
        return frames;
    }

    public boolean sentToolResult(String outputMarker) {
        return frames.stream().anyMatch(frame ->
                frame.contains("\"type\":\"tool.result\"") && frame.contains(outputMarker));
    }

    public boolean receivedToolCall(String tool) {
        return frames.stream().anyMatch(frame ->
                frame.contains("\"type\":\"tool.call\"") && frame.contains("\"" + tool + "\""));
    }

    @Override
    public void close() {
        WebSocket socket = webSocket;
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
    }

    private void handle(String text) {
        JsonNode frame = mapper.readTree(text);
        String type = frame.path("type").asString(null);
        switch (type == null ? "" : type) {
            case "welcome" -> send(registerFrame());
            case "registered" -> registered.countDown();
            case "error" -> {
                registrationError = frame.path("code").asString("unknown");
                registered.countDown();
            }
            case "tool.call" -> respondToToolCall(frame);
            case "ping" -> send(Map.of("type", "pong"));
            case "tool.cancel" -> log("tool.cancel received");
            default -> log("frame: " + type);
        }
    }

    private void respondToToolCall(JsonNode frame) {
        String callId = frame.path("callId").asString(null);
        String tool = frame.path("tool").asString(null);
        Map<String, Object> args = readArgs(frame.path("args"));
        ClientToolResult result = responder.apply(tool, args);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "tool.result");
        response.put("callId", callId);
        response.put("output", result.output());
        response.put("exitCode", result.exitCode());
        send(response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readArgs(JsonNode args) {
        if (args == null || args.isNull() || !args.isObject()) {
            return Map.of();
        }
        return mapper.readValue(args.toString(), Map.class);
    }

    private Map<String, Object> registerFrame() {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "register");
        frame.put("sessionId", sessionId);
        frame.put("basePath", basePath);
        frame.put("client", Map.of("version", "test-client-1", "tools", tools));
        return frame;
    }

    private void send(Object frame) {
        String json = mapper.writeValueAsString(frame);
        log(json);
        webSocket.sendText(json, true).orTimeout(SEND_WAIT_SECONDS, TimeUnit.SECONDS).join();
    }

    private void log(String frame) {
        frames.add(frame);
    }
}
