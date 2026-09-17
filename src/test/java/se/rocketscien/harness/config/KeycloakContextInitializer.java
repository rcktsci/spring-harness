package se.rocketscien.harness.config;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.keycloak.admin.client.Keycloak;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Живой Keycloak для тестового контекста (realm-импорт из classpath). Публикует техническое
 * свойство {@code containers.keycloak.url}; всегда поднят вместе с контекстом (D-2 пачки:
 * максимальная верность рантайму — позитивный путь и groups-гейт через реальный Keycloak).
 */
public class KeycloakContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final KeycloakContainer KEYCLOAK = new KeycloakContainer("quay.io/keycloak/keycloak:26.2.4")
            .withRealmImportFile("harness-realm.json");

    @Override
    public synchronized void initialize(ConfigurableApplicationContext applicationContext) {
        if (!KEYCLOAK.isRunning()) {
            KEYCLOAK.start();
        }
        applicationContext.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "keycloak-testcontainer",
                Map.of("containers.keycloak.url", KEYCLOAK.getAuthServerUrl())
        ));
    }

    public static String authServerUrl() {
        if (!KEYCLOAK.isRunning()) {
            KEYCLOAK.start();
        }
        return KEYCLOAK.getAuthServerUrl();
    }

    public static Keycloak adminClient() {
        return KEYCLOAK.getKeycloakAdminClient();
    }
}
