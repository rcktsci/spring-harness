package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Параметры задачного движка (пачка I): интервалы POLL/таймаут-скана, параметры
 * ShedLock-джоб ({@code task-scheduler}, {@code task-timeout-scanner}), дефолтные
 * таймауты по kind состояния (применяются, когда у состояния нет явного {@code timeout})
 * и флаг автораскачки BASH-состояний по EVENT-wake.
 */
@ConfigurationProperties(prefix = "harness.task")
public record TaskProperties(
        Duration pollInterval,
        Scheduler scheduler,
        Timeout timeout,
        Transition transition,
        BashDispatch bashDispatch) {

    /** Джоба {@code task-scheduler}: TTL ShedLock-лока и размер выборки за проход. */
    public record Scheduler(Duration ttl, int batchSize) {
    }

    /** Джоба {@code task-timeout-scanner}: интервал скана просроченных {@code deadline_at}. */
    public record Timeout(Duration scanInterval) {
    }

    /** Параметры переходов: дефолтные таймауты по kind и лимит metaTool {@code transition} на Turn (D-52/D-59). */
    public record Transition(KindTimeouts kindTimeouts, Integer maxPerTurn) {
    }

    /**
     * Дефолтные таймауты по kind состояния; {@code null} — дедлайн не ставится
     * (состояние без явного {@code timeout} живёт без страха таймаут-скана).
     */
    public record KindTimeouts(Duration bash, Duration waitWebhook, Duration waitTasks, Duration agent) {
    }

    /** Автораскачка BASH-состояний по EVENT-wake (виртуальный поток на исполнение). */
    public record BashDispatch(boolean enabled) {
    }
}
