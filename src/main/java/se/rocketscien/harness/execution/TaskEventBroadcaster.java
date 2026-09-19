package se.rocketscien.harness.execution;

import se.rocketscien.harness.task.TaskEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * SSE-широковещатель событий задачи (api-contracts §3.2, пачка J.4): подписчики эндпоинта
 * {@code GET /api/v1/tasks/{id}/events} получают события через этот контракт. <b>Это не
 * wake-шина движка</b> (та — {@code InProcessTaskWakeBus}, пачка I.5): здесь — живая доставка
 * и backlog для бэкфилла реконнекта.
 *
 * <p>Доставка — строго по возрастанию per-task курсора {@code task_event_seq} (буфер
 * переупорядочивания: seq резервируется row-lock'ом раньше публикации, afterCommit-колбэки
 * соседних транзакций могут прийти в обратном порядке; дыра всегда закрывается — инкремент
 * сериализован блокировкой строки задачи). Backlog ограничен ({@code harness.sse.task-backlog})
 * и живёт в границах процесса (стиль D-J-5): после рестарта реконнект добирает только снапшот
 * (истинное состояние — в строке task), пропущенные в простое кадры не восстанавливаются.</p>
 */
public interface TaskEventBroadcaster {

    /** Подписка на события задачи; отписка — {@link Subscription#close()} (идемпотентна). */
    Subscription subscribe(UUID taskId, Consumer<TaskEvent> consumer);

    /** Кадры backlog с {@code seq > afterSeq} по возрастанию (бэкфилл реконнекта). */
    List<TaskEvent> backlog(UUID taskId, long afterSeq);

    /** Подписка; close — отписка. */
    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
