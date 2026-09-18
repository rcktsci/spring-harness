package se.rocketscien.harness.tests.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.testclient.ApiClient;
import se.rocketscien.harness.testclient.api.SessionsApi;
import se.rocketscien.harness.testclient.model.CreateSessionRequest;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static se.rocketscien.harness.tests.api.ApiFixtures.apiClient;
import static se.rocketscien.harness.tests.api.ApiFixtures.insertAgentChain;
import static se.rocketscien.harness.tests.api.ApiFixtures.keycloakToken;

/**
 * Задача 8.5 (specs/session-api: «SSE-поток событий сессии») реальным SSE-клиентом
 * (java.net.http, BodyHandlers.ofLines): retry + снапшот session.status первым кадром,
 * message.created с id=seq за (since, …], реконнект по Last-Event-ID без дублей и пропусков,
 * ping-комментарий по конфигу (в тест-профиле 200ms).
 */
class SessionEventsSseTest extends BaseApplicationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http = HttpClient.newHttpClient();
    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private String aliceToken;
    private ApiClient aliceClient;
    private String agentKey;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IdGenerator idGenerator;
    @Autowired
    private Environment environment;
    @Autowired
    private SessionStore sessionStore;

    private final List<CompletableFuture<?>> pendingReads = new ArrayList<>();

    @BeforeEach
    void setUp() {
        aliceToken = keycloakToken(http, "alice", "alice-password");
        aliceClient = apiClient(localServerUrl(), aliceToken);
        agentKey = insertAgentChain(jdbcTemplate, idGenerator, environment).agentKey();
    }

    @AfterEach
    void drainPendingReads() {
        pendingReads.forEach(future -> future.cancel(true));
        pendingReads.clear();
    }

    @Test
    void firstFrameIsRetryAndStatusSnapshotThenBackfill() throws Exception {
        UUID sessionId = new SessionsApi(aliceClient)
                .createSession(new CreateSessionRequest().agentKey(agentKey)).getId();
        appendUser(sessionId, "до коннекта");

        List<String> lines = readLines(openStream(sessionId, "0", null), 2);

        assertThat(lines).anyMatch(line -> line.startsWith("retry:") && line.contains("5000"));
        int snapshotIndex = indexOfEvent(lines, "session.status");
        int messageIndex = indexOfEvent(lines, "message.created");
        assertThat(snapshotIndex).as("retry и снапшот идут до бэкфилла").isGreaterThan(-1);
        assertThat(messageIndex).isGreaterThan(snapshotIndex);

        JsonNode snapshotData = MAPPER.readTree(dataOf(lines, snapshotIndex));
        assertThat(snapshotData.get("runtimeStatus").asString()).isEqualTo("IDLE");

        JsonNode messageData = MAPPER.readTree(dataOf(lines, messageIndex));
        assertThat(messageData.get("kind").asString()).isEqualTo("USER");
        assertThat(messageData.get("payload").get("text").asString()).isEqualTo("до коннекта");
    }

    @Test
    void sinceFiltersEventsAtOrBelowCursor() throws Exception {
        UUID sessionId = new SessionsApi(aliceClient)
                .createSession(new CreateSessionRequest().agentKey(agentKey)).getId();
        appendUser(sessionId, "скрыт курсором");
        appendUser(sessionId, "доставлен");

        List<String> lines = readLines(openStream(sessionId, "1", null), 2);

        assertThat(messageIds(lines)).containsExactly(2L);
        assertThat(pretty(lines)).doesNotContain("скрыт курсором");
    }

    @Test
    void reconnectByLastEventIdContinuesWithoutDupesOrGaps() throws Exception {
        UUID sessionId = new SessionsApi(aliceClient)
                .createSession(new CreateSessionRequest().agentKey(agentKey)).getId();
        appendUser(sessionId, "первое");
        appendUser(sessionId, "второе");

        List<String> firstConnection = readLines(openStream(sessionId, "0", null), 3);
        assertThat(messageIds(firstConnection)).containsExactly(1L, 2L);

        // разрыв и реконнект с Last-Event-ID=2: снапшот + только события после seq 2
        appendUser(sessionId, "третье");
        List<String> reconnected = readLines(openStream(sessionId, null, 2L), 2);
        assertThat(indexOfEvent(reconnected, "session.status"))
                .as("снапшот — первым и на реконнекте").isGreaterThan(-1);
        List<Long> delivered = messageIds(reconnected);
        assertThat(delivered).containsExactly(3L);

        // ни одного пропуска и дубля по обеим попыткам
        List<Long> all = new ArrayList<>();
        all.addAll(messageIds(firstConnection));
        all.addAll(delivered);
        assertThat(all).containsExactly(1L, 2L, 3L);
    }

    @Test
    void liveMessagesDeliveredInSeqOrderWithIds() throws Exception {
        UUID sessionId = new SessionsApi(aliceClient)
                .createSession(new CreateSessionRequest().agentKey(agentKey)).getId();

        HttpResponse<Stream<String>> response = openStream(sessionId, "0", null);

        // один проход по потоку: снапшот + три живых дописи подряд
        appendUser(sessionId, "живое-1");
        appendUser(sessionId, "живое-2");
        appendUser(sessionId, "живое-3");

        List<String> lines = readLines(response, 4);
        assertThat(indexOfEvent(lines, "session.status")).as("снапшот — первый кадр").isGreaterThan(-1);
        List<Long> ids = messageIds(lines);
        assertThat(ids).containsSubsequence(1L, 2L, 3L);
        assertThat(ids.stream().distinct().count()).isEqualTo(ids.size());
    }

    @Test
    void pingCommentsArrivePerConfiguredInterval() throws Exception {
        Duration pingInterval = environment.getRequiredProperty("harness.sse.ping-interval", Duration.class);
        UUID sessionId = new SessionsApi(aliceClient)
                .createSession(new CreateSessionRequest().agentKey(agentKey)).getId();

        HttpResponse<Stream<String>> response = openStream(sessionId, "0", null);
        List<String> lines = readLinesForDuration(response, pingInterval.multipliedBy(5));

        long pings = lines.stream().filter(line -> line.startsWith(":") && line.contains("ping")).count();
        assertThat(pings).as("ping-комментарии по интервалу %s", pingInterval).isGreaterThanOrEqualTo(2);
    }

    @Test
    void streamOfUnknownSessionReturns404ProblemJson() throws Exception {
        HttpResponse<Stream<String>> response = openStream(UUID.randomUUID(), "0", null);
        assertThat(response.statusCode()).isEqualTo(404);
        String body = response.body().collect(Collectors.joining());
        assertThat(body).contains("\"code\":\"session-not-found\"");
    }

    private HttpResponse<Stream<String>> openStream(UUID sessionId, String since, Long lastEventId) throws Exception {
        StringBuilder url = new StringBuilder(localServerUrl() + "/api/v1/sessions/" + sessionId + "/events");
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
        CompletableFuture<List<String>> future = CompletableFuture.supplyAsync(() -> {
            List<String> collected = new ArrayList<>();
            var iterator = response.body().iterator();
            int events = 0;
            boolean awaitingFrameEnd = false;
            while (iterator.hasNext()) {
                String line = iterator.next();
                collected.add(line);
                if (line.startsWith("event:")) {
                    events++;
                    awaitingFrameEnd = true;
                } else if (line.isEmpty() && awaitingFrameEnd && events >= eventCount) {
                    return collected;
                }
            }
            return collected;
        }, sseExecutor);
        pendingReads.add(future);
        try {
            return future.get(READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("SSE-поток не доставил " + eventCount + " событий за " + READ_TIMEOUT, e);
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("Чтение SSE-потока прервано", e);
        }
    }

    /** Читает строки заданную длительность (для ping без новых событий). */
    private List<String> readLinesForDuration(HttpResponse<Stream<String>> response, Duration duration) {
        CompletableFuture<List<String>> future = CompletableFuture.supplyAsync(() -> {
            List<String> collected = new ArrayList<>();
            long deadline = System.nanoTime() + duration.toNanos();
            var iterator = response.body().iterator();
            while (System.nanoTime() < deadline) {
                if (!iterator.hasNext()) {
                    break;
                }
                collected.add(iterator.next());
            }
            return collected;
        }, sseExecutor);
        pendingReads.add(future);
        try {
            return future.get(duration.plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Чтение SSE-потока прервано", e);
        }
    }

    private void appendUser(UUID sessionId, String text) {
        sessionStore.appendEvent(sessionId, MessageKind.USER, null, Map.of("text", text));
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

    private static List<Long> messageIds(List<String> lines) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("event:") && lines.get(i).contains("message.created")) {
                for (int j = i + 1; j < lines.size() && !lines.get(j).isEmpty(); j++) {
                    if (lines.get(j).startsWith("id:")) {
                        ids.add(Long.parseLong(lines.get(j).substring("id:".length()).trim()));
                        break;
                    }
                }
            }
        }
        return ids;
    }

    private static String pretty(List<String> lines) {
        return String.join("\n", lines);
    }
}
