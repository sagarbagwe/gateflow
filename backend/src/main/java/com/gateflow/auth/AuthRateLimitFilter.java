package com.gateflow.auth;

import com.gateflow.http.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Set;

public class AuthRateLimitFilter extends OncePerRequestFilter {
    private static final Set<String> ROUTES = Set.of("/api/v1/auth/login", "/api/v1/auth/signup");
    private final AuthRateLimiter limiter;
    private final Problems problems;
    public AuthRateLimitFilter(AuthRateLimiter limiter, Problems problems) { this.limiter = limiter; this.problems = problems; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !ROUTES.contains(request.getServletPath());
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            // Do not trust client-supplied Forwarded/X-Forwarded-For headers.
            if (!limiter.allow(request.getServletPath(), request.getRemoteAddr())) {
                response.setHeader("Retry-After", Long.toString(limiter.retryAfterSeconds()));
                problems.write(request, response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many authentication attempts");
                return;
            }
        } catch (DataAccessException unavailable) {
            problems.write(request, response, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Authentication storage is unavailable");
            return;
        }
        chain.doFilter(request, response);
    }
}
