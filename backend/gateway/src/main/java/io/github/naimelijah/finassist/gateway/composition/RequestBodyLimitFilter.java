package io.github.naimelijah.finassist.gateway.composition;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses every request whose body is larger than {@value #MAX_REQUEST_BODY_BYTES} bytes, before
 * the security chain or the relay reads it.
 *
 * <p>A deliberate copy of the ledger's {@code RequestBodyLimitFilter}, as {@link
 * HostAllowListFilter} is a copy of the ledger's own: the two deployables share no code. The ledger
 * and the assistant each refuse a body over the same limit, but the relay streams a body on as it
 * arrives, so without this the refusal would come from the service part-way through that stream,
 * and a body far over the limit could end with the connection closed and a {@code 502} rather than
 * the documented {@code 413}. Nothing the gateway serves takes a body of more than a few kilobytes.
 *
 * <p>A declared {@code Content-Length} over the limit is refused unread. A body of undeclared
 * length, sent chunked, is read here up to one byte past the limit: over it, the request is
 * refused; within it, the request goes on carrying the bytes already read, which the relay then
 * sends on whole. A declared length within the limit is passed on as it is, because the container
 * never reads past the length a request declares.
 *
 * <p>A refusal is {@code 413} with the {@code CONTENT_TOO_LARGE} problem the ledger and the
 * assistant answer for the same limit, written through {@link GatewayProblems} because this runs
 * before the dispatcher, and is logged once at WARN with nothing from the request.
 *
 * <p>{@link GatewayConfiguration} registers it after the Host allow-list and the request
 * observation, and ahead of every filter that could read a body, Spring Security's and the
 * form-content filter's included.
 */
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    /** The largest request body the gateway reads: 64 KiB, the limit the ledger and the assistant apply. */
    public static final int MAX_REQUEST_BODY_BYTES = 64 * 1024;

    /** What {@link HttpServletRequest#getContentLengthLong()} answers when a request declares no length. */
    private static final long UNDECLARED_LENGTH = -1;

    private static final Logger LOG = LoggerFactory.getLogger(RequestBodyLimitFilter.class);

    private static final String REFUSAL_DETAIL =
            "The request body is larger than the " + MAX_REQUEST_BODY_BYTES + " bytes this gateway accepts.";

    /** Serialised once, because nothing in it varies. */
    private static final byte[] REFUSAL =
            GatewayProblems.write(HttpStatus.CONTENT_TOO_LARGE, REFUSAL_DETAIL, GatewayProblems.CONTENT_TOO_LARGE);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAX_REQUEST_BODY_BYTES) {
            refuse(response);
            return;
        }
        if (declaredLength != UNDECLARED_LENGTH) {
            chain.doFilter(request, response);
            return;
        }
        Optional<byte[]> body = readWithinLimit(request);
        if (body.isEmpty()) {
            refuse(response);
            return;
        }
        chain.doFilter(new ReadBodyRequest(request, body.get()), response);
    }

    /** The whole body, or nothing if it runs past the limit; at most one byte past it is ever read. */
    private static Optional<byte[]> readWithinLimit(HttpServletRequest request) throws IOException {
        byte[] read = request.getInputStream().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
        return read.length > MAX_REQUEST_BODY_BYTES ? Optional.empty() : Optional.of(read);
    }

    private static void refuse(HttpServletResponse response) throws IOException {
        LOG.warn("Refused a request whose body is larger than {} bytes", MAX_REQUEST_BODY_BYTES);
        GatewayProblems.send(response, HttpStatus.CONTENT_TOO_LARGE, REFUSAL);
    }

    /**
     * The request as it arrived, with the body this filter has already read in its place. The
     * length is now known, so it is reported, and both the stream and the reader serve those same
     * bytes.
     */
    private static final class ReadBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        ReadBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ReadBodyInputStream(body);
        }

        /** Decoded in the request's character encoding, or in UTF-8 if it names none. */
        @Override
        public BufferedReader getReader() throws UnsupportedEncodingException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(body), encoding == null ? StandardCharsets.UTF_8.name() : encoding));
        }
    }

    /** A body already in memory, so it is always ready and never blocks. */
    private static final class ReadBodyInputStream extends ServletInputStream {

        private final ByteArrayInputStream bytes;

        ReadBodyInputStream(byte[] body) {
            this.bytes = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return bytes.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return bytes.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
            return bytes.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        /** Non-blocking reads are not offered: nothing the gateway runs starts one. */
        @Override
        public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException("This body is already read; it offers no non-blocking reads");
        }
    }
}
