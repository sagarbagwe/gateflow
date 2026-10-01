package com.gateflow.async;

import jakarta.validation.constraints.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "gateflow.async")
public record AsyncProperties(
        boolean enabled,
        boolean relayEnabled,
        boolean consumerEnabled,
        @Min(1) @Max(100) int batchSize,
        @Min(100) @Max(60000) int pollIntervalMs,
        Duration confirmTimeout,
        Duration leaseDuration,
        Duration retryBase,
        Duration retryMax,
        List<Duration> retryDelays) {
    @AssertTrue(message = "Invalid async confirmation, lease or bounded retry settings")
    public boolean isDurationsValid() {
        return confirmTimeout != null
                && confirmTimeout.toMillis() >= 100
                && confirmTimeout.toMillis() <= 10000
                && leaseDuration != null
                && leaseDuration.toMillis() >= confirmTimeout.toMillis() + 2000
                && leaseDuration.toMillis() <= 300000
                && retryBase != null
                && retryBase.toMillis() >= 100
                && retryMax != null
                && retryMax.toMillis() >= retryBase.toMillis()
                && retryMax.toMillis() <= 3600000
                && retryDelays != null
                && retryDelays.size() == 3
                && retryDelays.stream()
                        .allMatch(d -> d != null && d.toMillis() >= 100 && d.toMillis() <= 3600000);
    }

    public AsyncProperties {
        if (retryDelays != null) retryDelays = List.copyOf(retryDelays);
    }
}
