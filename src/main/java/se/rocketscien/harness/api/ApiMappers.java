package se.rocketscien.harness.api;

import se.rocketscien.harness.api.gen.model.AgentRef;
import se.rocketscien.harness.api.gen.model.MessageDto;
import se.rocketscien.harness.api.gen.model.SessionDto;
import se.rocketscien.harness.api.gen.model.SessionStatusEvent;
import se.rocketscien.harness.api.gen.model.WorkspaceBinding;
import se.rocketscien.harness.execution.TurnPayloads;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionEvent;
import se.rocketscien.harness.session.SessionMessageEntity;
import se.rocketscien.harness.session.SessionRuntimeStatus;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.session.TurnOutcome;

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
}
