package se.rocketscien.harness.tests.execution;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.LoggerFactory;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.execution.TurnManager;
import se.rocketscien.harness.mcp.McpAgentConfigAuditor;
import se.rocketscien.harness.mcp.McpAuthRefresher;
import se.rocketscien.harness.mcp.McpClientRegistry;
import se.rocketscien.harness.mcp.McpToolDescriptor;
import se.rocketscien.harness.config.McpProperties;
import se.rocketscien.harness.session.MessageKind;
import se.rocketscien.harness.session.Session;
import se.rocketscien.harness.session.SessionStore;
import se.rocketscien.harness.session.TurnOutcome;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Пачка Q (D-63): MCP-клиент — ленивые соединения (холодный старт чист), манифест
 * {@code server.tool} с include/exclude-фильтрами (Q.3), вызов в стандартном контракте
 * через адаптер (Q.2), auth-refresh через прокси: 401 → refresh → retry; повторный 401 →
 * auth-refresh-failed (Q.4). Async-capable MCP-инструмент ({@code _meta["async-capable"]})
 * паркуется в то же окно, что нативные (Q.2).
 */
class McpToolsTest extends BaseApplicationTest {

    private static final String PATH = "/v1/chat/completions";
    private static final String MCP_PATH = "/mcp";
    private static final String PROXY_PATH = "/mcp-auth-proxy";

    @Autowired
    private TurnManager turnManager;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    @Autowired
    private Environment environment;

    @Autowired
    private McpClientRegistry mcpClients;

    @Autowired
    private McpAuthRefresher authRefresher;

    @Autowired
    private McpAgentConfigAuditor auditor;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void resetStubs() {
        llmWireMock.resetAll();
        stubMcpBasics();
    }

    @Test
    void coldStartIsLazyAndManifestIsCached() {
        // Ручной реестр на тех же свойствах: до первого обращения клиентов нет (Q.1)
        McpProperties props = new McpProperties(List.of(new McpProperties.Server(
                        "demo", environment.getRequiredProperty("wiremock.llm.url"), "http",
                        new McpProperties.ServerAuth("oauth-bearer"), "HARNESS_MCP_DEMO_TOKEN", "/mcp")),
                new McpProperties.Auth(environment.getRequiredProperty("wiremock.llm.url") + PROXY_PATH,
                        5000, 10000),
                Duration.ofSeconds(10), Duration.ofSeconds(10));
        McpClientRegistry fresh = new McpClientRegistry(props, authRefresher);
        assertThat(fresh.initializedClients()).isZero();

        List<McpToolDescriptor> tools = fresh.listTools("demo");

        assertThat(fresh.initializedClients()).isEqualTo(1);
        assertThat(tools).extracting(McpToolDescriptor::namespacedName)
                .containsExactly("demo.search", "demo.slow_report");
        assertThat(tools.get(1).asyncCapable()).as("async-capable из _meta").isTrue();

        // Манифест кэшируется: второй вызов без нового tools/list
        int before = wiremockCount();
        fresh.listTools("demo");
        assertThat(wiremockCount()).isEqualTo(before);
    }

    @Test
    void manifestIncludesNamespacedMcpToolsWithFilters() {
        // Q-1: per-server фильтры — массив объектов [{server, include?, exclude?}]
        Session session = mcpSession(List.of(Map.of(
                "server", "demo",
                "include", List.of("demo.search"))));

        llmWireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(sse(TurnEngineWireMockTest.textChunk("готово"))));
        sessionStore.appendEvent(session.id(), MessageKind.USER, session.ownerUserId(),
                Map.of("text", "что у тебя есть"));
        turnManager.tryStart(session.id());
        awaitOutcome(session.id(), TurnOutcome.COMPLETED);

        // В промпт LLM ушёл только включённый фильтром инструмент
        llmWireMock.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(containing("demo.search")));
        llmWireMock.verify(0, postRequestedFor(urlEqualTo(PATH)).withRequestBody(containing("slow_report")));
    }

    @Test
    void excludeWinsOverIncludeOnOverlap() {
        // Q-2: семантика — сначала include (белый), затем exclude вычитает поверх:
        // slow_report в обоих списках → не входит в манифест
        Session session = mcpSession(List.of(Map.of(
                "server", "demo",
                "include", List.of("demo.search", "demo.slow_report"),
                "exclude", List.of("demo.slow_report"))));

        llmWireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(sse(TurnEngineWireMockTest.textChunk("готово"))));
        sessionStore.appendEvent(session.id(), MessageKind.USER, session.ownerUserId(),
                Map.of("text", "что у тебя есть"));
        turnManager.tryStart(session.id());
        awaitOutcome(session.id(), TurnOutcome.COMPLETED);

        llmWireMock.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(containing("demo.search")));
        llmWireMock.verify(0, postRequestedFor(urlEqualTo(PATH)).withRequestBody(containing("slow_report")));
    }

    @Test
    void mcpToolCallReturnsStandardContract() {
        Session session = mcpSession(List.of(Map.of("server", "demo")));

        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("sync-call")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(toolCallChunk("call-1", "demo.search",
                        "{\"query\":\"spring\"}")))
                .willSetStateTo("final"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("sync-call")
                .whenScenarioStateIs("final")
                .willReturn(sse(TurnEngineWireMockTest.textChunk("готово"))));

        sessionStore.appendEvent(session.id(), MessageKind.USER, session.ownerUserId(),
                Map.of("text", "поищи"));
        turnManager.tryStart(session.id());
        awaitOutcome(session.id(), TurnOutcome.COMPLETED);

        assertThat(journalKinds(session.id())).containsExactly(
                "USER", "ASSISTANT", "TOOL_CALL", "TOOL_RESULT", "ASSISTANT");
        assertThat(journalField(session.id(), "TOOL_RESULT", "status")).isEqualTo("OK");
        // Аргументы дошли до сервера, текст ответа — в стандартном output
        assertThat(journalField(session.id(), "TOOL_RESULT", "output")).isEqualTo("найдено: spring");
        llmWireMock.verify(postRequestedFor(urlEqualTo(MCP_PATH))
                .withRequestBody(containing("\"query\"")));
    }

    @Test
    void asyncCapableMcpToolParksThenLateResult() {
        Session session = mcpSession(List.of(Map.of("server", "demo")));

        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("async-mcp")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(toolCallChunk("call-1", "demo.slow_report", "{}")))
                .willSetStateTo("final"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("async-mcp")
                .whenScenarioStateIs("final")
                .willReturn(sse(TurnEngineWireMockTest.textChunk("отчитался"))));
        // slow_report отвечает 12 с (окно 10 с, SDK call-timeout 20 с) — парковка,
        // поздний результат
        llmWireMock.stubFor(post(urlEqualTo(MCP_PATH))
                .withRequestBody(matchingJsonPath("$.method", equalTo("tools/call")))
                .withRequestBody(matchingJsonPath("$.params.name", equalTo("slow_report")))
                .atPriority(1)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(12000)
                        .withTransformers("response-template")
                        .withBody("{\"jsonrpc\":\"2.0\",\"id\":\"{{jsonPath request.body '$.id'}}\","
                                + "\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"отчёт готов\"}],"
                                + "\"isError\":false}}")));

        sessionStore.appendEvent(session.id(), MessageKind.USER, session.ownerUserId(),
                Map.of("text", "медленный отчёт"));
        turnManager.tryStart(session.id());

        awaitOutcome(session.id(), TurnOutcome.COMPLETED);
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(100))
                .until(() -> journalKinds(session.id()).contains("TOOL_RESULT"));
        // Wake по позднему результату: новый Turn доводит журнал финальным ASSISTANT
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .until(() -> journalKinds(session.id()).getLast().equals("ASSISTANT"));

        assertThat(journalKinds(session.id())).containsExactly(
                "USER", "ASSISTANT", "TOOL_CALL", "ASYNC_ACCEPTED", "TOOL_RESULT", "ASSISTANT");
        assertThat(journalField(session.id(), "TOOL_RESULT", "late")).isEqualTo("true");
        assertThat(journalField(session.id(), "TOOL_RESULT", "status")).isEqualTo("OK");
        assertThat(journalField(session.id(), "TOOL_RESULT", "output")).contains("отчёт готов");
    }

    @Test
    void unknownMcpServerInAgentConfigFailsTurn() {
        Session session = mcpSession(List.of(Map.of("server", "ghost")));

        sessionStore.appendEvent(session.id(), MessageKind.USER, session.ownerUserId(),
                Map.of("text", "позови призрака"));
        turnManager.tryStart(session.id());
        awaitOutcome(session.id(), TurnOutcome.FAILED);

        assertThat(journalField(session.id(), "SYSTEM", "text")).contains("неизвестный MCP-сервер");
    }

    @Test
    void authRefreshRetriesOnceAfterUnauthorized() {
        // Q.4: первый tools/call → 401 → refresh через прокси → retry → 200
        llmWireMock.stubFor(post(urlEqualTo(MCP_PATH))
                .withRequestBody(matchingJsonPath("$.method", equalTo("tools/call")))
                .atPriority(1)
                .inScenario("auth-ok")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("authorized"));
        llmWireMock.stubFor(post(urlEqualTo(MCP_PATH))
                .withRequestBody(matchingJsonPath("$.method", equalTo("tools/call")))
                .atPriority(1)
                .inScenario("auth-ok")
                .whenScenarioStateIs("authorized")
                .willReturn(callToolResult("найдено: spring")));
        llmWireMock.stubFor(post(urlEqualTo(PROXY_PATH))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"token\":\"fresh-token\"}")));

        Session session = mcpSession(List.of(Map.of("server", "demo")));
        runSearchTurn(session);

        assertThat(journalField(session.id(), "TOOL_RESULT", "status")).isEqualTo("OK");
        assertThat(journalField(session.id(), "TOOL_RESULT", "output")).isEqualTo("найдено: spring");
        llmWireMock.verify(1, postRequestedFor(urlEqualTo(PROXY_PATH)));
    }

    @Test
    void secondUnauthorizedYieldsAuthRefreshFailed() {
        // Q.4: повторный 401 после refresh — auth-refresh-failed, без новых попыток
        llmWireMock.stubFor(post(urlEqualTo(MCP_PATH))
                .withRequestBody(matchingJsonPath("$.method", equalTo("tools/call")))
                .atPriority(1)
                .willReturn(aResponse().withStatus(401)));
        llmWireMock.stubFor(post(urlEqualTo(PROXY_PATH))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"token\":\"fresh-token\"}")));

        Session session = mcpSession(List.of(Map.of("server", "demo")));
        runSearchTurn(session);

        assertThat(journalField(session.id(), "TOOL_RESULT", "status")).isEqualTo("ERROR");
        assertThat(journalField(session.id(), "TOOL_RESULT", "output")).contains("auth-refresh-failed");
        // Ровно одно обновление токена (retry один, без шторма)
        llmWireMock.verify(1, postRequestedFor(urlEqualTo(PROXY_PATH))
                .withRequestBody(containing("demo")));
    }

    @Test
    void auditorLogsUnknownServerAndWarnsOnNonCanonicalMcpForm() throws Exception {
        // R-1: SQL-предикат jsonb_exists должен находить строки (оператор `?` PgJDBC принимает
        // за плейсхолдер); R-2: mcp не-массив — WARNING, а не silent skip
        ExecutionFixtures.newSessionWithAgent(jdbcTemplate, sessionStore, idGenerator, environment, null,
                json.writeValueAsString(Map.of("mcp", List.of(Map.of("server", "jira")))));
        ExecutionFixtures.newSessionWithAgent(jdbcTemplate, sessionStore, idGenerator, environment, null,
                json.writeValueAsString(Map.of("mcp", List.of(Map.of("server", "demo")))));
        Session nonCanonical = ExecutionFixtures.newSessionWithAgent(
                jdbcTemplate, sessionStore, idGenerator, environment, null,
                json.writeValueAsString(Map.of("mcp", Map.of("server", "jira"))));

        Logger logger = (Logger) LoggerFactory.getLogger(McpAgentConfigAuditor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            auditor.audit();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .contains("неизвестный MCP-сервер").contains("jira");
        });
        assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                .noneMatch(message -> message.contains("неизвестный MCP-сервер 'demo'"));
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains(agentKeyOf(nonCanonical)).contains("не является массивом");
        });
    }

    @Test
    void mcpNameCollisionFailsFastOnDuplicateServerNames() {
        // Q-3: дубликаты name в harness.mcp.servers — fail-fast при создании реестра
        McpProperties props = new McpProperties(List.of(
                new McpProperties.Server("dup", "http://localhost:1", "http", null, null, null),
                new McpProperties.Server("dup", "http://localhost:2", "http", null, null, null)),
                null, Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThatThrownBy(() -> new McpClientRegistry(props, authRefresher))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP-name-collision");
    }

    // ---------------------------------------------------------------- хелперы

    private String agentKeyOf(Session session) {
        return jdbcTemplate.queryForObject(
                "SELECT key FROM agent WHERE id = ?", String.class, session.agentRevisionId());
    }

    private Session mcpSession(List<Map<String, Object>> mcpConfig) {
        String toolsJson;
        try {
            toolsJson = json.writeValueAsString(Map.of("mcp", mcpConfig));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return ExecutionFixtures.newSessionWithAgent(
                jdbcTemplate, sessionStore, idGenerator, environment, null, toolsJson);
    }

    private void runSearchTurn(Session session) {
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("search-run")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(sse(toolCallChunk("call-1", "demo.search", "{\"query\":\"spring\"}")))
                .willSetStateTo("final"));
        llmWireMock.stubFor(post(urlEqualTo(PATH)).inScenario("search-run")
                .whenScenarioStateIs("final")
                .willReturn(sse(TurnEngineWireMockTest.textChunk("готово"))));

        sessionStore.appendEvent(session.id(), MessageKind.USER, session.ownerUserId(),
                Map.of("text", "поищи"));
        turnManager.tryStart(session.id());
        awaitOutcome(session.id(), TurnOutcome.COMPLETED);
    }

    /**
     * Базовые stub'ы streamable-HTTP MCP: initialize (echo protocolVersion/id через
     * response-template), notifications/initialized → 202, tools/list → манифест.
     */
    private void stubMcpBasics() {
        llmWireMock.stubFor(post(urlEqualTo(MCP_PATH))
                .withRequestBody(matchingJsonPath("$.method", equalTo("initialize")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Mcp-Session-Id", "test-session-1")
                        .withTransformers("response-template")
                        .withBody("{\"jsonrpc\":\"2.0\",\"id\":\"{{jsonPath request.body '$.id'}}\","
                                + "\"result\":{\"protocolVersion\":\"{{jsonPath request.body '$.params.protocolVersion'}}\","
                                + "\"capabilities\":{\"tools\":{}},"
                                + "\"serverInfo\":{\"name\":\"demo\",\"version\":\"1.0\"}}}")));
        llmWireMock.stubFor(post(urlEqualTo(MCP_PATH))
                .withRequestBody(matchingJsonPath("$.method", equalTo("notifications/initialized")))
                .willReturn(aResponse().withStatus(202)));
        llmWireMock.stubFor(post(urlEqualTo(MCP_PATH))
                .withRequestBody(matchingJsonPath("$.method", equalTo("tools/list")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withTransformers("response-template")
                        .withBody("{\"jsonrpc\":\"2.0\",\"id\":\"{{jsonPath request.body '$.id'}}\","
                                + "\"result\":{\"tools\":["
                                + "{\"name\":\"search\",\"description\":\"Search docs\","
                                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{"
                                + "\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}},"
                                + "{\"name\":\"slow_report\",\"description\":\"Slow report\","
                                + "\"_meta\":{\"async-capable\":true},"
                                + "\"inputSchema\":{\"type\":\"object\"}}]}}")));
        // Дефолтный tools/call — успех (отдельные тесты перекрывают 401-сценариями)
        llmWireMock.stubFor(post(urlEqualTo(MCP_PATH))
                .withRequestBody(matchingJsonPath("$.method", equalTo("tools/call")))
                .willReturn(callToolResult("найдено: {{jsonPath request.body '$.params.arguments.query'}}")));
    }

    private static ResponseDefinitionBuilder callToolResult(String text) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withTransformers("response-template")
                .withBody("{\"jsonrpc\":\"2.0\",\"id\":\"{{jsonPath request.body '$.id'}}\","
                        + "\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],"
                        + "\"isError\":false}}");
    }

    private int wiremockCount() {
        return llmWireMock.findAll(postRequestedFor(urlEqualTo(MCP_PATH))
                .withRequestBody(matchingJsonPath("$.method", equalTo("tools/list")))).size();
    }

    private void awaitOutcome(UUID sessionId, TurnOutcome outcome) {
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> sessionStore.findSession(sessionId)
                        .map(s -> s.lastTurnOutcome() == outcome)
                        .orElse(false));
    }

    private List<String> journalKinds(UUID sessionId) {
        return jdbcTemplate.queryForList(
                "SELECT kind FROM session_message WHERE session_id = ? ORDER BY seq", String.class, sessionId);
    }

    private String journalField(UUID sessionId, String kind, String field) {
        return jdbcTemplate.queryForObject(
                "SELECT payload_jsonb ->> ? FROM session_message WHERE session_id = ? AND kind = ?"
                        + " ORDER BY seq DESC LIMIT 1",
                String.class, field, sessionId, kind);
    }

    private static String toolCallChunk(String id, String tool, String argumentsJson) {
        return "{\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"" + id
                + "\",\"type\":\"function\",\"function\":{\"name\":\"" + tool + "\",\"arguments\":\""
                + argumentsJson.replace("\"", "\\\"") + "\"}}]},\"finish_reason\":\"tool_calls\"}]}";
    }

    private static ResponseDefinitionBuilder sse(String... events) {
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
}
