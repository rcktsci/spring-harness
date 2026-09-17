package se.rocketscien.harness.intelligence;

import com.openai.errors.OpenAIRetryableException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import se.rocketscien.harness.config.TurnProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-J-2: ретрай LLM только до первого доставленного элемента. Ошибка до начала стрима —
 * ретраится; mid-stream обрыв — не перезапускает поток (без дубля префикса) и уходит наверх.
 */
class LlmInvokerRetrySemanticsTest {

    private static final UUID MODEL_ID = UUID.randomUUID();

    private final TurnProperties turnProperties =
            new TurnProperties(Duration.ofSeconds(1), 3, Duration.ofMillis(10));

    @Test
    void retriesWhenErrorArrivesBeforeFirstDelta() {
        FakeChatModel model = new FakeChatModel(subscription -> subscription == 1
                ? Flux.error(new OpenAIRetryableException("connection reset"))
                : Flux.just(response("ok")));
        LlmInvoker invoker = invoker(model);

        List<ChatResponse> received = invoker.stream(MODEL_ID, new Prompt("hi")).collectList().block();

        assertThat(received).hasSize(1);
        assertThat(text(received.get(0))).isEqualTo("ok");
        assertThat(model.subscriptions()).isEqualTo(2);
    }

    @Test
    void doesNotRetryAfterFirstDelta() {
        FakeChatModel model = new FakeChatModel(subscription ->
                Flux.concat(Flux.just(response("Hel")), Flux.error(new OpenAIRetryableException("mid-stream reset"))));
        LlmInvoker invoker = invoker(model);

        List<ChatResponse> received = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        invoker.stream(MODEL_ID, new Prompt("hi"))
                .doOnNext(received::add)
                .onErrorResume(throwable -> {
                    failure.set(throwable);
                    return Flux.empty();
                })
                .blockLast();

        assertThat(received).hasSize(1);
        assertThat(text(received.get(0))).isEqualTo("Hel");
        assertThat(failure.get()).isInstanceOf(OpenAIRetryableException.class);
        assertThat(model.subscriptions()).isEqualTo(1);
    }

    private LlmInvoker invoker(ChatModel model) {
        return new LlmInvoker(id -> model, turnProperties);
    }

    private static String text(ChatResponse response) {
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static class FakeChatModel implements ChatModel {

        private final java.util.function.IntFunction<Flux<ChatResponse>> streamFactory;
        private final AtomicInteger subscriptions = new AtomicInteger();

        FakeChatModel(java.util.function.IntFunction<Flux<ChatResponse>> streamFactory) {
            this.streamFactory = streamFactory;
        }

        int subscriptions() {
            return subscriptions.get();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("call is not used in this test");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> streamFactory.apply(subscriptions.incrementAndGet()));
        }
    }
}
