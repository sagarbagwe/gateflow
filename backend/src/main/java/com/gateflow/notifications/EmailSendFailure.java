package com.gateflow.notifications;

public class EmailSendFailure extends RuntimeException {
    private static final java.util.Set<String> CODES =
            java.util.Set.of(
                    "SOURCE_UNAVAILABLE",
                    "SMTP_CONNECTION",
                    "SMTP_AUTH",
                    "SMTP_UNCERTAIN",
                    "INVALID_MESSAGE",
                    "PROVIDER_RETRYABLE",
                    "PROVIDER_PERMANENT",
                    "PROVIDER_UNKNOWN",
                    "WORKER_UNCERTAIN");

    public enum Kind {
        RETRYABLE,
        PERMANENT,
        UNKNOWN
    }

    private final Kind kind;
    private final String reason;

    public EmailSendFailure(Kind kind, String reason) {
        super(reason);
        if (kind == null || !CODES.contains(reason))
            throw new IllegalArgumentException("Unsupported email failure code");
        this.kind = kind;
        this.reason = reason;
    }

    public Kind kind() {
        return kind;
    }

    public String reason() {
        return reason;
    }
}
