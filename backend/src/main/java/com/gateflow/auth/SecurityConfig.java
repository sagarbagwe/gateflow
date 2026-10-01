package com.gateflow.auth;

import com.gateflow.http.Problems;
import com.gateflow.http.RequestBodyLimitFilter;
import com.gateflow.http.RequestIdFilter;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.*;

@Configuration
public class SecurityConfig {
    private static final int BCRYPT_COST = 12;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_COST);
    }

    @Bean
    public CsrfTokenRepository csrfRepository(AuthProperties properties) {
        var repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(properties.cookieSecure() ? "__Host-XSRF-TOKEN" : "XSRF-TOKEN");
        repository.setCookieCustomizer(
                cookie -> cookie.secure(properties.cookieSecure()).sameSite("Lax").path("/"));
        return repository;
    }

    @Bean
    public SecurityFilterChain security(
            HttpSecurity http,
            CsrfTokenRepository csrf,
            SessionService sessions,
            AuthCookies cookies,
            AuthRateLimiter limiter,
            Problems problems)
            throws Exception {
        http.httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                // Restoring an opaque session is NOT a fresh login. Avoid servlet session
                // strategies that would clear CSRF on every restored authenticated request.
                .sessionManagement(AbstractHttpConfigurer::disable)
                .securityContext(
                        config ->
                                config.securityContextRepository(
                                        new NullSecurityContextRepository()))
                .csrf(
                        config ->
                                config.csrfTokenRepository(csrf)
                                        .csrfTokenRequestHandler(
                                                new CsrfTokenRequestAttributeHandler() {
                                                    @Override
                                                    public String resolveCsrfTokenValue(
                                                            HttpServletRequest request,
                                                            CsrfToken token) {
                                                        return request.getHeader(
                                                                token
                                                                        .getHeaderName()); // JSON
                                                                                           // SPA:
                                                                                           // never
                                                                                           // accept
                                                                                           // form-token substitution.
                                                    }
                                                }))
                .exceptionHandling(
                        config ->
                                config.authenticationEntryPoint(
                                                (request, response, error) ->
                                                        problems.write(
                                                                request,
                                                                response,
                                                                HttpStatus.UNAUTHORIZED,
                                                                "AUTHENTICATION_REQUIRED",
                                                                "Authentication required"))
                                        .accessDeniedHandler(
                                                (request, response, error) ->
                                                        problems.write(
                                                                request,
                                                                response,
                                                                HttpStatus.FORBIDDEN,
                                                                "ACCESS_DENIED",
                                                                "Request is not allowed")))
                .authorizeHttpRequests(
                        config ->
                                config.requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/v1/auth/signup",
                                                "/api/v1/auth/login",
                                                "/api/v1/auth/logout")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .addFilterBefore(new RequestIdFilter(), SecurityContextHolderFilter.class)
                .addFilterAfter(new AuthRateLimitFilter(limiter, problems), CsrfFilter.class)
                .addFilterBefore(
                        new SessionAuthenticationFilter(sessions, cookies, problems),
                        AnonymousAuthenticationFilter.class)
                .addFilterAfter(new RequestBodyLimitFilter(problems), AuthorizationFilter.class);
        return http.build();
    }
}
