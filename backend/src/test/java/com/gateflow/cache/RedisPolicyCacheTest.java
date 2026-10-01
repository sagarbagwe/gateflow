package com.gateflow.cache;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

class RedisPolicyCacheTest {
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    final AtomicLong now = new AtomicLong(1000000);
    final WorkflowCacheProperties properties =
            new WorkflowCacheProperties(true, Duration.ofMinutes(10), Duration.ofSeconds(5), 65536);
    final RedisPolicyCache cache = new RedisPolicyCache(redis, properties, now::get);

    @BeforeEach
    void setup() {
        lenient().when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void keyIncludesTenantDefinitionVersionAndSchemaNamespace() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), v = UUID.randomUUID();
        assertThat(RedisPolicyCache.key(a, b, v))
                .isEqualTo("gateflow:workflow-policy:v1:" + a + ":" + b + ":" + v);
        assertThat(RedisPolicyCache.key(UUID.randomUUID(), b, v))
                .isNotEqualTo(RedisPolicyCache.key(a, b, v));
    }

    @Test
    void disabledMeansNoRedisInteraction() {
        var disabled =
                new RedisPolicyCache(
                        redis,
                        new WorkflowCacheProperties(
                                false, Duration.ofMinutes(1), Duration.ofSeconds(1), 65536),
                        now::get);
        assertThat(disabled.get("key")).isEmpty();
        disabled.put("key", "value");
        disabled.evict("key");
        verifyNoInteractions(redis, values);
    }

    @Test
    void boundedReadReturnsValue() {
        when(redis.execute(any(RedisScript.class), eq(List.of("key")), eq("65536")))
                .thenReturn("value");
        assertThat(cache.get("key")).contains("value");
    }

    @Test
    void absentIsMissNotFailure() {
        assertThat(cache.get("key")).isEmpty();
        assertThat(cache.available()).isTrue();
    }

    @Test
    void readOutageFallsBackAndSkipsWriteDuringCooldown() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("private endpoint details"));
        assertThat(cache.get("key")).isEmpty();
        cache.get("other");
        cache.put("key", "value");
        cache.evict("key");
        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), anyString());
        verifyNoInteractions(values);
        assertThat(cache.available()).isFalse();
    }

    @Test
    void retryAllowedAfterMonotonicCooldown() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("fixture"))
                .thenReturn("recovered");
        cache.get("key");
        now.addAndGet(Duration.ofSeconds(5).toNanos());
        assertThat(cache.get("key")).contains("recovered");
        assertThat(cache.available()).isTrue();
    }

    @Test
    void writesAtomicallyIncludeExpiryAndBoundedJitter() {
        cache.put("key", "value");
        var ttl = ArgumentCaptor.forClass(Duration.class);
        verify(values).set(eq("key"), eq("value"), ttl.capture());
        assertThat(ttl.getValue()).isBetween(Duration.ofMinutes(10), Duration.ofMinutes(11));
        verifyNoMoreInteractions(values);
    }

    @Test
    void oversizedUtf8ValueIsNotWritten() {
        cache.put("key", "東".repeat(22000));
        verifyNoInteractions(redis, values);
    }

    @Test
    void writeFailureIsNonfatalAndStartsCooldown() {
        doThrow(new DataAccessResourceFailureException("fixture"))
                .when(values)
                .set(anyString(), anyString(), any(Duration.class));
        assertThatCode(() -> cache.put("key", "value")).doesNotThrowAnyException();
        assertThat(cache.available()).isFalse();
    }

    @Test
    void deleteFailureIsNonfatal() {
        when(redis.unlink("key")).thenThrow(new DataAccessResourceFailureException("fixture"));
        assertThatCode(() -> cache.evict("key")).doesNotThrowAnyException();
    }

    @Test
    void durationValidationRejectsMissingTooSmallAndUnbounded() {
        assertThat(properties.isDurationsValid()).isTrue();
        for (var p :
                List.of(
                        new WorkflowCacheProperties(true, null, Duration.ofSeconds(1), 65536),
                        new WorkflowCacheProperties(
                                true, Duration.ofMillis(999), Duration.ofSeconds(1), 65536),
                        new WorkflowCacheProperties(
                                true, Duration.ofDays(2), Duration.ofSeconds(1), 65536),
                        new WorkflowCacheProperties(
                                true, Duration.ofSeconds(1), Duration.ofMinutes(2), 65536)))
            assertThat(p.isDurationsValid()).isFalse();
    }
}
