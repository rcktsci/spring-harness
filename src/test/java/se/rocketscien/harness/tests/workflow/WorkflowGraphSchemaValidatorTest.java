package se.rocketscien.harness.tests.workflow;

import se.rocketscien.harness.common.jsonschema.JsonSchemaError;
import se.rocketscien.harness.workflow.WorkflowGraphSchemaValidator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты {@link WorkflowGraphSchemaValidator} на правила §2 workflow-domain и сценарии спеки
 * workflow-engine (code-unique, fan-out-forbidden, bash-timeout-required, wait-error-required,
 * enum, required, cancel-edge-forbidden, достижимость TERMINAL, start_state). Чистая функция —
 * без контекста (прецедент: VisibilityRendererTest).
 */
class WorkflowGraphSchemaValidatorTest {

    private final WorkflowGraphSchemaValidator validator = new WorkflowGraphSchemaValidator();

    private static List<JsonSchemaError> validate(Map<String, Object> graph, String startState) {
        return new WorkflowGraphSchemaValidator().validate(graph, startState);
    }

    private static List<String> rules(List<JsonSchemaError> errors) {
        return errors.stream().map(JsonSchemaError::rule).toList();
    }

    @Test
    void validTwoPhaseGraphPasses() {
        assertThat(validate(WorkflowTestFixtures.twoPhaseGraph(), "plan")).isEmpty();
    }

    /** V-1 регресс: цикл «review → возврат в plan» с выходом в терминал — валиден. */
    @Test
    void cyclicReviewWithReturnToPlanPasses() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("review", "AGENT", Map.of("agent_key", "reviewer")),
                        WorkflowTestFixtures.state("plan", "AGENT", Map.of("agent_key", "orchestrator")),
                        WorkflowTestFixtures.state("merge", "TERMINAL", Map.of("outcome", "SUCCESS"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("review", "plan", "ERROR"),
                        WorkflowTestFixtures.transition("review", "merge", "NEXT"),
                        WorkflowTestFixtures.transition("plan", "review", "NEXT")
                )
        );
        assertThat(validate(graph, "plan")).isEmpty();
    }

    /**
     * V-1 полный сценарий ревью: plan → bash → reviewer-1 → возврат в plan (ERROR) →
     * reviewer-2 → merge (SUCCESS); ERROR+TIMEOUT у bash.
     */
    @Test
    void fullTwoPhaseReviewCyclePasses() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("plan", "AGENT", Map.of("agent_key", "orchestrator")),
                        WorkflowTestFixtures.state("bash", "BASH_SCRIPT",
                                Map.of("script", "make test", "timeout", "PT1M")),
                        WorkflowTestFixtures.state("reviewer-1", "AGENT", Map.of("agent_key", "reviewer-1")),
                        WorkflowTestFixtures.state("reviewer-2", "AGENT", Map.of("agent_key", "reviewer-2")),
                        WorkflowTestFixtures.state("merge", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("plan", "bash", "NEXT"),
                        WorkflowTestFixtures.transition("bash", "reviewer-1", "NEXT"),
                        WorkflowTestFixtures.transition("reviewer-1", "plan", "ERROR"),
                        WorkflowTestFixtures.transition("reviewer-1", "reviewer-2", "NEXT"),
                        WorkflowTestFixtures.transition("reviewer-2", "merge", "NEXT"),
                        WorkflowTestFixtures.transition("reviewer-2", "failed", "ERROR"),
                        WorkflowTestFixtures.transition("bash", "failed", "ERROR"),
                        WorkflowTestFixtures.transition("bash", "failed", "TIMEOUT")
                )
        );
        assertThat(validate(graph, "plan")).isEmpty();
    }

    @Test
    void validCycleGraphWithTerminalPasses() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("plan", "AGENT", Map.of("agent_key", "a")),
                        WorkflowTestFixtures.state("checks", "BASH_SCRIPT",
                                Map.of("script", "true", "timeout", "PT30S")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("plan", "checks", "NEXT"),
                        WorkflowTestFixtures.transition("checks", "plan", "ERROR"),
                        WorkflowTestFixtures.transition("checks", "done", "NEXT"),
                        WorkflowTestFixtures.transition("checks", "done", "TIMEOUT")
                )
        );
        assertThat(validate(graph, "plan")).isEmpty();
    }

    @Test
    void singleTerminalStateGraphPasses() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS"))),
                List.of()
        );
        assertThat(validate(graph, "done")).isEmpty();
    }

    @Test
    void duplicateCodeReportsCodeUniqueAtSecondOccurrence() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "FAILED"))
                ),
                List.of()
        );
        List<JsonSchemaError> errors = validate(graph, "done");
        assertThat(errors).anySatisfy(error -> {
            assertThat(error.rule()).isEqualTo("code-unique");
            assertThat(error.pointer()).isEqualTo("/states/1/code");
        });
    }

    @Test
    void fanOutForbidden() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("plan", "AGENT", Map.of("agent_key", "a")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("plan", "done", "NEXT"),
                        WorkflowTestFixtures.transition("plan", "done", "NEXT")
                )
        );
        assertThat(rules(validate(graph, "plan"))).contains("fan-out-forbidden");
    }

    @Test
    void bashWithoutTimeoutReportsBashTimeoutRequired() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("plan", "AGENT", Map.of("agent_key", "a")),
                        WorkflowTestFixtures.state("checks", "BASH_SCRIPT", Map.of("script", "true")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("plan", "checks", "NEXT"),
                        WorkflowTestFixtures.transition("checks", "done", "NEXT"),
                        WorkflowTestFixtures.transition("checks", "failed", "ERROR")
                )
        );
        assertThat(rules(validate(graph, "plan"))).contains("bash-timeout-required");
    }

    @Test
    void bashWithoutErrorReportsBashErrorRequired() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("checks", "BASH_SCRIPT",
                                Map.of("script", "true", "timeout", "PT1M")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("checks", "done", "NEXT"),
                        WorkflowTestFixtures.transition("checks", "done", "TIMEOUT")
                )
        );
        assertThat(rules(validate(graph, "checks"))).contains("bash-error-required");
    }

    /** Сценарий спеки workflow-engine: WAIT_WEBHOOK только с TIMEOUT → wait-error-required. */
    @Test
    void waitWebhookWithoutErrorReportsWaitErrorRequired() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("wait", "WAIT_WEBHOOK", Map.of("timeout", "PT2M")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("wait", "done", "NEXT"),
                        WorkflowTestFixtures.transition("wait", "done", "TIMEOUT")
                )
        );
        assertThat(rules(validate(graph, "wait"))).contains("wait-error-required");
    }

    @Test
    void waitTasksWithoutTimeoutReportsWaitTimeoutRequired() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("gather", "WAIT_TASKS",
                                Map.of("scope", "ALL_CHILDREN", "condition", "ALL_TERMINAL")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("gather", "done", "NEXT"),
                        WorkflowTestFixtures.transition("gather", "failed", "ERROR")
                )
        );
        assertThat(rules(validate(graph, "gather"))).contains("wait-timeout-required");
    }

    @Test
    void waitTasksWithoutScopeAndConditionReportsRequired() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("gather", "WAIT_TASKS", Map.of()),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("gather", "done", "NEXT"),
                        WorkflowTestFixtures.transition("gather", "done", "ERROR"),
                        WorkflowTestFixtures.transition("gather", "done", "TIMEOUT")
                )
        );
        List<JsonSchemaError> errors = validate(graph, "gather");
        assertThat(errors).anySatisfy(error -> {
            assertThat(error.rule()).isEqualTo("required");
            assertThat(error.pointer()).isEqualTo("/states/0/scope");
        });
        assertThat(errors).anySatisfy(error -> {
            assertThat(error.rule()).isEqualTo("required");
            assertThat(error.pointer()).isEqualTo("/states/0/condition");
        });
    }

    /** Сценарий спеки: type=FORK → rule=enum. */
    @Test
    void unknownStateTypeReportsEnum() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(WorkflowTestFixtures.state("fork", "FORK", Map.of())),
                List.of()
        );
        assertThat(rules(validate(graph, "fork"))).contains("enum");
    }

    @Test
    void missingStateTypeReportsRequired() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(WorkflowTestFixtures.state("plan", null, Map.of("agent_key", "a"))),
                List.of()
        );
        assertThat(rules(validate(graph, "plan"))).contains("required");
    }

    @Test
    void transitionToUnknownCodeReportsUnknownState() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS"))),
                List.of(WorkflowTestFixtures.transition("done", "nowhere", "NEXT"))
        );
        assertThat(rules(validate(graph, "done"))).contains("unknown-state");
    }

    @Test
    void cancelEdgeForbidden() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("plan", "AGENT", Map.of("agent_key", "a")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS"))
                ),
                List.of(WorkflowTestFixtures.transition("plan", "done", "CANCEL"))
        );
        assertThat(rules(validate(graph, "plan"))).contains("cancel-edge-forbidden");
    }

    @Test
    void nonTerminalWithoutOutgoingReportsOutgoingRequired() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("plan", "AGENT", Map.of("agent_key", "a")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS"))
                ),
                List.of()
        );
        List<JsonSchemaError> errors = validate(graph, "plan");
        assertThat(rules(errors)).contains("outgoing-required", "terminal-unreachable");
    }

    @Test
    void unreachableTerminalReportedForEveryNonTerminal() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("plan", "AGENT", Map.of("agent_key", "a")),
                        WorkflowTestFixtures.state("checks", "BASH_SCRIPT",
                                Map.of("script", "true", "timeout", "PT1M"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("plan", "checks", "NEXT"),
                        WorkflowTestFixtures.transition("checks", "plan", "NEXT"),
                        WorkflowTestFixtures.transition("checks", "plan", "ERROR"),
                        WorkflowTestFixtures.transition("checks", "plan", "TIMEOUT")
                )
        );
        List<JsonSchemaError> errors = validate(graph, "plan");
        assertThat(errors).filteredOn(error -> error.rule().equals("terminal-unreachable")).hasSize(2);
    }

    @Test
    void terminalWithoutOutcomeAndAgentWithoutAgentKeyReportRequired() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("plan", "AGENT", Map.of()),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of())
                ),
                List.of(WorkflowTestFixtures.transition("plan", "done", "NEXT"))
        );
        List<JsonSchemaError> errors = validate(graph, "plan");
        assertThat(errors).anySatisfy(error -> {
            assertThat(error.rule()).isEqualTo("required");
            assertThat(error.pointer()).isEqualTo("/states/0/agent_key");
        });
        assertThat(errors).anySatisfy(error -> {
            assertThat(error.rule()).isEqualTo("required");
            assertThat(error.pointer()).isEqualTo("/states/1/outcome");
        });
    }

    @Test
    void bashWithoutScriptReportsRequired() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("checks", "BASH_SCRIPT",
                                Map.of("timeout", "PT1M")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("checks", "done", "NEXT"),
                        WorkflowTestFixtures.transition("checks", "done", "ERROR"),
                        WorkflowTestFixtures.transition("checks", "done", "TIMEOUT")
                )
        );
        List<JsonSchemaError> errors = validate(graph, "checks");
        assertThat(errors).anySatisfy(error -> {
            assertThat(error.rule()).isEqualTo("required");
            assertThat(error.pointer()).isEqualTo("/states/0/script");
        });
    }

    @Test
    void badTimeoutFormatReported() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("wait", "WAIT_WEBHOOK", Map.of("timeout", "5 minutes")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("wait", "done", "NEXT"),
                        WorkflowTestFixtures.transition("wait", "failed", "ERROR"),
                        WorkflowTestFixtures.transition("wait", "done", "TIMEOUT")
                )
        );
        assertThat(rules(validate(graph, "wait"))).contains("timeout-format");
    }

    @Test
    void badWorkspaceAndSchemaTypesReported() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("wait", "WAIT_WEBHOOK",
                                Map.of("payloadSchema", "not-an-object", "timeout", "PT2M")),
                        WorkflowTestFixtures.state("done", "TERMINAL", Map.of("outcome", "SUCCESS")),
                        WorkflowTestFixtures.state("failed", "TERMINAL", Map.of("outcome", "FAILED"))
                ),
                List.of(
                        WorkflowTestFixtures.transition("wait", "done", "NEXT"),
                        WorkflowTestFixtures.transition("wait", "failed", "ERROR"),
                        WorkflowTestFixtures.transition("wait", "done", "TIMEOUT")
                )
        );
        List<JsonSchemaError> errors = validate(graph, "wait");
        assertThat(errors).anySatisfy(error -> {
            assertThat(error.rule()).isEqualTo("type");
            assertThat(error.pointer()).isEqualTo("/states/0/payloadSchema");
        });
    }

    @Test
    void allViolationsCollectedNotOnlyFirst() {
        Map<String, Object> graph = WorkflowTestFixtures.graph(
                List.of(
                        WorkflowTestFixtures.state("plan", "AGENT", Map.of()),
                        WorkflowTestFixtures.state("plan", "FORK", Map.of())
                ),
                List.of()
        );
        List<JsonSchemaError> errors = validate(graph, "plan");
        assertThat(rules(errors)).contains("required", "code-unique", "enum", "outgoing-required");
        assertThat(errors.size()).isGreaterThanOrEqualTo(4);
    }

    /** H-1: start_state обязателен. */
    @Test
    void missingStartStateReportsRequired() {
        Map<String, Object> graph = WorkflowTestFixtures.twoPhaseGraph();
        assertThat(validate(graph, null)).anySatisfy(error -> {
            assertThat(error.rule()).isEqualTo("required");
            assertThat(error.pointer()).isEqualTo("/start_state");
        });
        assertThat(rules(validate(graph, "  "))).contains("required");
    }

    /** H-1: start_state замкнут на существующие codes. */
    @Test
    void unknownStartStateReportsUnknownState() {
        List<JsonSchemaError> errors = validate(WorkflowTestFixtures.twoPhaseGraph(), "nowhere");
        assertThat(errors).anySatisfy(error -> {
            assertThat(error.rule()).isEqualTo("unknown-state");
            assertThat(error.pointer()).isEqualTo("/start_state");
        });
    }
}
