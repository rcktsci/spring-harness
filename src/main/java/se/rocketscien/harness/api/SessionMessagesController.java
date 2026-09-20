package se.rocketscien.harness.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import se.rocketscien.harness.api.gen.SessionMessagesApi;
import se.rocketscien.harness.api.gen.model.MessageDto;
import se.rocketscien.harness.api.gen.model.MessagePage;
import se.rocketscien.harness.api.gen.model.SendMessageAccepted;
import se.rocketscien.harness.api.gen.model.SendMessageRequest;
import se.rocketscien.harness.config.LimitsProperties;
import se.rocketscien.harness.execution.TurnManager;
import se.rocketscien.harness.execution.TurnPayloads;
import se.rocketscien.harness.identity.AppUserDirectory;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.SessionMessageEntity;
import se.rocketscien.harness.session.SessionNotFoundException;
import se.rocketscien.harness.session.SessionStore;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Журнал сообщений сессии (api-contracts §2): POST — допись USER-события (атрибуция из JWT)
 * с немедленным wake EVENT ({@code TurnManager.tryStart}); GET — видимые события за (since, …]
 * по возрастанию seq с конверт-пагинацией (nextCursor = seq последнего события страницы).
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class SessionMessagesController implements SessionMessagesApi {

    private final SessionStore sessionStore;
    private final TurnManager turnManager;
    private final AppUserDirectory users;
    private final Caller caller;
    private final LimitsProperties limits;

    @Override
    public ResponseEntity<SendMessageAccepted> sendMessage(UUID id, SendMessageRequest sendMessageRequest) {
        rejectBlank(sendMessageRequest.getText());

        UUID authorId = caller.userId();
        // USER-допись сама снимает персистентный stop (O-2: сброс cancel_requested в
        // SessionStore.appendEvent) — явный resume для гейта tryStart
        SessionStore.AppendedEvent appended = sessionStore.appendEvent(
                id, MessageKind.USER, authorId, Map.of(TurnPayloads.TEXT, sendMessageRequest.getText()));

        // Wake EVENT (7.3/8.3): допись журнала и start Turn'а — разные контуры; неудачный
        // старт не влияет на 202: недоставленное подберёт POLL
        try {
            turnManager.tryStart(id);
        } catch (RuntimeException e) {
            log.warn("Wake EVENT сессии {} не удался — подберёт POLL: {}", id, e.getMessage());
        }
        return ResponseEntity.accepted().body(new SendMessageAccepted(appended.ulid(), appended.seq()));
    }

    @Override
    public ResponseEntity<MessagePage> listMessages(UUID id, Long since, Integer limit) {
        if (!sessionStore.findSession(id).isPresent()) {
            throw new SessionNotFoundException("Сессия %s не найдена".formatted(id));
        }
        long cursor = since == null ? 0L : since;
        int pageSize = clampLimit(limit);

        List<SessionMessageEntity> visible = sessionStore.renderVisible(id).stream()
                .filter(message -> message.getId().seq() > cursor)
                .limit(pageSize + 1L)
                .toList();

        boolean hasMore = visible.size() > pageSize;
        List<SessionMessageEntity> page = hasMore ? visible.subList(0, pageSize) : visible;

        Set<UUID> authorIds = page.stream()
                .map(SessionMessageEntity::getAuthorUserId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> usernames = users.usernames(authorIds);

        MessagePage result = new MessagePage(page.stream()
                .map(message -> ApiMappers.toDto(message, usernames))
                .toList());
        if (hasMore) {
            result.setNextCursor(page.getLast().getId().seq());
        }
        return ResponseEntity.ok(result);
    }

    private int clampLimit(Integer limit) {
        int max = limits.page();
        return limit == null ? max : Math.min(limit, max);
    }

    private static void rejectBlank(String text) {
        if (text != null && text.isBlank()) {
            throw new ApiValidationException(List.of(new ApiValidationException.ValidationError(
                    "/text", "blank", "text не может быть пустой строкой")));
        }
    }
}
