package se.rocketscien.harness.tests.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.execution.impl.TaskWakeDispatcher;
import se.rocketscien.harness.testclient.ApiClient;
import se.rocketscien.harness.testclient.api.SessionMessagesApi;
import se.rocketscien.harness.testclient.api.TasksApi;
import se.rocketscien.harness.testclient.model.CreateTaskRequest;
import se.rocketscien.harness.testclient.model.SendMessageAccepted;
import se.rocketscien.harness.testclient.model.SendMessageRequest;
import se.rocketscien.harness.testclient.model.TaskDto;
import se.rocketscien.harness.testclient.model.TransitionDto;
import se.rocketscien.harness.testclient.model.TransitionKind;
import se.rocketscien.harness.tests.execution.DockerTestSupport;
import se.rocketscien.harness.tests.task.TaskTestFixtures;
import se.rocketscien.harness.tests.workflow.WorkflowTestFixtures;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Приёмочный e2e M2 (tasks.md M.2, roadmap): «двухфазное ревью с возвратом» на живом
 * приложении — живой Keycloak (alice по password grant), WireMock-LLM, реальный
 * helper-контейнер для BASH-состояний. Граф:
 * {@code plan(AGENT) → run-checks(BASH) → reviewer-1(AGENT) → reviewer-2(AGENT) → merge(BASH)
 * → done/failed}, цикл — ERROR-рёбра ревьюеров назад в {@code plan}.
 *
 * <p>Задача создаётся по REST (владелец — alice); AGENT-состояния раскачиваются
 * EVENT-wake → bootstrap STATE-сессии → seed-ход; агент переводит задачу мета-инструментом
 * {@code transition} с обязательным reason (USER-ход через {@code POST /sessions/{id}/messages}
 * — гейт D-52/D-59). BASH-исполнение — реальный одноразовый контейнер
 * {@code harness-task-<id>} через {@link TaskWakeDispatcher#runBashStateOnce} (та же ветка,
 * что по EVENT-wake; автораскачка в тестовом профиле выключена ради детерминизма остальных
 * тестов). Проверки: состояния проходят в порядке (история через REST), FAILED reviewer-1
 * возвращает задачу в plan (цикл работает), STATE-сессия plan резюмируется (D-53), итоговый
 * терминал SUCCESS; артефакт bash-состояния физически лежит в workspace задачи; таймаут
 * bash-состояния (PT5S в графе) даёт TIMEOUT-переход в failed.</p>
 */
class AcceptanceTwoPhaseReviewTest extends BaseApplicationTest {

    static {
        DockerTestSupport.helperImage();
    }

    private static final String PATH = "/v1/chat/completions";
    private static final String SCENARIO = "review";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration AWAIT = Duration.ofSeconds(60);

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IdGenerator idGenerator;
    @Autowired
    private Environment environment;
    @Autowired
    private TaskWakeDispatcher taskDispatcher;

    private final HttpClient http = HttpClient.newHttpClient();

    private TasksApi tasksApi;
    private SessionMessagesApi messagesApi;
    private ApiFixtures.AgentSeed agentSeed;

    @BeforeEach
    void setUp() {
        String aliceToken = ApiFixtures.keycloakToken(http, "alice", "alice-password");
        ApiClient aliceClient = ApiFixtures.apiClient(localServerUrl(), aliceToken);
        tasksApi = new TasksApi(aliceClient);
        messagesApi = new SessionMessagesApi(aliceClient);
        agentSeed = ApiFixtures.insertAgentChain(jdbcTemplate, idGenerator, environment);
        llmWireMock.resetAll();
    }

    @Test
    void acceptanceTwoPhaseReviewWithReturn() throws Exception {
        // --- граф двухфазного ревью с циклом возврата; BASH-таймауты — PT5S в графе
        TaskTestFixtures.WfRevision wf = TaskTestFixtures.insertRevisionWithKey(
                jdbcTemplate, idGenerator, twoPhaseReviewGraph(), "plan");
        stubReviewScenario();

        TaskDto task = tasksApi.createTask(new CreateTaskRequest()
                .title("Двухфазное ревью").description("Приёмочный сценарий M2")
                .workflowKey(wf.workflowKey()).rev(1));

        // plan: bootstrap STATE-сессии (EVENT-wake) → seed-ход (SYSTEM) без перехода
        UUID planSession = awaitStateSession(task.getId(), "plan");
        awaitTurnConsumed(planSession);
        assertThat(currentState(task.getId())).isEqualTo("plan");

        raiseUser(planSession, "начинай: прогони проверки");
        awaitTurnConsumed(planSession);
        awaitState(task.getId(), "run-checks");

        pumpBash(task.getId());
        awaitState(task.getId(), "reviewer-1");

        // reviewer-1 (заход 1): FAILED review — ERROR-ребро возвращает задачу в plan
        UUID reviewer1 = awaitStateSession(task.getId(), "reviewer-1");
        awaitTurnConsumed(reviewer1);
        raiseUser(reviewer1, "что скажешь по изменениям?");
        awaitTurnConsumed(reviewer1);
        awaitState(task.getId(), "plan");

        // plan резюмирует ту же STATE-сессию (D-53) — второй заход цикла
        assertThat(awaitStateSession(task.getId(), "plan")).isEqualTo(planSession);
        raiseUser(planSession, "замечания устранены, продолжай");
        awaitTurnConsumed(planSession);
        awaitState(task.getId(), "run-checks");

        pumpBash(task.getId());
        awaitState(task.getId(), "reviewer-1");

        raiseUser(reviewer1, "повторное ревью — ок?");
        awaitTurnConsumed(reviewer1);
        awaitState(task.getId(), "reviewer-2");

        UUID reviewer2 = awaitStateSession(task.getId(), "reviewer-2");
        awaitTurnConsumed(reviewer2);
        raiseUser(reviewer2, "второе ревью — согласован?");
        awaitTurnConsumed(reviewer2);
        awaitState(task.getId(), "merge");

        pumpBash(task.getId());
        awaitTerminal(task.getId(), "done", "SUCCEEDED");

        // --- история: состояния в порядке + цикл возврата; kinds и reasons корректны
        List<TransitionDto> history = tasksApi.listTaskHistory(task.getId(), null, null).getItems();
        assertThat(history).extracting(AcceptanceTwoPhaseReviewTest::edge)
                .containsExactly(
                        "plan->run-checks:NEXT",
                        "run-checks->reviewer-1:NEXT",
                        "reviewer-1->plan:ERROR",
                        "plan->run-checks:NEXT",
                        "run-checks->reviewer-1:NEXT",
                        "reviewer-1->reviewer-2:NEXT",
                        "reviewer-2->merge:NEXT",
                        "merge->done:NEXT");
        assertThat(reasonText(history.get(2))).as("reason ERROR-возврата от агента")
                .contains("замечания");
        assertThat(reasonText(history.get(3))).as("reason повторного NEXT от агента")
                .contains("устранены");
        assertThat(history.get(1).getReason()).as("bash-reason run-checks")
                .containsEntry("kind", "bash")
                .containsEntry("exitCode", 0);
        assertThat(String.valueOf(history.get(1).getReason().get("output")))
                .as("bash реально исполнялся в контейнере").contains("checks-ok");
        assertThat(String.valueOf(history.get(7).getReason().get("output")))
                .as("merge читает артефакт run-checks").contains("merge-done");

        // --- доказательство реального контейнера: артефакт в host-workspace задачи
        Path artifact = Path.of(System.getProperty("java.io.tmpdir"), "harness-it-workspaces",
                "task-" + task.getId(), "review", "checks.txt");
        assertThat(artifact).as("артефакт bash-состояния").exists();
        assertThat(Files.readString(artifact)).isEqualTo("checks-ok\n");

        // --- bash-таймаут: PT5S в графе, скрипт спит 30с → TIMEOUT → failed
        TaskTestFixtures.WfRevision probe = TaskTestFixtures.insertRevisionWithKey(
                jdbcTemplate, idGenerator, timeoutProbeGraph(), "probe");
        TaskDto timeoutTask = tasksApi.createTask(new CreateTaskRequest()
                .title("Таймаут bash").description("Приёмочный сценарий M2: TIMEOUT")
                .workflowKey(probe.workflowKey()).rev(1));

        long startedAt = System.nanoTime();
        pumpBash(timeoutTask.getId());
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        awaitTerminal(timeoutTask.getId(), "failed", "FAILED");
        List<TransitionDto> probeHistory =
                tasksApi.listTaskHistory(timeoutTask.getId(), null, null).getItems();
        assertThat(probeHistory).extracting(TransitionDto::getKind)
                .as("таймаут состояния → TIMEOUT-переход").containsExactly(TransitionKind.TIMEOUT);
        assertThat(probeHistory.getFirst().getReason())
                .containsEntry("kind", "bash")
                .containsEntry("attempt", 1)
                .as("процесс убит по таймауту — exit не 0")
                .returns(true, reason -> ((Number) reason.get("exitCode")).intValue() != 0);
        assertThat(((Number) probeHistory.getFirst().getReason().get("durationMs")).longValue())
                .as("скрипт убит по PT5S").isBetween(4_000L, 20_000L);
        assertThat(elapsedMs).as("pump не ждал sleep 30").isLessThan(25_000L);
    }

    // --- графы и WireMock-сценарий -------------------------------------------

    /**
     * Граф приёмочного сценария: план → проверки → два ревью → слияние; ERROR-рёбра
     * ревьюеров в {@code plan} (цикл возврата), у BASH — ERROR+TIMEOUT в {@code failed}
     * (правила §2: fan-out ≤ 1 на kind, достижимость терминала).
     */
    private Map<String, Object> twoPhaseReviewGraph() {
        String agentKey = agentSeed.agentKey();
        return WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("plan", "AGENT", Map.of("agent_key", agentKey)),
                        WorkflowTestFixtures.state("run-checks", "BASH_SCRIPT", Map.of(
                                "script", "mkdir -p review && printf 'checks-ok\\n' > review/checks.txt"
                                        + " && cat review/checks.txt",
                                "timeout", "PT5S")),
                        WorkflowTestFixtures.state("reviewer-1", "AGENT", Map.of("agent_key", agentKey)),
                        WorkflowTestFixtures.state("reviewer-2", "AGENT", Map.of("agent_key", agentKey)),
                        WorkflowTestFixtures.state("merge", "BASH_SCRIPT", Map.of(
                                "script", "cat review/checks.txt && echo merge-done",
                                "timeout", "PT5S")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("plan", "run-checks", "NEXT"),
                        WorkflowTestFixtures.transition("plan", "failed", "ERROR"),
                        WorkflowTestFixtures.transition("run-checks", "reviewer-1", "NEXT"),
                        WorkflowTestFixtures.transition("run-checks", "failed", "ERROR"),
                        WorkflowTestFixtures.transition("run-checks", "failed", "TIMEOUT"),
                        WorkflowTestFixtures.transition("reviewer-1", "reviewer-2", "NEXT"),
                        WorkflowTestFixtures.transition("reviewer-1", "plan", "ERROR"),
                        WorkflowTestFixtures.transition("reviewer-2", "merge", "NEXT"),
                        WorkflowTestFixtures.transition("reviewer-2", "plan", "ERROR"),
                        WorkflowTestFixtures.transition("merge", "done", "NEXT"),
                        WorkflowTestFixtures.transition("merge", "failed", "ERROR"),
                        WorkflowTestFixtures.transition("merge", "failed", "TIMEOUT")
                ));
    }

    /** Граф пробы таймаута: BASH с PT5S и {@code sleep 30}; TIMEOUT-исход → failed. */
    private Map<String, Object> timeoutProbeGraph() {
        return WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("probe", "BASH_SCRIPT",
                                Map.of("script", "sleep 30", "timeout", "PT5S")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("probe", "done", "NEXT"),
                        WorkflowTestFixtures.transition("probe", "failed", "ERROR"),
                        WorkflowTestFixtures.transition("probe", "failed", "TIMEOUT")
                ));
    }

    /**
     * Сценарий LLM в порядке вызовов (детерминирован ожиданиями теста): seed-ходы и финальные
     * тексты чередуются с tool-call {@code transition}. Финальный текст USER-хода и seed
     * следующего AGENT-состояния могут прийти в любом порядке — оба текстовые заглушки,
     * взаимозаменяемы.
     */
    private void stubReviewScenario() {
        text(STARTED, "принял задачу, составляю план", "plan-1");
        transition("plan-1", "run-checks", null, "проверки локальны, прогоняю", "plan-1-final");
        text("plan-1-final", "перевожу на ревью", "reviewer-1-seed");
        text("reviewer-1-seed", "смотрю изменения", "reviewer-1-1");
        transition("reviewer-1-1", "plan", "ERROR", "замечания по реализации — возврат на план",
                "reviewer-1-1-final");
        text("reviewer-1-1-final", "вернул задачу на доработку", "plan-2");
        transition("plan-2", "run-checks", null, "замечания устранены, повторяю проверки", "plan-2-final");
        text("plan-2-final", "снова отправляю на ревью", "reviewer-1-2");
        transition("reviewer-1-2", "reviewer-2", null, "первое ревью пройдено", "reviewer-1-2-final");
        text("reviewer-1-2-final", "передаю второму ревьюеру", "reviewer-2-seed");
        text("reviewer-2-seed", "проводим второе ревью", "reviewer-2-1");
        transition("reviewer-2-1", "merge", null, "второе ревью согласовано", "reviewer-2-1-final");
        text("reviewer-2-1-final", "сливаю изменения", "end");
    }

    private void text(String stateIs, String content, String nextState) {
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario(SCENARIO)
                .whenScenarioStateIs(stateIs)
                .willReturn(sse(textChunk(content), usageChunk()))
                .willSetStateTo(nextState));
    }

    private void transition(String stateIs, String toState, String kind, String reason, String nextState) {
        Map<String, Object> arguments = kind == null
                ? Map.of("toState", toState, "reason", reason)
                : Map.of("toState", toState, "kind", kind, "reason", reason);
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario(SCENARIO)
                .whenScenarioStateIs(stateIs)
                .willReturn(sse(toolCallChunk("call-" + stateIs, escapedJson(arguments))))
                .willSetStateTo(nextState));
    }

    // --- шаги сценария и ожидания ---------------------------------------------

    /** USER-сообщение в STATE-сессию по REST (живой Keycloak JWT, владелец задачи). */
    private void raiseUser(UUID sessionId, String text) throws se.rocketscien.harness.testclient.ApiException {
        SendMessageAccepted accepted = messagesApi.sendMessage(sessionId, new SendMessageRequest().text(text));
        assertThat(accepted.getSeq()).as("USER дописан в журнал").isPositive();
    }

    /** Реальный контейнер через ветку диспетчера: скрипт + переход по исходу, синхронно. */
    private void pumpBash(UUID taskId) {
        taskDispatcher.runBashStateOnce(taskId);
    }

    /** STATE-сессия состояния появилась (bootstrap по EVENT-wake). */
    private UUID awaitStateSession(UUID taskId, String stateCode) {
        await().atMost(AWAIT).pollInterval(Duration.ofMillis(100)).until(() ->
                !sessionsOf(taskId, stateCode).isEmpty());
        return sessionsOf(taskId, stateCode).getFirst();
    }

    /** Ход сессии доработал: COMPLETED и батч потреблён (watermark догнал last_seq). */
    private void awaitTurnConsumed(UUID sessionId) {
        await().atMost(AWAIT).pollInterval(Duration.ofMillis(100)).until(() -> {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT last_turn_outcome, last_consumed_seq, last_seq FROM session WHERE id = ?",
                    sessionId);
            return "COMPLETED".equals(row.get("last_turn_outcome"))
                    && ((Number) row.get("last_consumed_seq")).longValue()
                    >= ((Number) row.get("last_seq")).longValue();
        });
    }

    /** Задача вошла в ожидаемое состояние. */
    private void awaitState(UUID taskId, String stateCode) {
        await().atMost(AWAIT).pollInterval(Duration.ofMillis(100))
                .until(() -> stateCode.equals(currentState(taskId)));
    }

    /** Задача в терминале: state и статус-проекция (TERMINAL SUCCESS/FAILED). */
    private void awaitTerminal(UUID taskId, String stateCode, String projection) {
        await().atMost(AWAIT).pollInterval(Duration.ofMillis(100)).until(() -> {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT current_state, status_projection FROM task WHERE id = ?", taskId);
            return stateCode.equals(row.get("current_state"))
                    && projection.equals(row.get("status_projection"));
        });
    }

    private String currentState(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT current_state FROM task WHERE id = ?", String.class, taskId);
    }

    private List<UUID> sessionsOf(UUID taskId, String stateCode) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM session WHERE task_id = ? AND state_code = ? AND kind = 'STATE'",
                UUID.class, taskId, stateCode);
    }

    private static String edge(TransitionDto transition) {
        return transition.getFromState() + "->" + transition.getToState() + ":" + transition.getKind();
    }

    private static String reasonText(TransitionDto transition) {
        return String.valueOf(transition.getReason().get("text"));
    }

    // --- каркасы WireMock-ответов (как в AgentTransitionToolTest) ---------------

    private static String escapedJson(Map<String, Object> value) {
        return JSON.writeValueAsString(value).replace("\"", "\\\"");
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder sse(
            String... events) {
        StringBuilder body = new StringBuilder();
        for (String event : events) {
            body.append("data: ").append(event).append("\n\n");
        }
        body.append("data: [DONE]\n\n");
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(body.toString());
    }

    private static String textChunk(String content) {
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"},\"finish_reason\":null}]}";
    }

    private static String usageChunk() {
        return "{\"id\":\"2\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    }

    /** tool-call перехода; argumentsJson — вложенный JSON с экранированными кавычками. */
    private static String toolCallChunk(String id, String escapedArgumentsJson) {
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"" + id
                + "\",\"type\":\"function\",\"function\":{\"name\":\"transition\",\"arguments\":\""
                + escapedArgumentsJson + "\"}}]},\"finish_reason\":\"tool_calls\"}]}";
    }
}
