package se.rocketscien.harness.tests.execution;

import se.rocketscien.harness.execution.SessionPromptBuilder;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.SessionMessageEntity;
import se.rocketscien.harness.session.SessionMessageId;
import se.rocketscien.harness.session.SessionStore;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 7.2: журнал → промпт: ролевой SYSTEM, USER/ASSISTANT/SYSTEM-тексты, слияние
 * ASSISTANT+TOOL_CALL в одно сообщение с tool-calls, TOOL_RESULT → tool-ответы по
 * провайдерскому tool_call_id, COMPACT-пересказ как SYSTEM.
 */
class SessionPromptBuilderTest {

    private final SessionPromptBuilder builder =
            new SessionPromptBuilder(JsonMapper.builder().build());
    private final UUID sessionId = UUID.randomUUID();

    @Test
    void buildsRolePromptAndPlainTurns() {
        List<Message> messages = builder.buildPrompt(
                agent("Ты ревьюер.", null),
                List.of(
                        event(1, MessageKind.USER, Map.of("text", "привет")),
                        event(2, MessageKind.ASSISTANT, Map.of("text", "ответ"))
                )).getInstructions();

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(0).getText()).isEqualTo("Ты ревьюер.");
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(1).getText()).isEqualTo("привет");
        assertThat(messages.get(2)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(2).getText()).isEqualTo("ответ");
    }

    @Test
    void mergesAssistantWithFollowingToolCalls() {
        List<Message> messages = builder.buildPrompt(
                agent(null, null),
                List.of(
                        event(1, MessageKind.ASSISTANT, Map.of("text", "смотрю файл")),
                        toolCallEvent(2, "call-internal-1", "call-provider-1", "read_file", Map.of("path", "a.txt")),
                        toolCallEvent(3, "call-internal-2", "call-provider-2", "bash", Map.of("command", "ls"))
                )).getInstructions();

        assertThat(messages).hasSize(1);
        AssistantMessage assistant = (AssistantMessage) messages.get(0);
        assertThat(assistant.getText()).isEqualTo("смотрю файл");
        assertThat(assistant.getToolCalls()).hasSize(2);
        assertThat(assistant.getToolCalls().get(0).id()).isEqualTo("call-provider-1");
        assertThat(assistant.getToolCalls().get(0).name()).isEqualTo("read_file");
        assertThat(assistant.getToolCalls().get(0).arguments()).isEqualTo("{\"path\":\"a.txt\"}");
        assertThat(assistant.getToolCalls().get(1).id()).isEqualTo("call-provider-2");
    }

    @Test
    void toolResultMapsBackToProviderCallId() {
        List<Message> messages = builder.buildPrompt(
                agent(null, null),
                List.of(
                        event(1, MessageKind.ASSISTANT, Map.of("text", "")),
                        toolCallEvent(2, "call-internal-1", "call-provider-1", "bash", Map.of("command", "ls")),
                        event(3, MessageKind.TOOL_RESULT, Map.of(
                                "callId", "call-internal-1", "tool", "bash", "status", "OK", "output", "file-listing"))
                )).getInstructions();

        assertThat(messages).hasSize(2);
        assertThat(messages.get(1)).isInstanceOf(ToolResponseMessage.class);
        ToolResponseMessage toolResponse = (ToolResponseMessage) messages.get(1);
        assertThat(toolResponse.getResponses()).hasSize(1);
        assertThat(toolResponse.getResponses().getFirst().id()).isEqualTo("call-provider-1");
        assertThat(toolResponse.getResponses().getFirst().name()).isEqualTo("bash");
        assertThat(toolResponse.getResponses().getFirst().responseData()).isEqualTo("file-listing");
    }

    @Test
    void compactSummaryBecomesSystemMessage() {
        List<Message> messages = builder.buildPrompt(
                agent(null, null),
                List.of(
                        event(1, MessageKind.COMPACT, Map.of("covers", List.of(), "summary", "ранее обсудили а, б, в")),
                        event(2, MessageKind.USER, Map.of("text", "новый вопрос"))
                )).getInstructions();

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(0).getText()).contains("ранее обсудили а, б, в");
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
    }

    @Test
    void toolCallWithoutPrecedingAssistantGetsEmptyAssistantMessage() {
        List<Message> messages = builder.buildPrompt(
                agent(null, null),
                List.of(toolCallEvent(1, "call-internal-1", "call-provider-1", "glob", Map.of("pattern", "*.java")))).getInstructions();

        assertThat(messages).hasSize(1);
        AssistantMessage assistant = (AssistantMessage) messages.get(0);
        assertThat(assistant.getText()).isEmpty();
        assertThat(assistant.getToolCalls()).hasSize(1);
    }

    @Test
    void missingTextFallsBackToEmptyString() {
        List<Message> messages = builder.buildPrompt(
                agent(null, null),
                List.of(event(1, MessageKind.USER, Map.of("другое", "поле")))).getInstructions();

        assertThat(messages.getFirst().getText()).isEmpty();
    }

    @Test
    void interleavedUserGoesAfterToolResponseGroup() {
        List<Message> messages = builder.buildPrompt(
                agent(null, null),
                List.of(
                        event(1, MessageKind.ASSISTANT, Map.of("text", "смотрю файл")),
                        toolCallEvent(2, "call-internal-1", "call-provider-1", "bash", Map.of("command", "ls")),
                        event(3, MessageKind.USER, Map.of("text", "межходовое сообщение")),
                        event(4, MessageKind.TOOL_RESULT, Map.of(
                                "callId", "call-internal-1", "tool", "bash", "status", "OK", "output", "listing")),
                        event(5, MessageKind.ASSISTANT, Map.of("text", "ответ"))
                )
        ).getInstructions();

        // OpenAI-протокол (D-J-3): tool-ответы строго сразу за assistant(tool_calls),
        // USER между ними — после группы tool-ответов
        assertThat(messages).hasSize(4);
        AssistantMessage assistant = (AssistantMessage) messages.get(0);
        assertThat(assistant.hasToolCalls()).isTrue();
        assertThat(assistant.getToolCalls().getFirst().id()).isEqualTo("call-provider-1");
        assertThat(messages.get(1)).isInstanceOf(ToolResponseMessage.class);
        assertThat(((ToolResponseMessage) messages.get(1)).getResponses().getFirst().id())
                .isEqualTo("call-provider-1");
        assertThat(messages.get(2)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(2).getText()).isEqualTo("межходовое сообщение");
        assertThat(messages.get(3)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(3).getText()).isEqualTo("ответ");
    }

    @Test
    void asyncAcceptedRendersAsToolResponseWithInternalCallId() {
        // D-65: плейсхолдер «принято, в полёте» — tool-ответ с id = callId (не провайдерский)
        List<Message> messages = builder.buildPrompt(
                agent(null, null),
                List.of(
                        event(1, MessageKind.ASSISTANT, Map.of("text", "запускаю долгую команду")),
                        toolCallEvent(2, "call-internal-1", "call-provider-1", "bash", Map.of("command", "sleep 30")),
                        event(3, MessageKind.ASYNC_ACCEPTED, Map.of(
                                "callId", "call-internal-1", "tool", "bash"))
                )).getInstructions();

        assertThat(messages).hasSize(2);
        AssistantMessage assistant = (AssistantMessage) messages.get(0);
        assertThat(assistant.getToolCalls()).hasSize(1);
        assertThat(messages.get(1)).isInstanceOf(ToolResponseMessage.class);
        ToolResponseMessage.ToolResponse response =
                ((ToolResponseMessage) messages.get(1)).getResponses().getFirst();
        assertThat(response.id()).isEqualTo("call-internal-1");
        assertThat(response.name()).isEqualTo("bash");
        assertThat(response.responseData()).contains("принято");
    }

    @Test
    void lateToolResultGetsUniqueProviderId() {
        // D-65: поздний результат — с уникальным id callId + "-late" (дубль tool_call_id недопустим)
        List<Message> messages = builder.buildPrompt(
                agent(null, null),
                List.of(
                        event(1, MessageKind.ASSISTANT, Map.of("text", "")),
                        toolCallEvent(2, "call-internal-1", "call-provider-1", "bash", Map.of("command", "sleep 30")),
                        event(3, MessageKind.ASYNC_ACCEPTED, Map.of(
                                "callId", "call-internal-1", "tool", "bash")),
                        event(4, MessageKind.TOOL_RESULT, Map.of(
                                "callId", "call-internal-1", "tool", "bash", "status", "OK",
                                "output", "готово", "late", true))
                )).getInstructions();

        assertThat(messages).hasSize(2);
        assertThat(messages.get(1)).isInstanceOf(ToolResponseMessage.class);
        List<ToolResponseMessage.ToolResponse> responses =
                ((ToolResponseMessage) messages.get(1)).getResponses();
        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).id()).isEqualTo("call-internal-1");
        assertThat(responses.get(1).id()).isEqualTo("call-internal-1-late");
        assertThat(responses.get(1).responseData()).isEqualTo("готово");
    }

    private SessionStore.AgentRuntime agent(String rolePrompt, Map<String, Object> permissions) {
        return new SessionStore.AgentRuntime(
                UUID.randomUUID(), "agent", 1, rolePrompt, null, permissions, UUID.randomUUID());
    }

    private SessionMessageEntity event(long seq, MessageKind kind, Map<String, Object> payload) {
        return new SessionMessageEntity(
                new SessionMessageId(sessionId, seq), "ulid-" + seq, kind, null, payload, null, Instant.now());
    }

    private SessionMessageEntity toolCallEvent(long seq, String callId, String toolCallId, String tool,
                                               Map<String, Object> arguments) {
        return event(seq, MessageKind.TOOL_CALL, Map.of(
                "callId", callId, "toolCallId", toolCallId, "tool", tool, "arguments", arguments));
    }
}
