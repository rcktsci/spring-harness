package se.rocketscien.harness.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesRegistrar.class);

    @Test
    void bindsSecurityDefaults() {
        contextRunner.run(context -> {
            SecurityProperties properties = context.getBean(SecurityProperties.class);
            assertThat(properties.allowedGroups()).containsExactly("harness-users");
            assertThat(properties.issuerUri()).isEqualTo("http://localhost:8080/realms/harness");
            assertThat(properties.jwkSetUri())
                    .isEqualTo("http://localhost:8080/realms/harness/protocol/openid-connect/certs");
        });
    }

    @Test
    void bindsLockDefaults() {
        contextRunner.run(context -> {
            LockProperties properties = context.getBean(LockProperties.class);
            assertThat(properties.sessionTtl()).isEqualTo(Duration.ofMinutes(10));
            assertThat(properties.heartbeatInterval()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.jobTtl()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @Test
    void bindsTurnDefaults() {
        contextRunner.run(context -> {
            TurnProperties properties = context.getBean(TurnProperties.class);
            assertThat(properties.pollInterval()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.llmRetries()).isEqualTo(3);
            assertThat(properties.backoffBase()).isEqualTo(Duration.ofSeconds(1));
        });
    }

    @Test
    void bindsLlmDefaults() {
        contextRunner.run(context -> {
            LlmProperties properties = context.getBean(LlmProperties.class);
            assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.encryptionKeys()).containsKey(1);
        });
    }

    @Test
    void bindsCompactDefaults() {
        contextRunner.run(context -> {
            CompactProperties properties = context.getBean(CompactProperties.class);
            assertThat(properties.threshold()).isEqualTo(0.8);
        });
    }

    @Test
    void bindsDockerDefaults() {
        contextRunner.run(context -> {
            DockerProperties properties = context.getBean(DockerProperties.class);
            assertThat(properties.helperImage()).isEqualTo("harness-helper:local");
            assertThat(properties.workspaceRoot()).isEqualTo("workspaces/sessions");
            assertThat(properties.cpuNanos()).isEqualTo(1_000_000_000L);
            assertThat(properties.memory()).isEqualTo(org.springframework.util.unit.DataSize.ofMegabytes(512));
            assertThat(properties.network()).isEqualTo("none");
            assertThat(properties.pullTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.startTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.execTimeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.statePollInterval()).isEqualTo(Duration.ofMillis(50));
            assertThat(properties.containerStopConfirm()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.writeChunkBytes()).isEqualTo(60_000);
            assertThat(properties.pullRetries()).isEqualTo(3);
            assertThat(properties.pullBackoff()).isEqualTo(Duration.ofSeconds(2));
        });
    }

    @Test
    void bindsSseDefaults() {
        contextRunner.run(context -> {
            SseProperties properties = context.getBean(SseProperties.class);
            assertThat(properties.pingInterval()).isEqualTo(Duration.ofSeconds(15));
        });
    }

    @Test
    void bindsLimitsDefaults() {
        contextRunner.run(context -> {
            LimitsProperties properties = context.getBean(LimitsProperties.class);
            assertThat(properties.body()).isEqualTo(org.springframework.util.unit.DataSize.ofMegabytes(1));
            assertThat(properties.toolOutput()).isEqualTo(org.springframework.util.unit.DataSize.ofKilobytes(256));
            assertThat(properties.toolCaptureMargin()).isEqualTo(org.springframework.util.unit.DataSize.ofKilobytes(4));
            assertThat(properties.bashTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.bashTimeoutCap()).isEqualTo(Duration.ofMinutes(5));
        });
    }

    @Test
    void emptyAllowedGroupsFailsContextStartup() {
        contextRunner
                .withPropertyValues("harness.security.allowed-groups=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()
                                    .getCause()
                                    .getCause())
                            .hasMessageContaining("allowedGroups");
                });
    }

    @Test
    void overridesValuesFromPropertySource() {
        contextRunner
                .withPropertyValues("harness.lock.session-ttl=30m")
                .run(context -> {
                    LockProperties properties = context.getBean(LockProperties.class);
                    assertThat(properties.sessionTtl()).isEqualTo(Duration.ofMinutes(30));
                });
    }

    @Configuration
    @EnableConfigurationProperties({
            SecurityProperties.class,
            LockProperties.class,
            TurnProperties.class,
            LlmProperties.class,
            CompactProperties.class,
            DockerProperties.class,
            SseProperties.class,
            LimitsProperties.class
    })
    static class PropertiesRegistrar {
    }
}
