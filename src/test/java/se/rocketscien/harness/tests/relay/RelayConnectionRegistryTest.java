package se.rocketscien.harness.tests.relay;

import org.junit.jupiter.api.Test;
import se.rocketscien.harness.relay.RelayCloseCodes;
import se.rocketscien.harness.relay.RelayConnection;
import se.rocketscien.harness.relay.RelayConnectionRegistry;
import se.rocketscien.harness.relay.RelayConnectionRegistry.RegisterOutcome;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 T.4: политика реестра соединений релея (D-78) — регистрация, takeover того же
 * principal, отказ чужому principal, идемпотентный повтор и CAS-удаление по identity.
 */
class RelayConnectionRegistryTest {

    private final RelayConnectionRegistry registry = new RelayConnectionRegistry();

    @Test
    void registersFreeSession() {
        UUID sessionId = UUID.randomUUID();
        FakeConnection connection = new FakeConnection("alice");

        assertThat(registry.register(sessionId, connection)).isEqualTo(RegisterOutcome.REGISTERED);
        assertThat(registry.findForSession(sessionId)).contains(connection);
        assertThat(connection.closedWith()).isNull();
    }

    @Test
    void reRegisterSameConnectionIsIdempotent() {
        UUID sessionId = UUID.randomUUID();
        FakeConnection connection = new FakeConnection("alice");
        registry.register(sessionId, connection);

        assertThat(registry.register(sessionId, connection)).isEqualTo(RegisterOutcome.REGISTERED);
        assertThat(registry.findForSession(sessionId)).contains(connection);
        assertThat(connection.closedWith()).isNull();
    }

    @Test
    void takeoverBySamePrincipalClosesOldConnection() {
        UUID sessionId = UUID.randomUUID();
        FakeConnection oldConnection = new FakeConnection("alice");
        FakeConnection newConnection = new FakeConnection("alice");
        registry.register(sessionId, oldConnection);

        assertThat(registry.register(sessionId, newConnection)).isEqualTo(RegisterOutcome.REGISTERED);
        assertThat(registry.findForSession(sessionId)).contains(newConnection);
        assertThat(oldConnection.closedWith()).isEqualTo(RelayCloseCodes.CONFLICT + ":superseded");
        assertThat(newConnection.closedWith()).isNull();
    }

    @Test
    void otherPrincipalIsRejectedAndKeepsExisting() {
        UUID sessionId = UUID.randomUUID();
        FakeConnection owner = new FakeConnection("alice");
        FakeConnection intruder = new FakeConnection("bob");
        registry.register(sessionId, owner);

        assertThat(registry.register(sessionId, intruder)).isEqualTo(RegisterOutcome.OCCUPIED);
        assertThat(registry.findForSession(sessionId)).contains(owner);
        assertThat(owner.closedWith()).isNull();
        assertThat(intruder.closedWith()).isNull();
    }

    @Test
    void unregisterIsCasByIdentity() {
        UUID sessionId = UUID.randomUUID();
        FakeConnection current = new FakeConnection("alice");
        FakeConnection stale = new FakeConnection("alice");
        registry.register(sessionId, current);

        assertThat(registry.unregister(sessionId, stale)).isFalse();
        assertThat(registry.findForSession(sessionId)).contains(current);

        assertThat(registry.unregister(sessionId, current)).isTrue();
        assertThat(registry.findForSession(sessionId)).isEmpty();
    }

    private static final class FakeConnection implements RelayConnection {

        private final String principal;
        private String closedWith;

        private FakeConnection(String principal) {
            this.principal = principal;
        }

        @Override
        public String principal() {
            return principal;
        }

        @Override
        public void sendText(String frame) {
        }

        @Override
        public void close(int statusCode, String reason) {
            this.closedWith = statusCode + ":" + reason;
        }

        private String closedWith() {
            return closedWith;
        }
    }
}
