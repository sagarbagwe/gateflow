package com.gateflow.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class RequestIdFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = "gateflow.requestId";
    private static final Set<String> AUTH_PATHS = Set.of("/api/v1/auth/csrf", "/api/v1/auth/signup",
            "/api/v1/auth/login", "/api/v1/auth/logout", "/api/v1/auth/me");
    private static final Logger LOG = LoggerFactory.getLogger(RequestIdFilter.class);
    private static String safePath(String path) {
        if (AUTH_PATHS.contains(path) || "/api/v1/organizations".equals(path)) return path;
        if (path.startsWith("/api/v1/organizations/")) return "/api/v1/organizations/{resource}";
        return "<unmatched>";
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String id = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader("X-Request-ID", id);
        long start = System.nanoTime();
        try { chain.doFilter(request, response); }
        finally {
            LOG.info("request_id={} method={} path={} status={} duration_ms={}", id, request.getMethod(),
                    safePath(request.getRequestURI()), response.getStatus(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }
}
