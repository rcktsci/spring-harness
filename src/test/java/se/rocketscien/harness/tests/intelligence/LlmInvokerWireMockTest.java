package se.rocketscien.harness.tests.intelligence;
import com.github.tomakehurst.wiremock.stubbing.Scenario;


import se.rocketscien.harness.intelligence.ChatModelFactory;
import se.rocketscien.harness.intelligence.impl.AesGcmCredentialDecryptor;
import se.rocketscien.harness.intelligence.LlmCredentials;
import se.rocketscien.harness.intelligence.LlmInvoker;
import se.rocketscien.harness.intelligence.LlmModel;
import se.rocketscien.harness.intelligence.LlmRetriesExhaustedException;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import se.rocketscien.harness.common.AesGcmEncryption;
import se.rocketscien.harness.config.LlmProperties;
import se.rocketscien.harness.config.TurnProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tasks 5.2/5.3: стриминг deltas, отмена, ретраи 429 с backoff, исчерпание попыток, учёт usage —
 * против WireMock (OpenAI-совместимый SSE).
 */
class LlmInvokerWireMockTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final String PATH = "/v1/chat/completions";

    private static WireMockServer server;

    private LlmInvoker invoker;
    private final UUID modelId = UUID.randomUUID();

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        server.resetAll();
        OpenAiChatModel model = model(server.baseUrl() + "/v1");
        invoker = new LlmInvoker(id -> model,
                new TurnProperties(Duration.ofSeconds(5), 3, Duration.ofMillis(20)));
    }

    @AfterEach
    void tearDown() {
        server.resetAll();
    }

    @Test
    void streamsDeltasIncrementally() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(sse(chunk("Hel"), chunk("lo "), chunk("world"), finalChunk(5, 7)))));

        List<String> deltas = new ArrayList<>();
        String text = invoker.stream(modelId, new Prompt("hi"))
                .map(this::text)
                .filter(delta -> !delta.isEmpty())
                .doOnNext(deltas::add)
                .collectList()
                .block()
                .stream()
                .reduce("", String::concat);

        assertThat(deltas).containsExactly("Hel", "lo ", "world");
        assertThat(text).isEqualTo("Hello world");
    }

    @Test
    void cancellationStopsTheStream() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(sse(chunk("a"), chunk("b"), chunk("c"), finalChunk(1, 1)))));

        AtomicBoolean cancelled = new AtomicBoolean(false);
        List<ChatResponse> received = invoker.stream(modelId, new Prompt("hi"))
                .doOnCancel(() -> cancelled.set(true))
                .take(1)
                .collectList()
                .block();

        assertThat(received).hasSize(1);
        assertThat(cancelled).isTrue();
    }

    @Test
    void retriesTransientErrorAndSucceeds() {
        server.stubFor(post(urlEqualTo(PATH)).inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withBody("{\"error\":\"rate limited\"}"))
                .willSetStateTo("second"));
        server.stubFor(post(urlEqualTo(PATH)).inScenario("retry")
                .whenScenarioStateIs("second")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(sse(chunk("ok"), finalChunk(1, 1)))));

        String text = invoker.stream(modelId, new Prompt("hi"))
                .map(this::text)
                .collectList()
                .block()
                .stream()
                .reduce("", String::concat);

        assertThat(text).isEqualTo("ok");
        server.verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void exhaustingRetriesFailsWithDedicatedException() {
        server.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(503).withBody("{\"error\":\"unavailable\"}")));

        Flux<ChatResponse> stream = invoker.stream(modelId, new Prompt("hi"));

        assertThatThrownBy(() -> stream.collectList().block())
                .isInstanceOf(LlmRetriesExhaustedException.class);
        server.verify(3, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void recordsCompletionTokensFromUsage() {
        server.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(sse(chunk("answer"), finalChunk(5, 7)))));

        List<ChatResponse> responses = invoker.stream(modelId, new Prompt("hi")).collectList().block();

        assertThat(responses).isNotNull();
        ChatResponse last = responses.get(responses.size() - 1);
        assertThat(last.getMetadata().getUsage().getCompletionTokens()).isEqualTo(7);
    }

    private String text(ChatResponse response) {
        var output = response.getResult().getOutput();
        return output.getText() == null ? "" : output.getText();
    }

    private OpenAiChatModel model(String baseUrl) {
        LlmProperties properties = new LlmProperties(Duration.ofSeconds(5),
                Map.of(1, Base64.getEncoder().encodeToString(KEY)));
        String apiKey;
        try {
            apiKey = AesGcmEncryption.encrypt("sk-test", KEY);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        LlmCredentials credentials = new LlmCredentials(UUID.randomUUID(), "test", baseUrl, apiKey, 1, Instant.now());
        LlmModel model = new LlmModel(modelId, credentials.getId(), "gpt-4",
                Map.of("temperature", 0.0), Instant.now());
        return new ChatModelFactory(properties, new AesGcmCredentialDecryptor(properties.encryptionKeys()))
                .create(model, credentials);
    }

    private static String sse(String... events) {
        StringBuilder body = new StringBuilder();
        for (String event : events) {
            body.append("data: ").append(event).append("\n\n");
        }
        body.append("data: [DONE]\n\n");
        return body.toString();
    }

    private static String chunk(String content) {
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"},\"finish_reason\":null}]}";
    }

    private static String finalChunk(int promptTokens, int completionTokens) {
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4\","
                + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":" + promptTokens + ",\"completion_tokens\":" + completionTokens
                + ",\"total_tokens\":" + (promptTokens + completionTokens) + "}}";
    }
}
