package se.rocketscien.harness.tests.relay;

import se.rocketscien.harness.relay.ClientToolRegistry;
import se.rocketscien.harness.relay.RelayConnection;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Программное тестовое соединение релея (M4 batch V): принимает кадры {@code tool.call} и
 * синхронно отвечает {@code tool.result} через {@link ClientToolRegistry} — полноценный
 * WS-клиент планируется в batch X. Позволяет тестировать оверлей и маршрутизацию без сокета.
 */
public final class TestRelayConnection implements RelayConnection {

    private static final Pattern CALL_ID = Pattern.compile("\"callId\":\"([^\"]+)\"");

    private final String principal;
    private final ClientToolRegistry registry;
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private volatile boolean respond = true;
    private volatile String responseOutput = "client-output";
    private volatile Integer responseExitCode = 0;

    public TestRelayConnection(String principal, ClientToolRegistry registry) {
        this.principal = principal;
        this.registry = registry;
    }

    /** Настроить ответ клиента (по умолчанию {@code client-output}, exitCode 0). */
    public TestRelayConnection respondingWith(String output, Integer exitCode) {
        this.responseOutput = output;
        this.responseExitCode = exitCode;
        return this;
    }

    /** Не отвечать на {@code tool.call} (для проверки tool-timeout / LOST). */
    public TestRelayConnection silent() {
        this.respond = false;
        return this;
    }

    public List<String> sent() {
        return sent;
    }

    public boolean sentToolCall(String tool) {
        return sent.stream().anyMatch(frame ->
                frame.contains("\"type\":\"tool.call\"") && frame.contains("\"" + tool + "\""));
    }

    @Override
    public String principal() {
        return principal;
    }

    @Override
    public void sendText(String frame) {
        sent.add(frame);
        if (!respond || registry == null || !frame.contains("\"type\":\"tool.call\"")) {
            return;
        }
        Matcher matcher = CALL_ID.matcher(frame);
        if (matcher.find()) {
            registry.completeResult(matcher.group(1), responseOutput, responseExitCode);
        }
    }

    @Override
    public void close(int statusCode, String reason) {
    }
}
