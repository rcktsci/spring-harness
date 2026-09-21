package se.rocketscien.harness.tests.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.execution.impl.TaskEngine;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.TransitionKind;
import se.rocketscien.harness.tests.task.TaskTestFixtures;
import se.rocketscien.harness.tests.workflow.WorkflowTestFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static se.rocketscien.harness.tests.api.ApiFixtures.keycloakToken;

/**
 * SSE-поток событий задачи (пачка J.4; спека session-api «SSE-поток событий задачи»,
 * api-contracts §3.2): снапшот task.status первым кадром (с taskEventSeq — точкой live-точки),
 * кадры task.transition/task.status/subtask.terminal/task.comment с id = task_event_seq,
 * реконнект по Last-Event-ID без пропусков и дублей, 404 task-not-found.
 */
class TaskEventsSseTest extends BaseApplicationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http = HttpClient.newHttpClient();
    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private String aliceToken;

    @Autowired
    private TaskRegistry taskRegistry;

    @Autowired
    private TaskEngine taskEngine;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    private final List<CompletableFuture<?>> pendingReads = new ArrayList<>();

    @BeforeEach
    void setUp() {
        aliceToken = keycloakToken(http, "alice", "alice-password");
    }

    @AfterEach
    void drainPendingReads() {
        pendingReads.forEach(future -> future.cancel(true));
        pendingReads.clear();
    }

    @Test
    void firstFrameIsRetryAndSnapshotThenLiveTransitionFrames() throws Exception {
        UUID taskId = newWaitWebhookTask();

        HttpResponse<Stream<String>> response = openStream(taskId, "0", null);

        taskEngine.processTaskTransition(taskId, "wait", "done", TransitionKind.NEXT,
                Map.of("text", "вебхук пришёл"));

        List<String> lines = readLines(response, 3);
        assertThat(lines).anyMatch(line -> line.startsWith("retry:") && line.contains("5000"));
        int snapshotIndex = indexOfEvent(lines, "task.status");
        int transitionIndex = indexOfEvent(lines, "task.transition");
        assertThat(snapshotIndex).as("снапшот — до событий").isGreaterThan(-1);

        JsonNode snapshot = MAPPER.readTree(dataOf(lines, snapshotIndex));
        assertThat(snapshot.get("currentState").asString()).isEqualTo("wait");
        assertThat(snapshot.get("statusProjection").asString()).isEqualTo("WAITING");
        assertThat(snapshot.get("suspended").asBoolean()).isFalse();
        assertThat(snapshot.get("taskEventSeq").asLong()).isZero();

        JsonNode transition = MAPPER.readTree(dataOf(lines, transitionIndex));
        assertThat(transition.get("fromState").asString()).isEqualTo("wait");
        assertThat(transition.get("toState").asString()).isEqualTo("done");
        assertThat(transition.get("kind").asString()).isEqualTo("NEXT");
        assertThat(transition.get("reason").get("text").asString()).isEqualTo("вебхук пришёл");
        assertThat(frameId(lines, transitionIndex)).isEqualTo("1");

        List<Long> statusFrames = idsOfEvent(lines, "task.status");
        assertThat(statusFrames).as("парный task.status делит seq перехода; снапшот — без id").containsExactly(1L);
    }

    @Test
    void reconnectByLastEventIdContinuesWithoutDupesOrGaps() throws Exception {
        UUID taskId = newChainTask();

        taskEngine.processTaskTransition(taskId, "wait1", "wait2", TransitionKind.NEXT,
                Map.of("text", "первый вебхук"));
        List<String> first = readLines(openStream(taskId, "0", null), 3);
        assertThat(idsOfEvent(first, "task.transition")).containsExactly(1L);

        taskEngine.processTaskTransition(taskId, "wait2", "done", TransitionKind.NEXT,
                Map.of("text", "второй вебхук"));
        List<String> reconnected = readLines(openStream(taskId, null, 1L), 3);
        assertThat(indexOfEvent(reconnected, "task.status"))
                .as("снапшот — первым и на реконнекте").isGreaterThan(-1);
        assertThat(idsOfEvent(reconnected, "task.transition"))
                .as("добрано только пропущенное (seq > 1)")
                .containsExactly(2L);
        assertThat(pretty(reconnected)).doesNotContain("первый вебхук");
    }

    @Test
    void subtaskTerminalFrameArrivesOnParentStream() throws Exception {
        UUID parentId = newWaitTasksParent();
        UUID childId = newChildWebhookTask(parentId);

        HttpResponse<Stream<String>> response = openStream(parentId, "0", null);

        taskEngine.processTaskTransition(childId, "wait", "done", TransitionKind.NEXT,
                Map.of("text", "ребёнок завершён"));

        List<String> lines = readLinesUntil(response, "subtask.terminal", Duration.ofSeconds(10));
        int terminalIndex = indexOfEvent(lines, "subtask.terminal");
        JsonNode terminal = MAPPER.readTree(dataOf(lines, terminalIndex));
        assertThat(terminal.get("taskId").asString()).isEqualTo(parentId.toString());
        assertThat(terminal.get("terminalTaskId").asString()).isEqualTo(childId.toString());
        assertThat(terminal.get("terminalStatus").asString()).isEqualTo("SUCCEEDED");
        List<Long> ids = idsOfEvent(lines, "subtask.terminal");
        assertThat(ids).as("курсор кадра — task_event_seq родителя").containsExactly(1L);
    }

    @Test
    void commentFrameCarriesAuthorUsername() throws Exception {
        UUID taskId = newWaitWebhookTask();
        UUID authorId = ApiFixtures.insertAppUser(jdbcTemplate);

        HttpResponse<Stream<String>> response = openStream(taskId, "0", null);
        taskRegistry.addComment(taskId, authorId, "посмотрите сюда");

        List<String> lines = readLines(response, 2);
        int commentIndex = indexOfEvent(lines, "task.comment");
        assertThat(commentIndex).as("кадр task.comment доставлен").isGreaterThan(-1);
        JsonNode comment = MAPPER.readTree(dataOf(lines, commentIndex));
        assertThat(comment.get("body").asString()).isEqualTo("посмотрите сюда");
        assertThat(comment.get("author").asString()).isEqualTo("user-" + authorId);
        assertThat(frameId(lines, commentIndex)).isEqualTo("1");
    }

    @Test
    void streamOfUnknownTaskReturns404ProblemJson() throws Exception {
        HttpResponse<Stream<String>> response = openStream(UUID.randomUUID(), "0", null);
        assertThat(response.statusCode()).isEqualTo(404);
        String body = response.body().collect(Collectors.joining());
        assertThat(body).contains("\"code\":\"task-not-found\"");
    }

    // --- фикстуры ------------------------------------------------------------

    /** Пассивное WAIT_WEBHOOK-старт (переоценки нет): переходы — только руками теста. */
    private UUID newWaitWebhookTask() {
        return taskRegistry.createTask(TaskTestFixtures.command(
                TaskTestFixtures.insertRevision(jdbcTemplate, idGenerator,
                        WorkflowTestFixtures.waitWebhookGraph(), "wait"),
                WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator),
                null, Map.of())).id();
    }

    /** Цепочка wait1 → wait2 → done/failed: два перехода руками теста (реконнект). */
    private UUID newChainTask() {
        return taskRegistry.createTask(TaskTestFixtures.command(
                TaskTestFixtures.insertRevision(jdbcTemplate, idGenerator, WorkflowTestFixtures.graph(
                        List.of(
                                WorkflowTestFixtures.state("wait1", "WAIT_WEBHOOK", null),
                                WorkflowTestFixtures.state("wait2", "WAIT_WEBHOOK", null),
                                WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                                WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))
                        ),
                        List.of(
                                WorkflowTestFixtures.transition("wait1", "wait2", "NEXT"),
                                WorkflowTestFixtures.transition("wait2", "done", "NEXT"),
                                WorkflowTestFixtures.transition("wait2", "failed", "ERROR"),
                                WorkflowTestFixtures.transition("wait2", "done", "TIMEOUT")
                        )), "wait1"),
                WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator),
                null, Map.of())).id();
    }

    /** Родитель-барьер ALL_CHILDREN/ALL_TERMINAL (без таймаута — переходы от терминала ребёнка). */
    private UUID newWaitTasksParent() {
        return taskRegistry.createTask(TaskTestFixtures.command(
                TaskTestFixtures.insertRevision(jdbcTemplate, idGenerator,
                        TaskTestFixtures.waitTasksGraph(), "gather"),
                WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator),
                null, Map.of())).id();
    }

    /** Ребёнок в WAIT_WEBHOOK-старте, привязанный к родителю. */
    private UUID newChildWebhookTask(UUID parentId) {
        return taskRegistry.createTask(TaskTestFixtures.command(
                TaskTestFixtures.insertRevision(jdbcTemplate, idGenerator,
                        WorkflowTestFixtures.waitWebhookGraph(), "wait"),
                WorkflowTestFixtures.insertAppUser(jdbcTemplate, idGenerator),
                parentId, Map.of())).id();
    }

    // --- SSE-клиент ----------------------------------------------------------

    private HttpResponse<Stream<String>> openStream(UUID taskId, String since,
                                                                     Long lastEventId) throws Exception {
        StringBuilder url = new StringBuilder(localServerUrl() + "/api/v1/tasks/" + taskId + "/events");
        if (since != null) {
            url.append("?since=").append(since);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url.toString()))
                .header("Authorization", "Bearer " + aliceToken)
                .GET();
        if (lastEventId != null) {
            builder.header("Last-Event-ID", Long.toString(lastEventId));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofLines());
    }

    /** Читает строки, пока не завершится {@code eventCount}-й кадр event: (пустая строка — конец кадра). */
    private List<String> readLines(HttpResponse<Stream<String>> response, int eventCount) {
        return readLinesUntilInternal(response, lines -> countEvents(lines) >= eventCount, READ_TIMEOUT,
                eventCount + " событий");
    }

    private List<String> readLinesUntil(HttpResponse<Stream<String>> response,
                                        String eventName, Duration timeout) {
        return readLinesUntilInternal(response, lines -> indexOfEvent(lines, eventName) >= 0, timeout,
                "кадр " + eventName);
    }

    private List<String> readLinesUntilInternal(HttpResponse<Stream<String>> response,
                                                Predicate<List<String>> enough,
                                                Duration timeout, String what) {
        List<String> collected = new ArrayList<>();
        CompletableFuture<List<String>> future = CompletableFuture.supplyAsync(() -> {
            var iterator = response.body().iterator();
            boolean awaitingFrameEnd = false;
            while (iterator.hasNext()) {
                String line = iterator.next();
                synchronized (collected) {
                    collected.add(line);
                }
                boolean done = awaitingFrameEnd && line.isEmpty() && enough.test(collected);
                if (line.startsWith("event:")) {
                    awaitingFrameEnd = true;
                } else if (line.isEmpty()) {
                    awaitingFrameEnd = false;
                }
                if (done) {
                    return collected;
                }
            }
            return collected;
        }, sseExecutor);
        pendingReads.add(future);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            synchronized (collected) {
                throw new AssertionError("SSE-поток не доставил " + what + " за " + timeout
                        + "; получено строк: " + collected.size() + ": " + collected.stream().limit(60).toList(), e);
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("Чтение SSE-потока прервано", e);
        }
    }

    private static int countEvents(List<String> lines) {
        return (int) lines.stream().filter(line -> line.startsWith("event:")).count();
    }

    private static int indexOfEvent(List<String> lines, String eventName) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("event:") && lines.get(i).contains(eventName)) {
                return i;
            }
        }
        return -1;
    }

    private static String dataOf(List<String> lines, int eventIndex) {
        for (int i = eventIndex + 1; i < lines.size(); i++) {
            if (lines.get(i).startsWith("data:")) {
                return lines.get(i).substring("data:".length()).trim();
            }
            if (lines.get(i).isEmpty()) {
                break;
            }
        }
        throw new AssertionError(
                "У кадра нет data: " + lines.subList(eventIndex, Math.min(eventIndex + 5, lines.size())));
    }

    /** id: кадра, начинающегося на eventIndex (null — кадра без id, например retry). */
    private static String frameId(List<String> lines, int eventIndex) {
        for (int i = eventIndex + 1; i < lines.size() && !lines.get(i).isEmpty(); i++) {
            if (lines.get(i).startsWith("id:")) {
                return lines.get(i).substring("id:".length()).trim();
            }
        }
        return null;
    }

    private static List<Long> idsOfEvent(List<String> lines, String eventName) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("event:") && lines.get(i).contains(eventName)) {
                String id = frameId(lines, i);
                if (id != null) {
                    ids.add(Long.parseLong(id));
                }
            }
        }
        return ids;
    }

    private static String pretty(List<String> lines) {
        return String.join("\n", lines);
    }
}
