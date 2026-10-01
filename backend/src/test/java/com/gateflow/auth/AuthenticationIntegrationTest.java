package com.gateflow.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.net.*;
import java.net.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gateflow.auth.cookie-secure=false", "gateflow.auth.login-limit=4", "gateflow.auth.signup-limit=10"})
class AuthenticationIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:17-bookworm@sha256:639ab7ceb90e13123085b741fb31ef493fba25463002f6da665352e7b534b652").asCompatibleSubstituteFor("postgres"))
        .withCommand("postgres", "-c", "log_error_verbosity=terse", "-c", "log_min_error_statement=panic", "-c", "log_parameter_max_length_on_error=0")
        .withDatabaseName("gateflow_auth_test").withUsername("gateflow_test").withPassword(UUID.randomUUID().toString());
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PasswordEncoder encoder;
    @Autowired AuthRateLimiter limiter;
    CookieManager cookieManager;
    HttpClient client;
    private static final String PASSWORD = "integration-password-2026";
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM auth_rate_limit_buckets");
        jdbc.update("DELETE FROM users");
        cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        client = HttpClient.newBuilder().cookieHandler(cookieManager).build();
    }
    URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api/v1/auth" + path); }
    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(String path, Map<String, String> payload) throws Exception {
        var csrf = mapper.readTree(get("/csrf").body());
        return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> signup() throws Exception {
        return post("/signup", Map.of("email", "user@example.test", "displayName", "User", "password", PASSWORD));
    }
    String sessionToken() {
        return cookieManager.getCookieStore().getCookies().stream().filter(c -> c.getName().equals(AuthCookies.SESSION_NAME))
                .findFirst().orElseThrow().getValue();
    }
    HttpResponse<String> replay(String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/me"))
                .header("Cookie", AuthCookies.SESSION_NAME + "=" + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    void problem(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("requestId").asText()).isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
        assertThat(response.body()).doesNotContain(PASSWORD, "password_hash", "token_hash");
    }
    @Test void anonymousProtectedRequestGetsJson401() throws Exception {
        problem(get("/me"), 401, "AUTHENTICATION_REQUIRED");
    }
    @Test void signupHashesPasswordAndTokenAndCreatesAuthenticatedCookie() throws Exception {
        var csrfResponse = get("/csrf");
        assertThat(csrfResponse.headers().allValues("Set-Cookie").stream()
                .anyMatch(header -> header.startsWith("XSRF-TOKEN=") && header.contains("SameSite=Lax"))).isTrue();
        var response = signup();
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("user@example.test").doesNotContain("password", "token");
        assertThat(response.headers().allValues("Set-Cookie").toString()).contains("HttpOnly", "SameSite=Lax")
                .doesNotContain("JSESSIONID");
        String stored = jdbc.queryForObject("SELECT password_hash FROM users", String.class);
        assertThat(stored).startsWith("$2a$12$").isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, stored)).isTrue();
        String raw = sessionToken();
        assertThat(jdbc.queryForObject("SELECT token_hash FROM auth_sessions", String.class))
                .isEqualTo(new TokenCodec().hash(raw)).isNotEqualTo(raw);
        assertThat(get("/me").statusCode()).isEqualTo(200);
    }
    @Test void caseInsensitiveLoginRotatesAndRevokesOldSession() throws Exception {
        signup(); String old = sessionToken();
        var login = post("/login", Map.of("email", "USER@EXAMPLE.TEST", "password", PASSWORD));
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(sessionToken()).isNotEqualTo(old);
        problem(replay(old), 401, "AUTHENTICATION_REQUIRED");
        assertThat(get("/me").statusCode()).isEqualTo(200);
    }
    @Test void logoutRevokesReplayAndIsIdempotentWithCsrf() throws Exception {
        signup(); String old = sessionToken();
        assertThat(post("/logout", Map.of()).statusCode()).isEqualTo(204);
        problem(replay(old), 401, "AUTHENTICATION_REQUIRED");
        problem(get("/me"), 401, "AUTHENTICATION_REQUIRED");
        assertThat(post("/logout", Map.of()).statusCode()).isEqualTo(204);
    }
    @Test void missingAndInvalidCsrfAreRejected() throws Exception {
        var without = client.send(HttpRequest.newBuilder(uri("/login")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}" )).build(), HttpResponse.BodyHandlers.ofString());
        problem(without, 403, "ACCESS_DENIED");
        get("/csrf");
        var invalid = client.send(HttpRequest.newBuilder(uri("/login")).header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", "invalid").POST(HttpRequest.BodyPublishers.ofString("{}" )).build(), HttpResponse.BodyHandlers.ofString());
        problem(invalid, 403, "ACCESS_DENIED");
    }
    @Test void validationRejectsWeakPasswordsAndOversizedUtf8WithoutEchoingSecrets() throws Exception {
        problem(post("/signup", Map.of("email", "invalid", "displayName", "User", "password", "short")), 400, "VALIDATION_FAILED");
        problem(post("/signup", Map.of("email", "u@example.test", "displayName", "User", "password", "😀".repeat(20))), 400, "VALIDATION_FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
    }
    @Test void unsupportedSignupFieldsAreRejected() throws Exception {
        problem(post("/signup", Map.of("email", "u@example.test", "displayName", "User", "password", PASSWORD, "status", "ADMIN")), 400, "INVALID_JSON");
    }
    @Test void csrfTokenRemainsUsableAcrossAuthenticatedReads() throws Exception {
        signup();
        var csrf = mapper.readTree(get("/csrf").body());
        var read = get("/me");
        assertThat(read.headers().allValues("Set-Cookie").stream()
                .anyMatch(header -> header.startsWith("XSRF-TOKEN=") && header.contains("Max-Age=0"))).isFalse();
        var logout = client.send(HttpRequest.newBuilder(uri("/logout"))
                .header("X-XSRF-TOKEN", csrf.get("token").asText())
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(logout.statusCode()).isEqualTo(204);
    }
    @Test void malformedJsonAndFrameworkErrorsUseAppropriateStatuses() throws Exception {
        signup();
        var csrf = mapper.readTree(get("/csrf").body());
        var malformed = client.send(HttpRequest.newBuilder(uri("/signup"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf.get("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":" )).build(), HttpResponse.BodyHandlers.ofString());
        problem(malformed, 400, "INVALID_JSON");
        var unsupported = client.send(HttpRequest.newBuilder(uri("/signup"))
                .header("Content-Type", "text/plain").header("X-XSRF-TOKEN", csrf.get("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString("unsupported")).build(), HttpResponse.BodyHandlers.ofString());
        problem(unsupported, 415, "HTTP_ERROR");
        var wrongMethod = get("/signup");
        problem(wrongMethod, 405, "HTTP_ERROR");
        assertThat(wrongMethod.headers().firstValue("Allow").orElse("")).contains("POST");
        problem(get("/unknown"), 404, "HTTP_ERROR");
    }
    @Test void duplicateSignupCannotCreateAdditionalUserOrSession() throws Exception {
        signup();
        problem(signup(), 409, "ACCOUNT_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions", Integer.class)).isEqualTo(1);
    }
    @Test void unknownAndWrongPasswordUseSameGenericFailureAndPreserveExistingSession() throws Exception {
        signup(); String old = sessionToken();
        var unknown = post("/login", Map.of("email", "missing@example.test", "password", "incorrect-password"));
        var wrong = post("/login", Map.of("email", "user@example.test", "password", "incorrect-password"));
        problem(unknown, 401, "INVALID_CREDENTIALS"); problem(wrong, 401, "INVALID_CREDENTIALS");
        assertThat(mapper.readTree(unknown.body()).get("detail")).isEqualTo(mapper.readTree(wrong.body()).get("detail"));
        assertThat(sessionToken()).isEqualTo(old);
        assertThat(get("/me").statusCode()).isEqualTo(200);
    }
    @Test void expiredSessionIsNotAuthenticated() throws Exception {
        signup();
        jdbc.update("UPDATE auth_sessions SET created_at=CURRENT_TIMESTAMP-INTERVAL '2 hours', expires_at=CURRENT_TIMESTAMP-INTERVAL '1 hour'");
        problem(get("/me"), 401, "AUTHENTICATION_REQUIRED");
    }
    @Test void disablingAccountImmediatelyInvalidatesExistingSessionAndLogin() throws Exception {
        signup();
        jdbc.update("UPDATE users SET status='DISABLED'");
        problem(get("/me"), 401, "AUTHENTICATION_REQUIRED");
        problem(post("/login", Map.of("email", "user@example.test", "password", PASSWORD)), 401, "INVALID_CREDENTIALS");
    }
    @Test void validFormatUnknownAndMalformedTokensAreRejected() throws Exception {
        problem(replay(new TokenCodec().generate()), 401, "AUTHENTICATION_REQUIRED");
        problem(replay("short"), 401, "AUTHENTICATION_REQUIRED");
    }
    @Test void csrfFormParameterCannotReplaceRequiredHeader() throws Exception {
        signup();
        var csrf = mapper.readTree(get("/csrf").body());
        var response = client.send(HttpRequest.newBuilder(uri("/logout"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + csrf.get("token").asText())).build(), HttpResponse.BodyHandlers.ofString());
        problem(response, 403, "ACCESS_DENIED");
        assertThat(get("/me").statusCode()).isEqualTo(200);
    }
    @Test void signupAttemptsAreRateLimitedEvenWhenValidationFails() throws Exception {
        for (int i = 0; i < 10; i++) {
            problem(post("/signup", Map.of("email", "u@example.test", "displayName", "User", "password", "short")), 400, "VALIDATION_FAILED");
        }
        problem(post("/signup", Map.of("email", "u@example.test", "displayName", "User", "password", "short")), 429, "RATE_LIMITED");
    }
    @Test void accountLimiterCountsConcurrentAttemptsAtomically() throws Exception {
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(6)) {
            var jobs = new ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int i = 0; i < 24; i++) jobs.add(() -> limiter.allowLoginAccount("target@example.test"));
            int allowed = 0;
            for (var future : executor.invokeAll(jobs)) if (future.get()) allowed++;
            assertThat(allowed).isEqualTo(12); // 3x the configured IP login limit of 4.
        }
    }
    @Test void accountLimitPersistsAcrossFailedLogins() throws Exception {
        for (int i = 0; i < 12; i++) {
            jdbc.update("DELETE FROM auth_rate_limit_buckets WHERE key_hash=?", new TokenCodec().hash("/api/v1/auth/login\n127.0.0.1"));
            problem(post("/login", Map.of("email", "target@example.test", "password", "wrong-password")), 401, "INVALID_CREDENTIALS");
        }
        jdbc.update("DELETE FROM auth_rate_limit_buckets WHERE key_hash=?", new TokenCodec().hash("/api/v1/auth/login\n127.0.0.1"));
        problem(post("/login", Map.of("email", "target@example.test", "password", "wrong-password")), 429, "RATE_LIMITED");
    }
    @Test void loginLimitCannotBeBypassedUsingForwardedHeadersAndWindowResets() throws Exception {
        for (int i = 0; i < 4; i++) {
            var csrf = mapper.readTree(get("/csrf").body());
            var response = client.send(HttpRequest.newBuilder(uri("/login"))
                    .header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf.get("token").asText())
                    .header("X-Forwarded-For", "192.0.2." + i)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("email", "missing@example.test", "password", "wrong-password"))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(401);
        }
        var limited = post("/login", Map.of("email", "missing@example.test", "password", "wrong-password"));
        problem(limited, 429, "RATE_LIMITED");
        assertThat(limited.headers().firstValue("Retry-After")).contains("60");
        jdbc.update("UPDATE auth_rate_limit_buckets SET window_started_at=CURRENT_TIMESTAMP-INTERVAL '2 minutes'");
        problem(post("/login", Map.of("email", "missing@example.test", "password", "wrong-password")), 401, "INVALID_CREDENTIALS");
    }
}
