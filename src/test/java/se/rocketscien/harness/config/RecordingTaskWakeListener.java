package se.rocketscien.harness.config;

import se.rocketscien.harness.task.TaskWakeListener;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Тестовая запись task-wake событий (unit-проверка публикации после коммита в TaskRegistry).
 * Инертен вне задачных тестов; попадает в общий контекст через component-scan test-classpath
 * (как DatabaseCleaner/TestPingController).
 */
@Component
public class RecordingTaskWakeListener implements TaskWakeListener {

    private final List<UUID> wakes = new CopyOnWriteArrayList<>();

    @Override
    public void onTaskWake(UUID taskId) {
        wakes.add(taskId);
    }

    public List<UUID> wakes() {
        return List.copyOf(wakes);
    }

    public void clear() {
        wakes.clear();
    }
}
