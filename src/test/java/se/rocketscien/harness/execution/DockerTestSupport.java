package se.rocketscien.harness.execution;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Общая опора docker-интеграционных тестов пачки C: единоразовая сборка helper-образа из
 * {@code docker/Dockerfile} (task 6.1) и сборка docker-java клиента против локального демона.
 */
public final class DockerTestSupport {

    private static volatile String helperImage;

    private DockerTestSupport() {
    }

    public static synchronized String helperImage() {
        if (helperImage == null) {
            ImageFromDockerfile image = new ImageFromDockerfile("harness-helper:test", false)
                    .withDockerfile(Path.of("docker", "Dockerfile"));
            try {
                helperImage = image.get();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to build helper image from docker/Dockerfile", e);
            }
        }
        return helperImage;
    }

    public static DockerClient dockerClient() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(60))
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }
}
