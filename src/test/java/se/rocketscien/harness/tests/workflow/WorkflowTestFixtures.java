package se.rocketscien.harness.tests.workflow;

import se.rocketscien.harness.common.IdGenerator;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Общая фикстура workflow-тестов: уникальный пользователь, эталонные графы
 * (workflow-domain §2/§3), вставка workflow+ревизии мимо реестра. Публична —
 * используется и task-тестами (общие эталонные графы).
 */
public final class WorkflowTestFixtures {

    private WorkflowTestFixtures() {
    }

    public static UUID insertAppUser(JdbcTemplate jdbcTemplate, IdGenerator idGenerator) {
        UUID userId = idGenerator.newUuidV7();
        jdbcTemplate.update(
                "INSERT INTO app_user (id, keycloak_subject, username, display_name, created_at) VALUES (?, ?, ?, ?, now())",
                userId,
                "subject-" + userId,
                "user-" + userId,
                "Пользователь"
        );
        return userId;
    }

    /** state-узел графа. */
    public static Map<String, Object> state(String code, String type, Map<String, Object> extra) {
        var state = new LinkedHashMap<String, Object>();
        state.put("code", code);
        state.put("type", type);
        if (extra != null) {
            state.putAll(extra);
        }
        return Collections.unmodifiableMap(state);
    }

    public static Map<String, Object> transition(String from, String to, String kind) {
        return Map.of("from", from, "to", to, "kind", kind);
    }

    public static Map<String, Object> graph(List<Map<String, Object>> states,
                                            List<Map<String, Object>> transitions) {
        return Map.of("states", List.copyOf(states), "transitions", List.copyOf(transitions));
    }

    /**
     * Эталон «двухфазного ревью» (tasks.md M.2): plan(AGENT) → checks(BASH) → done/failed,
     * ERROR+TIMEOUT у BASH, ERROR у AGENT, циклов нет.
     */
    public static Map<String, Object> twoPhaseGraph() {
        return graph(
                List.of(
                        state("plan", "AGENT", Map.of("agent_key", "orchestrator")),
                        state("checks", "BASH_SCRIPT", Map.of("script", "make test", "timeout", "PT1M")),
                        state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        state("failed", "TERMINAL", Map.of("outcome", "FAILED"))
                ),
                List.of(
                        transition("plan", "checks", "NEXT"),
                        transition("plan", "failed", "ERROR"),
                        transition("checks", "done", "NEXT"),
                        transition("checks", "failed", "ERROR"),
                        transition("checks", "done", "TIMEOUT")
                )
        );
    }

    /** WAIT_WEBHOOK-граф с таймаутом. */
    public static Map<String, Object> waitWebhookGraph() {
        return graph(
                List.of(
                        state("wait", "WAIT_WEBHOOK", Map.of("timeout", "PT2M")),
                        state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        state("failed", "TERMINAL", Map.of("outcome", "FAILED"))
                ),
                List.of(
                        transition("wait", "done", "NEXT"),
                        transition("wait", "failed", "ERROR"),
                        transition("wait", "done", "TIMEOUT")
                )
        );
    }
}
