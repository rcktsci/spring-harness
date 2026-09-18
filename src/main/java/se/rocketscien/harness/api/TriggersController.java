package se.rocketscien.harness.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import se.rocketscien.harness.api.gen.TriggersApi;
import se.rocketscien.harness.api.gen.model.CreateTriggerRequest;
import se.rocketscien.harness.api.gen.model.TriggerDto;
import se.rocketscien.harness.api.gen.model.TriggerPage;

import java.util.UUID;

/**
 * Триггеры (api-contracts §4.3) — wiring D.2. Реализация — пачка L
 * (TriggerRegistry, revoke, capability-URL).
 */
@RestController
public class TriggersController implements TriggersApi {

    private static final String STUB = "D.2: stub — реализация в пачках H/I/J/K/L";

    @Override
    public ResponseEntity<TriggerDto> createTrigger(CreateTriggerRequest createTriggerRequest) {
        throw new ApiNotImplementedException(STUB);
    }

    @Override
    public ResponseEntity<TriggerPage> listTriggers(Boolean mine, String cursor, Integer limit) {
        throw new ApiNotImplementedException(STUB);
    }

    @Override
    public ResponseEntity<Void> revokeTrigger(UUID id) {
        throw new ApiNotImplementedException(STUB);
    }
}
