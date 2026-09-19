package se.rocketscien.harness.tests.execution.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.execution.TurnManager;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.session.StateSessionService;
import se.rocketscien.harness.task.TransitionKind;
import se.rocketscien.harness.tests.workflow.WorkflowTestFixtures;
import se.rocketscien.harness.tests.execution.DockerTestSupport;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Мета-инструмент {@code transition} (пачка J.2; спека agent-turn «Мета-инструмент transition
 * (гейт metaTools)»): USER-ход разрешает переход (применение — транзакционно в момент
 * tool-call), ход от TOOL_RESULT блокируется гейтом D-52/D-59, пустой reason отклоняется,
 * лимит {@code harness.task.transition.max-per-turn} исчерпывается вторым вызовом.
 * Подъём хода — J.3-путь (bootstrap STATE-сессии); seed-Turn (SYSTEM) дорабатывает до USER-хода.
 */
class AgentTransitionToolTest extends BaseApplicationTest {

    private static final String PATH = "/v1/chat/completions";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Тот же ключ, что harness.llm.encryption-keys.1 в application-test.yml. */
    private static final byte[] LLM_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /** Сценарий WireMock: bootstrap-ход (STARTED) → USER-ход с transition → финальный текст. */
    private static final String ST_BOOTSTRAP = com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
    private static final String ST_AWAIT_USER = "await-user";
    private static final String ST_FINAL = "final-answer";
    /** Расширенная цепочка J-1: bootstrap-текст → долгий bash → transition → финал. */
    private static final String ST_BASH = "with-bash";
    private static final String ST_TRANSITION = "with-transition";
    /** Цепочка R-1 (COMPLETED): финал хода 1 → transition хода 2 → финал хода 2. */
    private static final String ST_TRANSITION2 = "with-transition-2";
    private static final String ST_FINAL2 = "final-answer-2";

    static {
        DockerTestSupport.helperImage();
    }

    @Autowired
    private TurnManager turnManager;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private StateSessionService stateSessions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    @Autowired
    private Environment environment;

    private String agentKey;
    private UUID agentRevisionId;

    @BeforeEach
    void seedAgent() {
        llmWireMock.resetAll();
        agentRevisionId = idGenerator.newUuidV7();
        agentKey = "orchestrator-" + agentRevisionId;
        UUID credentialsId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                """
                INSERT INTO llm_credentials (id, name, base_url, api_key_encrypted, key_version, created_at)
                VALUES (?, ?, ?, ?, 1, now())
                """,
                credentialsId, "creds-" + credentialsId,
                environment.getRequiredProperty("wiremock.llm.url") + "/v1", encrypt("sk-test"));
        UUID modelId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                "INSERT INTO llm_model (id, credentials_id, model_id, created_at) VALUES (?, ?, ?, now())",
                modelId, credentialsId, "gpt-test");
        jdbcTemplate.update(
                """
                INSERT INTO agent (id, key, name, rev, role_prompt, llm_model_id, created_at)
                VALUES (?, ?, 'Оркестратор', 1, 'Ты оркестратор.', ?, now())
                """,
                agentRevisionId, agentKey, modelId);
    }

    @Test
    void userRaisedTurnAppliesTransitionTransactionally() {
        UUID taskId = newAgentTask();
        Session session = bootstrapTurn(taskId, transitionAnswer("тесты зелёные"));

        long userSeq = raiseByUser(session);
        awaitConsumed(session.id(), userSeq);

        assertThat(currentState(taskId)).as("переход применён в момент tool-call").isEqualTo("checks");
        Map<String, Object> history = jdbcTemplate.queryForMap(
                "SELECT from_state, to_state, kind, reason_jsonb FROM task_transition_history "
                        + "WHERE task_id = ?", taskId);
        assertThat(history.get("from_state")).isEqualTo("plan");
        assertThat(history.get("to_state")).isEqualTo("checks");
        assertThat(history.get("kind")).isEqualTo(TransitionKind.NEXT.name());
        assertThat(history.get("reason_jsonb").toString()).contains("тесты зелёные");
        assertThat(toolResultField(session.id(), "transition", "status")).isEqualTo("OK");
        assertThat(taskEventSeq(taskId)).as("инкремент task_event_seq транзакционно с переходом").isEqualTo(1);
    }

    @Test
    void toolResultRaisedTurnIsBlockedByMetaToolsGate() {
        UUID taskId = newAgentTask();
        Session session = bootstrapTurn(taskId, transitionAnswer("попытка из TOOL_RESULT"));

        // ход поднят результатом инструмента (USER в батче нет) — гейт metaTools отклоняет
        long orphanSeq = sessionStore.appendEvent(session.id(), MessageKind.TOOL_RESULT, null,
                Map.of("callId", "orphan", "tool", "bash", "status", "OK", "output", "сирота"), null).seq();
        turnManager.tryStart(session.id());
        awaitConsumed(session.id(), orphanSeq);

        Integer history = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM task_transition_history WHERE task_id = ?", Integer.class, taskId);
        assertThat(history).as("переход не записан").isZero();
        assertThat(toolResultField(session.id(), "transition", "output")).contains("instructionSource");
        assertThat(currentState(taskId)).isEqualTo("plan");
    }

    @Test
    void blankReasonIsRejectedWithoutTransition() {
        UUID taskId = newAgentTask();
        Session session = bootstrapTurn(taskId, transitionAnswer("   "));

        long userSeq = raiseByUser(session);
        awaitConsumed(session.id(), userSeq);

        Integer history = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM task_transition_history WHERE task_id = ?", Integer.class, taskId);
        assertThat(history).as("переход без reason не записан").isZero();
        assertThat(toolResultField(session.id(), "transition", "output")).contains("reason");
        assertThat(currentState(taskId)).isEqualTo("plan");
    }

    @Test
    void secondTransitionInSameTurnExceedsLimit() {
        UUID taskId = newAgentTask();
        Session session = bootstrapTurn(taskId,
                sse(toolCallEvent("call-1", transitionJson("checks", "первый переход"), 0),
                        toolCallEvent("call-2", transitionJson("failed", "второй переход"), 1)));

        long userSeq = raiseByUser(session);
        awaitConsumed(session.id(), userSeq);

        Integer history = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM task_transition_history WHERE task_id = ?", Integer.class, taskId);
        assertThat(history).as("max-per-turn=1: применён только первый вызов").isEqualTo(1);
        // TOOL_RESULT несёт внутренний callId — джойнимся через TOOL_CALL-строку его провайдерского id
        String secondOutput = jdbcTemplate.queryForObject(
                """
                SELECT tr.payload_jsonb ->> 'output' FROM session_message tr
                WHERE tr.session_id = ? AND tr.kind = 'TOOL_RESULT'
                  AND tr.payload_jsonb ->> 'callId' = (
                      SELECT tc.payload_jsonb ->> 'callId' FROM session_message tc
                      WHERE tc.session_id = ? AND tc.kind = 'TOOL_CALL'
                        AND tc.payload_jsonb ->> 'toolCallId' = 'call-2')
                """, String.class, session.id(), session.id());
        assertThat(secondOutput).contains("лимит");
        assertThat(currentState(taskId)).isEqualTo("checks");
    }

    /**
     * J-1: USER, дописанный при занятом локе (HTTP-wake выпал бы), не должен потерять
     * USER-намерение. Ход 2 (источник TOOL_RESULT) исполняет долгий bash; USER + stop —
     * ход CANCELLED с watermark до USER (непрочитан) → post-finish re-wake → ход 3 со
     * source=USER → transition разрешён гейтом и применён.
     */
    @Test
    void userArrivingDuringToolResultTurnStartsRewokenTurnWithUserGate() {
        UUID taskId = newAgentTask();

        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("j1").whenScenarioStateIs(ST_BOOTSTRAP)
                .willReturn(sse(textChunk("готов"), usageChunk())).willSetStateTo(ST_BASH));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("j1").whenScenarioStateIs(ST_BASH)
                .willReturn(sse(toolCallNamed("call-b", "bash", escapedJson(Map.of("command", "sleep 3")), 0)))
                .willSetStateTo(ST_TRANSITION));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("j1").whenScenarioStateIs(ST_TRANSITION)
                .willReturn(sse(toolCallEvent("call-1", transitionJson("checks", "после re-wake"), 0)))
                .willSetStateTo(ST_FINAL));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("j1").whenScenarioStateIs(ST_FINAL)
                .willReturn(sse(textChunk("резерв"), usageChunk())));

        stateSessions.findOrCreate(taskId, "plan", agentRevisionId);
        Session session = stateSessions.findOrCreate(taskId, "plan", agentRevisionId);
        turnManager.tryStart(session.id());
        awaitCompleted(session.id());

        // ход 2 от TOOL_RESULT (SYSTEM/TOOL_RESULT-источник — гейт закрыт)
        sessionStore.appendEvent(session.id(), MessageKind.TOOL_RESULT, null,
                Map.of("callId", "orphan", "tool", "bash", "status", "OK", "output", "сирота"), null);
        turnManager.tryStart(session.id());
        awaitToolCall(session.id(), "bash");

        // race-окно: USER при активном ходе (лок занят), затем stop — CANCELLED не потребляет USER
        long userSeq = sessionStore.appendEvent(session.id(), MessageKind.USER, session.ownerUserId(),
                Map.of("text", "переведи задачу"), null).seq();
        turnManager.requestStop(session.id());

        awaitConsumed(session.id(), userSeq);

        assertThat(currentState(taskId)).as("transition из re-woken USER-хода применён").isEqualTo("checks");
        Integer history = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM task_transition_history WHERE task_id = ?", Integer.class, taskId);
        assertThat(history).isEqualTo(1);
        assertThat(toolResultField(session.id(), "transition", "status")).isEqualTo("OK");
    }

    /**
     * J-1/R-1, COMPLETED-сценарий: TOOL_RESULT-ход рендерит mid-turn USER доп. раундом, гейт
     * блокирует его transition, но финальный ASSISTANT НЕ потребляет USER — движок замораживает
     * {@code last_consumed_seq} на нём (pending-намерение). Re-wake поднимает ход 2 с
     * source=USER — transition применён; запись в истории одна (от хода 2).
     */
    @Test
    void completedToolResultTurnKeepsUserIntentPendingAndRewakes() {
        UUID taskId = newAgentTask();

        // Линейный сценарий: bootstrap-текст → bash → transition (блок в ходе 1) →
        // текст (финал хода 1) → transition (ход 2) → финальный текст.
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("r1").whenScenarioStateIs(ST_BOOTSTRAP)
                .willReturn(sse(textChunk("готов"), usageChunk())).willSetStateTo(ST_BASH));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("r1").whenScenarioStateIs(ST_BASH)
                .willReturn(sse(toolCallNamed("call-b", "bash", escapedJson(Map.of("command", "sleep 2")), 0)))
                .willSetStateTo(ST_TRANSITION));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("r1").whenScenarioStateIs(ST_TRANSITION)
                .willReturn(sse(toolCallEvent("call-1", transitionJson("checks", "попытка в чужом ходе"), 0)))
                .willSetStateTo(ST_TRANSITION2));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("r1").whenScenarioStateIs(ST_TRANSITION2)
                .willReturn(sse(textChunk("не могу, жду пользователя"), usageChunk())).willSetStateTo(ST_FINAL));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("r1").whenScenarioStateIs(ST_FINAL)
                .willReturn(sse(toolCallEvent("call-2", transitionJson("checks", "после re-wake"), 0))
                        .withFixedDelay(1200))
                .willSetStateTo(ST_FINAL2));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("r1").whenScenarioStateIs(ST_FINAL2)
                .willReturn(sse(textChunk("готово"), usageChunk())));

        stateSessions.findOrCreate(taskId, "plan", agentRevisionId);
        Session session = stateSessions.findOrCreate(taskId, "plan", agentRevisionId);
        turnManager.tryStart(session.id());
        awaitCompleted(session.id());

        // ход 1 от TOOL_RESULT; во время долгого bash дописываем USER (лок занят — wake выпал бы)
        sessionStore.appendEvent(session.id(), MessageKind.TOOL_RESULT, null,
                Map.of("callId", "orphan", "tool", "bash", "status", "OK", "output", "сирота"), null);
        turnManager.tryStart(session.id());
        awaitToolCall(session.id(), "bash");
        long userSeq = sessionStore.appendEvent(session.id(), MessageKind.USER, session.ownerUserId(),
                Map.of("text", "переведи задачу"), null).seq();

        // ход 1: гейт заблокировал transition (доп. раунд увидел USER)
        awaitToolResultStatus(session.id(), "transition", "ERROR");
        // ход 1 COMPLETED, но last_consumed заморожен на USER (pending-намерение) — ход 2 в очереди
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> {
                    var row = jdbcTemplate.queryForMap(
                            "SELECT last_turn_outcome, last_consumed_seq, last_seq FROM session WHERE id = ?",
                            session.id());
                    return "COMPLETED".equals(row.get("last_turn_outcome"))
                            && ((Number) row.get("last_consumed_seq")).longValue() == userSeq - 1
                            && ((Number) row.get("last_seq")).longValue() > userSeq - 1;
                });

        // re-wake: ход 2 с source=USER применяет transition и потребляет всё
        awaitConsumed(session.id(), userSeq);

        Integer history = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM task_transition_history WHERE task_id = ?", Integer.class, taskId);
        assertThat(history).as("запись истории одна — от re-woken хода").isEqualTo(1);
        assertThat(currentState(taskId)).isEqualTo("checks");
        assertThat(toolResultField(session.id(), "transition", "status"))
                .as("последний transition — применённый ходом 2").isEqualTo("OK");
        Integer blocked = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM session_message
                WHERE session_id = ? AND kind = 'TOOL_RESULT' AND payload_jsonb ->> 'tool' = 'transition'
                  AND payload_jsonb ->> 'status' = 'ERROR'
                  AND payload_jsonb ->> 'output' LIKE '%instructionSource%'
                """, Integer.class, session.id());
        assertThat(blocked).as("блокировка гейтом в ходе 1 задокументирована в журнале").isEqualTo(1);
        long consumed = jdbcTemplate.queryForObject(
                "SELECT last_consumed_seq FROM session WHERE id = ?", Long.class, session.id());
        long lastSeq = jdbcTemplate.queryForObject(
                "SELECT last_seq FROM session WHERE id = ?", Long.class, session.id());
        assertThat(consumed).as("после хода 2 батч потреблён полностью").isEqualTo(lastSeq);
    }

    // --- сценарии и помощники ------------------------------------------------

    /**
     * J.3-путь: bootstrap STATE-сессии (findOrCreate + wake), seed-Turn (SYSTEM-источник)
     * дорабатывает до конца — дальше тест поднимает ходы сам.
     *
     * @param userTurnResponse ответ LLM на первый раунд USER-хода (null — ход будет без LLM-вызова)
     */
    private Session bootstrapTurn(UUID taskId,
                                  com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder userTurnResponse) {
        stubFor(ST_BOOTSTRAP, sse(textChunk("готов"), usageChunk()));
        if (userTurnResponse != null) {
            stubFor(ST_AWAIT_USER, userTurnResponse);
        }
        stubFor(ST_FINAL, sse(textChunk("резерв"), usageChunk()));

        stateSessions.findOrCreate(taskId, "plan", agentRevisionId);
        Session session = stateSessions.findOrCreate(taskId, "plan", agentRevisionId);
        turnManager.tryStart(session.id());
        awaitCompleted(session.id());
        return session;
    }

    /** Ответ-переход для USER-хода (финальный текст — следующий раунд из ST_FINAL). */
    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder transitionAnswer(
            String reason) {
        return sse(toolCallEvent("call-1", transitionJson("checks", reason), 0));
    }

    private static String transitionJson(String toState, String reason) {
        return escapedJson(Map.of("toState", toState, "reason", reason));
    }

    /** Аргументы tool-call, экранированные для вложения в JSON строки-чанка. */
    private static String escapedJson(Map<String, Object> value) {
        return JSON.writeValueAsString(value).replace("\"", "\\\"");
    }

    private long raiseByUser(Session session) {
        long seq = sessionStore.appendEvent(session.id(), MessageKind.USER, session.ownerUserId(),
                Map.of("text", "переведи задачу дальше"), null).seq();
        turnManager.tryStart(session.id());
        return seq;
    }

    private void stubFor(String state, com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response) {
        var builder = post(urlEqualTo(PATH)).inScenario("turn").whenScenarioStateIs(state)
                .willReturn(response);
        if (!ST_FINAL.equals(state)) {
            builder.willSetStateTo(ST_AWAIT_USER.equals(state) ? ST_FINAL : ST_AWAIT_USER);
        }
        llmWireMock.stubFor(builder);
    }

    private void awaitCompleted(UUID sessionId) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> "COMPLETED".equals(jdbcTemplate.queryForObject(
                        "SELECT last_turn_outcome FROM session WHERE id = ?", String.class, sessionId)));
    }

    /** TOOL_CALL конкретного инструмента зажурналирован (write-ahead) — инструмент начал/начинает работу. */
    private void awaitToolCall(UUID sessionId, String tool) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> jdbcTemplate.queryForObject(
                        """
                        SELECT count(*) FROM session_message
                        WHERE session_id = ? AND kind = 'TOOL_CALL' AND payload_jsonb ->> 'tool' = ?
                        """, Integer.class, sessionId, tool) > 0);
    }

    /** TOOL_RESULT конкретного инструмента пришёл в заданном статусе (OK/ERROR/...). */
    private void awaitToolResultStatus(UUID sessionId, String tool, String status) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> jdbcTemplate.queryForObject(
                        """
                        SELECT count(*) FROM session_message
                        WHERE session_id = ? AND kind = 'TOOL_RESULT'
                          AND payload_jsonb ->> 'tool' = ? AND payload_jsonb ->> 'status' = ?
                        """, Integer.class, sessionId, tool, status) > 0);
    }

    /** Turn конкретного батча доработал: исход COMPLETED и watermark потребления догнал seq. */
    private void awaitConsumed(UUID sessionId, long batchSeq) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    var row = jdbcTemplate.queryForMap(
                            "SELECT last_turn_outcome, last_consumed_seq FROM session WHERE id = ?", sessionId);
                    return "COMPLETED".equals(row.get("last_turn_outcome"))
                            && ((Number) row.get("last_consumed_seq")).longValue() >= batchSeq;
                });
    }

    private String currentState(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT current_state FROM task WHERE id = ?", String.class, taskId);
    }

    private long taskEventSeq(UUID taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT task_event_seq FROM task WHERE id = ?", Long.class, taskId);
    }

    private String toolResultField(UUID sessionId, String tool, String field) {
        return jdbcTemplate.queryForObject(
                """
                SELECT payload_jsonb ->> ? FROM session_message
                WHERE session_id = ? AND kind = 'TOOL_RESULT' AND payload_jsonb ->> 'tool' = ?
                ORDER BY seq DESC LIMIT 1
                """, String.class, field, sessionId, tool);
    }

    /** AGENT-старт; вставка мимо реестра — wake реестра не поднимает bootstrap до готовности теста. */
    private UUID newAgentTask() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("plan", "AGENT", Map.of("agent_key", agentKey)),
                        WorkflowTestFixtures.state("checks", "BASH_SCRIPT", Map.of("script", "true")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("plan", "checks", "NEXT"),
                        WorkflowTestFixtures.transition("plan", "failed", "ERROR"),
                        WorkflowTestFixtures.transition("checks", "done", "NEXT"),
                        WorkflowTestFixtures.transition("checks", "failed", "ERROR"),
                        WorkflowTestFixtures.transition("checks", "done", "TIMEOUT")
                ));
        return TaskEngineTestFixtures.insertTaskRaw(jdbcTemplate, idGenerator,
                graph, "plan", "plan", "AGENT", "RUNNING", false);
    }

    @lombok.SneakyThrows
    private static String encrypt(String value) {
        return se.rocketscien.harness.common.AesGcmEncryption.encrypt(value, LLM_KEY);
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

    private static String toolCallEvent(String id, String escapedArgumentsJson, int index) {
        return toolCallNamed(id, "transition", escapedArgumentsJson, index);
    }

    private static String toolCallNamed(String id, String tool, String escapedArgumentsJson, int index) {
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":" + index
                + ",\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\"" + tool
                + "\",\"arguments\":\"" + escapedArgumentsJson + "\"}}]},\"finish_reason\":\"tool_calls\"}]}";
    }
}
