package com.gateflow.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateflow.http.Problems;
import com.gateflow.http.RequestIdFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageFailureFilterTest {
    private final Problems problems = new Problems(new ObjectMapper());
    private MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setAttribute(RequestIdFilter.ATTRIBUTE, "test-request-id");
        return request;
    }
    @Test void sessionStorageFailureReturns503AndDoesNotAuthenticate() throws Exception {
        var sessions = mock(SessionService.class);
        var cookies = mock(AuthCookies.class);
        String token = new TokenCodec().generate();
        when(cookies.read(any())).thenReturn(token);
        when(sessions.authenticate(token)).thenThrow(new DataAccessResourceFailureException("sensitive-database-detail"));
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        new SessionAuthenticationFilter(sessions, cookies, problems).doFilter(request("GET", "/api/v1/auth/me"), response, chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("SERVICE_UNAVAILABLE").doesNotContain("sensitive-database-detail");
        verifyNoInteractions(chain);
    }
    @Test void limiterStorageFailureFailsClosedWith503() throws Exception {
        var limiter = mock(AuthRateLimiter.class);
        when(limiter.allow(anyString(), anyString())).thenThrow(new DataAccessResourceFailureException("sensitive-database-detail"));
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        new AuthRateLimitFilter(limiter, problems).doFilter(request("POST", "/api/v1/auth/login"), response, chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).doesNotContain("sensitive-database-detail");
        verifyNoInteractions(chain);
    }
    @Test void exhaustedLimitProvidesRetryAfterWithoutCallingController() throws Exception {
        var limiter = mock(AuthRateLimiter.class);
        when(limiter.allow(anyString(), anyString())).thenReturn(false);
        when(limiter.retryAfterSeconds()).thenReturn(60L);
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        new AuthRateLimitFilter(limiter, problems).doFilter(request("POST", "/api/v1/auth/login"), response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        verifyNoInteractions(chain);
    }
}
