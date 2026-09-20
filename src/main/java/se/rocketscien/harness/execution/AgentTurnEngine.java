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
import se.rocketscien.harness.config.TaskProperties;
import se.rocketscien.harness.execution.impl.AsyncToolExecutor;
import se.rocketscien.harness.intelligence.LlmInvoker;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionKind;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
 *
 * <p>Мета-инструменты (D-52/D-59): в STATE-сессии доступен {@code transition}; гейт —
 * разрешён только при {@code instructionSource = USER} (источник резолвится из батча,
 * поднявшего Turn: USER-сообщение / TOOL_RESULT / системный seed); лимит применений на
 * Turn — {@code harness.task.transition.max-per-turn}. Запрещённый гейтом вызов — ошибка
 * инструмента, переход не происходит. Применение — транзакционно в момент исполнения
 * tool-call (TransitionMetaTool → TaskEngine), не откладывается до turn-finish.</p>
 *
 * <p>J-1/R-1 — pending USER-намерение: если в не-USER-ходе модель увидела USER-сообщение
 * (рендер доп. раунда) и её {@code transition} заблокирован гейтом из-за чужого источника,
 * финальный watermark COMPLETED не двигается дальше этого USER ({@code last_consumed_seq}
 * замораживается на {@code userIntentSeq - 1}) — USER остаётся непрочитанным, и post-finish
 * re-wake {@code TurnManager}'а поднимает следующий Turn уже с {@code instructionSource=USER}.
 * Без cap'а COMPLETED потребил бы USER финальным ASSISTANT'ом, и намерение терялось.</p>
 *
 * <p>Async-инструменты (M3, D-60/D-65): TOOL_CALL всех вызовов — в write-ahead батче хода
 * (execution-model §1: журнал — маркер «в полёте»; рестарт-скан закрывает потерянные).
 * Async-capable (по {@code asyncCapabilities}) исполняются в окне
 * {@code harness.async.window.default-ms}: уложившийся — TOOL_RESULT как sync; превысивший —
 * в журнал дописывается {@code ASYNC_ACCEPTED(callId)}, ход завершается COMPLETED (исход
 * раунда не расширяется), сессия паркуется в {@code PARKED_ASYNC} (TurnResult.parkedAsync →
 * TurnManager), фоновое продолжение {@code AsyncToolExecutor} публикует поздний
 * {@code TOOL_RESULT(late=true)} под sess-локом (D-64, «первый финальный выигрывает») с wake.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentTurnEngine {


    private final SessionStore sessionStore;
    private final LlmInvoker llmInvoker;
    private final NativeAgentTools agentTools;
    private final TransitionMetaTool transitionTool;
    private final SessionPromptBuilder promptBuilder;
    private final AsyncToolExecutor asyncExecutor;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;
    private final TaskProperties taskProperties;

    /**
     * Итог Turn'а: источник инструкции (J-1) и признак парковки в ожидании поздних
     * результатов async-инструментов (M3 D-60) — по нему {@code TurnManager} публикует
     * {@code PARKED_ASYNC} вместо {@code IDLE}. {@code source == null} — сессия исчезла.
     */
    public record TurnResult(InstructionSource source, boolean parkedAsync) {
    }

    /**
     * Прогон Turn'а; возвращает источник инструкции, поднятвшего его (J-1), и признак
     * парковки (M3). {@code null} — сессия исчезла, Turn не начинался.
     */
    public TurnResult run(UUID sessionId, TurnCancellation cancellation) {
        Session session = sessionStore.findSession(sessionId).orElse(null);
        if (session == null) {
            return null;
        }
        SessionStore.AgentRuntime agent = sessionStore.agentRuntime(session.agentRevisionId());
        List<ToolCallback> toolCallbacks = new ArrayList<>(agentTools.declarations(agent));
        if (session.kind() == SessionKind.STATE) {
            toolCallbacks.add(transitionTool.declaration());
        }
        InstructionSource source = instructionSource(session);
        TurnState state = new TurnState(source, session.lastConsumedSeq());

        long renderedWatermark = 0;
        while (true) {
            if (isCancelled(sessionId, cancellation)) {
                finishTurn(sessionId, TurnOutcome.CANCELLED, cappedConsumption(state, renderedWatermark));
                return new TurnResult(source, false);
            }

            Session current = sessionStore.findSession(sessionId).orElse(null);
            if (current == null) {
                return null;
            }
            long roundBasisSeq = current.lastSeq();
            int appendedByRound = 0;

            List<SessionMessageEntity> visible = sessionStore.renderVisible(sessionId);
            if (!visible.isEmpty()) {
                renderedWatermark = Math.max(renderedWatermark, visible.getLast().getId().seq());
            }
            trackPendingUser(state, visible);

            LlmRound round = callModel(agent, visible, toolCallbacks, cancellation);
            if (round.cancelled()) {
                finishTurn(sessionId, TurnOutcome.CANCELLED, cappedConsumption(state, renderedWatermark));
                return new TurnResult(source, false);
            }
            if (round.error() != null) {
                log.warn("LLM-вызов сессии {} не удался: {}", sessionId, round.error().getMessage());
                SessionStore.AppendedEvent systemEvent = sessionStore.appendEvent(
                        sessionId, MessageKind.SYSTEM, null, TurnPayloads.systemFailure(round.error()), null);
                finishTurn(sessionId, TurnOutcome.FAILED, cappedConsumption(state, systemEvent.seq()));
                return new TurnResult(source, false);
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

            List<PendingCall> syncCalls = new ArrayList<>();
            List<PendingCall> asyncCalls = new ArrayList<>();
            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                String callId = idGenerator.newUlid();
                Map<String, Object> arguments = parseArguments(toolCall.arguments());
                PendingCall pendingCall = new PendingCall(callId, toolCall.name(), arguments);
                if (asyncExecutor.isAsyncCapable(toolCall.name())) {
                    asyncCalls.add(pendingCall);
                } else {
                    syncCalls.add(pendingCall);
                }
                // Write-ahead (execution-model §1) — для всех вызовов: журнал всегда отражает
                // «в полёте»; крах окна закрывает рестарт-скан синтетическим LOST
                SessionStore.AppendedEvent toolCallEvent = sessionStore.appendEvent(
                        sessionId, MessageKind.TOOL_CALL, null,
                        TurnPayloads.toolCall(callId, toolCall.id(), toolCall.name(), arguments),
                        null);
                lastAppendedSeq = toolCallEvent.seq();
                appendedByRound++;
            }

            if (isCancelled(sessionId, cancellation)) {
                for (PendingCall pendingCall : syncCalls) {
                    sessionStore.appendEvent(sessionId, MessageKind.TOOL_RESULT, null,
                            TurnPayloads.toolResultSynthetic(
                                    pendingCall.callId(), pendingCall.tool(), ToolStatus.CANCELLED,
                                    "отменено пользователем"),
                            null);
                }
                for (PendingCall pendingCall : asyncCalls) {
                    sessionStore.appendEvent(sessionId, MessageKind.TOOL_RESULT, null,
                            TurnPayloads.toolResultSynthetic(
                                    pendingCall.callId(), pendingCall.tool(), ToolStatus.CANCELLED,
                                    "отменено пользователем"),
                            null);
                }
                // Watermark — по рендеру: собственные результаты и свежий USER поднимут новый Turn
                finishTurn(sessionId, TurnOutcome.CANCELLED, cappedConsumption(state, renderedWatermark));
                return new TurnResult(source, false);
            }

            if (syncCalls.isEmpty() && asyncCalls.isEmpty()) {
                Session after = sessionStore.findSession(sessionId).orElse(null);
                if (after == null) {
                    return null;
                }
                if (after.lastSeq() - roundBasisSeq == appendedByRound) {
                    finishTurn(sessionId, TurnOutcome.COMPLETED, cappedConsumption(state, lastAppendedSeq));
                    return new TurnResult(source, false);
                }
                log.debug("Сессия {}: новые события во время хода ({} > {}) — дополнительный раунд",
                        sessionId, after.lastSeq(), roundBasisSeq + appendedByRound);
                continue;
            }

            for (PendingCall pendingCall : syncCalls) {
                if (isCancelled(sessionId, cancellation)) {
                    sessionStore.appendEvent(sessionId, MessageKind.TOOL_RESULT, null,
                            TurnPayloads.toolResultSynthetic(pendingCall.callId(), pendingCall.tool(),
                                    ToolStatus.CANCELLED, "отменено пользователем"),
                            null);
                    continue;
                }
                ToolResult result = executeToolCall(session, state, pendingCall, cancellation);
                sessionStore.appendEvent(sessionId, MessageKind.TOOL_RESULT, null,
                        TurnPayloads.toolResult(pendingCall.callId(), pendingCall.tool(), result), null);
            }

            boolean parked = false;
            for (PendingCall asyncCall : asyncCalls) {
                if (isCancelled(sessionId, cancellation)) {
                    sessionStore.appendEvent(sessionId, MessageKind.TOOL_RESULT, null,
                            TurnPayloads.toolResultSynthetic(asyncCall.callId(), asyncCall.tool(),
                                    ToolStatus.CANCELLED, "отменено пользователем"),
                            null);
                    continue;
                }
                AsyncToolExecutor.Outcome outcome = asyncExecutor.execute(
                        sessionId, asyncCall.callId(), asyncCall.tool(), asyncCall.arguments(), cancellation);
                SessionStore.AppendedEvent resolutionEvent;
                if (outcome instanceof AsyncToolExecutor.Outcome.Resolved resolved) {
                    resolutionEvent = sessionStore.appendEvent(sessionId, MessageKind.TOOL_RESULT, null,
                            TurnPayloads.toolResult(asyncCall.callId(), asyncCall.tool(), resolved.result()), null);
                } else if (outcome instanceof AsyncToolExecutor.Outcome.Parked) {
                    // Плейсхолдер «принято, в полёте» (D-60/D-65): ход возвращается модели,
                    // сессия паркуется; TOOL_CALL уже в write-ahead — рестарт-скан надёжен
                    resolutionEvent = sessionStore.appendEvent(sessionId, MessageKind.ASYNC_ACCEPTED, null,
                            TurnPayloads.asyncAccepted(asyncCall.callId(), asyncCall.tool()), null);
                    parked = true;
                    log.info("Сессия {}: инструмент {} (callId {}) превысил окно — ASYNC_ACCEPTED, фон дорабатывает",
                            sessionId, asyncCall.tool(), asyncCall.callId());
                } else {
                    continue;
                }
                lastAppendedSeq = resolutionEvent.seq();
            }

            if (parked) {
                // D-60: парковка — состояние сессии (PARKED_ASYNC публикует TurnManager),
                // исход раунда COMPLETED при pending > 0; wake придёт с поздним TOOL_RESULT
                finishTurn(sessionId, TurnOutcome.COMPLETED, cappedConsumption(state, lastAppendedSeq));
                return new TurnResult(source, true);
            }
        }
    }

    /** Состояние Turn'а, изменяемое по ходу раундов (J-1/R-1: трекинг pending USER-намерения). */
    private static final class TurnState {
        private final InstructionSource source;
        private final AtomicInteger transitions = new AtomicInteger();
        /** Батч Turn'а: события позже него — новые для этого хода. */
        private final long baselineSeq;
        /** Минимальный seq USER-сообщения, отрендеренного этим не-USER-ходом; 0 — таких нет. */
        private long minPendingUserSeq;
        /** Подтверждённое намерение: гейт заблокировал transition при отрендеренном USER; 0 — нет. */
        private final AtomicLong userIntentSeq = new AtomicLong();

        private TurnState(InstructionSource source, long baselineSeq) {
            this.source = source;
            this.baselineSeq = baselineSeq;
        }
    }

    /** USER-сообщения, новые для этого хода (рендер видимого журнала после батча Turn'а). */
    private static void trackPendingUser(TurnState state, List<SessionMessageEntity> visible) {
        if (state.source == InstructionSource.USER) {
            return;
        }
        for (SessionMessageEntity message : visible) {
            if (message.getKind() == MessageKind.USER) {
                long seq = message.getId().seq();
                if (seq > state.baselineSeq && (state.minPendingUserSeq == 0 || seq < state.minPendingUserSeq)) {
                    state.minPendingUserSeq = seq;
                }
            }
        }
    }

    /**
     * Cap потребления при pending USER-намерении (J-1/R-1): {@code last_consumed_seq} не
     * двигается дальше USER-сообщения — оно остаётся непрочитанным, post-finish re-wake
     * {@code TurnManager}'а поднимает следующий Turn с {@code instructionSource=USER}.
     */
    private static long cappedConsumption(TurnState state, long consumedSeq) {
        long intent = state.userIntentSeq.get();
        return intent > 0 ? Math.min(consumedSeq, intent - 1) : consumedSeq;
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

    /**
     * Исполнение tool-call. Мета-инструменты (D-52/D-59) диспетчируются отдельно: гейт
     * {@code instructionSource = USER}, лимит {@code harness.task.transition.max-per-turn}
     * и применение (транзакционно, в момент tool-call). Нативные — как раньше.
     */
    private ToolResult executeToolCall(Session session, TurnState state, PendingCall pendingCall,
                                       TurnCancellation cancellation) {
        if (TransitionMetaTool.NAME.equals(pendingCall.tool())) {
            return executeTransition(session, state, pendingCall);
        }
        try {
            return agentTools.execute(session.id(), pendingCall.tool(), pendingCall.arguments(), cancellation);
        } catch (Exception e) {
            log.warn("Инструмент {} сессии {} упал: {}", pendingCall.tool(), session.id(), e.getMessage());
            return ToolResult.error(pendingCall.callId(), pendingCall.tool(), e.getMessage());
        }
    }

    /**
     * Гейт metaTools: USER-инструкция обязательна; сверх лимита — ошибка инструмента.
     * Блокировка гейтом при отрендеренном USER (не-USER-ход) — pending USER-намерение (J-1/R-1).
     */
    private ToolResult executeTransition(Session session, TurnState state, PendingCall pendingCall) {
        if (state.source != InstructionSource.USER) {
            if (state.userIntentSeq.get() == 0 && state.minPendingUserSeq > 0) {
                state.userIntentSeq.set(state.minPendingUserSeq);
                log.info("Сессия {}: гейт metaTools заблокировал transition — pending USER-намерение (seq={})",
                        session.id(), state.minPendingUserSeq);
            }
            return ToolResult.error(pendingCall.callId(), pendingCall.tool(),
                    "transition разрешён только в ходе, поднятом сообщением пользователя (instructionSource="
                            + state.source + ")");
        }
        if (session.kind() != SessionKind.STATE) {
            return ToolResult.error(pendingCall.callId(), pendingCall.tool(),
                    "transition доступен только в STATE-сессии задачи");
        }
        int limit = maxTransitionsPerTurn();
        if (state.transitions.incrementAndGet() > limit) {
            log.info("Гейт metaTools: transition сессии {} отклонён — лимит {} переходов на Turn",
                    session.id(), limit);
            return ToolResult.error(pendingCall.callId(), pendingCall.tool(),
                    "лимит переходов на Turn исчерпан (" + limit + ")");
        }
        try {
            return transitionTool.execute(session.id(), pendingCall.callId(), pendingCall.arguments());
        } catch (Exception e) {
            log.warn("Инструмент {} сессии {} упал: {}", pendingCall.tool(), session.id(), e.getMessage());
            return ToolResult.error(pendingCall.callId(), pendingCall.tool(), e.getMessage());
        }
    }

    private int maxTransitionsPerTurn() {
        TaskProperties.Transition transition = taskProperties.transition();
        Integer limit = transition == null ? null : transition.maxPerTurn();
        return limit == null || limit < 1 ? 1 : limit;
    }

    /**
     * Источник инструкции Turn'а (D-59) — по составу незапрошенного батча
     * {@code (lastConsumedSeq, …]}: USER-сообщение → USER; только TOOL_RESULT → продолжение
     * после инструмента; иначе — системное событие (seed/bootstrap).
     */
    private InstructionSource instructionSource(Session session) {
        List<MessageKind> pendingKinds = sessionStore.findPendingKinds(session.id(), session.lastConsumedSeq());
        if (pendingKinds.contains(MessageKind.USER)) {
            return InstructionSource.USER;
        }
        if (pendingKinds.contains(MessageKind.TOOL_RESULT)) {
            return InstructionSource.TOOL_RESULT;
        }
        return InstructionSource.SYSTEM;
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
