package com.gateflow.cache;

import io.lettuce.core.*;

import org.springframework.boot.autoconfigure.data.redis.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

import java.time.Duration;
import java.util.Optional;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkflowCacheProperties.class)
public class WorkflowCacheConfiguration {
    @Bean
    LettuceClientConfigurationBuilderCustomizer cacheRedisClientOptions(
            RedisProperties properties) {
        // Preserve explicit Boot timeouts while bounding queues and rejecting offline replay.
        var connect =
                Optional.ofNullable(properties.getConnectTimeout()).orElse(Duration.ofMillis(200));
        var command = Optional.ofNullable(properties.getTimeout()).orElse(Duration.ofMillis(250));
        return builder ->
                builder.clientOptions(
                        ClientOptions.builder()
                                .autoReconnect(true)
                                .socketOptions(
                                        SocketOptions.builder().connectTimeout(connect).build())
                                .timeoutOptions(TimeoutOptions.enabled(command))
                                .requestQueueSize(256)
                                .disconnectedBehavior(
                                        ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                                .build());
    }
}
