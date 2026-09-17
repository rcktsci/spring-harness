package se.rocketscien.harness.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Тестовый эндпоинт для проверки JWT-гейта на /api/v1/** (боевых REST-контроллеров в M1-пачке B ещё нет).
 * Живёт только в test-classpath, в jar не попадает.
 */
@RestController
public class TestPingController {

    @GetMapping("/api/v1/ping")
    public Map<String, String> ping() {
        return Map.of("status", "ok");
    }
}
