package se.rocketscien.harness.task;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Событие задачи для SSE-потока {@code tasks/{id}/events} (api-contracts §3.2, спека session-api).
 * {@code seq} — значение монотонного durable-счётчика {@code task.task_event_seq}, зарезервированное
 * транзакционно с эмиссией (по стилю {@code last_seq}); SSE-кадр события несёт его в {@code id:}.
 *
 * <p>J-6: парный кадр {@code task.status} НЕ расходует собственный seq — он делит
 * {@code task_event_seq} своего перехода (кадр того же {@code seq}, что и {@code task.transition};
 * статусные кадры без перехода — только снапшот при коннекте, без {@code id:}). Таким образом
 * счётчик нумерует СОБЫТИЯ (переход+комментарий+терминал подзадачи), а не кадры.</p>
 *
 * <p>Типы живут в task-модуле, потому что эмиттеры — и движок (execution), и реестр
 * (suspend/stop/comments task-модуля); listeners внедряются списком (стиль {@link TaskWakeListener}).
 * Доставка — только после коммита транзакции-эмиттера.</p>
 */
public sealed interface TaskEvent {

    /** Курсор события (task_event_seq на момент эмиссии); {@code < 0} — безкурсорный кадр. */
    long seq();

    /** UUID строки-владельца потока, на котором событие доставляется подписчикам. */
    UUID streamTaskId();

    /** Переход задачи: кадры {@code task.transition} (+ парный {@code task.status} того же seq). */
    record Transition(long seq, UUID taskId, UUID id, String fromState, String toState,
                      TransitionKind kind, Map<String, Object> reason, Instant createdAt) implements TaskEvent {

        @Override
        public long seq() {
            return seq;
        }

        @Override
        public UUID streamTaskId() {
            return taskId;
        }
    }

    /**
     * Смена статуса/приостановки: кадр {@code task.status}; НЕ расходует собственный seq —
     * делит {@code task_event_seq} парного перехода (J-6).
     */
    record Status(long seq, UUID taskId, String currentState, TaskStatus statusProjection,
                  boolean suspended) implements TaskEvent {

        @Override
        public long seq() {
            return seq;
        }

        @Override
        public UUID streamTaskId() {
            return taskId;
        }
    }

    /** Терминал подзадачи: кадр {@code subtask.terminal} на потоке родителя ({@code taskId}). */
    record SubtaskTerminal(long seq, UUID taskId, UUID terminalTaskId,
                           TaskStatus terminalStatus) implements TaskEvent {

        @Override
        public long seq() {
            return seq;
        }

        @Override
        public UUID streamTaskId() {
            return taskId;
        }
    }

    /** Комментарий: кадр {@code task.comment}. */
    record Comment(long seq, UUID taskId, UUID id, UUID authorUserId, String body,
                   Instant createdAt) implements TaskEvent {

        @Override
        public long seq() {
            return seq;
        }

        @Override
        public UUID streamTaskId() {
            return taskId;
        }
    }

    /**
     * Служебный кадр broadcaster'а (только live, {@code seq < 0}, в backlog/историю не попадает):
     * backlog потока переполнился и старые кадры выброшены — реконнект по курсору их уже не
     * доберёт, клиенту signaled {@code notify-dropped-events} для полной ресинхронизации по снапшоту.
     */
    record BacklogOverflow(UUID taskId, long lastDroppedSeq) implements TaskEvent {

        @Override
        public long seq() {
            return -1;
        }

        @Override
        public UUID streamTaskId() {
            return taskId;
        }
    }
}
