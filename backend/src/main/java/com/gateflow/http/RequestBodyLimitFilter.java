package com.gateflow.http;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Bound JSON allocation before MVC deserialization. Current APIs use synchronous handling. */
public class RequestBodyLimitFilter extends OncePerRequestFilter {
    public static final int MAX_BYTES = 256 * 1024;
    private final Problems problems;

    public RequestBodyLimitFilter(Problems problems) {
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !Set.of("POST", "PUT", "PATCH").contains(request.getMethod());
    }

    private void reject(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        problems.write(
                request,
                response,
                HttpStatus.PAYLOAD_TOO_LARGE,
                "PAYLOAD_TOO_LARGE",
                "JSON request body exceeds 256 KiB");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BYTES) {
            reject(request, response);
            return;
        }
        byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            reject(request, response);
            return;
        }
        chain.doFilter(
                new HttpServletRequestWrapper(request) {
                    @Override
                    public int getContentLength() {
                        return bytes.length;
                    }

                    @Override
                    public long getContentLengthLong() {
                        return bytes.length;
                    }

                    @Override
                    public ServletInputStream getInputStream() {
                        var stream = new ByteArrayInputStream(bytes);
                        return new ServletInputStream() {
                            @Override
                            public int read() {
                                return stream.read();
                            }

                            @Override
                            public int read(byte[] b, int off, int len) {
                                return stream.read(b, off, len);
                            }

                            @Override
                            public boolean isFinished() {
                                return stream.available() == 0;
                            }

                            @Override
                            public boolean isReady() {
                                return true;
                            }

                            @Override
                            public void setReadListener(ReadListener listener) {
                                throw new IllegalStateException(
                                        "Asynchronous body reading is not supported by these JSON"
                                            + " APIs");
                            }
                        };
                    }

                    @Override
                    public BufferedReader getReader() {
                        return new BufferedReader(
                                new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
                    }
                },
                response);
    }
}
