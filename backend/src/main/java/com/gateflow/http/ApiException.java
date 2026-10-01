package com.gateflow.http;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final long retryAfterSeconds;
    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, 0);
    }
    public ApiException(HttpStatus status, String code, String message, long retryAfterSeconds) {
        super(message); this.status = status; this.code = code; this.retryAfterSeconds = retryAfterSeconds;
    }
    public HttpStatus status() { return status; }
    public long retryAfterSeconds() { return retryAfterSeconds; }
    public String code() { return code; }
}
