package se.rocketscien.harness.common;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Генератор UUID версии 7 (UUIDv7) с поддержкой двух режимов работы.
 * Соответствует RFC 9562 и обеспечивает либо строгую упорядоченность, либо максимальную производительность.
 * <ul>
 *     <li><a href="https://datatracker.ietf.org/doc/html/rfc9562#name-uuid-version-7">RFC 9562</a></li>
 *     <li><a href="https://habr.com/ru/articles/795909/">https://habr.com/ru/articles/795909/</a></li>
 * </ul>
 */
public class UUIDv7Generator {

    private static final ZonedDateTime CUTOFF_ZONED_DATETIME = ZonedDateTime
            .ofInstant(Instant.EPOCH, ZoneOffset.UTC)
            .withYear(2025);

    /**
     * 2025-01-01 00:00:00 UTC в миллисекундах.
     */
    public static final long UUIDv7_EPOCH_OFFSET = CUTOFF_ZONED_DATETIME
            .toInstant()
            .toEpochMilli();

    /**
     * Маска для 12-битного счётчика (0xFFFL = 4095).
     */
    private static final long COUNTER_MASK = 0xFFFL;

    /**
     * Длительность ожидания при переполнении счётчика (1 мс в наносекундах).
     */
    private static final long SLEEP_NANOS = 1_000_000;

    /**
     * Криптографически безопасный генератор случайных чисел.
     */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Режимы работы генератора.
     */
    public enum GenerationMode {

        /**
         * Режим строгой временной упорядоченности.
         * Гарантирует монотонное возрастание UUID.
         * </p>
         * Значение по умолчанию.
         */
        STRICT_ORDERING,

        /**
         * Режим максимальной производительности.
         * Использует больше случайных бит для уменьшения коллизий.
         */
        MAX_THROUGHPUT,
    }

    /**
     * Текущий режим работы генератора.
     */
    private final GenerationMode mode;

    /**
     * Уникальный seed для данного экземпляра генератора.
     */
    private final long instanceSeed = generateInstanceSeed();

    /**
     * Атомарное состояние генератора (только для режима STRICT_ORDERING).
     */
    final AtomicLong state;

    /**
     * Создаёт генератор в режиме строгой упорядоченности (по умолчанию).
     */
    public UUIDv7Generator() {
        this(GenerationMode.STRICT_ORDERING);
    }

    /**
     * Создаёт генератор с указанным режимом работы.
     *
     * @param generationMode Режим работы (STRICT_ORDERING или MAX_THROUGHPUT).
     */
    public UUIDv7Generator(GenerationMode generationMode) {
        this.mode = generationMode;

        switch (generationMode) {
            case STRICT_ORDERING -> {
                this.state = new AtomicLong();
                initializeState();
            }
            case MAX_THROUGHPUT -> this.state = null;
            case null -> throw new IllegalArgumentException("Parameter 'generationMode' cannot be null.");
        }
    }

    /**
     * Генерирует новый UUID версии 7 в соответствии с выбранным режимом.
     *
     * @return Сгенерированный UUIDv7.
     */
    public UUID generate() {
        return switch (mode) {
            case STRICT_ORDERING -> generateStrictOrdering();
            case MAX_THROUGHPUT -> generateMaxThroughput();
        };
    }

    private long generateInstanceSeed() {
        long seed = Objects.hash(getHostnameSafe());
        seed ^= System.nanoTime();
        seed ^= secureRandom.nextLong();
        return seed;
    }

    private String getHostnameSafe() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host-" + secureRandom.nextInt(1000);
        }
    }

    void initializeState() {
        long now = getUuidTimestamp();
        long initialCounter = (secureRandom.nextLong() ^ instanceSeed) & COUNTER_MASK;
        state.set(now << 12 | initialCounter);
    }

    private UUID generateStrictOrdering() {
        long uuidTimestamp = getUuidTimestamp();
        long counter = getNextCounter(uuidTimestamp);
        return buildOrderedUuid(uuidTimestamp, counter);
    }

    private long getNextCounter(long timestamp) {
        while (true) {
            long current = state.get();
            long lastTime = current >>> 12;
            long counter = current & COUNTER_MASK;

            if (timestamp < lastTime) {
                long nextCounter = counter + 1 & COUNTER_MASK;
                long nextState = lastTime << 12 | nextCounter;
                if (state.compareAndSet(current, nextState)) {
                    return nextCounter;
                }
            } else if (timestamp == lastTime) {
                if (counter < COUNTER_MASK) {
                    long nextCounter = counter + 1;
                    long nextState = timestamp << 12 | nextCounter;
                    if (state.compareAndSet(current, nextState)) {
                        return nextCounter;
                    }
                } else {
                    LockSupport.parkNanos(SLEEP_NANOS);
                    timestamp = getUuidTimestamp();
                }
            } else {
                long newCounter = secureRandom.nextLong() & COUNTER_MASK;
                long nextState = timestamp << 12 | newCounter;
                if (state.compareAndSet(current, nextState)) {
                    return newCounter;
                }
            }
        }
    }

    private UUID buildOrderedUuid(long uuidTimestamp, long counter) {
        // Применяем смещение эпохи.

        byte[] randomBytes = new byte[8];
        secureRandom.nextBytes(randomBytes);

        // Старшие 64 бита (версия 7 + timestamp + counter).
        long msb = ((uuidTimestamp & 0xFFFFFFFFFFFL) << 16)
                   | (0x7000L | (counter >>> 8));

        // 1. Собираем все компоненты LSB кроме варианта.
        long lsbContent = ((counter & 0xFFL) << 56)
                          | ((randomBytes[0] & 0x3FL) << 56)  // 6 бит.
                          | ((randomBytes[1] & 0xFFL) << 48)
                          | ((randomBytes[2] & 0xFFL) << 40)
                          | ((randomBytes[3] & 0xFFL) << 32)
                          | ((randomBytes[4] & 0xFFL) << 24)
                          | ((randomBytes[5] & 0xFFL) << 16)
                          | ((randomBytes[6] & 0xFFL) << 8)
                          | (randomBytes[7] & 0xFFL);

        // 2. Применяем маску варианта (0b10xxxxxx)
        long lsb = (lsbContent & 0x3FFFFFFFFFFFFFFFL)  // Обнуляем биты 62-63
                   | 0x8000000000000000L;                // Устанавливаем бит 63 в 1

        return new UUID(msb, lsb);
    }

    private UUID generateMaxThroughput() {
        // Применяем смещение эпохи.
        long uuidTimestamp = getUuidTimestamp();

        byte[] randomBytes = new byte[10];
        secureRandom.nextBytes(randomBytes);

        long msb = ((uuidTimestamp & 0xFFFFFFFFFFFL) << 16)
                   | (0x7000L | ((randomBytes[0] & 0x0FL) << 8))
                   | (randomBytes[1] & 0xFFL);

        long lsb = ((randomBytes[2] & 0x3FL) << 56)
                   | 0x8000000000000000L
                   | ((randomBytes[3] & 0xFFL) << 48)
                   | ((randomBytes[4] & 0xFFL) << 40)
                   | ((randomBytes[5] & 0xFFL) << 32)
                   | ((randomBytes[6] & 0xFFL) << 24)
                   | ((randomBytes[7] & 0xFFL) << 16)
                   | ((randomBytes[8] & 0xFFL) << 8)
                   | (randomBytes[9] & 0xFFL);

        return new UUID(msb, lsb);
    }

    private static long getUuidTimestamp() {
        long currentTime = System.currentTimeMillis();
        if (currentTime < UUIDv7_EPOCH_OFFSET) {
            throw new IllegalStateException("System time is before UUIDv7 epoch (%s)".formatted(CUTOFF_ZONED_DATETIME.toString()));
        }

        return currentTime - UUIDv7_EPOCH_OFFSET;
    }

    @Override
    public String toString() {
        return "%s(%s)".formatted(this.getClass().getSimpleName(), this.mode);
    }
}
