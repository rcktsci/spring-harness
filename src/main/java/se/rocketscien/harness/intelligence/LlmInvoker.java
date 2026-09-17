package se.rocketscien.harness.intelligence;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;
import se.rocketscien.harness.config.TurnProperties;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Вызов модели со стримингом и собственной политикой ретраев (D-M1-3): временные ошибки 429/5xx/
 * сетевые (C-J-6 #7) повторяются с экспоненциальным backoff ({@code harness.turn.backoff-base}),
 * лимит попыток — {@code harness.turn.llm-retries}. Встроенные ретраи Spring AI отключены
 * ({@code maxRetries(0)}). Ретрай возможен только ДО первого доставленного элемента: mid-stream
 * обрыв не перезапускает поток (иначе потребитель получил бы дубль префикса — C-J-2) и уходит
 * наверх как неисправимая ошибка. Отмена — dispose подписки на {@link Flux}.
 */
@Service
public class LlmInvoker {

    private static final Logger log = LoggerFactory.getLogger(LlmInvoker.class);

    private final LlmGateway llmGateway;
    private final TurnProperties turnProperties;

    public LlmInvoker(LlmGateway llmGateway, TurnProperties turnProperties) {
        this.llmGateway = llmGateway;
        this.turnProperties = turnProperties;
    }

    public Flux<ChatResponse> stream(UUID llmModelId, Prompt prompt) {
        // chatModel резолвится на подписке: LlmConfigurationException приходит как сигнал Flux,
        // а не синхронным исключением (C-J-6 #14).
        return Flux.defer(() -> {
            ChatModel model = llmGateway.chatModel(llmModelId);
            long retries = Math.max(0, turnProperties.llmRetries() - 1L);
            if (retries == 0) {
                return model.stream(prompt);
            }
            AtomicBoolean delivered = new AtomicBoolean(false);
            return model.stream(prompt)
                    .doOnNext(response -> delivered.set(true))
                    .retryWhen(Retry.backoff(retries, turnProperties.backoffBase())
                            .filter(throwable -> isTransient(throwable) && !delivered.get())
                            .doBeforeRetry(signal -> log.warn(
                                    "Retrying LLM call after transient error: {}",
                                    signal.failure().getMessage()))
                            .onRetryExhaustedThrow((spec, signal) -> new LlmRetriesExhaustedException(
                                    "LLM call failed after " + turnProperties.llmRetries() + " attempts",
                                    signal.failure())));
        });
    }

    static boolean isTransient(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof OpenAIRetryableException || cause instanceof OpenAIIoException) {
                return true;
            }
            if (cause instanceof OpenAIServiceException serviceException) {
                int status = serviceException.statusCode();
                return status == 429 || status >= 500;
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return false;
    }
}
