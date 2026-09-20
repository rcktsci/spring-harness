package se.rocketscien.harness.api;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import se.rocketscien.harness.api.gen.TriggersApi;
import se.rocketscien.harness.api.gen.model.CreateTriggerRequest;
import se.rocketscien.harness.api.gen.model.TriggerDto;
import se.rocketscien.harness.api.gen.model.TriggerPage;
import se.rocketscien.harness.config.LimitsProperties;
import se.rocketscien.harness.identity.AppUserDirectory;
import se.rocketscien.harness.task.InvalidCursorException;
import se.rocketscien.harness.task.Trigger;
import se.rocketscien.harness.task.TriggerRegistry;
import se.rocketscien.harness.workflow.WorkflowRegistry;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Триггеры (api-contracts §4.3, пачка L.4; спека inbound-triggers «CRUD триггеров»):
 * создание (201 + Location = capability-URL из {@code url}, ревью L-4; пин ревизии
 * latest/явный; owner = JWT), список с фильтром {@code mine} и конверт-пагинацией
 * (createdAt desc), DELETE = revoke (ревью L-3: 204 — отозван этим вызовом; повторный
 * revoke и несуществующий → 404 trigger-not-found; capability-URL умирает мгновенно —
 * последующие вебхуки → 410).
 *
 * <p>{@code owner} — пользователь JWT (M2: все входы API пользовательские, D-59);
 * rev — из запроса или latestRev workflow; params валидируются {@code paramsSchema}
 * пиннутой ревизии ({@code TriggerRegistry.create} → 422 params-schema). limit без
 * значения — {@code harness.limits.page}.</p>
 */
@RestController
@RequiredArgsConstructor
public class TriggersController implements TriggersApi {

    private final TriggerRegistry triggers;
    private final WorkflowRegistry workflows;
    private final AppUserDirectory users;
    private final Caller caller;
    private final LimitsProperties limits;

    @Override
    public ResponseEntity<TriggerDto> createTrigger(CreateTriggerRequest createTriggerRequest) {
        rejectBlank("/name", createTriggerRequest.getName());
        rejectBlank("/workflowKey", createTriggerRequest.getWorkflowKey());
        // Пин ревизии: rev из запроса или latestRev (TaskDto-паттерн resolveRevision, пачка K.1);
        // саму ревизию резолвит реестр — здесь достаточно 404 на неизвестном ключе
        Integer rev = createTriggerRequest.getRev();
        if (rev == null) {
            rev = workflows.get(createTriggerRequest.getWorkflowKey()).latestRev();
        }
        TriggerDto dto = ApiMappers.toDto(triggers.create(new TriggerRegistry.CreateTriggerCommand(
                caller.userId(),
                createTriggerRequest.getName(),
                createTriggerRequest.getWorkflowKey(),
                rev,
                createTriggerRequest.getParams(),
                createTriggerRequest.getTags()
        )), usernameOf());
        // Location — capability-URL (ревью L-4): рабочий эндпоинт триггера, а не
        // DELETE-only REST-ресурс
        return ResponseEntity.created(dto.getUrl()).body(dto);
    }

    @Override
    public ResponseEntity<TriggerPage> listTriggers(Boolean mine, String cursor, Integer limit) {
        TriggerRegistry.TriggerPage result;
        try {
            result = triggers.list(new TriggerRegistry.TriggerSearchCriteria(
                    Boolean.TRUE.equals(mine) ? caller.userId() : null,
                    cursor,
                    clampLimit(limit)
            ));
        } catch (InvalidCursorException e) {
            throw cursorInvalid();
        }
        List<UUID> ownerIds = result.items().stream()
                .map(Trigger::ownerUserId)
                .distinct()
                .toList();
        Map<UUID, String> usernames = users.usernames(ownerIds);
        TriggerPage page = new TriggerPage(result.items().stream()
                .map(trigger -> ApiMappers.toDto(trigger, usernames.get(trigger.ownerUserId())))
                .toList());
        if (result.nextCursor() != null) {
            page.setNextCursor(result.nextCursor());
        }
        return ResponseEntity.ok(page);
    }

    @Override
    public ResponseEntity<Void> revokeTrigger(UUID id) {
        // revoke необратим (ревью L-3): 204 — отозван этим вызовом; повторный revoke и
        // несуществующий → 404 trigger-not-found (реестр бросает TriggerNotFoundException)
        triggers.revoke(id);
        return ResponseEntity.noContent().build();
    }

    private String usernameOf() {
        return users.usernames(List.of(caller.userId())).get(caller.userId());
    }

    private int clampLimit(Integer limit) {
        int max = limits.page();
        return limit == null ? max : Math.min(limit, max);
    }

    private static ApiValidationException cursorInvalid() {
        // Битый/подделанный opaque-курсор — ошибка клиента (E-J-3, стиль TasksController)
        return new ApiValidationException(List.of(new ApiValidationException.ValidationError(
                "/cursor", "cursor", "Курсор страницы некорректен")));
    }

    private static void rejectBlank(String pointer, String value) {
        if (value != null && value.isBlank()) {
            throw new ApiValidationException(List.of(new ApiValidationException.ValidationError(
                    pointer, "blank", "Значение не может быть пустой строкой")));
        }
    }
}
