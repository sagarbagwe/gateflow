package com.gateflow.auth;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class PasswordValidationTest {
    @Test void utf8ByteLimitPreventsBcryptTruncation() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new AuthRequests.Signup("valid@example.test", "Valid", "valid-password-2026"))).isEmpty();
            assertThat(validator.validate(new AuthRequests.Signup("valid@example.test", "Valid", "short"))).isNotEmpty();
            assertThat(validator.validate(new AuthRequests.Signup("valid@example.test", "Valid", "😀".repeat(20)))).isNotEmpty();
        }
    }
    @Test void configurationRejectsUnsafeDurationsAndLimits() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new AuthProperties(Duration.ZERO, true, 0, 5, Duration.ofMinutes(1))))
                    .isNotEmpty();
        }
    }
    @Test void credentialDtosDoNotExposeSecretsInToString() {
        String password = "never-log-this-password";
        assertThat(new AuthRequests.Signup("private@example.test", "Private", password).toString())
                .doesNotContain(password, "private@example.test");
        assertThat(new AuthRequests.Login("private@example.test", password).toString()).doesNotContain(password);
    }
}
