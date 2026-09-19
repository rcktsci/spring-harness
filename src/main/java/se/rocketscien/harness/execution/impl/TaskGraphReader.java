package se.rocketscien.harness.execution.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import se.rocketscien.harness.task.Task;
import se.rocketscien.harness.task.TaskStateKind;
import se.rocketscien.harness.task.TransitionKind;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Чтение графа {@code graph_jsonb} пиннутой ревизии задачи (workflow-domain §2) для движка
 * состояний: states по code, рёбра по (from, to, kind) и по (from, kind) — fan-out одного
 * kind запрещён валидатором графа, поэтому «ребро по kind» единственно. Граф — данные ревизии,
 * а не её API: собственный SQL-запрос по id (та же логика, что в {@code TaskRegistryImpl};
 * модуль execution не зависит от Java-классов workflow).
 */
@Component
@RequiredArgsConstructor
public class TaskGraphReader {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;

    /** Граф по задаче: пиннутая ревизия из {@code task.workflow_revision_id}. */
    public RevisionGraph loadForTask(Task task) {
        return loadByRevisionId(task.workflowRevisionId());
    }

    /** Граф ревизии по id; отсутствие ревизии — повреждённые данные (защитный отказ). */
    public RevisionGraph loadByRevisionId(UUID revisionId) {
        List<String> raw = jdbcTemplate.query(
                "SELECT graph_jsonb::text FROM workflow_revision WHERE id = ?",
                (rs, rowNum) -> rs.getString(1),
                revisionId);
        if (raw.isEmpty()) {
            throw new IllegalStateException("Ревизия workflow %s не найдена".formatted(revisionId));
        }
        Map<String, Object> graph = JSON.readValue(raw.getFirst(), JSON_MAP);
        Map<String, GraphState> states = states(graph).stream()
                .collect(Collectors.toMap(state -> String.valueOf(state.get("code")), GraphState::new));
        List<GraphEdge> transitions = transitions(graph).stream()
                .map(GraphEdge::new)
                .toList();
        return new RevisionGraph(states, transitions);
    }

    /** Состояние по code; отсутствие — повреждённые данные (валидатор графа не даёт их сохранить). */
    public GraphState state(RevisionGraph graph, String code) {
        GraphState state = graph.states().get(code);
        if (state == null) {
            throw new IllegalStateException(
                    "Состояние '%s' не найдено в графе ревизии".formatted(code));
        }
        return state;
    }

    /** Ребро (from → to, kind); отсутствие — недопустимый переход ({@code wrong-transition}). */
    public Optional<GraphEdge> edge(RevisionGraph graph, String from, String to, TransitionKind kind) {
        return graph.transitions().stream()
                .filter(edge -> edge.from().equals(from) && edge.to().equals(to) && edge.kind() == kind)
                .findFirst();
    }

    /** Ребро из состояния по kind (fan-out одного kind запрещён — ребро единственно). */
    public Optional<GraphEdge> edgeByKind(RevisionGraph graph, String from, TransitionKind kind) {
        return graph.transitions().stream()
                .filter(edge -> edge.from().equals(from) && edge.kind() == kind)
                .findFirst();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> states(Map<String, Object> graph) {
        if (!(graph.get("states") instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream()
                .filter(Map.class::isInstance)
                .map(state -> (Map<String, Object>) state)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> transitions(Map<String, Object> graph) {
        if (!(graph.get("transitions") instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream()
                .filter(Map.class::isInstance)
                .map(transition -> (Map<String, Object>) transition)
                .toList();
    }

    /**
     * Граф ревизии: состояния по code и список рёбер. {@code startState} движку не нужен —
     * задача уже несёт {@code current_state}.
     */
    public record RevisionGraph(Map<String, GraphState> states, List<GraphEdge> transitions) {
    }

    /** Состояние графа: code, kind, сырой узел (script/scope/condition/timeout/workspace/…). */
    public record GraphState(String code, TaskStateKind kind, Map<String, Object> raw) {

        private GraphState(Map<String, Object> raw) {
            this(String.valueOf(raw.get("code")),
                    TaskStateKind.valueOf(String.valueOf(raw.get("type"))),
                    raw);
        }

        /** Явный {@code timeout} состояния (ISO-8601); absent — пусто. */
        public Optional<Duration> timeout() {
            if (raw.get("timeout") instanceof String timeout && !timeout.isBlank()) {
                return Optional.of(Duration.parse(timeout));
            }
            return Optional.empty();
        }
    }

    /** Ребро графа: from → to, kind (NEXT/ERROR/TIMEOUT/CANCEL). */
    public record GraphEdge(String from, String to, TransitionKind kind) {

        private GraphEdge(Map<String, Object> raw) {
            this(String.valueOf(raw.get("from")),
                    String.valueOf(raw.get("to")),
                    TransitionKind.valueOf(String.valueOf(raw.get("kind"))));
        }
    }
}
