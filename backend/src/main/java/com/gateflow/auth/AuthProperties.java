package com.gateflow.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "gateflow.auth")
public record AuthProperties(Duration sessionTtl, boolean cookieSecure,
        @Min(1) @Max(1000) int loginLimit, @Min(1) @Max(1000) int signupLimit, Duration rateWindow) {
    @AssertTrue(message = "Session TTL must be 1 second to 24 hours; rate window 1 second to 1 hour")
    public boolean isDurationsValid() {
        return sessionTtl != null && sessionTtl.toSeconds() >= 1 && sessionTtl.toSeconds() <= 86400
                && rateWindow != null && rateWindow.toSeconds() >= 1 && rateWindow.toSeconds() <= 3600;
    }
}
