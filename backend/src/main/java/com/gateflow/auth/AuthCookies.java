package com.gateflow.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Arrays;

@Component
public class AuthCookies {
    public static final String SESSION_NAME = "GATEFLOW_SESSION";
    private final AuthProperties properties;
    public AuthCookies(AuthProperties properties) { this.properties = properties; }
    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        var matches = Arrays.stream(cookies).filter(c -> sessionName().equals(c.getName())).toList();
        return matches.size() == 1 ? matches.getFirst().getValue() : null;
    }
    private String sessionName() { return properties.cookieSecure() ? "__Host-" + SESSION_NAME : SESSION_NAME; }
    public void set(HttpServletResponse response, String token) {
        write(response, token, properties.sessionTtl());
    }
    public void clear(HttpServletResponse response) { write(response, "", Duration.ZERO); }
    private void write(HttpServletResponse response, String token, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(sessionName(), token)
                .httpOnly(true).secure(properties.cookieSecure()).sameSite("Lax")
                .path("/").maxAge(maxAge).build().toString());
    }
}
