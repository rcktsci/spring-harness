package se.rocketscien.harness.execution;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import se.rocketscien.harness.session.SessionMessageEntity;
import se.rocketscien.harness.session.SessionStore;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Видимый журнал → промпт (execution-model §2: «рендер видимых → LlmGateway.stream»):
 * ASSISTANT с последующими TOOL_CALL сливается в одно сообщение с tool-calls, TOOL_RESULT
 * возвращается провайдеру по его {@code tool_call_id} (agent-tools §5), COMPACT-пересказ —
 * SYSTEM-сообщением. Декларации инструментов в options добавляет {@code LlmInvoker}.
 *
 * <p>D-J-3 (OpenAI-протокол): tool-ответы обязаны идти строго сразу за своим
 * {@code assistant(tool_calls)}; события, interleaved'нутые в журнал между tool-calls и
 * их результатами (USER во время долгого bash), выносятся ПОСЛЕ группы tool-ответов —
 * порядок ролей валиден при любом интерлингe.</p>
 *
 * <p>M3 (D-60/D-65): плейсхолдер {@code ASYNC_ACCEPTED} рендерится tool-ответом «принято,
 * в полёте» с id = callId; поздний {@code TOOL_RESULT} (payload late=true) — с уникальным
 * id {@code callId + "-late"} (OpenAI-протокол не допускает дубль tool_call_id).</p>
 */
@Component
@RequiredArgsConstructor
public class SessionPromptBuilder {

    private static final String TOOL_CALL_TYPE = "function";

    private static final String ACCEPTED_OUTPUT =
            "принято: исполняется в фоне, результат придёт отдельным сообщением";

    private final ObjectMapper objectMapper;

    public Prompt buildPrompt(SessionStore.AgentRuntime agent, List<SessionMessageEntity> visible) {
        List<Message> messages = new ArrayList<>();
        if (agent.rolePrompt() != null && !agent.rolePrompt().isBlank()) {
            messages.add(new SystemMessage(agent.rolePrompt()));
        }

        ToolGroup group = null;
        List<Message> heldAfterGroup = new ArrayList<>();
        Map<String, String> providerIds = new HashMap<>();
        for (SessionMessageEntity event : visible) {
            Map<String, Object> payload = event.getPayloadJsonb();
            switch (event.getKind()) {
                case USER -> {
                    if (group != null) {
                        heldAfterGroup.add(new UserMessage(TurnPayloads.text(payload)));
                    } else {
                        messages.add(new UserMessage(TurnPayloads.text(payload)));
                    }
                }
                case SYSTEM -> {
                    if (group != null) {
                        heldAfterGroup.add(new SystemMessage(TurnPayloads.text(payload)));
                    } else {
                        messages.add(new SystemMessage(TurnPayloads.text(payload)));
                    }
                }
                case COMPACT -> {
                    Message compact = new SystemMessage(
                            "Предыдущий контекст (компакция): " + TurnPayloads.summary(payload));
                    if (group != null) {
                        heldAfterGroup.add(compact);
                    } else {
                        messages.add(compact);
                    }
                }
                case ASSISTANT -> {
                    flush(messages, group, heldAfterGroup);
                    group = new ToolGroup(TurnPayloads.text(payload));
                }
                case TOOL_CALL -> {
                    if (group == null) {
                        group = new ToolGroup("");
                    }
                    String callId = TurnPayloads.callId(payload);
                    String providerId = TurnPayloads.toolCallId(payload);
                    if (callId != null && providerId != null) {
                        providerIds.put(callId, providerId);
                    }
                    group.toolCalls().add(new AssistantMessage.ToolCall(
                            providerId, TOOL_CALL_TYPE, TurnPayloads.tool(payload),
                            argumentsJson(TurnPayloads.arguments(payload))));
                }
                case TOOL_RESULT -> {
                    if (group == null) {
                        // Сиротский результат без assistant-группы — наружу как есть
                        // (в валидном журнале не встречается)
                        messages.add(toolResponseMessage(providerIds, payload));
                    } else {
                        group.responses().add(toolResponse(providerIds, payload));
                    }
                }
                case ASYNC_ACCEPTED -> {
                    // D-65: плейсхолдер «принято, в полёте» модель видит tool-ответом с
                    // уникальным id (= callId); поздний результат придёт отдельным сообщением
                    ToolResponseMessage.ToolResponse accepted = acceptedResponse(payload);
                    if (group == null) {
                        messages.add(ToolResponseMessage.builder().responses(List.of(accepted)).build());
                    } else {
                        group.responses().add(accepted);
                    }
                }
                default -> flush(messages, group, heldAfterGroup);
            }
        }
        flush(messages, group, heldAfterGroup);

        return new Prompt(messages);
    }

    private void flush(List<Message> messages, ToolGroup group, List<Message> heldAfterGroup) {
        if (group == null) {
            return;
        }
        messages.add(AssistantMessage.builder()
                .content(group.text())
                .toolCalls(group.toolCalls())
                .build());
        if (!group.responses().isEmpty()) {
            messages.add(ToolResponseMessage.builder().responses(group.responses()).build());
        }
        messages.addAll(heldAfterGroup);
        heldAfterGroup.clear();
    }

    private ToolResponseMessage toolResponseMessage(Map<String, String> providerIds, Map<String, Object> payload) {
        return ToolResponseMessage.builder()
                .responses(List.of(toolResponse(providerIds, payload)))
                .build();
    }

    private ToolResponseMessage.ToolResponse toolResponse(Map<String, String> providerIds,
                                                          Map<String, Object> payload) {
        String callId = TurnPayloads.callId(payload);
        String providerId = providerIdFor(providerIds, payload, callId);
        return new ToolResponseMessage.ToolResponse(providerId, TurnPayloads.tool(payload), outputOf(payload));
    }

    /**
     * id tool-ответа для провайдера (D-65): обычный результат — по карте провайдерских id;
     * поздний (late=true) — с уникальным {@code callId + "-late"} (дубль id провайдер
     * отверг бы); ASYNC_ACCEPTED — с id = callId (уникальный, в карте провайдерских нет).
     */
    private String providerIdFor(Map<String, String> providerIds, Map<String, Object> payload, String callId) {
        if (callId == null) {
            return null;
        }
        if (TurnPayloads.late(payload)) {
            return callId + TurnPayloads.LATE_ID_SUFFIX;
        }
        return providerIds.getOrDefault(callId, callId);
    }

    /** Текст tool-ответа плейсхолдера: модель знает, что результат придёт позже. */
    private ToolResponseMessage.ToolResponse acceptedResponse(Map<String, Object> payload) {
        String callId = TurnPayloads.callId(payload);
        return new ToolResponseMessage.ToolResponse(callId, TurnPayloads.tool(payload), ACCEPTED_OUTPUT);
    }

    private String argumentsJson(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String outputOf(Map<String, Object> payload) {
        if (payload != null && payload.get(TurnPayloads.OUTPUT) instanceof String output) {
            return output;
        }
        return "";
    }

    private record ToolGroup(String text,
                             List<AssistantMessage.ToolCall> toolCalls,
                             List<ToolResponseMessage.ToolResponse> responses) {

        private ToolGroup(String text) {
            this(text, new ArrayList<>(), new ArrayList<>());
        }
    }
}
