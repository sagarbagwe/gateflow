package com.gateflow.cache;

import jakarta.validation.constraints.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "gateflow.cache.workflow")
public record WorkflowCacheProperties(
        boolean enabled,
        Duration ttl,
        Duration failureCooldown,
        @Min(1024) @Max(65536) int maxBytes) {
    @AssertTrue(
            message =
                    "Workflow cache TTL must be 1 second to 24 hours, cooldown 1 second to 1"
                            + " minute")
    public boolean isDurationsValid() {
        return ttl != null
                && ttl.toMillis() >= 1000
                && ttl.toMillis() <= 86400000
                && failureCooldown != null
                && failureCooldown.toMillis() >= 1000
                && failureCooldown.toMillis() <= 60000;
    }
}
