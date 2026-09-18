package se.rocketscien.harness.tests.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.testclient.ApiClient;
import se.rocketscien.harness.testclient.ApiException;
import se.rocketscien.harness.testclient.api.AgentsApi;
import se.rocketscien.harness.testclient.api.SessionsApi;
import se.rocketscien.harness.testclient.model.CreateSessionRequest;
import se.rocketscien.harness.testclient.model.SessionDto;
import se.rocketscien.harness.testclient.model.SessionKind;
import se.rocketscien.harness.testclient.model.SessionPage;
import se.rocketscien.harness.testclient.model.UpdateSessionRequest;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static se.rocketscien.harness.tests.api.ApiFixtures.apiClient;
import static se.rocketscien.harness.tests.api.ApiFixtures.insertAgentChain;
import static se.rocketscien.harness.tests.api.ApiFixtures.keycloakToken;
import static se.rocketscien.harness.tests.api.ApiFixtures.sendRaw;

/**
 * Задача 8.2 (specs/session-api: «Каталог агентов», «CRUD сессий»): GET /agents — последние
 * ревизии; POST /sessions — 201+Location, только FREE, пин ревизии; GET список — фильтры
 * mine/kind/q, сортировка lastActivityAt desc, конверт-пагинация; GET/PATCH по id.
 * Позитивный путь — сгенерированный тест-клиент (Jackson 2-клиент → сервер на Jackson 3).
 */
class SessionsApiTest extends BaseApplicationTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private String aliceToken;
    private ApiClient aliceClient;
    private String agentKey;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IdGenerator idGenerator;
    @Autowired
    private Environment environment;

    @BeforeEach
    void setUpClients() {
        aliceToken = keycloakToken(http, "alice", "alice-password");
        aliceClient = apiClient(localServerUrl(), aliceToken);
        agentKey = insertAgentChain(jdbcTemplate, idGenerator, environment).agentKey();
    }

    @Test
    void listAgentsReturnsLatestRevisionOfEachKey() throws Exception {
        AgentsApi agentsApi = new AgentsApi(aliceClient);
        var seed = insertAgentChain(jdbcTemplate, idGenerator, environment);

        var item = agentsApi.listAgents().getItems().stream()
                .filter(i -> i.getKey().equals(seed.agentKey()))
                .findFirst().orElseThrow();
        assertThat(item.getLatestRev()).isEqualTo(1);
        assertThat(item.getName()).isEqualTo("Агент API");
        assertThat(item.getDescription()).isEqualTo("Описание агента");

        insertRevision(jdbcTemplate, seed.revisionId(), 2);

        var refreshed = agentsApi.listAgents();
        assertThat(refreshed.getItems().stream()
                .filter(i -> i.getKey().equals(seed.agentKey()))
                .findFirst().orElseThrow().getLatestRev()).isEqualTo(2);
    }

    @Test
    void createSessionReturns201WithLocationAndSessionDto() throws Exception {
        SessionsApi sessionsApi = new SessionsApi(aliceClient);

        SessionDto created = sessionsApi.createSession(new CreateSessionRequest()
                .title("Разбор пайплайна")
                .agentKey(agentKey));

        assertThat(created.getId()).isNotNull();
        assertThat(created.getKind()).isEqualTo(SessionKind.FREE);
        assertThat(created.getTitle()).isEqualTo("Разбор пайплайна");
        assertThat(created.getOwner()).isEqualTo("alice");
        assertThat(created.getAgent().getKey()).isEqualTo(agentKey);
        assertThat(created.getAgent().getRev()).isEqualTo(1);
        assertThat(created.getWorkspaceBinding().getType().getValue()).isEqualTo("SERVER_DIR");
        assertThat(created.getRuntimeStatus().getValue()).isEqualTo("IDLE");
        assertThat(created.getLastTurnOutcome()).isNull();
        assertThat(created.getLastSeq()).isZero();
        assertThat(created.getLastActivityAt()).isNotNull();
        assertThat(created.getCreatedAt()).isNotNull();

        // Location — URL созданного ресурса в форме спеки (/api/v1/sessions/{id});
        // сверяем с id из тела этого же ответа
        HttpResponse<String> raw = sendRaw(http, localServerUrl(), "POST", "/api/v1/sessions",
                aliceToken, "application/json", null,
                "{\"title\":\"с заголовком\",\"agentKey\":\"" + agentKey + "\"}");
        assertThat(raw.statusCode()).isEqualTo(201);
        assertThat(raw.headers().firstValue("Location").orElseThrow())
                .isEqualTo("/api/v1/sessions/" + new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(raw.body()).get("id").asText());
    }

    @Test
    void createSessionWithoutTitleStoresNullTitle() throws Exception {
        SessionsApi sessionsApi = new SessionsApi(aliceClient);

        // nullable-поле: "title": null сериализован Jackson 3 и прочитан клиентом на Jackson 2
        SessionDto created = sessionsApi.createSession(new CreateSessionRequest().agentKey(agentKey));

        assertThat(created.getTitle()).isNull();
    }

    @Test
    void createSessionPinsRequestedRevision() throws Exception {
        var seed = insertAgentChain(jdbcTemplate, idGenerator, environment);
        insertRevision(jdbcTemplate, seed.revisionId(), 3);
        SessionsApi sessionsApi = new SessionsApi(aliceClient);

        SessionDto pinned = sessionsApi.createSession(new CreateSessionRequest()
                .agentKey(seed.agentKey()).agentRev(3));
        SessionDto latest = sessionsApi.createSession(new CreateSessionRequest().agentKey(seed.agentKey()));

        assertThat(pinned.getAgent().getRev()).isEqualTo(3);
        assertThat(latest.getAgent().getRev()).isEqualTo(3);
    }

    @Test
    void unknownAgentKeyReturns404AgentNotFound() {
        ApiException exception = assertThrows(ApiException.class,
                () -> new SessionsApi(aliceClient).createSession(new CreateSessionRequest().agentKey("no-such-agent")));
        assertThat(exception.getCode()).isEqualTo(404);
        assertThat(exception.getResponseBody()).contains("\"code\":\"agent-not-found\"");
    }

    @Test
    void getSessionReturns404ForUnknownId() {
        ApiException exception = assertThrows(ApiException.class,
                () -> new SessionsApi(aliceClient).getSession(UUID.randomUUID()));
        assertThat(exception.getCode()).isEqualTo(404);
        assertThat(exception.getResponseBody()).contains("\"code\":\"session-not-found\"");
    }

    @Test
    void listSessionsSortsByLastActivityDesc() throws Exception {
        SessionsApi sessionsApi = new SessionsApi(aliceClient);
        SessionDto older = sessionsApi.createSession(new CreateSessionRequest().title("aaa").agentKey(agentKey));
        Thread.sleep(20);
        SessionDto newer = sessionsApi.createSession(new CreateSessionRequest().title("bbb").agentKey(agentKey));

        SessionPage page = sessionsApi.listSessions(null, null, null, null, null);

        List<UUID> ids = page.getItems().stream().map(SessionDto::getId).toList();
        assertThat(ids).contains(newer.getId(), older.getId());
        assertThat(ids.indexOf(newer.getId())).isLessThan(ids.indexOf(older.getId()));
    }

    @Test
    void listSessionsFiltersMineKindAndTitleSubstring() throws Exception {
        SessionsApi aliceSessions = new SessionsApi(aliceClient);
        aliceSessions.createSession(new CreateSessionRequest().title("alpha discussion").agentKey(agentKey));
        new SessionsApi(apiClient(localServerUrl(), keycloakToken(http, "carol", "carol-password")))
                .createSession(new CreateSessionRequest().title("alpha by carol").agentKey(agentKey));

        List<SessionDto> mine = aliceSessions.listSessions(true, null, null, null, null).getItems();
        assertThat(mine).isNotEmpty();
        assertThat(mine).allSatisfy(session -> assertThat(session.getOwner()).isEqualTo("alice"));

        List<SessionDto> byTitle = aliceSessions.listSessions(null, null, "alpha by carol", null, null).getItems();
        assertThat(byTitle).hasSize(1);
        assertThat(byTitle.getFirst().getOwner()).isEqualTo("carol");

        List<SessionDto> freeOnly = aliceSessions.listSessions(null, SessionKind.FREE, null, null, null).getItems();
        assertThat(freeOnly).allSatisfy(session -> assertThat(session.getKind()).isEqualTo(SessionKind.FREE));

        assertThat(aliceSessions.listSessions(null, null, "нет-такой-подстроки", null, null).getItems()).isEmpty();
    }

    @Test
    void listSessionsPaginatesWithOpaqueCursorWithoutDupesOrLosses() throws Exception {
        SessionsApi sessionsApi = new SessionsApi(aliceClient);
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            created.add(sessionsApi.createSession(
                    new CreateSessionRequest().title("страница-" + i).agentKey(agentKey)).getId());
            Thread.sleep(10);
        }

        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            SessionPage page = sessionsApi.listSessions(null, null, "страница-", cursor, 2);
            page.getItems().forEach(item -> seen.add(item.getId()));
            cursor = page.getNextCursor();
            pages++;
            assertThat(pages).as("Защита от вечного цикла на кривом курсоре").isLessThan(10);
        } while (cursor != null);

        assertThat(seen).containsExactlyInAnyOrderElementsOf(created);
        assertThat(pages).isEqualTo(2);
    }

    @Test
    void paginationSurvivesSameMillisecondTies() throws Exception {
        // E-J-3: несколько сессий с идентичным last_activity_at (одна миллисекунда) —
        // порядок и курсор определяются парой (lastActivityAt, id), без дублей и потерь
        SessionsApi sessionsApi = new SessionsApi(aliceClient);
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            created.add(sessionsApi.createSession(
                    new CreateSessionRequest().title("тай-" + i).agentKey(agentKey)).getId());
        }
        java.sql.Timestamp tie = java.sql.Timestamp.from(
                java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        jdbcTemplate.update("UPDATE session SET last_activity_at = ? WHERE title LIKE 'тай-%'", tie);

        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            SessionPage page = sessionsApi.listSessions(null, null, "тай-", cursor, 1);
            page.getItems().forEach(item -> seen.add(item.getId()));
            cursor = page.getNextCursor();
            pages++;
            assertThat(pages).as("Тай-брейк обязан сходиться: страниц не больше числа сессий").isLessThan(10);
        } while (cursor != null);

        assertThat(seen).containsExactlyInAnyOrderElementsOf(created);
        // Одинаковое время — сортировка вырождается в id desc: строгий порядок без дублей
        assertThat(seen).doesNotHaveDuplicates();
    }

    @Test
    void garbageCursorReturns422ValidationFailed() throws Exception {
        HttpResponse<String> response = sendRaw(http, localServerUrl(), "GET",
                "/api/v1/sessions?cursor=not-a-valid-base64-cursor!", aliceToken, null, null, null);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("\"code\":\"validation-failed\"")
                .contains("\"pointer\":\"/cursor\"");
    }

    @Test
    void patchSessionMergePatchSemantics() throws Exception {
        SessionsApi sessionsApi = new SessionsApi(aliceClient);
        SessionDto created = sessionsApi.createSession(
                new CreateSessionRequest().title("первое имя").agentKey(agentKey));

        // строка — новое имя (через сгенерированный клиент: Content-Type merge-patch из спеки)
        SessionDto renamed = sessionsApi.patchSession(created.getId(),
                new UpdateSessionRequest().title("второе имя"));
        assertThat(renamed.getTitle()).isEqualTo("второе имя");

        // отсутствующее поле — не менять
        SessionDto untouched = readSession(sendPatchRaw(created.getId(), "{}"));
        assertThat(untouched.getTitle()).isEqualTo("второе имя");

        // null — очистить
        SessionDto cleared = readSession(sendPatchRaw(created.getId(), "{\"title\":null}"));
        assertThat(cleared.getTitle()).isNull();

        // неизвестные члены патча — игнорируются
        SessionDto ignoredUnknown = readSession(sendPatchRaw(created.getId(), "{\"unknown\":{\"a\":1}}"));
        assertThat(ignoredUnknown.getTitle()).isNull();
    }

    @Test
    void patchSessionWithEmptyTitleReturns422() throws Exception {
        SessionDto created = new SessionsApi(aliceClient).createSession(
                new CreateSessionRequest().title("имя").agentKey(agentKey));

        HttpResponse<String> response = sendPatchRaw(created.getId(), "{\"title\":\"\"}");
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("\"code\":\"validation-failed\"")
                .contains("\"pointer\":\"/title\"");

        HttpResponse<String> blank = sendPatchRaw(created.getId(), "{\"title\":\" \"}");
        assertThat(blank.statusCode()).isEqualTo(422);
        assertThat(blank.body()).contains("\"rule\":\"blank\"");
    }

    @Test
    void patchSessionWithPlainJsonContentTypeReturns415() throws Exception {
        SessionDto created = new SessionsApi(aliceClient).createSession(
                new CreateSessionRequest().title("имя").agentKey(agentKey));

        HttpResponse<String> response = sendRaw(http, localServerUrl(), "PATCH",
                "/api/v1/sessions/" + created.getId(), aliceToken, "application/json", null,
                "{\"title\":\"другое\"}");

        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(response.body()).contains("\"code\":\"unsupported-media-type\"");
    }

    @Test
    void patchUnknownSessionReturns404() {
        ApiException exception = assertThrows(ApiException.class,
                () -> new SessionsApi(aliceClient).patchSession(UUID.randomUUID(), new UpdateSessionRequest()));
        assertThat(exception.getCode()).isEqualTo(404);
    }

    /** PATCH сырым клиентом — точное тело (клиент генерации не умеет явный null/absent различать). */
    private HttpResponse<String> sendPatchRaw(UUID id, String jsonBody) {
        return sendRaw(http, localServerUrl(), "PATCH", "/api/v1/sessions/" + id,
                aliceToken, "application/merge-patch+json", null, jsonBody);
    }

    private SessionDto readSession(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.findAndRegisterModules();
        return mapper.readValue(response.body(), se.rocketscien.harness.testclient.model.SessionDto.class);
    }

    /** Дополнительная ревизия существующего ключа агента (иммутабельные ревизии, data-model §2). */
    private static void insertRevision(JdbcTemplate jdbcTemplate, UUID originalRevisionId, int rev) {
        UUID revisionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO agent (id, key, name, description, rev, role_prompt, llm_model_id, created_at)"
                        + " SELECT ?, key, name, description, ?, role_prompt, llm_model_id, now()"
                        + " FROM agent WHERE id = ?",
                revisionId, rev, originalRevisionId);
    }
}
