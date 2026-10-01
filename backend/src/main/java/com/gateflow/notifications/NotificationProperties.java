package com.gateflow.notifications;

import jakarta.validation.constraints.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "gateflow.notifications")
public record NotificationProperties(
        boolean enabled,
        boolean consumerEnabled,
        boolean emailEnabled,
        boolean emailWorkerEnabled,
        @Min(1) @Max(10) int maxAttempts,
        @Min(1) @Max(20) int batchSize,
        @Min(100) @Max(60000) int pollIntervalMs,
        Duration leaseDuration,
        Duration retryBase,
        Duration retryMax,
        Duration maxAge,
        @NotBlank @Email String from) {
    @AssertTrue(message = "Invalid email lease/backoff/age bounds")
    public boolean isDurationsValid() {
        return leaseDuration != null
                && leaseDuration.toSeconds() >= 30
                && leaseDuration.toSeconds() <= 300
                && retryBase != null
                && retryBase.toMillis() >= 100
                && retryMax != null
                && retryMax.compareTo(retryBase) >= 0
                && retryMax.toSeconds() <= 3600
                && maxAge != null
                && maxAge.toSeconds() >= 60
                && maxAge.compareTo(Duration.ofDays(7)) <= 0;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationProperties.class)
    public static class Config {}
}
