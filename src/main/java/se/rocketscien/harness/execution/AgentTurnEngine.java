package se.rocketscien.harness.execution;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.intelligence.LlmInvoker;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionMessageEntity;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.session.TurnOutcome;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Агентный цикл Turn'а (execution-model §2–§3, спека agent-turn): раунды
 * «рендер видимых → LlmGateway (LlmInvoker) → write-ahead (ASSISTANT + TOOL_CALL) →
 * sync-инструменты → TOOL_RESULT → раунд». Выход — отсутствие tool-calls и новых событий
 * (COMPLETED); ошибка LLM после ретраев — SYSTEM-событие причины + FAILED (без автоповтора);
 * события во время хода — дополнительный раунд; отмена — между вызовами, in-flight bash
 * прерывается областью отмены, in-flight LLM-стрим — дисподом подписки (D-J-4), ответ при
 * отмене не журналируется.
 *
 * <p>Потребление батча — watermark виденного (D-45, D-J-2): COMPLETED — финальный ASSISTANT,
 * FAILED — SYSTEM-событие причины (без retry-шторма), CANCELLED — последний рендер
 * (собственные результаты и свежий USER остаются непотреблёнными и поднимают новый Turn).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentTurnEngine {


    private final SessionStore sessionStore;
    private final LlmInvoker llmInvoker;
    private final NativeAgentTools agentTools;
    private final SessionPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;

    public void run(UUID sessionId, TurnCancellation cancellation) {
        Session session = sessionStore.findSession(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        SessionStore.AgentRuntime agent = sessionStore.agentRuntime(session.agentRevisionId());
        List<ToolCallback> toolCallbacks = agentTools.declarations(agent);

        long renderedWatermark = 0;
        while (true) {
            if (isCancelled(sessionId, cancellation)) {
                finishTurn(sessionId, TurnOutcome.CANCELLED, renderedWatermark);
                return;
            }

            Session current = sessionStore.findSession(sessionId).orElse(null);
            if (current == null) {
                return;
            }
            long roundBasisSeq = current.lastSeq();
            int appendedByRound = 0;

            List<SessionMessageEntity> visible = sessionStore.renderVisible(sessionId);
            if (!visible.isEmpty()) {
                renderedWatermark = Math.max(renderedWatermark, visible.getLast().getId().seq());
            }

            LlmRound round = callModel(agent, visible, toolCallbacks, cancellation);
            if (round.cancelled()) {
                finishTurn(sessionId, TurnOutcome.CANCELLED, renderedWatermark);
                return;
            }
            if (round.error() != null) {
                log.warn("LLM-вызов сессии {} не удался: {}", sessionId, round.error().getMessage());
                SessionStore.AppendedEvent systemEvent = sessionStore.appendEvent(
                        sessionId, MessageKind.SYSTEM, null, TurnPayloads.systemFailure(round.error()), null);
                finishTurn(sessionId, TurnOutcome.FAILED, systemEvent.seq());
                return;
            }
            ChatResponse response = round.response();

            AssistantMessage output = response == null || response.getResult() == null
                    ? null
                    : response.getResult().getOutput();
            List<AssistantMessage.ToolCall> toolCalls =
                    output == null || output.getToolCalls() == null ? List.of() : output.getToolCalls();
            String text = output != null && output.getText() != null ? output.getText() : "";

            // Write-ahead: сначала журнал, потом исполнение (execution-model §1)
            SessionStore.AppendedEvent assistantEvent = sessionStore.appendEvent(
                    sessionId, MessageKind.ASSISTANT, null,
                    TurnPayloads.assistant(text), totalTokens(response));
            long lastAppendedSeq = assistantEvent.seq();
            appendedByRound++;

            List<PendingCall> pendingCalls = new ArrayList<>();
            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                String callId = idGenerator.newUlid();
                Map<String, Object> arguments = parseArguments(toolCall.arguments());
                SessionStore.AppendedEvent toolCallEvent = sessionStore.appendEvent(
                        sessionId, MessageKind.TOOL_CALL, null,
                        TurnPayloads.toolCall(callId, toolCall.id(), toolCall.name(), arguments),
                        null);
                lastAppendedSeq = toolCallEvent.seq();
                appendedByRound++;
                pendingCalls.add(new PendingCall(callId, toolCall.name(), arguments));
            }

            if (isCancelled(sessionId, cancellation)) {
                for (PendingCall pendingCall : pendingCalls) {
                    sessionStore.appendEvent(sessionId, MessageKind.TOOL_RESULT, null,
                            TurnPayloads.toolResultSynthetic(
                                    pendingCall.callId(), pendingCall.tool(), ToolStatus.CANCELLED,
                                    "отменено пользователем"),
                            null);
                }
                // Watermark — по рендеру: собственные результаты и свежий USER поднимут новый Turn
                finishTurn(sessionId, TurnOutcome.CANCELLED, renderedWatermark);
                return;
            }

            if (pendingCalls.isEmpty()) {
                Session after = sessionStore.findSession(sessionId).orElse(null);
                if (after == null) {
                    return;
                }
                if (after.lastSeq() - roundBasisSeq == appendedByRound) {
                    finishTurn(sessionId, TurnOutcome.COMPLETED, lastAppendedSeq);
                    return;
                }
                log.debug("Сессия {}: новые события во время хода ({} > {}) — дополнительный раунд",
                        sessionId, after.lastSeq(), roundBasisSeq + appendedByRound);
                continue;
            }

            for (PendingCall pendingCall : pendingCalls) {
                if (isCancelled(sessionId, cancellation)) {
                    sessionStore.appendEvent(sessionId, MessageKind.TOOL_RESULT, null,
                            TurnPayloads.toolResultSynthetic(pendingCall.callId(), pendingCall.tool(),
                                    ToolStatus.CANCELLED, "отменено пользователем"),
                            null);
                    continue;
                }
                ToolResult result = executeTool(sessionId, pendingCall, cancellation);
                sessionStore.appendEvent(sessionId, MessageKind.TOOL_RESULT, null,
                        TurnPayloads.toolResult(pendingCall.callId(), pendingCall.tool(), result), null);
            }
        }
    }

    /**
     * Блокирующий вызов модели с ручным управлением подпиской (D-J-4): cancel диспозит
     * подписку — стрим не дочитывается; гонка «cancel до subscribe» закрывается повторной
     * проверкой после установки ссылки. Таймаут — клиентский ({@code harness.llm.timeout}).
     */
    private LlmRound callModel(SessionStore.AgentRuntime agent,
                               List<SessionMessageEntity> visible,
                               List<ToolCallback> toolCallbacks,
                               TurnCancellation cancellation) {
        Prompt prompt = promptBuilder.buildPrompt(agent, visible);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ChatResponse> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
        AutoCloseable interruptor = cancellation.registerInterrupt(() -> {
            Disposable subscription = subscriptionRef.get();
            if (subscription != null) {
                subscription.dispose();
            }
        });
        try {
            // Агрегат приходит в consumer aggregate()'а (responseRef::set); выходной поток —
            // сырые чанки, поэтому в subscribe onNext — no-op: последний чанк (finish/usage,
            // пустой delta) затирал бы агрегат пустым текстом ASSISTANT (E-J-2)
            Disposable subscription = new MessageAggregator()
                    .aggregate(llmInvoker.stream(agent.llmModelId(), prompt, toolCallbacks), responseRef::set)
                    .doOnCancel(done::countDown)
                    .subscribe(chunk -> { },
                            error -> {
                                errorRef.set(error);
                                done.countDown();
                            },
                            done::countDown);
            subscriptionRef.set(subscription);
            if (cancellation.isCancelled()) {
                subscription.dispose();
            }
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new LlmRound(null, e, false);
        } finally {
            try {
                interruptor.close();
            } catch (Exception e) {
                log.debug("Снятие LLM-прерывателя не удалось: {}", e.getMessage());
            }
        }
        if (errorRef.get() != null) {
            return new LlmRound(null, errorRef.get(), false);
        }
        return new LlmRound(responseRef.get(), null, cancellation.isCancelled());
    }

    private ToolResult executeTool(UUID sessionId, PendingCall pendingCall, TurnCancellation cancellation) {
        try {
            return agentTools.execute(sessionId, pendingCall.tool(), pendingCall.arguments(), cancellation);
        } catch (Exception e) {
            log.warn("Инструмент {} сессии {} упал: {}", pendingCall.tool(), sessionId, e.getMessage());
            return ToolResult.error(pendingCall.callId(), pendingCall.tool(), e.getMessage());
        }
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(argumentsJson, Map.class);
        } catch (Exception e) {
            log.warn("Аргументы tool-call не распарсены ({}): {}", argumentsJson, e.getMessage());
            return Map.of();
        }
    }

    private boolean isCancelled(UUID sessionId, TurnCancellation cancellation) {
        if (cancellation.isCancelled()) {
            return true;
        }
        return sessionStore.findSession(sessionId).map(Session::cancelRequested).orElse(false);
    }

    private void finishTurn(UUID sessionId, TurnOutcome outcome, long consumedSeq) {
        sessionStore.finishTurn(sessionId, outcome, consumedSeq);
    }

    private Integer totalTokens(ChatResponse response) {
        try {
            if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                return response.getMetadata().getUsage().getTotalTokens();
            }
        } catch (Exception e) {
            log.debug("Usage недоступен: {}", e.getMessage());
        }
        return null;
    }

    private record PendingCall(String callId, String tool, Map<String, Object> arguments) {
    }

    private record LlmRound(ChatResponse response, Throwable error, boolean cancelled) {
    }
}
