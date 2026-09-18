package se.rocketscien.harness.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import se.rocketscien.harness.config.LimitsProperties;

import java.io.IOException;
import java.io.InputStream;

/**
 * Лимит тела запроса (api-contracts §0, {@code harness.limits.body}): превышение →
 * {@code 413 payload-too-large}. Известный Content-Length проверяется до диспетчеризации;
 * потоковая передача (chunked) отсекается лимитирующим обёрточным потоком —
 * {@link PayloadTooLargeException} ловит {@link ApiExceptionHandler}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PayloadSizeFilter extends OncePerRequestFilter {

    private final LimitsProperties limits;
    private final ApiProblemWriter problemWriter;

    @Override
    @SneakyThrows
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
        long limitBytes = limits.body().toBytes();
        long contentLength = request.getContentLengthLong();
        if (contentLength > limitBytes) {
            log.debug("Тело {} байт превышает лимит {} — 413 до диспетчеризации", contentLength, limits.body());
            problemWriter.write(response, HttpStatus.PAYLOAD_TOO_LARGE,
                    ProblemCodes.PAYLOAD_TOO_LARGE, "Тело запроса превышает лимит " + limits.body(), null);
            return;
        }
        if (contentLength < 0) {
            chain.doFilter(new BoundedRequest(request, limitBytes), response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static final class BoundedRequest extends HttpServletRequestWrapper {

        private final long limitBytes;

        private BoundedRequest(HttpServletRequest request, long limitBytes) {
            super(request);
            this.limitBytes = limitBytes;
        }

        @Override
        @SneakyThrows
        public ServletInputStream getInputStream() {
            return new BoundedServletInputStream(new BoundedInputStream(super.getInputStream(), limitBytes));
        }
    }

    /** Отсекает чтение за пределом — выброс до накопления тела в памяти. */
    private static final class BoundedInputStream extends InputStream {

        private final InputStream delegate;
        private final long limitBytes;
        private long readTotal;

        private BoundedInputStream(InputStream delegate, long limitBytes) {
            this.delegate = delegate;
            this.limitBytes = limitBytes;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0 && ++readTotal > limitBytes) {
                throw tooLarge();
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                readTotal += n;
                if (readTotal > limitBytes) {
                    throw tooLarge();
                }
            }
            return n;
        }

        private PayloadTooLargeException tooLarge() {
            return new PayloadTooLargeException("Потоковое тело превысило лимит " + limitBytes + " байт");
        }
    }

    private static final class BoundedServletInputStream extends ServletInputStream {

        private final InputStream delegate;

        private BoundedServletInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isFinished() {
            try {
                return delegate.available() == 0;
            } catch (IOException e) {
                return true;
            }
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("Асинхронное чтение не поддерживается");
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return delegate.read(buffer, offset, length);
        }
    }
}
