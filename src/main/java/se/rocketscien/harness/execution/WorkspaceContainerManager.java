package se.rocketscien.harness.execution;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import se.rocketscien.harness.config.DockerProperties;
import se.rocketscien.harness.config.LimitsProperties;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lifecycle per-session контейнера {@code harness-<sessionId>} (D-M1-7, specs/workspace-tools):
 * ленивое создание при первом вызове инструмента, bind-mount хостового каталога
 * {@code workspaceRoot/{sessionId}} в {@code /workspace}, лимиты cpu/mem из конфига, сеть off.
 * Pull-политика: локальный образ приоритетен; pull — только backoff-обновление при отсутствии
 * локального; недоступность registry не фейлит вызов (пока образ есть локально).
 */
@Service
@RequiredArgsConstructor
public class WorkspaceContainerManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceContainerManager.class);
    private static final String WORKSPACE_MOUNT = "/workspace";
    private static final String CONTAINER_PREFIX = "harness-";

    private final DockerClient dockerClient;
    private final DockerProperties properties;
    private final LimitsProperties limits;
    private final ConcurrentMap<UUID, String> containers = new ConcurrentHashMap<>();

    public String containerName(UUID sessionId) {
        return CONTAINER_PREFIX + sessionId;
    }

    public Path workspaceDir(UUID sessionId) {
        return Paths.get(properties.workspaceRoot(), sessionId.toString());
    }

    /**
     * Ленивое создание: повторный вызов возвращает тот же работающий контейнер. Остановленный
     * контейнер (C-J-3) удаляется и создаётся заново — «мёртвый» id не кэшируется и не превращается
     * в вечный LOST; контейнер, упавший между create и start, удаляется в {@link #createAndStart}.
     */
    public synchronized String ensureContainer(UUID sessionId) {
        String cached = containers.get(sessionId);
        if (cached != null && isContainerRunning(cached)) {
            return cached;
        }
        containers.remove(sessionId);

        String name = containerName(sessionId);
        String existing = findContainerId(name);
        if (existing != null) {
            if (isContainerRunning(existing)) {
                log.debug("Reusing existing running container {}", name);
                containers.put(sessionId, existing);
                return existing;
            }
            log.warn("Removing stopped workspace container {} and recreating", name);
            removeContainerId(existing);
        }
        String created = createAndStart(name, sessionId);
        containers.put(sessionId, created);
        return created;
    }

    public boolean isRunning(UUID sessionId) {
        String containerId = containers.get(sessionId);
        if (containerId == null) {
            containerId = findContainerId(containerName(sessionId));
        }
        return containerId != null && isContainerRunning(containerId);
    }

    public void removeContainer(UUID sessionId) {
        containers.remove(sessionId);
        String containerId = findContainerId(containerName(sessionId));
        if (containerId == null) {
            return;
        }
        removeContainerId(containerId);
    }

    private void removeContainerId(String containerId) {
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (Exception e) {
            log.warn("Failed to remove container {}: {}", containerId, e.getMessage());
        }
    }

    public ContainerExecResult exec(UUID sessionId, List<String> command, byte[] stdin, Duration timeout) {
        return exec(sessionId, command, stdin, timeout, null);
    }

    /**
     * exec с областью отмены: ожидание завершения прерывается по {@code cancellation}
     * (само убийство процесса — прерыватель, зарегистрированный вызывающей стороной;
     * здесь — только выход из блокирующего ожидания).
     *
     * <p>Ожидание — собственный latch с поллингом отмены срезами {@code statePollInterval}:
     * повторный вызов {@code awaitCompletion(timeout)} недопустим — его {@code finally}
     * закрывает поток чтения (обрыв вывода); завершение/ошибка/переполнение вывода считают
     * один и тот же latch через {@code close()}.</p>
     */
    public ContainerExecResult exec(UUID sessionId, List<String> command, byte[] stdin, Duration timeout,
                                    TurnCancellation cancellation) {
        String containerId = ensureContainer(sessionId);
        if (!isContainerRunning(containerId)) {
            throw new WorkspaceContainerException(
                    "Workspace container is not running: " + containerName(sessionId), true, null);
        }

        String execId;
        try {
            execId = dockerClient.execCreateCmd(containerId)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withAttachStdin(stdin != null)
                    .withCmd(command.toArray(String[]::new))
                    .exec()
                    .getId();
        } catch (Exception e) {
            throw new WorkspaceContainerException(
                    "Failed to create exec in " + containerName(sessionId) + ": " + e.getMessage(), true, e);
        }

        long captureLimit = limits.toolOutput().toBytes() + limits.toolCaptureMargin().toBytes();
        BoundedOutputStream output = new BoundedOutputStream(captureLimit);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch streamClosed = new CountDownLatch(1);
        ExecStartResultCallback callback = new ExecStartResultCallback(output, output) {
            @Override
            public void onError(Throwable throwable) {
                failure.compareAndSet(null, throwable);
                super.onError(throwable);
            }

            @Override
            public void close() throws java.io.IOException {
                try {
                    super.close();
                } finally {
                    streamClosed.countDown();
                }
            }
        };
        output.setOnOverflow(() -> {
            try {
                callback.close();
            } catch (Exception e) {
                log.debug("Could not stop exec {} after output limit: {}", execId, e.getMessage());
            }
        });
        boolean completed = false;
        try {
            var start = dockerClient.execStartCmd(execId).withDetach(false);
            if (stdin != null) {
                start.withStdIn(new ByteArrayInputStream(stdin));
            }
            start.exec(callback);
            long deadline = System.nanoTime() + timeout.toNanos();
            long slice = Math.max(1, properties.statePollInterval().toMillis());
            while (!completed && System.nanoTime() < deadline
                    && (cancellation == null || !cancellation.isCancelled())) {
                completed = streamClosed.await(slice, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkspaceContainerException("Exec interrupted: " + e.getMessage(), true, e);
        } catch (Exception e) {
            if (!isContainerRunning(containerId)) {
                throw new WorkspaceContainerException(
                        "Workspace container died during execution: " + containerName(sessionId), true, e);
            }
            throw new WorkspaceContainerException("Exec failed: " + e.getMessage(), true, e);
        }
        Throwable streamFailure = failure.get();
        if (streamFailure != null) {
            if (!isContainerRunning(containerId)) {
                throw new WorkspaceContainerException(
                        "Workspace container died during execution: " + containerName(sessionId), true, streamFailure);
            }
            throw new WorkspaceContainerException("Exec failed: " + streamFailure.getMessage(), true, streamFailure);
        }

        Long exitCode = null;
        try {
            exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
        } catch (Exception e) {
            log.debug("Could not inspect exec {}: {}", execId, e.getMessage());
        }

        if (!isContainerRunning(containerId)) {
            throw new WorkspaceContainerException(
                    "Workspace container died during execution: " + containerName(sessionId), true, null);
        }
        // Сигнальная смерть команды (exit >= 128): демон может отдавать состояние с задержкой —
        // короткое окно containerStopConfirm отличает смерть контейнера от self-kill команды,
        // не блокируя валидный exit 128+ на всё окно startTimeout (C-J-6 #4). Отменённый exec —
        // уже известный self-kill, окно не тратим.
        if (exitCode != null && exitCode >= 128
                && (cancellation != null && cancellation.isCancelled() || containerStoppedWithin(containerId))) {
            throw new WorkspaceContainerException(
                    "Workspace container died during execution: " + containerName(sessionId), true, null);
        }

        String text = output.asString();
        if (exitCode == null || !completed) {
            return new ContainerExecResult(text, exitCode == null ? -1 : exitCode.intValue(), true, output.isTruncated());
        }
        return new ContainerExecResult(text, exitCode.intValue(), exitCode == 124L, output.isTruncated());
    }

    /**
     * Рестарт-скан (execution-model §1): удаляет контейнеры {@code harness-<sessionId>} без
     * живой сессии в БД; контейнеры живых сессий не трогает. {@code harness-task-*} (M2) не
     * рассматриваются — суффикс парсится как UUID.
     */
    public int removeOrphanContainers(Set<UUID> liveSessionIds) {
        var listed = dockerClient.listContainersCmd()
                .withShowAll(true)
                .exec();
        int removed = 0;
        for (var container : listed) {
            String[] names = container.getNames();
            if (names == null || names.length == 0 || names[0] == null) {
                continue;
            }
            String name = names[0].startsWith("/") ? names[0].substring(1) : names[0];
            if (!name.startsWith(CONTAINER_PREFIX)) {
                continue;
            }
            String suffix = name.substring(CONTAINER_PREFIX.length());
            UUID sessionId;
            try {
                sessionId = UUID.fromString(suffix);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!liveSessionIds.contains(sessionId)) {
                log.info("Removing orphan workspace container {}", name);
                removeContainerId(container.getId());
                removed++;
            }
        }
        return removed;
    }

    private String createAndStart(String name, UUID sessionId) {
        Path hostDir = workspaceDir(sessionId).toAbsolutePath().normalize();
        if (Files.exists(hostDir) && !Files.isDirectory(hostDir)) {
            throw new WorkspaceContainerException(
                    "Workspace path is not a directory: " + hostDir, false, null);
        }
        try {
            Files.createDirectories(hostDir);
        } catch (IOException e) {
            throw new WorkspaceContainerException(
                    "Failed to prepare workspace directory " + hostDir + ": " + e.getMessage(), false, e);
        }

        ensureImage();

        String containerId = null;
        try {
            containerId = dockerClient.createContainerCmd(properties.helperImage())
                    .withName(name)
                    .withWorkingDir(WORKSPACE_MOUNT)
                    .withCmd("sh", "-c", "while true; do sleep 3600; done")
                    .withHostConfig(HostConfig.newHostConfig()
                            .withBinds(new Bind(hostDir.toString(), new Volume(WORKSPACE_MOUNT)))
                            .withNetworkMode(properties.network())
                            .withMemory(properties.memory().toBytes())
                            .withNanoCPUs(properties.cpuNanos()))
                    .exec()
                    .getId();
            dockerClient.startContainerCmd(containerId).exec();
            awaitRunning(containerId);
            log.info("Started workspace container {} ({})", name, containerId);
            return containerId;
        } catch (Exception e) {
            // Полусозданный/незапустившийся контейнер не кэшируем и не оставляем в Docker (C-J-3).
            if (containerId != null) {
                removeContainerId(containerId);
            }
            if (e instanceof WorkspaceContainerException wce) {
                throw wce;
            }
            throw new WorkspaceContainerException(
                    "Failed to start workspace container " + name + ": " + e.getMessage(), false, e);
        }
    }

    private void ensureImage() {
        if (imageAvailableLocally()) {
            return;
        }
        int attempts = Math.max(1, properties.pullRetries());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                dockerClient.pullImageCmd(properties.helperImage())
                        .exec(new PullImageResultCallback())
                        .awaitCompletion(properties.pullTimeout().toMillis(), TimeUnit.MILLISECONDS);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Pull attempt {}/{} for {} failed: {}",
                        attempt, attempts, properties.helperImage(), e.getMessage());
                if (attempt < attempts) {
                    sleep(properties.pullBackoff().toMillis() * attempt);
                }
            }
        }
        log.warn("Helper image {} unavailable from registry; relying on local image",
                properties.helperImage());
    }

    private boolean imageAvailableLocally() {
        try {
            dockerClient.inspectImageCmd(properties.helperImage()).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        } catch (Exception e) {
            log.debug("Could not inspect helper image {}: {}", properties.helperImage(), e.getMessage());
            return false;
        }
    }

    private void awaitRunning(String containerId) {
        long deadline = System.nanoTime() + properties.startTimeout().toNanos();
        while (System.nanoTime() < deadline) {
            if (isContainerRunning(containerId)) {
                return;
            }
            sleep(properties.statePollInterval().toMillis());
        }
        if (!isContainerRunning(containerId)) {
            throw new WorkspaceContainerException(
                    "Workspace container did not start within " + properties.startTimeout(), false, null);
        }
    }

    /**
     * Подтверждает остановку контейнера коротким окном {@code container-stop-confirm}. Если
     * контейнер жив на входе — выходим сразу (иначе валидный exit≥128 блокировал бы поток всё окно).
     */
    private boolean containerStoppedWithin(String containerId) {
        if (!isContainerRunning(containerId)) {
            return true;
        }
        long deadline = System.nanoTime() + properties.containerStopConfirm().toNanos();
        while (System.nanoTime() < deadline) {
            if (!isContainerRunning(containerId)) {
                return true;
            }
            sleep(properties.statePollInterval().toMillis());
        }
        return !isContainerRunning(containerId);
    }

    private boolean isContainerRunning(String containerId) {
        try {
            var state = dockerClient.inspectContainerCmd(containerId).exec().getState();
            return state != null && Boolean.TRUE.equals(state.getRunning());
        } catch (Exception e) {
            return false;
        }
    }

    private String findContainerId(String name) {
        try {
            return dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withNameFilter(List.of(name))
                    .exec()
                    .stream()
                    .filter(container -> container.getNames() != null
                            && Arrays.asList(container.getNames()).contains("/" + name))
                    .map(container -> container.getId())
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.debug("Could not list containers: {}", e.getMessage());
            return null;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
