package se.rocketscien.harness.api;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import se.rocketscien.harness.api.gen.SessionCommandsApi;
import se.rocketscien.harness.execution.TurnManager;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionKind;
import se.rocketscien.harness.session.SessionNotFoundException;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.session.WrongSessionKindException;

import java.util.UUID;

/**
 * RPC-команды сессии (api-contracts §2, осознанный RPC-стиль): compact — только FREE
 * (иначе 409 wrong-session-kind; исполнение на границе раунда — задача 9.2), stop —
 * отмена активного Turn'а (идемпотентна).
 */
@RestController
@RequiredArgsConstructor
public class SessionCommandsController implements SessionCommandsApi {

    private final SessionStore sessionStore;
    private final TurnManager turnManager;

    @Override
    public ResponseEntity<Object> compactSession(UUID id) {
        Session session = sessionStore.findSession(id)
                .orElseThrow(() -> new SessionNotFoundException("Сессия %s не найдена".formatted(id)));
        if (session.kind() != SessionKind.FREE) {
            throw new WrongSessionKindException(
                    "Команда compact применима только к FREE-сессиям, эта — %s".formatted(session.kind()));
        }
        // Тело по спеке пустое (schema: {}): билдится Void-ответ, объявленный тип — Object
        return ResponseEntity.accepted().<Object>build();
    }

    @Override
    public ResponseEntity<Object> stopSession(UUID id) {
        sessionStore.findSession(id)
                .orElseThrow(() -> new SessionNotFoundException("Сессия %s не найдена".formatted(id)));
        turnManager.requestStop(id);
        return ResponseEntity.accepted().<Object>build();
    }
}
