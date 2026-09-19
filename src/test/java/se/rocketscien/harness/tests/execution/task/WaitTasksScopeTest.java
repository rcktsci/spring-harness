package se.rocketscien.harness.tests.execution.task;

import se.rocketscien.harness.execution.impl.WaitTasksScope;
import se.rocketscien.harness.execution.impl.WaitTasksScope.Condition;
import se.rocketscien.harness.execution.impl.WaitTasksScope.Evaluation;
import se.rocketscien.harness.execution.impl.WaitTasksScope.Scope;
import se.rocketscien.harness.execution.impl.WaitTasksScope.ScopeKind;
import se.rocketscien.harness.task.TaskStatus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Чистая логика WAIT_TASKS (спека task-engine): разбор scope-выражений workflow-domain §2
 * и вычисление условий ALL_TERMINAL/ALL_SUCCESS (пустой scope не закрывает барьер).
 */
class WaitTasksScopeTest {

    @Test
    void parsesAllChildrenAndBlockedBy() {
        Scope children = WaitTasksScope.parse("ALL_CHILDREN");
        assertThat(children.kind()).isEqualTo(ScopeKind.ALL_CHILDREN);

        Scope blocked = WaitTasksScope.parse("BLOCKED_BY");
        assertThat(blocked.kind()).isEqualTo(ScopeKind.BLOCKED_BY);
    }

    @Test
    void parsesTaggedWithLiteralTag() {
        Scope scope = WaitTasksScope.parse("TAGGED(batch-1)");
        assertThat(scope.kind()).isEqualTo(ScopeKind.TAGGED);
        assertThat(scope.tag()).isEqualTo("batch-1");
    }

    @Test
    void parsesExplicitWithParamsKey() {
        Scope scope = WaitTasksScope.parse("EXPLICIT(${task.params.watch_ids})");
        assertThat(scope.kind()).isEqualTo(ScopeKind.EXPLICIT);
        assertThat(scope.paramsKey()).isEqualTo("watch_ids");
    }

    @Test
    void rejectsUnknownOrEmptyScope() {
        assertThatThrownBy(() -> WaitTasksScope.parse(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WaitTasksScope.parse("  ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WaitTasksScope.parse("WHAT(x)")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WaitTasksScope.parse("TAGGED(")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parsesConditionOrThrows() {
        assertThat(WaitTasksScope.parseCondition("ALL_SUCCESS")).isEqualTo(Condition.ALL_SUCCESS);
        assertThat(WaitTasksScope.parseCondition(" ALL_TERMINAL ")).isEqualTo(Condition.ALL_TERMINAL);
        assertThatThrownBy(() -> WaitTasksScope.parseCondition("ANY")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WaitTasksScope.parseCondition(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allTerminalRequiresEveryTaskTerminal() {
        assertThat(WaitTasksScope.evaluate(Condition.ALL_TERMINAL,
                List.of(TaskStatus.SUCCEEDED, TaskStatus.FAILED))).isEqualTo(Evaluation.NEXT);
        assertThat(WaitTasksScope.evaluate(Condition.ALL_TERMINAL,
                List.of(TaskStatus.SUCCEEDED, TaskStatus.CANCELLED))).isEqualTo(Evaluation.NEXT);
        assertThat(WaitTasksScope.evaluate(Condition.ALL_TERMINAL,
                List.of(TaskStatus.SUCCEEDED, TaskStatus.RUNNING))).isEqualTo(Evaluation.PENDING);
        assertThat(WaitTasksScope.evaluate(Condition.ALL_TERMINAL,
                List.of(TaskStatus.WAITING))).isEqualTo(Evaluation.PENDING);
    }

    @Test
    void allSuccessFailsImmediatelyOnFailedOrCancelled() {
        assertThat(WaitTasksScope.evaluate(Condition.ALL_SUCCESS,
                List.of(TaskStatus.SUCCEEDED, TaskStatus.FAILED))).isEqualTo(Evaluation.ERROR);
        assertThat(WaitTasksScope.evaluate(Condition.ALL_SUCCESS,
                List.of(TaskStatus.RUNNING, TaskStatus.CANCELLED))).isEqualTo(Evaluation.ERROR);
        assertThat(WaitTasksScope.evaluate(Condition.ALL_SUCCESS,
                List.of(TaskStatus.SUCCEEDED, TaskStatus.SUCCEEDED))).isEqualTo(Evaluation.NEXT);
        assertThat(WaitTasksScope.evaluate(Condition.ALL_SUCCESS,
                List.of(TaskStatus.RUNNING))).isEqualTo(Evaluation.PENDING);
    }

    @Test
    void emptyScopeNeverSatisfiesBarrier() {
        assertThat(WaitTasksScope.evaluate(Condition.ALL_TERMINAL, List.of())).isEqualTo(Evaluation.PENDING);
        assertThat(WaitTasksScope.evaluate(Condition.ALL_SUCCESS, List.of())).isEqualTo(Evaluation.PENDING);
    }
}
