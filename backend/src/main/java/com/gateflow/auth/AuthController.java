package com.gateflow.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    private final SessionService sessions;
    private final AuthCookies cookies;
    private final CsrfTokenRepository csrf;
    public AuthController(AuthService auth, SessionService sessions, AuthCookies cookies, CsrfTokenRepository csrf) {
        this.auth = auth; this.sessions = sessions; this.cookies = cookies; this.csrf = csrf;
    }
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
    @PostMapping(value = "/signup", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public UserPrincipal signup(@Valid @RequestBody AuthRequests.Signup body,
            HttpServletRequest request, HttpServletResponse response) {
        return establish(auth.signup(body, cookies.read(request)), request, response);
    }
    @PostMapping(value = "/login", consumes = "application/json")
    public UserPrincipal login(@Valid @RequestBody AuthRequests.Login body,
            HttpServletRequest request, HttpServletResponse response) {
        return establish(auth.login(body, cookies.read(request)), request, response);
    }
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.revoke(cookies.read(request));
        cookies.clear(response);
        csrf.saveToken(null, request, response);
    }
    @GetMapping("/me")
    public UserPrincipal me(@AuthenticationPrincipal UserPrincipal principal) { return principal; }
    private UserPrincipal establish(AuthService.Result result, HttpServletRequest request, HttpServletResponse response) {
        cookies.set(response, result.token());
        csrf.saveToken(null, request, response);
        return result.user();
    }
}
