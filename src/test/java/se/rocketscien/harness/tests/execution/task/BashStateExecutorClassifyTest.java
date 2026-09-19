package se.rocketscien.harness.tests.execution.task;

import org.junit.jupiter.api.Test;
import se.rocketscien.harness.execution.ToolResult;
import se.rocketscien.harness.execution.ToolStatus;
import se.rocketscien.harness.execution.impl.BashStateExecutor;
import se.rocketscien.harness.task.TransitionKind;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Парсинг результата bash-исполнения (спека task-engine BASH_SCRIPT): exit=0 → NEXT,
 * exit≠0 → ERROR, таймаут → TIMEOUT, отказ контейнера (LOST/ERROR) → ERROR, CANCELLED →
 * null (терминал уже записан stop'ом). Поля reason: exitCode/output/durationMs/attempt.
 */
class BashStateExecutorClassifyTest {

    private static ToolResult ok(Integer exitCode, Boolean timedOut, String output) {
        return new ToolResult("call-1", "bash", ToolStatus.OK, output, exitCode, false, timedOut);
    }

    @Test
    void zeroExitMeansNext() {
        BashStateExecutor.Outcome outcome = BashStateExecutor.classify(ok(0, false, "готово"), 120, 3);

        assertThat(outcome.kind()).isEqualTo(TransitionKind.NEXT);
        assertThat(outcome.reason())
                .containsEntry("exitCode", 0)
                .containsEntry("output", "готово")
                .containsEntry("durationMs", 120L)
                .containsEntry("attempt", 3);
    }

    @Test
    void nonZeroExitMeansError() {
        BashStateExecutor.Outcome outcome = BashStateExecutor.classify(ok(3, false, "упало"), 50, 1);

        assertThat(outcome.kind()).isEqualTo(TransitionKind.ERROR);
        assertThat(outcome.reason()).containsEntry("exitCode", 3);
    }

    @Test
    void timedOutMeansTimeout() {
        BashStateExecutor.Outcome outcome = BashStateExecutor.classify(ok(124, true, ""), 1000, 2);

        assertThat(outcome.kind()).isEqualTo(TransitionKind.TIMEOUT);
        assertThat(outcome.reason()).containsEntry("exitCode", 124);
    }

    @Test
    void timedOutWithoutExitCodeKeepsNullExitCodeInReason() {
        BashStateExecutor.Outcome outcome = BashStateExecutor.classify(ok(null, true, ""), 900, 1);

        assertThat(outcome.kind()).isEqualTo(TransitionKind.TIMEOUT);
        assertThat(outcome.reason()).containsEntry("exitCode", null);
    }

    @Test
    void containerFailureMeansError() {
        BashStateExecutor.Outcome lost = BashStateExecutor.classify(
                ToolResult.lost("call-1", "bash", "container died"), 5, 1);
        BashStateExecutor.Outcome error = BashStateExecutor.classify(
                ToolResult.error("call-1", "bash", "mount failed"), 5, 1);

        assertThat(lost.kind()).isEqualTo(TransitionKind.ERROR);
        assertThat(lost.reason()).containsEntry("exitCode", null);
        assertThat(error.kind()).isEqualTo(TransitionKind.ERROR);
        assertThat(error.reason()).containsEntry("output", "mount failed");
    }

    @Test
    void cancelledMeansNoTransition() {
        assertThat(BashStateExecutor.classify(
                ToolResult.cancelled("call-1", "bash", "отменено пользователем"), 5, 1)).isNull();
    }
}
