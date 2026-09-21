package se.rocketscien.harness.tests.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.common.IdGenerator;
import se.rocketscien.harness.testclient.ApiClient;
import se.rocketscien.harness.testclient.api.SessionsApi;
import se.rocketscien.harness.testclient.model.CreateSessionRequest;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static se.rocketscien.harness.tests.api.ApiFixtures.apiClient;
import static se.rocketscien.harness.tests.api.ApiFixtures.insertAgentChain;
import static se.rocketscien.harness.tests.api.ApiFixtures.keycloakToken;
import static se.rocketscien.harness.tests.api.ApiFixtures.sendRaw;

/**
 * M4 T.3 (specs/workspace-download): интеграция GET /sessions/{id}/workspace/files на живой
 * SERVER-сессии — живой файл (200, поток), 404 file-not-found, 404 session-not-found,
 * 422 path-invalid, 422 extension-not-allowed, 413 payload-too-large (лимит в application-test.yml — 1KB).
 * Файлы кладутся в серверный workspace {@code harness.docker.workspace-root/{sessionId}}.
 */
class WorkspaceFilesApiTest extends BaseApplicationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private IdGenerator idGenerator;
    @Autowired
    private Environment environment;

    private String aliceToken;
    private UUID sessionId;
    private Path sessionDir;

    @BeforeEach
    void setUp() throws Exception {
        aliceToken = keycloakToken(http, "alice", "alice-password");
        ApiClient client = apiClient(localServerUrl(), aliceToken);
        ApiFixtures.AgentSeed seed = insertAgentChain(jdbcTemplate, idGenerator, environment);
        sessionId = new SessionsApi(client)
                .createSession(new CreateSessionRequest().agentKey(seed.agentKey()).title("workspace"))
                .getId();
        sessionDir = workspaceRoot().resolve(sessionId.toString());
        Files.createDirectories(sessionDir);
    }

    @Test
    void downloadsExistingTextFile() throws Exception {
        Files.writeString(sessionDir.resolve("note.md"), "hello harness", StandardCharsets.UTF_8);

        HttpResponse<String> response = download(sessionId, "note.md");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("hello harness");
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/octet-stream");
        assertThat(response.headers().firstValue("Content-Length").orElseThrow())
                .isEqualTo(Long.toString("hello harness".getBytes(StandardCharsets.UTF_8).length));
    }

    @Test
    void returnsFileNotFoundForMissingFile() throws Exception {
        HttpResponse<String> response = download(sessionId, "ghost.txt");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("file-not-found");
    }

    @Test
    void returnsSessionNotFoundForUnknownSession() throws Exception {
        HttpResponse<String> response = download(UUID.randomUUID(), "note.txt");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("session-not-found");
    }

    @Test
    void rejectsPathEscape() throws Exception {
        HttpResponse<String> response = download(sessionId, "../../etc/passwd");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("path-invalid");
    }

    @Test
    void rejectsDisallowedExtension() throws Exception {
        Files.write(sessionDir.resolve("data.bin"), new byte[]{1, 2, 3});

        HttpResponse<String> response = download(sessionId, "data.bin");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("extension-not-allowed");
    }

    @Test
    void rejectsOversizeFile() throws Exception {
        Files.write(sessionDir.resolve("big.txt"), new byte[2048]);

        HttpResponse<String> response = download(sessionId, "big.txt");

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("payload-too-large");
    }

    private HttpResponse<String> download(UUID id, String path) {
        String encoded = java.net.URLEncoder.encode(path, StandardCharsets.UTF_8);
        return sendRaw(http, localServerUrl(), "GET",
                "/api/v1/sessions/" + id + "/workspace/files?path=" + encoded,
                aliceToken, null, "application/octet-stream", null);
    }

    private Path workspaceRoot() {
        return Path.of(environment.getRequiredProperty("harness.docker.workspace-root"));
    }
}
