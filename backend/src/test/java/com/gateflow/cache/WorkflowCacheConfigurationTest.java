package com.gateflow.cache;

import static org.assertj.core.api.Assertions.*;

import io.lettuce.core.ClientOptions;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;

import java.time.Duration;

class WorkflowCacheConfigurationTest {
    @Test
    void clientOptionsPreserveConfiguredTimeoutsAndBoundQueues() {
        var props = new RedisProperties();
        props.setConnectTimeout(Duration.ofMillis(120));
        props.setTimeout(Duration.ofMillis(180));
        var builder = LettuceClientConfiguration.builder();
        new WorkflowCacheConfiguration().cacheRedisClientOptions(props).customize(builder);
        var options = builder.build().getClientOptions().orElseThrow();
        assertThat(options.getSocketOptions().getConnectTimeout())
                .isEqualTo(Duration.ofMillis(120));
        assertThat(
                        java.time.Duration.ofNanos(
                                options.getTimeoutOptions()
                                        .getSource()
                                        .getTimeUnit()
                                        .toNanos(
                                                options.getTimeoutOptions()
                                                        .getSource()
                                                        .getTimeout(null))))
                .isEqualTo(Duration.ofMillis(180));
        assertThat(options.getRequestQueueSize()).isEqualTo(256);
        assertThat(options.getDisconnectedBehavior())
                .isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
        assertThat(options.isAutoReconnect()).isTrue();
    }

    @Test
    void absentOptionalBootDurationsStillHaveShortFallbacks() {
        var props = new RedisProperties();
        props.setConnectTimeout(null);
        props.setTimeout(null);
        var builder = LettuceClientConfiguration.builder();
        new WorkflowCacheConfiguration().cacheRedisClientOptions(props).customize(builder);
        var options = builder.build().getClientOptions().orElseThrow();
        assertThat(options.getSocketOptions().getConnectTimeout())
                .isEqualTo(Duration.ofMillis(200));
        assertThat(
                        java.time.Duration.ofNanos(
                                options.getTimeoutOptions()
                                        .getSource()
                                        .getTimeUnit()
                                        .toNanos(
                                                options.getTimeoutOptions()
                                                        .getSource()
                                                        .getTimeout(null))))
                .isEqualTo(Duration.ofMillis(250));
    }
}
