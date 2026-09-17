package se.rocketscien.harness.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory область отмены Turn'а (glossary §5: CancellationToken-дерево; M1 — один узел,
 * поддерево субагентов — M3). Флаг проверяется между вызовами модели/инструментов;
 * зарегистрированные {@code interruptor}-ы запускаются немедленно (убийство in-flight bash
 * в контейнере). После падения процесса незавершённая отмена доводится повторным stop
 * (идемпотентен) — отдельного recovery нет (D-41).
 */
public final class TurnCancellation {

    private static final Logger log = LoggerFactory.getLogger(TurnCancellation.class);

    private volatile boolean cancelled;
    private final CopyOnWriteArrayList<Runnable> interruptors = new CopyOnWriteArrayList<>();

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Регистрирует немедленное прерывание; вернуть {@link AutoCloseable} для снятия (finally
     * вызова инструмента). Если отмена уже произошла — прерывание исполняется сразу.
     */
    public AutoCloseable registerInterrupt(Runnable interruptor) {
        interruptors.add(interruptor);
        if (cancelled) {
            interruptor.run();
        }
        return () -> interruptors.remove(interruptor);
    }

    public void cancel() {
        cancelled = true;
        for (Runnable interruptor : interruptors) {
            try {
                interruptor.run();
            } catch (Exception e) {
                log.warn("Прерывание отмены упало: {}", e.getMessage());
            }
        }
    }
}
