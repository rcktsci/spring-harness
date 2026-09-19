package se.rocketscien.harness.workflow;

import org.springframework.stereotype.Component;
import se.rocketscien.harness.common.jsonschema.JsonSchemaError;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Единая точка валидации {@code graph_jsonb} (D-56): правила §2 workflow-domain + контракт графа
 * (ограниченный профиль JSON-Schema D-58 — ручной обход). Используется при создании workflow,
 * новой ревизии и может переиспользоваться sanity-check'ом на старте.
 *
 * <p>Собирает все нарушения (не первое) в errors[] { pointer, rule, message }.</p>
 *
 * <p>Правила (rule-имена по сценариям спеки workflow-engine):</p>
 * <ul>
 *   <li>{@code required} / {@code type} / {@code enum} — контракт графа (обязательные поля по
 *       типу состояния, допустимые значения type/condition/outcome/workspace);</li>
 *   <li>{@code code-unique} — codes уникальны в ревизии;</li>
 *   <li>{@code unknown-state} — переходы замкнуты на существующие codes;</li>
 *   <li>{@code cancel-edge-forbidden} — CANCEL-рёбра в присылаемом графе невалидны (движковый
 *       резерв для stop, спека workflow-engine п.8);</li>
 *   <li>{@code fan-out-forbidden} — не более одного исходящего ребра каждого kind;</li>
 *   <li>{@code outgoing-required} — у нетерминального кода есть исходящий переход;</li>
 *   <li>{@code bash-error-required} / {@code bash-timeout-required} — BASH_SCRIPT обязан иметь
 *       и ERROR, и TIMEOUT;</li>
 *   <li>{@code wait-error-required} / {@code wait-timeout-required} — WAIT_* обязан иметь
 *       и ERROR, и TIMEOUT (без ERROR-ребра движок клинчится);</li>
 *   <li>{@code terminal-unreachable} — из любого состояния достижим TERMINAL;</li>
 *   <li>{@code timeout-format} — timeout парсится как ISO-8601 duration.</li>
 * </ul>
 *
 * <p>Циклы (возвраты) легальны — ограничений на итерации нет (§2).</p>
 */
@Component
public class WorkflowGraphSchemaValidator {

    private static final Set<String> STATE_TYPES =
            Set.of("AGENT", "BASH_SCRIPT", "WAIT_WEBHOOK", "WAIT_TASKS", "TERMINAL");
    private static final Set<String> TRANSITION_KINDS = Set.of("NEXT", "ERROR", "TIMEOUT");
    private static final Set<String> WAIT_CONDITIONS = Set.of("ALL_TERMINAL", "ALL_SUCCESS");
    private static final Set<String> TERMINAL_OUTCOMES = Set.of("SUCCESS", "FAILED", "CANCELLED");
    private static final Set<String> WORKSPACE_TYPES = Set.of("SERVER_DIR", "CLIENT_EXEC");
    private static final Set<String> WORKSPACE_MODES = Set.of("AUTO", "PATH");

    /** Валидация графа вместе с объявленным {@code start_state}; пустой список — валиден. */
    public List<JsonSchemaError> validate(Map<String, Object> graph, String startState) {
        List<JsonSchemaError> errors = new ArrayList<>();
        if (graph == null) {
            errors.add(new JsonSchemaError("", "required", "Граф обязателен"));
            return errors;
        }

        List<Map<String, Object>> states = readStates(graph, errors);
        List<Map<String, Object>> transitions = readTransitions(graph, errors);
        if (states == null) {
            return errors;
        }

        Set<String> seenCodes = new HashSet<>();
        Map<String, String> stateTypes = new LinkedHashMap<>();
        for (int i = 0; i < states.size(); i++) {
            String base = "/states/" + i;
            Map<String, Object> state = states.get(i);
            String code = state.get("code") instanceof String codeValue && !codeValue.isBlank()
                    ? codeValue : null;
            if (code == null) {
                errors.add(new JsonSchemaError(base + "/code", state.get("code") == null ? "required" : "type",
                        "code состояния — непустая строка"));
            } else if (!seenCodes.add(code)) {
                errors.add(new JsonSchemaError(base + "/code", "code-unique",
                        "Дубликат code '%s'".formatted(code)));
            }
            String type = readType(state, base, errors);
            if (type != null) {
                stateTypes.put(code != null ? code : base, type);
            }
            validateStateContract(state, base, type, errors);
        }

        Map<String, List<Edge>> outgoing = validateTransitions(transitions, seenCodes, errors);

        validateStartState(startState, seenCodes, errors);
        validateOutgoingAndKinds(states, stateTypes, outgoing, errors);
        validateTerminalReachability(states, stateTypes, outgoing, errors);
        return errors;
    }

    /** H-1: стартовое состояние объявлено явно и замкнуто на существующие codes. */
    private void validateStartState(String startState, Set<String> codes, List<JsonSchemaError> errors) {
        if (startState == null || startState.isBlank()) {
            errors.add(new JsonSchemaError("/start_state", "required",
                    "start_state ревизии обязателен"));
            return;
        }
        if (!codes.contains(startState)) {
            errors.add(new JsonSchemaError("/start_state", "unknown-state",
                    "start_state '%s' не входит в states[].code".formatted(startState)));
        }
    }

    private List<Map<String, Object>> readStates(Map<String, Object> graph, List<JsonSchemaError> errors) {
        if (!(graph.get("states") instanceof List<?> raw)) {
            errors.add(new JsonSchemaError("/states", "required", "Список states обязателен"));
            return null;
        }
        if (raw.isEmpty()) {
            errors.add(new JsonSchemaError("/states", "required", "Хотя бы одно состояние"));
            return null;
        }
        List<Map<String, Object>> states = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Map<?, ?> state) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) state;
                states.add(typed);
            } else {
                states.add(Map.of());
            }
        }
        return states;
    }

    private List<Map<String, Object>> readTransitions(Map<String, Object> graph, List<JsonSchemaError> errors) {
        if (graph.get("transitions") == null) {
            return List.of();
        }
        if (!(graph.get("transitions") instanceof List<?> raw)) {
            errors.add(new JsonSchemaError("/transitions", "type", "Список transitions — массив"));
            return List.of();
        }
        List<Map<String, Object>> transitions = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            if (raw.get(i) instanceof Map<?, ?> transition) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) transition;
                transitions.add(typed);
            } else {
                errors.add(new JsonSchemaError("/transitions/" + i, "type", "Переход — объект"));
            }
        }
        return transitions;
    }

    private String readType(Map<String, Object> state, String base, List<JsonSchemaError> errors) {
        Object type = state.get("type");
        if (type == null) {
            errors.add(new JsonSchemaError(base + "/type", "required", "type состояния обязателен"));
            return null;
        }
        if (!(type instanceof String typeValue) || !STATE_TYPES.contains(typeValue)) {
            errors.add(new JsonSchemaError(base + "/type", "enum",
                    "type состояния ∈ %s".formatted(sorted(STATE_TYPES))));
            return null;
        }
        return typeValue;
    }

    /** Обязательные/опциональные поля контракта по типу состояния (workflow-domain §2). */
    private void validateStateContract(Map<String, Object> state, String base, String type,
                                       List<JsonSchemaError> errors) {
        switch (type == null ? "" : type) {
            case "AGENT" -> requireText(state, base, "agent_key", errors);
            case "BASH_SCRIPT" -> requireText(state, base, "script", errors);
            case "WAIT_TASKS" -> {
                requireText(state, base, "scope", errors);
                requireEnum(state, base, "condition", WAIT_CONDITIONS, errors);
            }
            case "TERMINAL" -> requireEnum(state, base, "outcome", TERMINAL_OUTCOMES, errors);
            default -> {
                // WAIT_WEBHOOK — обязательных полей нет; прочие проверяются общими правилами
            }
        }
        validateWorkspace(state, base, errors);
        validateTimeout(state, base, errors);
        requireSchemaObject(state, base, "payloadSchema", errors);
        requireSchemaObject(state, base, "paramsSchema", errors);
    }

    private void requireText(Map<String, Object> state, String base, String field,
                             List<JsonSchemaError> errors) {
        Object value = state.get(field);
        if (value == null) {
            errors.add(new JsonSchemaError(base + "/" + field, "required",
                    "%s обязателен для этого типа состояния".formatted(field)));
        } else if (!(value instanceof String text) || text.isBlank()) {
            errors.add(new JsonSchemaError(base + "/" + field, "type", "%s — непустая строка".formatted(field)));
        }
    }

    private void requireEnum(Map<String, Object> state, String base, String field, Set<String> allowed,
                             List<JsonSchemaError> errors) {
        Object value = state.get(field);
        if (value == null) {
            errors.add(new JsonSchemaError(base + "/" + field, "required",
                    "%s обязателен для этого типа состояния".formatted(field)));
        } else if (!(value instanceof String text) || !allowed.contains(text)) {
            errors.add(new JsonSchemaError(base + "/" + field, "enum",
                    "%s ∈ %s".formatted(field, sorted(allowed))));
        }
    }

    private void validateWorkspace(Map<String, Object> state, String base, List<JsonSchemaError> errors) {
        if (state.get("workspace") == null) {
            return;
        }
        if (!(state.get("workspace") instanceof Map<?, ?> raw)) {
            errors.add(new JsonSchemaError(base + "/workspace", "type", "workspace — объект"));
            return;
        }
        Object type = raw.get("type");
        if (type == null) {
            errors.add(new JsonSchemaError(base + "/workspace/type", "required", "workspace.type обязателен"));
        } else if (!(type instanceof String typeValue) || !WORKSPACE_TYPES.contains(typeValue)) {
            errors.add(new JsonSchemaError(base + "/workspace/type", "enum",
                    "workspace.type ∈ %s".formatted(sorted(WORKSPACE_TYPES))));
        }
        Object mode = raw.get("mode");
        if (mode != null && (!(mode instanceof String modeValue) || !WORKSPACE_MODES.contains(modeValue))) {
            errors.add(new JsonSchemaError(base + "/workspace/mode", "enum",
                    "workspace.mode ∈ %s".formatted(sorted(WORKSPACE_MODES))));
        }
    }

    private void validateTimeout(Map<String, Object> state, String base, List<JsonSchemaError> errors) {
        Object timeout = state.get("timeout");
        if (timeout == null) {
            return;
        }
        if (!(timeout instanceof String text)) {
            errors.add(new JsonSchemaError(base + "/timeout", "type", "timeout — строка ISO-8601 duration"));
            return;
        }
        try {
            Duration.parse(text);
        } catch (RuntimeException e) {
            errors.add(new JsonSchemaError(base + "/timeout", "timeout-format",
                    "timeout не парсится как ISO-8601 duration: %s".formatted(text)));
        }
    }

    /** Схемы профиля D-58 — JSON-объекты (глубина схемы не ограничивается). */
    private void requireSchemaObject(Map<String, Object> state, String base, String field,
                                     List<JsonSchemaError> errors) {
        if (state.get(field) != null && !(state.get(field) instanceof Map)) {
            errors.add(new JsonSchemaError(base + "/" + field, "type", field + " — объект JSON-Schema"));
        }
    }

    private record Edge(int transitionIndex, String kind, String to) {
    }

    private Map<String, List<Edge>> validateTransitions(List<Map<String, Object>> transitions,
                                                        Set<String> codes, List<JsonSchemaError> errors) {
        Map<String, List<Edge>> outgoing = new HashMap<>();
        for (int i = 0; i < transitions.size(); i++) {
            String base = "/transitions/" + i;
            Map<String, Object> transition = transitions.get(i);
            Object from = transition.get("from");
            Object to = transition.get("to");
            Object kind = transition.get("kind");

            if (!(from instanceof String fromCode) || !codes.contains(fromCode)) {
                errors.add(new JsonSchemaError(base + "/from", "unknown-state",
                        "from ссылается на несуществующий code %s".formatted(from)));
                continue;
            }
            if (!(to instanceof String toCode) || !codes.contains(toCode)) {
                errors.add(new JsonSchemaError(base + "/to", "unknown-state",
                        "to ссылается на несуществующий code %s".formatted(to)));
                continue;
            }
            if (!(kind instanceof String kindValue)) {
                errors.add(new JsonSchemaError(base + "/kind", "required", "kind перехода обязателен"));
                continue;
            }
            if ("CANCEL".equals(kindValue)) {
                errors.add(new JsonSchemaError(base + "/kind", "cancel-edge-forbidden",
                        "CANCEL-рёбра в графе невалидны: CANCEL — движковый резерв для stop ('$CANCELLED')"));
                continue;
            }
            if (!TRANSITION_KINDS.contains(kindValue)) {
                errors.add(new JsonSchemaError(base + "/kind", "enum",
                        "kind ∈ %s".formatted(sorted(TRANSITION_KINDS))));
                continue;
            }
            outgoing.computeIfAbsent(fromCode, key -> new ArrayList<>()).add(new Edge(i, kindValue, toCode));
        }
        return outgoing;
    }

    /** Fan-out (≤1 ребро каждого kind) + обязательные kind-рёбра системных состояний. */
    private void validateOutgoingAndKinds(List<Map<String, Object>> states, Map<String, String> stateTypes,
                                          Map<String, List<Edge>> outgoing, List<JsonSchemaError> errors) {
        for (int i = 0; i < states.size(); i++) {
            Map<String, Object> state = states.get(i);
            String code = state.get("code") instanceof String codeValue ? codeValue : "/states/" + i;
            String type = stateTypes.get(code);
            List<Edge> edges = outgoing.getOrDefault(code, List.of());

            if (type == null || "TERMINAL".equals(type)) {
                continue;
            }

            Map<String, Integer> kindCounts = new HashMap<>();
            for (Edge edge : edges) {
                int prev = kindCounts.getOrDefault(edge.kind(), 0);
                kindCounts.put(edge.kind(), prev + 1);
                if (prev == 1) {
                    errors.add(new JsonSchemaError("/transitions/" + edge.transitionIndex(), "fan-out-forbidden",
                            "У состояния '%s' уже есть исходящий переход kind=%s".formatted(code, edge.kind())));
                }
            }

            if (edges.isEmpty()) {
                errors.add(new JsonSchemaError("/states/" + i, "outgoing-required",
                        "Нетерминальное состояние '%s' без исходящих переходов".formatted(code)));
            }
            switch (type) {
                case "BASH_SCRIPT" -> {
                    requireKindEdge(code, edges, "ERROR", "bash-error-required", errors);
                    requireKindEdge(code, edges, "TIMEOUT", "bash-timeout-required", errors);
                }
                case "WAIT_WEBHOOK", "WAIT_TASKS" -> {
                    requireKindEdge(code, edges, "ERROR", "wait-error-required", errors);
                    requireKindEdge(code, edges, "TIMEOUT", "wait-timeout-required", errors);
                }
                default -> {
                    // AGENT: ERROR-ребро опционально (агент может сигналить неудачу), NEXT обычно есть
                }
            }
        }
    }

    private void requireKindEdge(String code, List<Edge> edges, String kind, String rule,
                                 List<JsonSchemaError> errors) {
        if (edges.stream().noneMatch(edge -> edge.kind().equals(kind))) {
            errors.add(new JsonSchemaError("", rule,
                    "Состояние '%s' обязано иметь исходящий переход kind=%s".formatted(code, kind)));
        }
    }

    /**
     * Достижимость TERMINAL — обратный BFS от всех терминалов по встречным рёбрам (V-1): прямой
     * DFS с memo на циклах отравляет кэш (узел цикла, обрезанный guard'ом visiting, навсегда
     * получает false) и отвергает валидные графы «review → возврат в plan». Обратный обход
     * циклобезопасен по построению.
     */
    private void validateTerminalReachability(List<Map<String, Object>> states, Map<String, String> stateTypes,
                                              Map<String, List<Edge>> outgoing, List<JsonSchemaError> errors) {
        Map<String, List<String>> incoming = new HashMap<>();
        outgoing.forEach((from, edges) -> edges.forEach(edge -> incoming.computeIfAbsent(edge.to(),
                key -> new ArrayList<>()).add(from)));

        Set<String> reaching = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        stateTypes.forEach((code, type) -> {
            if ("TERMINAL".equals(type)) {
                reaching.add(code);
                queue.add(code);
            }
        });
        while (!queue.isEmpty()) {
            for (String predecessor : incoming.getOrDefault(queue.poll(), List.of())) {
                if (reaching.add(predecessor)) {
                    queue.add(predecessor);
                }
            }
        }

        for (int i = 0; i < states.size(); i++) {
            String code = states.get(i).get("code") instanceof String codeValue ? codeValue : null;
            String type = code != null ? stateTypes.get(code) : null;
            if ("TERMINAL".equals(type) || code == null) {
                continue;
            }
            if (!reaching.contains(code)) {
                errors.add(new JsonSchemaError("/states/" + i, "terminal-unreachable",
                        "Из состояния '%s' недостижим ни один TERMINAL".formatted(code)));
            }
        }
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }
}
