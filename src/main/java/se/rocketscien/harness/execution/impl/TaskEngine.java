package se.rocketscien.harness.execution.impl;

import se.rocketscien.harness.task.TaskNotFoundException;
import se.rocketscien.harness.task.Transition;
import se.rocketscien.harness.task.TransitionKind;

import java.util.Map;
import java.util.UUID;

/**
 * Движок переходов задачи (architecture.md §3; спека task-engine «Атомарность переходов»,
 * data-model §7.2): атомарный CAS {@code current_state} + денормализаций
 * ({@code status_projection}/{@code current_state_kind}/{@code deadline_at}) + инкремент
 * {@code state_attempt} для входа в BASH_SCRIPT + инкремент {@code task_event_seq}, транзакция
 * покрывает INSERT в {@code task_transition_history}. После коммита — EVENT-wake в
 * {@link InProcessTaskWakeBus} (bootstrap/переоценка/раскачка следующего состояния).
 *
 * <p>Гонки: двойной transition/ретрай вебхука/переоценка — один победитель CAS, проигравшие —
 * no-op ({@code null}). Исключение — stop: его CAS без гварды {@code suspended} (TaskRegistry)
 * выигрывает у любых переходов. CANCEL-kind движком не исполняется — принудительная отмена
 * живёт в {@code TaskRegistry.stop} ({@code '$CANCELLED'}).</p>
 *
 * <p>Внутренний контракт execution (пачка I); AGENT-переходы через инструмент {@code transition}
 * применяются тем же путём (пачка J — транзакционно в момент tool-call).</p>
 */
public interface TaskEngine {

    /**
     * CAS-переход {@code (fromState → toState, kind)} с записью в историю.
     *
     * @param taskId   задача (из неизвестной задачи — {@link TaskNotFoundException})
     * @param fromState ожидаемый текущий code (гвард CAS)
     * @param toState  code целевого состояния ревизии
     * @param kind     вид ребра NEXT/ERROR/TIMEOUT (CANCEL запрещён — см. TaskRegistry.stop)
     * @param reason   обоснование перехода (история; null → пустой объект)
     * @return запись истории; {@code null} — CAS промахнулся (уже другой state/suspended) — no-op
     * @throws IllegalArgumentException ребро (fromState → toState, kind) не существует в графе
     */
    Transition processTaskTransition(UUID taskId, String fromState, String toState,
                                     TransitionKind kind, Map<String, Object> reason);
}
