package se.rocketscien.harness.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * docker-java против локального демона (D-M1-7, {@code /var/run/docker.sock}). Клиент создаётся
 * без установления соединения — недоступность демона не ломает старт контекста.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DockerProperties.class)
public class DockerConfig {

    @Bean(destroyMethod = "close")
    public DockerClient dockerClient(DockerProperties properties) {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .connectionTimeout(properties.startTimeout())
                .responseTimeout(properties.execTimeout())
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }
}
