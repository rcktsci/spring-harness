package se.rocketscien.harness.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

/**
 * Тестовый REST-клиент (по корпоративному навыку): не бросает исключения на HTTP-ошибках —
 * статус проверяется ассертами на ответе.
 */
@Configuration
class TestRestClientConfig {

    @Bean
    RestClient testRestClient(RestClient.Builder builder) {
        return builder
                .defaultStatusHandler(HttpStatusCode::isError, (_, _) -> { })
                .build();
    }
}
