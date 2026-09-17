package se.rocketscien.harness.execution;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-J-1: bounded-захват вывода exec — память не растёт выше предела, излишек отбрасывается,
 * факт усечения доносится до результата.
 */
class BoundedOutputStreamTest {

    @Test
    void keepsAtMostLimitAndDiscardsRest() {
        BoundedOutputStream stream = new BoundedOutputStream(1024);
        byte[] chunk = new byte[256];

        for (int i = 0; i < 40_000; i++) {
            stream.write(chunk, 0, chunk.length);
        }

        assertThat(stream.size()).isEqualTo(1024);
        assertThat(stream.isTruncated()).isTrue();
    }

    @Test
    void belowLimitIsNotTruncated() {
        BoundedOutputStream stream = new BoundedOutputStream(1024);

        stream.write(new byte[500], 0, 500);

        assertThat(stream.size()).isEqualTo(500);
        assertThat(stream.isTruncated()).isFalse();
    }

    @Test
    void overflowHookFiresOnce() {
        BoundedOutputStream stream = new BoundedOutputStream(10);
        AtomicInteger hook = new AtomicInteger();
        stream.setOnOverflow(hook::incrementAndGet);

        stream.write(new byte[100], 0, 100);
        stream.write(new byte[100], 0, 100);

        assertThat(stream.isTruncated()).isTrue();
        assertThat(hook.get()).isEqualTo(1);
    }

    @Test
    void partialWriteFillsUpToLimit() {
        BoundedOutputStream stream = new BoundedOutputStream(10);

        stream.write(new byte[4], 0, 4);
        stream.write(new byte[100], 0, 100);

        assertThat(stream.size()).isEqualTo(10);
        assertThat(stream.isTruncated()).isTrue();
    }
}
