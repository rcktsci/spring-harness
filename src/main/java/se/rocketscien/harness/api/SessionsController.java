package se.rocketscien.harness.api;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import se.rocketscien.harness.api.gen.SessionsApi;
import se.rocketscien.harness.api.gen.model.AgentRef;
import se.rocketscien.harness.api.gen.model.CreateSessionRequest;
import se.rocketscien.harness.api.gen.model.SessionDto;
import se.rocketscien.harness.api.gen.model.SessionKind;
import se.rocketscien.harness.api.gen.model.SessionPage;
import se.rocketscien.harness.api.gen.model.SessionTreeNode;
import se.rocketscien.harness.api.gen.model.SessionTreePage;
import se.rocketscien.harness.api.gen.model.UpdateSessionRequest;
import se.rocketscien.harness.config.LimitsProperties;
import se.rocketscien.harness.identity.AppUserDirectory;
import se.rocketscien.harness.session.InvalidCursorException;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionEventBroadcaster;
import se.rocketscien.harness.session.SessionNotFoundException;
import se.rocketscien.harness.session.SessionStore;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Сессии (api-contracts §2): создание FREE (201+Location), чтение, список с фильтрами
 * mine/kind/q и конверт-пагинацией, переименование merge-patch (RFC 7396): absent — не менять,
 * null — очистить, ""/" " — 422, неизвестные члены — игнорировать.
 */
@RestController
@RequiredArgsConstructor
public class SessionsController implements SessionsApi {

    private final SessionStore sessionStore;
    private final SessionEventBroadcaster broadcaster;
    private final AppUserDirectory users;
    private final Caller caller;
    private final LimitsProperties limits;

    @Override
    public ResponseEntity<SessionDto> createSession(CreateSessionRequest createSessionRequest) {
        rejectBlank("/title", createSessionRequest.getTitle());
        rejectBlank("/agentKey", createSessionRequest.getAgentKey());

        UUID ownerId = caller.userId();
        Session session = sessionStore.createFreeSession(
                ownerId,
                createSessionRequest.getAgentKey(),
                createSessionRequest.getAgentRev(),
                createSessionRequest.getTitle()
        );
        return ResponseEntity
                .created(URI.create("/api/v1/sessions/" + session.id()))
                .body(toDto(session, ownerId));
    }

    @Override
    public ResponseEntity<SessionDto> getSession(UUID id) {
        Session session = sessionStore.findSession(id)
                .orElseThrow(() -> new SessionNotFoundException("Сессия %s не найдена".formatted(id)));
        return ResponseEntity.ok(toDto(session, session.ownerUserId()));
    }

    @Override
    public ResponseEntity<SessionPage> listSessions(Boolean mine, SessionKind kind,
                                                    String q, String cursor, Integer limit) {
        UUID callerUserId = caller.userId();
        SessionStore.SessionSearchResult result;
        try {
            result = sessionStore.searchSessions(new SessionStore.SessionSearchCriteria(
                    Boolean.TRUE.equals(mine) ? callerUserId : null,
                    kind == null ? null : se.rocketscien.harness.session.SessionKind.valueOf(kind.name()),
                    q,
                    cursor,
                    clampLimit(limit)
            ));
        } catch (InvalidCursorException e) {
            // Битый/подделанный opaque-курсор — ошибка клиента, не сервера (E-J-3)
            throw new ApiValidationException(List.of(new ApiValidationException.ValidationError(
                    "/cursor", "cursor", "Курсор страницы некорректен")));
        }
        Map<UUID, SessionStore.AgentRevisionSummary> agents =
                sessionStore.agentSummaries(result.items().stream().map(Session::agentRevisionId).toList());
        Map<UUID, String> usernames =
                users.usernames(result.items().stream().map(Session::ownerUserId).toList());

        SessionPage page = new SessionPage(result.items().stream()
                .map(session -> ApiMappers.toDto(
                        session,
                        agents.get(session.agentRevisionId()),
                        usernames.get(session.ownerUserId()),
                        broadcaster.statusSnapshot(session.id()).runtimeStatus()))
                .toList());
        if (result.nextCursor() != null) {
            page.setNextCursor(result.nextCursor());
        }
        return ResponseEntity.ok(page);
    }

    @Override
    public ResponseEntity<SessionDto> patchSession(UUID id, UpdateSessionRequest updateSessionRequest) {
        sessionStore.findSession(id)
                .orElseThrow(() -> new SessionNotFoundException("Сессия %s не найдена".formatted(id)));

        JsonNode patch = MergePatchBodyContext.current();
        if (patch != null && patch.has("title")) {
            JsonNode titleNode = patch.get("title");
            if (titleNode.isNull()) {
                sessionStore.renameSession(id, null);
            } else {
                String title = titleNode.asText();
                if (title.isBlank()) {
                    throw new ApiValidationException(List.of(new ApiValidationException.ValidationError(
                            "/title", "blank", "title не может быть пустой строкой")));
                }
                sessionStore.renameSession(id, title);
            }
        }
        return getSession(id);
    }

    @Override
    public ResponseEntity<SessionTreePage> getSessionTree(UUID id, Integer depth) {
        // K.1: поддерево по parent_session_id — плоский список узлов (родство несёт
        // parentSessionId каждого узла); STATE-узлы дополнительно несут taskId/stateCode
        List<Session> subtree = sessionStore.findSubtree(id, depth);
        Map<UUID, SessionStore.AgentRevisionSummary> agents =
                sessionStore.agentSummaries(subtree.stream().map(Session::agentRevisionId).toList());

        List<SessionTreeNode> nodes = subtree.stream()
                .map(session -> {
                    SessionStore.AgentRevisionSummary agent = agents.get(session.agentRevisionId());
                    return ApiMappers.toNode(session,
                            new AgentRef(agent.agentKey(), agent.rev()),
                            broadcaster.statusSnapshot(session.id()).runtimeStatus());
                })
                .toList();
        return ResponseEntity.ok(new SessionTreePage(nodes));
    }

    private SessionDto toDto(Session session, UUID ownerUserId) {
        SessionStore.AgentRevisionSummary agent =
                sessionStore.agentSummaries(List.of(session.agentRevisionId()))
                        .get(session.agentRevisionId());
        Map<UUID, String> usernames = users.usernames(List.of(ownerUserId));
        return ApiMappers.toDto(session, agent, usernames.get(ownerUserId),
                broadcaster.statusSnapshot(session.id()).runtimeStatus());
    }

    private int clampLimit(Integer limit) {
        int max = limits.page();
        return limit == null ? max : Math.min(limit, max);
    }

    private static void rejectBlank(String pointer, String value) {
        if (value != null && value.isBlank()) {
            throw new ApiValidationException(List.of(new ApiValidationException.ValidationError(
                    pointer, "blank", "Значение не может быть пустой строкой")));
        }
    }
}
