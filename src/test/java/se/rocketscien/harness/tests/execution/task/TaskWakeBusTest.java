package se.rocketscien.harness.tests.execution.task;

import org.junit.jupiter.api.Test;
import se.rocketscien.harness.execution.impl.InProcessTaskWakeBus;
import se.rocketscien.harness.execution.impl.TaskWakeHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In-process шина wake движка (пачка I.5): доставка обоих событий подписчикам, изоляция
 * падений обработчиков, отписка. In-memory — события переживают только текущий процесс
 * (страховка POLL — task-scheduler).
 */
class TaskWakeBusTest {

    @Test
    void deliversWakeAndTerminalToSubscribers() throws Exception {
        InProcessTaskWakeBus bus = new InProcessTaskWakeBus();
        List<String> received = new ArrayList<>();
        UUID taskId = UUID.randomUUID();
        TaskWakeHandler handler = new TaskWakeHandler() {
            @Override
            public void onTaskWake(UUID id) {
                received.add("wake:" + id);
            }

            @Override
            public void onTaskTerminal(UUID id) {
                received.add("terminal:" + id);
            }
        };

        try (AutoCloseable ignored = bus.subscribeTaskWake(handler)) {
            bus.publishTaskWake(taskId);
            bus.publishTaskTerminal(taskId);
        }

        assertThat(received).containsExactly("wake:" + taskId, "terminal:" + taskId);
    }

    @Test
    void handlerFailureDoesNotBlockOthers() {
        InProcessTaskWakeBus bus = new InProcessTaskWakeBus();
        UUID taskId = UUID.randomUUID();
        List<UUID> received = new ArrayList<>();
        bus.subscribeTaskWake(new TaskWakeHandler() {
            @Override
            public void onTaskWake(UUID id) {
                throw new IllegalStateException("boom");
            }
        });
        bus.subscribeTaskWake(received::add);

        bus.publishTaskWake(taskId);

        assertThat(received).containsExactly(taskId);
    }

    @Test
    void unsubscribeStopsDelivery() throws Exception {
        InProcessTaskWakeBus bus = new InProcessTaskWakeBus();
        List<UUID> received = new ArrayList<>();
        AutoCloseable subscription = bus.subscribeTaskWake(received::add);
        subscription.close();

        bus.publishTaskWake(UUID.randomUUID());

        assertThat(received).isEmpty();
    }

    @Test
    void listenerBridgeForwardsRegistryWakes() {
        InProcessTaskWakeBus bus = new InProcessTaskWakeBus();
        UUID taskId = UUID.randomUUID();
        List<UUID> received = new ArrayList<>();
        bus.subscribeTaskWake(received::add);

        bus.onTaskWake(taskId);

        assertThat(received).containsExactly(taskId);
    }
}
