package se.rocketscien.harness.execution.impl;

import se.rocketscien.harness.task.TaskStatus;

import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scope/condition состояния WAIT_TASKS (workflow-domain §3) — чистые функции: разбор
 * scope-выражения из графа и вычисление условия по статусам scope-задач.
 *
 * <p>Scope: {@code ALL_CHILDREN} (подзадачи по {@code parent_task_id}), {@code BLOCKED_BY}
 * (блокирующие по {@code task_dependency}), {@code TAGGED(x)} (задачи с тегом x),
 * {@code EXPLICIT(${task.params.key})} (явный список id из params задачи).
 * Condition: {@code ALL_TERMINAL} — все в терминале (CANCELLED — терминал); {@code ALL_SUCCESS} —
 * как ALL_TERMINAL, но первый FAILED/CANCELLED ведёт немедленно по ERROR.</p>
 *
 * <p>Пустой scope условие НЕ закрывает (вакусная истина открыла бы барьер до создания
 * подзадач — осмысленный WAIT всегда ждёт конкретные задачи).</p>
 */
public final class WaitTasksScope {

    private static final Pattern TAGGED = Pattern.compile("^TAGGED\\((.+)\\)$");
    private static final Pattern EXPLICIT = Pattern.compile("^EXPLICIT\\(\\$\\{task\\.params\\.(.+)}\\)$");

    private WaitTasksScope() {
    }

    /** Разбор scope-выражения состояния; null/пустое/неизвестное — повреждённый граф (IllegalState). */
    public static Scope parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("WAIT_TASKS без scope");
        }
        String trimmed = raw.trim();
        if ("ALL_CHILDREN".equals(trimmed)) {
            return new Scope(ScopeKind.ALL_CHILDREN, null, null);
        }
        if ("BLOCKED_BY".equals(trimmed)) {
            return new Scope(ScopeKind.BLOCKED_BY, null, null);
        }
        Matcher tagged = TAGGED.matcher(trimmed);
        if (tagged.matches()) {
            return new Scope(ScopeKind.TAGGED, tagged.group(1), null);
        }
        Matcher explicit = EXPLICIT.matcher(trimmed);
        if (explicit.matches()) {
            return new Scope(ScopeKind.EXPLICIT, null, explicit.group(1));
        }
        throw new IllegalStateException("Неизвестный scope WAIT_TASKS: '%s'".formatted(raw));
    }

    /** Вычисление условия по статусам scope-задач (пустой scope — {@code PENDING}). */
    public static Evaluation evaluate(Condition condition, Collection<TaskStatus> statuses) {
        if (statuses.isEmpty()) {
            return Evaluation.PENDING;
        }
        boolean allTerminal = statuses.stream().allMatch(TaskStatus::isTerminal);
        if (condition == Condition.ALL_SUCCESS) {
            boolean anyBad = statuses.stream().anyMatch(s -> s == TaskStatus.FAILED || s == TaskStatus.CANCELLED);
            if (anyBad) {
                return Evaluation.ERROR;
            }
            return allTerminal ? Evaluation.NEXT : Evaluation.PENDING;
        }
        return allTerminal ? Evaluation.NEXT : Evaluation.PENDING;
    }

    /** Вид scope (workflow-domain §2). */
    public enum ScopeKind {
        ALL_CHILDREN,
        BLOCKED_BY,
        TAGGED,
        EXPLICIT
    }

    /** Разобранный scope: kind + tag (TAGGED) + paramsKey (EXPLICIT). */
    public record Scope(ScopeKind kind, String tag, String paramsKey) {
    }

    /** Условие закрытия барьера (workflow-domain §2). */
    public enum Condition {
        ALL_TERMINAL,
        ALL_SUCCESS
    }

    /** Итог вычисления условия. */
    public enum Evaluation {
        NEXT,
        ERROR,
        PENDING
    }

    /** Разбор condition-строки состояния; неизвестное — повреждённый граф. */
    public static Condition parseCondition(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("WAIT_TASKS без condition");
        }
        try {
            return Condition.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Неизвестный condition WAIT_TASKS: '%s'".formatted(raw), e);
        }
    }

    /** Разрешённые значения scope — для сообщений об ошибках/доков. */
    public static List<String> knownScopes() {
        return List.of("ALL_CHILDREN", "BLOCKED_BY", "TAGGED(x)", "EXPLICIT(${task.params.key})");
    }
}
