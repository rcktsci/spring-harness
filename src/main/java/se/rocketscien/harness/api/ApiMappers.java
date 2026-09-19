package se.rocketscien.harness.api;

import se.rocketscien.harness.api.gen.model.AgentRef;
import se.rocketscien.harness.api.gen.model.CommentDto;
import se.rocketscien.harness.api.gen.model.MessageDto;
import se.rocketscien.harness.api.gen.model.SessionDto;
import se.rocketscien.harness.api.gen.model.SessionStatusEvent;
import se.rocketscien.harness.api.gen.model.SessionTreeNode;
import se.rocketscien.harness.api.gen.model.SubtaskTerminalEvent;
import se.rocketscien.harness.api.gen.model.TaskCommentEvent;
import se.rocketscien.harness.api.gen.model.TaskDto;
import se.rocketscien.harness.api.gen.model.TaskStatusEvent;
import se.rocketscien.harness.api.gen.model.TaskStatusProjection;
import se.rocketscien.harness.api.gen.model.TaskTransitionEvent;
import se.rocketscien.harness.api.gen.model.TaskTreeNode;
import se.rocketscien.harness.api.gen.model.TransitionDto;
import se.rocketscien.harness.api.gen.model.TransitionKind;
import se.rocketscien.harness.api.gen.model.WorkflowRef;
import se.rocketscien.harness.api.gen.model.WorkspaceBinding;
import se.rocketscien.harness.execution.TurnPayloads;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionEvent;
import se.rocketscien.harness.session.SessionMessageEntity;
import se.rocketscien.harness.session.SessionRuntimeStatus;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.session.TurnOutcome;
import se.rocketscien.harness.task.Comment;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskEvent;
import se.rocketscien.harness.task.TaskStatus;
import se.rocketscien.harness.task.Transition;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Тонкие ручные мапперы домен → сгенерированные DTO (шаг 2 contract-first). Время — ISO-8601 UTC
 * (Instant → OffsetDateTime UTC); перечисления домена и спеки совпадают по именам (name-based
 * конвертация); workspace — SERVER_DIR auto (M1); поле MessageDto.late (M3) не заполняется.
 * Перечисления генерации — FQDN: простые имена заняты доменными (SessionKind, MessageKind и пр.).
 */
final class ApiMappers {

    private ApiMappers() {
    }

    static SessionDto toDto(Session session, SessionStore.AgentRevisionSummary agent, String ownerUsername,
                            SessionRuntimeStatus runtimeStatus) {
        SessionDto dto = new SessionDto(
                session.id(),
                se.rocketscien.harness.api.gen.model.SessionKind.valueOf(session.kind().name()),
                session.title(),
                ownerUsername,
                new AgentRef(agent.agentKey(), agent.rev()),
                new WorkspaceBinding(WorkspaceBinding.TypeEnum.SERVER_DIR),
                toGenRuntimeStatus(runtimeStatus),
                session.lastSeq(),
                utc(session.lastActivityAt()),
                utc(session.createdAt())
        );
        if (session.lastTurnOutcome() != null) {
            dto.setLastTurnOutcome(toGenTurnOutcome(session.lastTurnOutcome()));
        }
        return dto;
    }

    static SessionStatusEvent toStatusEvent(SessionRuntimeStatus runtimeStatus, TurnOutcome lastTurnOutcome) {
        SessionStatusEvent event = new SessionStatusEvent(toGenRuntimeStatus(runtimeStatus));
        if (lastTurnOutcome != null) {
            event.setLastTurnOutcome(toGenTurnOutcome(lastTurnOutcome));
        }
        return event;
    }

    static MessageDto toDto(SessionMessageEntity message, Map<UUID, String> usernames) {
        MessageDto dto = new MessageDto(
                message.getUlid(),
                message.getId().seq(),
                se.rocketscien.harness.api.gen.model.MessageKind.valueOf(message.getKind().name()),
                message.getPayloadJsonb() == null ? Map.of() : message.getPayloadJsonb(),
                utc(message.getCreatedAt())
        );
        fillAuthorAndCallId(dto, message.getKind(),
                message.getKind() == MessageKind.USER && message.getAuthorUserId() != null
                        ? usernames.get(message.getAuthorUserId())
                        : null,
                TurnPayloads.callId(message.getPayloadJsonb()));
        if (message.getTokens() != null) {
            dto.setTokens(message.getTokens());
        }
        return dto;
    }

    static MessageDto toDto(SessionEvent.MessageCreated message, String authorUsername) {
        MessageDto dto = new MessageDto(
                message.ulid(),
                message.seq(),
                se.rocketscien.harness.api.gen.model.MessageKind.valueOf(message.kind().name()),
                message.payload() == null ? Map.of() : message.payload(),
                utc(message.createdAt())
        );
        fillAuthorAndCallId(dto, message.kind(),
                message.kind() == MessageKind.USER && message.authorUserId() != null
                        ? authorUsername
                        : null,
                TurnPayloads.callId(message.payload()));
        if (message.tokens() != null) {
            dto.setTokens(message.tokens());
        }
        return dto;
    }

    private static void fillAuthorAndCallId(MessageDto dto, MessageKind kind, String author, String callId) {
        if (author != null) {
            dto.setAuthor(author);
        }
        if (kind == MessageKind.TOOL_CALL || kind == MessageKind.TOOL_RESULT) {
            dto.setCallId(callId);
        }
    }

    private static se.rocketscien.harness.api.gen.model.SessionRuntimeStatus toGenRuntimeStatus(
            SessionRuntimeStatus status) {
        return se.rocketscien.harness.api.gen.model.SessionRuntimeStatus.valueOf(status.name());
    }

    private static se.rocketscien.harness.api.gen.model.TurnOutcome toGenTurnOutcome(TurnOutcome outcome) {
        return outcome == null ? null
                : se.rocketscien.harness.api.gen.model.TurnOutcome.valueOf(outcome.name());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** Кадр {@code task.status} (снапшот при коннекте/реконнекте — тот же payload). */
    static TaskStatusEvent toStatusEvent(UUID taskId, String currentState, TaskStatus projection,
                                         boolean suspended, long taskEventSeq) {
        return new TaskStatusEvent(taskId, currentState, genProjection(projection), suspended, taskEventSeq);
    }

    static TaskTransitionEvent toEvent(TaskEvent.Transition event) {
        return new TaskTransitionEvent(event.id(), event.taskId(), event.fromState(), event.toState(),
                TransitionKind.valueOf(event.kind().name()), event.reason(), utc(event.createdAt()));
    }

    static TaskStatusEvent toEvent(TaskEvent.Status event) {
        return toStatusEvent(event.taskId(), event.currentState(), event.statusProjection(),
                event.suspended(), event.seq());
    }

    static SubtaskTerminalEvent toEvent(TaskEvent.SubtaskTerminal event) {
        return new SubtaskTerminalEvent(event.taskId(), event.terminalTaskId(),
                genProjection(event.terminalStatus()));
    }

    /** {@code author} — username; null для системных комментариев (разрешение — контроллер). */
    static TaskCommentEvent toEvent(TaskEvent.Comment event, String authorUsername) {
        return new TaskCommentEvent(event.id(), event.taskId(), event.body(), authorUsername,
                utc(event.createdAt()));
    }

    private static TaskStatusProjection genProjection(TaskStatus status) {
        return TaskStatusProjection.valueOf(status.name());
    }

    // ---------------------------------------------------------------- задачи (пачка K.1)

    /**
     * Задача → TaskDto (api-contracts §4.1). {@code owner}/{@code author} — username (NULL-автор —
     * агентская запись); {@code webhookUrl} — capability-URL, только в WAIT_WEBHOOK-состоянии
     * (null в остальных — поле опущено); {@code workspaceBindings} — M4 (CLIENT_EXEC), в M2 пусто.
     */
    static TaskDto toDto(Task task, String ownerUsername, String authorUsername, WorkflowRef workflow,
                         URI webhookUrl) {
        TaskDto dto = new TaskDto(
                task.id(),
                task.title(),
                task.description(),
                ownerUsername,
                authorUsername,
                workflow,
                task.currentState(),
                genProjection(task.statusProjection()),
                task.suspended(),
                task.tags(),
                task.params(),
                utc(task.createdAt()),
                utc(task.updatedAt())
        );
        if (task.parentTaskId() != null) {
            dto.setParentTaskId(task.parentTaskId());
        }
        if (webhookUrl != null) {
            dto.setWebhookUrl(webhookUrl);
        }
        return dto;
    }

    /** Комментарий; {@code authorUsername} — null для агентского (NULL + агент-пометка). */
    static CommentDto toDto(Comment comment, String authorUsername) {
        CommentDto dto = new CommentDto(comment.id(), comment.taskId(), comment.body(),
                utc(comment.createdAt()));
        if (authorUsername != null) {
            dto.setAuthor(authorUsername);
        }
        return dto;
    }

    /** Переход истории; reason — свободная структура, пробрасывается как есть. */
    static TransitionDto toDto(Transition transition) {
        return new TransitionDto(
                transition.id(),
                transition.fromState(),
                transition.toState(),
                TransitionKind.valueOf(transition.kind().name()),
                transition.reason(),
                utc(transition.createdAt())
        );
    }

    /** Узел поддерева задач (BFS-структура реестра) — рекурсивно в сгенерированный TaskTreeNode. */
    static TaskTreeNode toNode(se.rocketscien.harness.task.TaskTreeNode node) {
        TaskTreeNode dto = new TaskTreeNode(
                node.task().id(),
                node.task().title(),
                node.task().currentState(),
                genProjection(node.task().statusProjection()),
                node.task().suspended()
        );
        for (se.rocketscien.harness.task.TaskTreeNode child : node.children()) {
            dto.addChildrenItem(toNode(child));
        }
        return dto;
    }

    /** Узел дерева сессий: STATE-узел дополнительно несёт taskId/stateCode (M2). */
    static SessionTreeNode toNode(Session session, AgentRef agent, SessionRuntimeStatus runtimeStatus) {
        SessionTreeNode node = new SessionTreeNode(
                session.id(),
                session.parentSessionId(),
                se.rocketscien.harness.api.gen.model.SessionKind.valueOf(session.kind().name()),
                agent,
                toGenRuntimeStatus(runtimeStatus),
                session.lastSeq(),
                utc(session.lastActivityAt())
        );
        if (session.kind() == se.rocketscien.harness.session.SessionKind.STATE) {
            node.setTaskId(session.taskId());
            node.setStateCode(session.stateCode());
        }
        return node;
    }
}
