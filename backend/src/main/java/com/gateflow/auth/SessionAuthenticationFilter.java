package com.gateflow.auth;

import com.gateflow.http.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

public class SessionAuthenticationFilter extends OncePerRequestFilter {
    private final SessionService sessions;
    private final AuthCookies cookies;
    private final Problems problems;
    public SessionAuthenticationFilter(SessionService sessions, AuthCookies cookies, Problems problems) {
        this.sessions = sessions; this.cookies = cookies; this.problems = problems;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            sessions.authenticate(cookies.read(request)).ifPresent(principal -> {
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
                SecurityContextHolder.setContext(context);
            });
        } catch (DataAccessException unavailable) {
            problems.write(request, response, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Authentication storage is unavailable");
            return;
        }
        chain.doFilter(request, response);
    }
}
