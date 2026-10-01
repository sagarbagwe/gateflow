package com.gateflow.cache;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Cache-only adapter: Redis loss never grants authority and never blocks a DB fallback. */
@Component
public class RedisPolicyCache {
    private static final Logger LOG = LoggerFactory.getLogger(RedisPolicyCache.class);
    // Bound data before transferring it. Atomic and constant-time aside from bounded GET and
    // nonblocking UNLINK.
    private static final DefaultRedisScript<String> BOUNDED_GET =
            new DefaultRedisScript<>(
                    """
                    local t=redis.call('TYPE',KEYS[1]).ok
if t ~= 'none' and t ~= 'string' then redis.call('UNLINK',KEYS[1]); return nil end
if redis.call('STRLEN',KEYS[1]) > tonumber(ARGV[1]) then
                      redis.call('UNLINK',KEYS[1]); return nil
                    end
                    return redis.call('GET',KEYS[1])
""",
                    String.class);
    private final StringRedisTemplate redis;
    private final WorkflowCacheProperties properties;
    private final LongSupplier nanos;
    private final AtomicLong nextAttempt = new AtomicLong();

    @Autowired
    public RedisPolicyCache(StringRedisTemplate redis, WorkflowCacheProperties properties) {
        this(redis, properties, System::nanoTime);
    }

    RedisPolicyCache(
            StringRedisTemplate redis, WorkflowCacheProperties properties, LongSupplier nanos) {
        this.redis = redis;
        this.properties = properties;
        this.nanos = nanos;
    }

    public static String key(UUID org, UUID definition, UUID version) {
        return "gateflow:workflow-policy:v1:" + org + ":" + definition + ":" + version;
    }

    boolean available() {
        long deadline = nextAttempt.get();
        return properties.enabled() && (deadline == 0 || nanos.getAsLong() - deadline >= 0);
    }

    public Optional<String> get(String key) {
        if (!available()) return Optional.empty();
        try {
            return Optional.ofNullable(
                    redis.execute(
                            BOUNDED_GET, List.of(key), Integer.toString(properties.maxBytes())));
        } catch (DataAccessException unavailable) {
            failed(unavailable);
            return Optional.empty();
        }
    }

    public void put(String key, String value) {
        if (!available() || value.getBytes(StandardCharsets.UTF_8).length > properties.maxBytes())
            return;
        try {
            long jitter = Math.min(60000, properties.ttl().toMillis() / 10);
            Duration ttl =
                    properties.ttl().plusMillis(ThreadLocalRandom.current().nextLong(jitter + 1));
            redis.opsForValue()
                    .set(key, value, ttl); // Atomic value+expiry; never SET followed by EXPIRE.
        } catch (DataAccessException unavailable) {
            failed(unavailable);
        }
    }

    public void evict(String key) {
        if (!available()) return;
        try {
            redis.unlink(key);
        } catch (DataAccessException unavailable) {
            failed(unavailable);
        }
    }

    private void failed(DataAccessException error) {
        long now = nanos.getAsLong(), before = nextAttempt.get();
        if ((before == 0 || now - before >= 0)
                && nextAttempt.compareAndSet(before, now + properties.failureCooldown().toNanos()))
            LOG.warn(
                    "workflow_cache_unavailable action=database_fallback cause={}",
                    error.getClass().getSimpleName());
        // No keys, values, endpoints, passwords or exception messages in logs. One warning per
        // cooldown.
    }
}
