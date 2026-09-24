package se.rocketscien.harness.intelligence;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;
import se.rocketscien.harness.config.TurnProperties;

import java.util.List;
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
@RequiredArgsConstructor
@Slf4j
public class LlmInvoker {


    private final LlmGateway llmGateway;
    private final TurnProperties turnProperties;

    public Flux<ChatResponse> stream(UUID llmModelId, Prompt prompt) {
        return stream(llmModelId, prompt, null);
    }

    /**
     * Декларации инструментов уходят в prompt-level options (провайдер-специфичный
     * {@link OpenAiChatOptions} — модель ожидает именно его); внутреннее исполнение Spring AI
     * не происходит — цикл инструментов ведёт Turn (write-ahead, execution-model §1).
     *
     * <p>Опции склеиваются с дефолтами модели, а не заменяют их: prompt-level options имеют
     * приоритет, и модель без tools (или с tools, собранными «с нуля») теряла
     * {@code model}/{@code temperature} — провайдер получал дефолт SDK (регрессия на живом
     * стенде 2026-09-24: LiteLLM отклонил {@code model=gpt-5-mini} вместо {@code MiniMax-M3}).</p>
     */
    public Flux<ChatResponse> stream(UUID llmModelId, Prompt prompt, List<ToolCallback> toolCallbacks) {
        return Flux.defer(() -> {
            ChatModel model = llmGateway.chatModel(llmModelId);
            Prompt finalPrompt = withToolCallbacks(prompt, toolCallbacks, model);
            long retries = Math.max(0, turnProperties.llmRetries() - 1L);
            if (retries == 0) {
                return model.stream(finalPrompt);
            }
            AtomicBoolean delivered = new AtomicBoolean(false);
            return model.stream(finalPrompt)
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

    private static Prompt withToolCallbacks(Prompt prompt, List<ToolCallback> toolCallbacks, ChatModel model) {
        if (toolCallbacks == null || toolCallbacks.isEmpty()) {
            return prompt;
        }
        if (model instanceof OpenAiChatModel openAiModel
                && openAiModel.getDefaultOptions() instanceof OpenAiChatOptions defaults) {
            return new Prompt(prompt.getInstructions(),
                    defaults.mutate().toolCallbacks(toolCallbacks).build());
        }
        return new Prompt(prompt.getInstructions(),
                OpenAiChatOptions.builder().toolCallbacks(toolCallbacks).build());
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
