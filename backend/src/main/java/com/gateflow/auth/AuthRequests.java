package com.gateflow.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthRequests {
    private AuthRequests() {}

    public record Signup(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank
                    @Size(min = 12, max = 64)
                    @MaxUtf8Bytes
                    @io.swagger.v3.oas.annotations.media.Schema(
                            accessMode =
                                    io.swagger.v3.oas.annotations.media.Schema.AccessMode
                                            .WRITE_ONLY,
                            format = "password",
                            description = "Never returned or logged; maximum 72 UTF-8 bytes")
                    String password) {
        @Override
        public String toString() {
            return "Signup[REDACTED]";
        }
    }

    public record Login(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank
                    @Size(max = 64)
                    @MaxUtf8Bytes
                    @io.swagger.v3.oas.annotations.media.Schema(
                            accessMode =
                                    io.swagger.v3.oas.annotations.media.Schema.AccessMode
                                            .WRITE_ONLY,
                            format = "password",
                            description = "Never returned or logged; maximum 72 UTF-8 bytes")
                    String password) {
        @Override
        public String toString() {
            return "Login[REDACTED]";
        }
    }
}
