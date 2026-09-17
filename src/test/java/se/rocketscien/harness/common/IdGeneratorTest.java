package se.rocketscien.harness.common;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class IdGeneratorTest {

    private static final Pattern CROCKFORD_BASE32 = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");

    private static final String CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private final IdGenerator idGenerator = new IdGenerator();

    @Test
    void uuidV7HasVersionAndVariantBits() {
        UUID id = idGenerator.newUuidV7();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void uuidV7TimestampIsNearCurrentTime() {
        long before = System.currentTimeMillis();
        UUID id = idGenerator.newUuidV7();
        long after = System.currentTimeMillis();

        long timestamp = decodeUnixMilli(id);

        assertThat(timestamp).isBetween(before, after);
    }

    @Test
    void uuidV7TimestampsAreNonDecreasing() {
        long previous = 0;

        for (int i = 0; i < 1000; i++) {
            UUID id = idGenerator.newUuidV7();
            long timestamp = decodeUnixMilli(id);
            assertThat(timestamp).isGreaterThanOrEqualTo(previous);
            previous = timestamp;
        }
    }

    private long decodeUnixMilli(UUID id) {
        return (id.getMostSignificantBits() >>> 16) + UUIDv7Generator.UUIDv7_EPOCH_OFFSET;
    }

    @Test
    void ulidHasLength26AndCrockfordAlphabet() {
        for (int i = 0; i < 1000; i++) {
            assertThat(idGenerator.newUlid()).matches(CROCKFORD_BASE32);
        }
    }

    @Test
    void ulidIsStrictlyMonotonicWithinSameMillisecond() {
        String previous = idGenerator.newUlid();

        for (int i = 0; i < 10_000; i++) {
            String ulid = idGenerator.newUlid();
            assertThat(ulid).isGreaterThan(previous);
            previous = ulid;
        }
    }

    @Test
    void ulidIsStrictlyMonotonicAcrossMilliseconds() throws InterruptedException {
        String first = idGenerator.newUlid();

        Thread.sleep(2);

        String second = idGenerator.newUlid();

        assertThat(second).isGreaterThan(first);
    }

    @Test
    void ulidTimestampPrefixMatchesCurrentTime() {
        long before = System.currentTimeMillis();
        String ulid = idGenerator.newUlid();
        long after = System.currentTimeMillis();

        long timestamp = decodeTimestamp(ulid);

        assertThat(timestamp).isBetween(before, after);
    }

    private long decodeTimestamp(String ulid) {
        long timestamp = 0;
        for (int i = 0; i < 10; i++) {
            timestamp = (timestamp << 5) | CROCKFORD_ALPHABET.indexOf(ulid.charAt(i));
        }
        return timestamp;
    }
}
