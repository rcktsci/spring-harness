package se.rocketscien.harness.tests.execution;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 6.1: helper-образ собирается из {@code docker/Dockerfile} и содержит утилиты, на которые
 * опирается контракт инструментов (find/grep/coreutils/git/bash).
 */
class HelperImageDockerTest {

    @Test
    void helperImageBuildsAndContainsRequiredUtilities() throws Exception {
        String image = DockerTestSupport.helperImage();
        assertThat(image).isEqualTo("harness-helper:test");

        try (GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(image))) {
            container.start();

            assertThat(container.execInContainer("bash", "--version").getExitCode()).isZero();
            assertThat(container.execInContainer("find", "--version").getExitCode()).isZero();
            assertThat(container.execInContainer("grep", "--version").getExitCode()).isZero();
            assertThat(container.execInContainer("git", "--version").getExitCode()).isZero();
            assertThat(container.execInContainer("sh", "-c", "test -d /workspace").getExitCode()).isZero();
        }
    }
}
