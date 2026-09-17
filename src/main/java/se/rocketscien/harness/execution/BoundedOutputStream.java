package se.rocketscien.harness.execution;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * OutputStream с жёстким пределом накопления (C-J-1): после {@code limit} байт данные
 * отбрасываются (дренаж без роста памяти), выставляется {@link #isTruncated()}. Используется
 * для stdout/stderr docker-exec, чтобы агентские {@code bash("yes")}/{@code cat big} не валили
 * оркестратор OOM до применения {@code harness.limits.tool-output}.
 */
public class BoundedOutputStream extends OutputStream {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final long limit;
    private Runnable onOverflow;
    private boolean truncated;

    public BoundedOutputStream(long limit) {
        this.limit = Math.max(0, limit);
    }

    public void setOnOverflow(Runnable onOverflow) {
        this.onOverflow = onOverflow;
    }

    @Override
    public void write(int b) {
        if (buffer.size() < limit) {
            buffer.write(b);
        } else {
            markTruncated();
        }
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
        int remaining = (int) Math.max(0, limit - buffer.size());
        if (remaining > 0) {
            buffer.write(bytes, offset, Math.min(remaining, length));
        }
        if (length > remaining) {
            markTruncated();
        }
    }

    public boolean isTruncated() {
        return truncated;
    }

    public int size() {
        return buffer.size();
    }

    public String asString() {
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private void markTruncated() {
        if (!truncated) {
            truncated = true;
            if (onOverflow != null) {
                onOverflow.run();
            }
        }
    }
}
