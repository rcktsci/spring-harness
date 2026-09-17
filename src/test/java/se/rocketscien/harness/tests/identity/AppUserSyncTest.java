package se.rocketscien.harness.tests.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import se.rocketscien.harness.BaseApplicationTest;
import se.rocketscien.harness.config.KeycloakContextInitializer;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppUserSyncTest extends BaseApplicationTest {

    private static final String ALICE_SUB = "alice";
    private static final String CAROL_SUB = "carol";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @AfterEach
    void restoreCarolName() {
        try {
            KeycloakContextInitializer.adminClient().realm("harness").users()
                    .search("carol").stream()
                    .findFirst()
                    .ifPresent(user -> {
                        if ("Caroline".equals(user.getFirstName())) {
                            user.setFirstName("Carol");
                            try {
                                KeycloakContextInitializer.adminClient().realm("harness")
                                        .users().get(user.getId()).update(user);
                            } catch (Exception ignore) {}
                        }
                    });
        } catch (Exception ignore) {}
    }

    @Test
    void firstRequestOfNewUserCreatesAppUser() throws Exception {
        String token = token("alice", "alice-password");

        HttpResponse<String> pingResponse = get("/api/v1/ping", token);

        assertThat(pingResponse.statusCode()).as(pingResponse.body()).isEqualTo(200);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT keycloak_subject, username, display_name FROM app_user WHERE keycloak_subject = ?",
                ALICE_SUB
        );

        assertThat(row)
                .containsEntry("username", "alice")
                .containsEntry("display_name", "Alice Smith");
    }

    @Test
    void userOutsideGroupsGets401AndIsNotSynced() throws Exception {
        String token = token("bob", "bob-password");

        int status = get("/api/v1/ping", token).statusCode();

        assertThat(status).isEqualTo(401);

        Integer bobRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE username = 'bob'",
                Integer.class
        );

        assertThat(bobRows).isZero();
    }

    @Test
    void displayNameChangeInTokenUpdatesAppUser() throws Exception {
        get("/api/v1/ping", token("carol", "carol-password"));

        try {
            KeycloakContextInitializer.adminClient().realm("harness").users().search("carol").stream()
                    .findFirst()
                    .ifPresent(user -> {
                        user.setFirstName("Caroline");
                        KeycloakContextInitializer.adminClient().realm("harness").users().get(user.getId()).update(user);
                    });
        } catch (Exception ignore) {}

        get("/api/v1/ping", token("carol", "carol-password"));

        String displayName = jdbcTemplate.queryForObject(
                "SELECT display_name FROM app_user WHERE keycloak_subject = ?",
                String.class,
                CAROL_SUB
        );

        assertThat(displayName).isEqualTo("Caroline Danvers");
    }

    private HttpResponse<String> get(String path, String bearerToken) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(localServerUrl() + path))
                .header("Authorization", "Bearer " + bearerToken)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String token(String username, String password) throws IOException, InterruptedException {
        String form = "grant_type=password"
                + "&client_id=harness-cli"
                + "&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(KeycloakContextInitializer.authServerUrl() + "/realms/harness/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return extractAccessToken(response.body());
    }

    private static String extractAccessToken(String tokenResponseBody) {
        int access_tokenIndex = tokenResponseBody.indexOf("\"access_token\":\"");
        int start = access_tokenIndex + "\"access_token\":\"".length();
        int end = tokenResponseBody.indexOf('"', start);
        return tokenResponseBody.substring(start, end);
    }
}
