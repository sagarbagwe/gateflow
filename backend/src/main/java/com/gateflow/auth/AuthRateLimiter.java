package com.gateflow.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuthRateLimiter {
    private static final int ACCOUNT_LIMIT_MULTIPLIER = 3;
    private final JdbcTemplate jdbc;
    private final TokenCodec codec;
    private final AuthProperties properties;
    public AuthRateLimiter(JdbcTemplate jdbc, TokenCodec codec, AuthProperties properties) {
        this.jdbc = jdbc; this.codec = codec; this.properties = properties;
    }
    public boolean allow(String route, String remoteAddress) {
        int limit = route.endsWith("/signup") ? properties.signupLimit() : properties.loginLimit();
        return consume(route + "\n" + remoteAddress, limit);
    }
    public boolean allowLoginAccount(String normalizedEmail) {
        return consume("login-account\n" + normalizedEmail, properties.loginLimit() * ACCOUNT_LIMIT_MULTIPLIER);
    }
    private boolean consume(String key, int limit) {
        int attempts = jdbc.queryForObject("""
                INSERT INTO auth_rate_limit_buckets (key_hash, window_started_at, attempts)
                VALUES (?, CURRENT_TIMESTAMP, 1)
                ON CONFLICT (key_hash) DO UPDATE SET
                  attempts = CASE WHEN auth_rate_limit_buckets.window_started_at <=
                    CURRENT_TIMESTAMP - (? * INTERVAL '1 second') THEN 1
                    ELSE LEAST(auth_rate_limit_buckets.attempts + 1, ?) END,
                  window_started_at = CASE WHEN auth_rate_limit_buckets.window_started_at <=
                    CURRENT_TIMESTAMP - (? * INTERVAL '1 second') THEN CURRENT_TIMESTAMP
                    ELSE auth_rate_limit_buckets.window_started_at END
                RETURNING attempts
                """, Integer.class, codec.hash(key),
                properties.rateWindow().toSeconds(), limit + 1, properties.rateWindow().toSeconds());
        return attempts <= limit;
    }
    public long retryAfterSeconds() { return properties.rateWindow().toSeconds(); }
}
