package com.gateflow.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AuthStorageCleanup {
    private static final Logger LOG = LoggerFactory.getLogger(AuthStorageCleanup.class);
    private final JdbcTemplate jdbc;
    public AuthStorageCleanup(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Scheduled(initialDelayString = "PT1H", fixedDelayString = "PT1H")
    public void cleanup() {
        // Bounded batches; multiple replicas may run safely, though row-lock contention is possible.
        int sessions = jdbc.update("""
                DELETE FROM auth_sessions WHERE id IN
                  (SELECT id FROM auth_sessions WHERE expires_at < CURRENT_TIMESTAMP
                   OR revoked_at < CURRENT_TIMESTAMP - INTERVAL '1 day' LIMIT 1000)
                  AND (expires_at < CURRENT_TIMESTAMP OR revoked_at < CURRENT_TIMESTAMP - INTERVAL '1 day')
                """);
        int buckets = jdbc.update("""
                DELETE FROM auth_rate_limit_buckets WHERE key_hash IN
                  (SELECT key_hash FROM auth_rate_limit_buckets
                   WHERE window_started_at < CURRENT_TIMESTAMP - INTERVAL '2 hours' LIMIT 1000)
                  AND window_started_at < CURRENT_TIMESTAMP - INTERVAL '2 hours'
                """);
        LOG.info("auth_cleanup deleted_sessions={} deleted_buckets={}", sessions, buckets);
    }
}
