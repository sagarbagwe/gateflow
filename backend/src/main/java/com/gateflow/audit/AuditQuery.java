package com.gateflow.audit;

import com.gateflow.http.ApiException;

import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.UUID;

public record AuditQuery(
        String action,
        String resourceType,
        UUID resourceId,
        UUID actorMembershipId,
        String requestId,
        Instant from,
        Instant before,
        int limit,
        int offset) {
    public AuditQuery {
        action = code(action);
        resourceType = code(resourceType);
        if (requestId != null && !requestId.matches("[A-Za-z0-9._:-]{1,128}")) throw invalid();
        if (from != null && before != null && !from.isBefore(before)) throw invalid();
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10000) throw invalid();
    }

    private static String code(String v) {
        if (v != null && !v.matches("[A-Z][A-Z0-9_]{0,79}")) throw invalid();
        return v;
    }

    private static ApiException invalid() {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_AUDIT_QUERY",
                "Invalid audit filter or pagination bounds");
    }
}
